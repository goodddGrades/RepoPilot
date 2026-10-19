package com.repopilot.it;

import com.repopilot.common.persistence.DeadlockRetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TASK-012：死锁重试的确定性测试。
 *
 * <p><b>为什么不用压测来验证</b>：死锁是概率事件，压测只能给出"这次没撞上"。
 * 这里用可编程的异常序列精确验证三件事：
 * 哪些异常该重试、哪些绝对不能重试、重试后业务结果是否仍然正确。
 *
 * <p>「哪些不能重试」比「哪些能重试」更重要：把唯一键冲突或连接失败也重试，
 * 只会把一个明确的错误放大成一段长时间的空转。
 */
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("TASK-012 死锁与锁等待的重试边界")
class DeadlockRetryIT extends AbstractApiIT {

    @Autowired
    private org.springframework.transaction.support.TransactionTemplate tx;

    @Test
    @DisplayName("死锁：重试后成功，业务副作用只发生一次")
    void retriesDeadlockAndKeepsResultConsistent() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger sideEffects = new AtomicInteger();

        String result = DeadlockRetry.execute(tx, () -> {
            int n = attempts.incrementAndGet();
            if (n < 3) {
                throw new DeadlockLoserDataAccessException("模拟死锁", new SQLException("Deadlock"));
            }
            sideEffects.incrementAndGet();
            return "ok-" + n;
        });

        assertThat(result).as("第三次应成功").isEqualTo("ok-3");
        assertThat(attempts.get()).as("应恰好重试到第 3 次").isEqualTo(3);
        assertThat(sideEffects.get())
                .as("失败的事务已回滚，重放不得叠加副作用").isEqualTo(1);
    }

    @Test
    @DisplayName("锁等待超时：同样可重试")
    void retriesLockTimeout() {
        AtomicInteger attempts = new AtomicInteger();

        DeadlockRetry.execute(tx, () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new CannotAcquireLockException("模拟锁等待超时");
            }
            return "ok";
        });

        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("SQLException 40001（死锁）即使绕过 Spring 翻译也要重试")
    void retriesRawSqlStateDeadlock() {
        AtomicInteger attempts = new AtomicInteger();

        // 有些驱动配置下异常翻译器不生效，直接抛 SQLException。
        // 只认 Spring 异常类型就会漏掉这类死锁。
        DeadlockRetry.execute(tx, () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("包装过",
                        new SQLException("Deadlock found", "40001"));
            }
            return "ok";
        });

        assertThat(attempts.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("超出重试上限：抛出原始异常而不是无限重试")
    void givesUpAfterMaxAttempts() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> DeadlockRetry.execute(tx, () -> {
            attempts.incrementAndGet();
            throw new DeadlockLoserDataAccessException("一直死锁", new SQLException("Deadlock"));
        }, 3)).isInstanceOf(DeadlockLoserDataAccessException.class);

        assertThat(attempts.get()).as("应恰好试满 3 次").isEqualTo(3);
    }

    @Test
    @DisplayName("唯一键冲突：绝不重试——重试只会重复失败")
    void doesNotRetryDuplicateKey() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> DeadlockRetry.execute(tx, () -> {
            attempts.incrementAndGet();
            throw new DuplicateKeyException("唯一键冲突");
        })).isInstanceOf(DuplicateKeyException.class);

        assertThat(attempts.get()).as("必须一次就失败，不做任何重试").isEqualTo(1);
    }

    @Test
    @DisplayName("连接故障：绝不重试——它不是锁冲突，重试无意义")
    void doesNotRetryConnectionFailure() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> DeadlockRetry.execute(tx, () -> {
            attempts.incrementAndGet();
            throw new DataAccessResourceFailureException("连接断开");
        })).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("业务异常：原样抛出，不被误判成锁冲突")
    void doesNotSwallowBusinessException() {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> DeadlockRetry.execute(tx, () -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("参数非法");
        })).isInstanceOf(IllegalArgumentException.class);

        assertThat(attempts.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("判定函数本身：只认死锁与锁等待两类")
    void classifierAcceptsOnlyLockFailures() {
        assertThat(DeadlockRetry.isRetryableLockFailure(
                new DeadlockLoserDataAccessException("x", new SQLException("y")))).isTrue();
        assertThat(DeadlockRetry.isRetryableLockFailure(
                new CannotAcquireLockException("x"))).isTrue();
        assertThat(DeadlockRetry.isRetryableLockFailure(
                new IllegalStateException("包装", new SQLException("x", "40001")))).isTrue();

        assertThat(DeadlockRetry.isRetryableLockFailure(
                new DuplicateKeyException("x"))).isFalse();
        assertThat(DeadlockRetry.isRetryableLockFailure(
                new DataAccessResourceFailureException("x"))).isFalse();
        assertThat(DeadlockRetry.isRetryableLockFailure(
                new IllegalArgumentException("x"))).isFalse();
        // 自引用 cause 不能造成死循环
        assertThat(DeadlockRetry.isRetryableLockFailure(
                new RuntimeException("无 cause"))).isFalse();
    }
}