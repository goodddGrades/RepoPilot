-- TASK-014：令牌桶的原子实现（Java 与 Python 两端共用的算法原型）。
--
-- 为什么整段逻辑必须放在 Lua 里：
--   Redis 以单线程执行脚本，脚本内的"读状态 → 补令牌 → 判断 → 扣减 → 写回"
--   五步之间不会插入其它客户端的命令。若放到应用侧写成
--   "GET → 本地计算 → SET"，N 个并发请求会读到同一个旧值、各自认为有余量，
--   超额放行量随并发度线性增长——这正是分布式限流最常见的假实现。
--
-- 为什么用 redis.call('TIME') 而不是应用传入时间：
--   TIME 是 **Redis 服务端时间**。多实例部署时本地时钟必有偏差，
--   用应用时间会让"时钟快"的实例多补令牌、"时钟慢"的实例少补，
--   限流精度取决于最不准的那台机器。
--
-- 参数（全部由调用方传入，脚本内不写死任何阈值）：
--   KEYS[1] 桶的 key（调用方负责命名空间、环境与主体哈希）
--   ARGV[1] capacity        容量，> 0
--   ARGV[2] refill_per_sec  补充速率（令牌/秒），> 0
--   ARGV[3] cost            本次请求消耗的令牌数，> 0
--   ARGV[4] ttl_ms          空闲过期时间（毫秒），> 0
--
-- 返回（四个整数，便于各语言客户端直接解析）：
--   {allowed, remaining, retry_after_ms, capacity}
--   allowed        1 = 放行，0 = 拒绝
--   remaining      扣减后剩余令牌（向下取整，仅用于展示与观测；决策已在上方做出）
--   retry_after_ms 拒绝时表示"还要多久才够一次 cost"，放行时为 0
--   capacity       回显容量，便于调用方核对脚本参数与预期一致
--
-- 兼容性：Redis 3.2–6.x 需要在脚本里显式开启效果复制才能使用 TIME；
-- Redis 7 起效果复制是默认行为，该调用退化为 no-op。用 pcall 包住，
-- 两种服务端都能跑，不为了兼容而在应用侧传入本地时间。

pcall(function() redis.replicate_commands() end)

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_per_sec = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])
local ttl_ms = tonumber(ARGV[4])

-- 参数非法时不做"宽松放行"：静默放行会让一个配置错误变成永久绕过限流。
-- 返回 -1 让调用方明确按配置错误处理。
if capacity == nil or capacity <= 0
    or refill_per_sec == nil or refill_per_sec <= 0
    or cost == nil or cost <= 0
    or ttl_ms == nil or ttl_ms <= 0 then
  return { -1, 0, -1, 0 }
end

local now = redis.call('TIME')
local now_ms = tonumber(now[1]) * 1000 + math.floor(tonumber(now[2]) / 1000)

local stored = redis.call('HMGET', key, 'tokens', 'ts')
local tokens = tonumber(stored[1])
local last_ms = tonumber(stored[2])

if tokens == nil then
  -- 首次创建：桶是满的。这符合令牌桶语义（新客户端不该一上来就被拒），
  -- 同时也避免了"用 0 初始化"导致的首次请求全部 429。
  tokens = capacity
  last_ms = now_ms
end

-- 时钟回拨（NTP 校正、主从切换）时 elapsed 可能为负：按 0 处理，
-- 只补不还，绝不因为时间倒退而扣掉已有令牌。
local elapsed_ms = now_ms - last_ms
if elapsed_ms < 0 then
  elapsed_ms = 0
end

tokens = math.min(capacity, tokens + (elapsed_ms / 1000) * refill_per_sec)

local allowed = 0
local retry_after_ms = 0
if tokens >= cost then
  tokens = tokens - cost
  allowed = 1
else
  local missing = cost - tokens
  retry_after_ms = math.ceil(missing * 1000 / refill_per_sec)
end

-- 保留 6 位小数：令牌是连续量，取整会让低速补充（例如 0.2 个/秒）
-- 永远补不上来——每次写入都被截断成 0。用字符串存，避免 Lua 的
-- 默认数字转字符串在大数时变成科学计数法。
redis.call('HSET', key, 'tokens', string.format('%.6f', tokens), 'ts', tostring(now_ms))
redis.call('PEXPIRE', key, ttl_ms)

return { allowed, math.floor(tokens), retry_after_ms, capacity }
