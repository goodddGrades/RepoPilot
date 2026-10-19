package com.repopilot.common.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * 死锁与锁等待的整事务重试（TASK-012，压测实测发现）。
 *
 * <p><b>为什么必须有</b>：MySQL 的 InnoDB 在检测到循环等待时会**主动回滚其中一个事务**
 * 并抛 {@code DeadlockLoserDataAccessException}。这不是故障，是并发数据库的正常机制——
 * 同一批 Run 被多个 Worker 并发领取、并发写轨迹时必然出现。
 *
 * <p>压测实测（{@code PerformanceBaselineIT}，8 并发 × 60 Run × 40 事件）：
 * 未加重试时事件写入有约 0.3% 的请求直接返回 <b>500</b>。
 * Worker 收到 500 后只能整批重传，已经成功的那些事件虽然幂等不会重复写，
 * 但这属于"把数据库的正常并发行为暴露成系统错误"。
 *
 * <p><b>为什么重试整个事务而不是单条语句</b>：死锁发生时事务已被 InnoDB 回滚，
 * 事务里的所有写都没了。只重试那一条 INSERT 会留下"前面几条已写、后面没写"的
 * 半截状态——虽然有幂等键兜底，但那是让上层去修数据库留下的坑。
 *
 * <p><b>为什么不靠调索引规避</b>：这里的锁来自 {@code (run_id, run_attempt_id)}
 * 复合外键插入时对父行的共享锁，不是索引选择问题。加索引不会消除外键检查，
 * 只会掩盖症状。正确做法就是承认并发数据库会有死锁，并让它可恢复。
 *
 * <p><b>退避必须有抖动</b>：所有并发事务在同一毫秒集体重试会再次同步碰撞，
 * 退避只退避不抖动等于没退。
 */
public final class DeadlockRetry {

    private static final Logger log = LoggerFactory.getLogger(DeadlockRetry.class);

    /** 默认重试次数。MySQL 的死锁检测本身很快，重试 2~3 次通常就成功。 */
    private static final int DEFAULT_MAX_ATTEMPTS = 3;

    private static final long BASE_BACKOFF_MICROS = 2_000L;
    private static final long MAX_BACKOFF_MICROS = 50_000L;

    private DeadlockRetry() {
    }

    /**
     * 执行一个事务，遇到死锁/锁等待超时则整体重试。
     *
     * <p>调用方传入的 {@code body} 必须可重复执行——重试意味着它会被调用多次。
     * 这不是问题：所有这类回调都是"按固定输入写固定输出"，
     * 幂等性由业务唯一键（eventId、attemptId）保证。
     */
    public static <T> T execute(TransactionTemplate tx, Supplier<T> body) {
        return execute(tx, body, DEFAULT_MAX_ATTEMPTS);
    }

    public static <T> T execute(TransactionTemplate tx, Supplier<T> body, int maxAttempts) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute((TransactionCallback<T>) status -> body.get());
            } catch (RuntimeException ex) {
                if (!isRetryableLockFailure(ex) || attempt >= maxAttempts) {
                    throw ex;
                }
                long backoff = backoffMicros(attempt);
                log.warn("检测到死锁/锁等待，第 {}/{} 次重试（退避 {}µs）: {}",
                        attempt, maxAttempts, backoff, rootMessage(ex));
                sleepQuietly(backoff);
            }
        }
    }

    /**
     * 是否是"值得重试"的锁冲突。
     *
     * <p>刻意<b>不</b>把 {@code DataAccessException} 整体纳入：连接断开、
     * 唯一键冲突、字段超长都继承自它，重试它们只会放大故障。
     * 只认死锁与锁等待超时这两类——它们是并发系统的正常噪声，不是错误。
     */
    public static boolean isRetryableLockFailure(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof DeadlockLoserDataAccessException
                    || current instanceof CannotAcquireLockException
                    || current instanceof PessimisticLockingFailureException) {
                return true;
            }
            // MySQL 错误 1213=死锁、1205=锁等待超时。某些驱动配置下
            // 会绕过 Spring 的异常翻译直接抛出 SQLException
            if (current instanceof java.sql.SQLException sql) {
                String state = sql.getSQLState();
                if (state != null && state.startsWith("40")
                        && ("40001".equals(state) || "41000".equals(state))) {
                    return true;
                }
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }

    /** 指数退避 + 抖动：2ms → 4ms → … 上限 50ms，随机抖动最多加 2ms。 */
    private static long backoffMicros(int attemptNo) {
        long base = Math.min(BASE_BACKOFF_MICROS << (attemptNo - 1), MAX_BACKOFF_MICROS);
        return base + (long) (Math.random() * 2_000L);
    }

    private static void sleepQuietly(long micros) {
        try {
            Thread.sleep(micros / 1000L, (int) (micros % 1000L) * 1000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待重试时被中断", ex);
        }
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ": " + current.getMessage();
    }
}