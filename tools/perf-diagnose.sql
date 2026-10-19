-- TASK-012 慢查询与锁等待采集（真实 MySQL，不用估算）。
--
-- 用法（在 repopilot_it 库上）：
--   mysql -h 127.0.0.1 -P 13306 -u repopilot -p<pwd> repopilot_it < tools/perf-diagnose.sql
--
-- 三段分别回答三个问题：
--   1) 有没有慢查询          → performance_schema.events_statements_summary_by_digest
--   2) 哪些索引没被用上      → 表的索引使用统计
--   3) 有没有锁等待          → data_locks / data_lock_waits

-- ---------------------------------------------------------------- 1. 慢查询 Top 20
SELECT
    ROUND(SUM_TIMER_WAIT / 1000000000000, 2)  AS total_seconds,
    ROUND(AVG_TIMER_WAIT / 1000000000, 2)       AS avg_ms,
    ROUND(MAX_TIMER_WAIT / 1000000000, 2)       AS max_ms,
    SUM_ROWS_EXAMINED                            AS rows_examined,
    SUM_ROWS_SENT                                AS rows_sent,
    SUM_NO_INDEX_USED                            AS no_index_used,
    LEFT(DIGEST_TEXT, 160)                       AS sql
  FROM performance_schema.events_statements_summary_by_digest
 WHERE SCHEMA_NAME = DATABASE()
   AND DIGEST_TEXT IS NOT NULL
 ORDER BY SUM_TIMER_WAIT DESC
 LIMIT 20;

-- 只看"扫得多、回得少"的语句：这是索引缺失最直接的信号
SELECT
    ROUND(SUM_TIMER_WAIT / 1000000000000, 3) AS total_seconds,
    SUM_ROWS_EXAMINED                       AS examined,
    SUM_ROWS_SENT                           AS sent,
    ROUND(SUM_ROWS_EXAMINED / GREATEST(SUM_ROWS_SENT, 1), 1) AS examined_per_sent,
    LEFT(DIGEST_TEXT, 140)                  AS sql
  FROM performance_schema.events_statements_summary_by_digest
 WHERE SCHEMA_NAME = DATABASE()
   AND SUM_ROWS_SENT > 0
   AND SUM_ROWS_EXAMINED > SUM_ROWS_SENT * 10
 ORDER BY SUM_TIMER_WAIT DESC
 LIMIT 20;

-- ---------------------------------------------------------------- 2. 未使用的索引
SELECT
    OBJECT_SCHEMA AS table_name,
    INDEX_NAME     AS index_name
  FROM performance_schema.table_io_waits_summary_by_index_usage
 WHERE OBJECT_SCHEMA = DATABASE()
   AND INDEX_NAME IS NOT NULL
   AND COUNT_READ = 0
   AND COUNT_WRITE = 0
 ORDER BY OBJECT_TABLE, INDEX_NAME;

-- 各表 I/O 与行数（判断哪张表是真的热）
SELECT
    OBJECT_NAME                            AS table_name,
    COUNT_READ                             AS reads,
    COUNT_WRITE                            AS writes,
    SUM_TIMER_READ / 1000000000000         AS read_seconds,
    SUM_TIMER_WRITE / 1000000000000        AS write_seconds
  FROM performance_schema.table_io_waits_summary_by_table
 WHERE OBJECT_SCHEMA = DATABASE()
 ORDER BY SUM_TIMER_READ DESC
 LIMIT 20;

-- ---------------------------------------------------------------- 3. 锁等待
-- 当前未解决的锁等待（跑压测的**同时**执行才有意义）
SELECT
    r.trx_id            AS waiting_trx,
    r.trx_mysql_thread_id AS waiting_thread,
    r.trx_state         AS waiting_state,
    LEFT(r.trx_query, 120) AS waiting_query,
    b.trx_mysql_thread_id AS blocking_thread,
    LEFT(b.trx_query, 120) AS blocking_query,
    TIMESTAMPDIFF(SECOND, r.trx_wait_started, NOW()) AS waited_seconds
  FROM performance_schema.data_lock_waits w
  JOIN information_schema.innodb_trx r ON r.trx_id = w.REQUESTING_TRX_ID
  JOIN information_schema.innodb_trx b ON b.trx_id = w.BLOCKING_TRX_ID;

-- 历史最长等待的锁（无需并发压测也能看）
SELECT
    EVENT_NAME,
    COUNT_STAR                       AS occurrences,
    ROUND(SUM_TIMER_WAIT / 1000000000, 2) AS total_ms,
    ROUND(MAX_TIMER_WAIT / 1000000000, 2) AS max_ms
  FROM performance_schema.events_waits_summary_global_by_event_name
 WHERE EVENT_NAME LIKE 'wait/lock/%'
 ORDER BY SUM_TIMER_WAIT DESC
 LIMIT 15;

-- 事务与死锁计数
SELECT
    (SELECT COUNT(*) FROM information_schema.innodb_trx)                  AS active_trx,
    (SELECT COUNT(*) FROM performance_schema.threads WHERE PROCESSLIST_STATE IS NOT NULL) AS active_threads;

SHOW ENGINE INNODB STATUS;