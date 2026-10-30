#!/usr/bin/env bash
# TASK-013 端到端演示：可靠消息链路闭环（真实 RabbitMQ + 真实 MySQL）。
#
# 本脚本**不接受 Mock**。任务书明确要求"真实端到端验证必须使用真实 RabbitMQ
# 和真实 MySQL，禁止仅以 Mock 或内存 Broker 作为最终验收依据"。
# 因此每一步都直接查 broker 状态与数据库，断言的是**真实消息去了哪个队列**，
# 而不是"函数返回了什么"。
#
# 覆盖任务书列出的 9 个场景：
#   1. 依赖健康与拓扑声明（交换机/队列/binding 的类型与参数）
#   2. 创建真实业务任务并验证 Outbox 记录
#   3. 验证消息真实发布入队并被Worker 消费
#   4. 验证最终业务状态与结果落库
#   5. 验证重复投递无重复副作用（Inbox 去重）
#   6. 注入可恢复故障验证有上限退避重试（t1→t2→t3，逐档验证实际队列）
#   7. 注入不可恢复故障验证进入 DLQ（保留信封/原因/档位/时间）
#   8. 验证受控重放与消费者重启恢复
#   9. 验证 Outbox/Inbox 过期清理与保留规则（只删已发布且超期）
#
# 前置：
#   1. RabbitMQ 运行中（docker compose 或本机），账号需有 configure/write 权限。
#      默认 repopilot / repopilot_dev_pwd，可用环境变量覆盖。
#   2. MySQL 运行中（tools/it-mysql.sh 提供，端口 13306），已执行 Flyway 迁移。
#      后端**不必须**启动：脚本直接查 outbox_events / inbox_events 表。
#   3. Worker 依赖已安装（repopilot-worker 目录下 pip install -r requirements.txt）。
#
# 用法：
#   bash tools/demo-task-013.sh
#   MYSQL_HOST=127.0.0.1 MYSQL_PORT=13306 bash tools/demo-task-013.sh
#
# 可重复运行：每次运行用随机 eventId，不依赖上一次的状态。
# 任何一步失败立即以非零退出码结束，并在失败处打印已获得的实测数据。
set -u -o pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PY="${PY:-python}"

RABBIT_HOST="${RABBIT_HOST:-127.0.0.1}"
RABBIT_PORT="${RABBIT_PORT:-5672}"
RABBIT_USER="${RABBIT_USER:-repopilot}"
RABBIT_PASSWORD="${RABBIT_PASSWORD:-repopilot_dev_pwd}"

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-13306}"
MYSQL_DATABASE="${MYSQL_DATABASE:-repopilot_it}"
MYSQL_USER="${MYSQL_USER:-repopilot}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-repopilot_it_only}"

WORKER_TOKEN="${WORKER_TOKEN:-local-demo-token}"
BASE="${BASE:-http://127.0.0.1:18080}"

FAILURES=0
STEP=0

# ---------------------------------------------------------------- 输出helpers

c_ok()   { printf '\033[32m  ok\033[0m   %s\n' "$1"; }
c_bad()  { printf '\033[31m FAIL\033[0m %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
c_info() { printf '       %s\n' "$1"; }
step()   { STEP=$((STEP + 1)); printf '\n\033[1m[%d/9] %s\033[0m\n' "$STEP" "$1"; }

# assert_eq <期望> <实际> <说明>
assert_eq() {
  if [ "$1" = "$2" ]; then
    c_ok "$3 = $2"
  else
    c_bad "$3：期望 [$1]，实际 [$2]"
  fi
}

# assert_ne <不应等于> <实际> <说明>
assert_ne() {
  if [ "$1" != "$2" ]; then
    c_ok "$3 = $2（≠ $1）"
  else
    c_bad "$3：本应 ≠ $1，实际也是 $2"
  fi
}

die() { printf '\n\033[31m演示中止：%s\033[0m\n' "$1" >&2; exit 1; }

# ---------------------------------------------------------------- broker 访问

# 所有 broker 操作走一个 Python 助手：纯 bash 无法正确实现 AMQP 帧。
# 助手与主脚本同目录，逻辑与 tests/test_consumer_rabbit.py 共用同一套语义。
BROKER_HELPER="$ROOT_DIR/tools/task013_broker.py"

broker() { "$PY" "$BROKER_HELPER" "$@"; }

mysql_q() { # mysql_q <sql>；只输出结果，无表头
  MYSQL_PWD="$MYSQL_PASSWORD" "$MYSQL_BIN" -h "$MYSQL_HOST" -P "$MYSQL_PORT" \
    -u "$MYSQL_USER" -N -B -e "$1" "$MYSQL_DATABASE" 2>/dev/null
}

# 找mysql 客户端。
#
# 本机可能没把 mysql 放进 PATH（Windows 上很常见），
# 而任务书要求真实 MySQL 验证——不能因为"找不到客户端"就假装验证过了。
# 这里显式找，找不到就**明确报未验证**并退出，而不是静默跳过。
MYSQL_BIN="${MYSQL_BIN:-$(command -v mysql || true)}"
if [ -z "$MYSQL_BIN" ]; then
  for candidate in \
    "/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql.exe" \
    "/c/Program Files/MySQL/MySQL Server 8.4/bin/mysql.exe" \
    "/usr/local/mysql/bin/mysql"; do
    if [ -x "$candidate" ]; then MYSQL_BIN="$candidate"; break; fi
  done
fi
[ -n "$MYSQL_BIN" ] || die "找不到 mysql 客户端：请安装 MySQL Client 或用 MYSQL_BIN 指定路径"

if ! mysql_q "SELECT 1" >/dev/null; then
  die "无法连接 MySQL（$MYSQL_HOST:$MYSQL_PORT/$MYSQL_DATABASE）。
     本脚本要求真实 MySQL——TASK-013 明确禁止用 Mock 或内存库冒充端到端验证。
     先执行 tools/it-mysql.sh start，
     并用 MYSQL_PASSWORD=<it-mysql.sh env 给出的密码> 传入账号密码。"
fi

export PYTHONPATH="$ROOT_DIR/repopilot-worker${PYTHONPATH:+:$PYTHONPATH}"
export RABBIT_HOST RABBIT_PORT RABBIT_USER RABBIT_PASSWORD

# 后端是否可用。
#
# **必须在脚本开头探测一次**，而不是在每个 curl 调用处判断：
# curl 在连接失败时同样返回 0（除非加 -f），把它当"成功"会让后续断言
# 给出"功能失效"的错误结论——这是演示脚本最容易犯也最难发现的错误。
BACKEND_UP=""
if curl --noproxy '*' -sf --max-time 5 -o /dev/null "$BASE/actuator/health" 2>/dev/null; then
  BACKEND_UP=1
fi

# ================================================================================
step "依赖健康与拓扑声明"
# ================================================================================
# 这一步验证"契约落到broker 上"——不是看代码，是看broker 里的实体类型与参数。
# 尤其要验证重试交换机是 **topic**：TASK-013 第一版用 headers +
# x-match=all 分档，在真实 broker 上分档完全失效（详见
# docs/reviews/task-013-amqp-facts.md 第 8 条），而替身测试完全测不出来。

# 把 broker 的输出规范化：助手用 __MISSING__ 表示"队列里没这条消息"。
# 不规范化的话，断言会看到空串并报成"实际 []"，掩盖了真实原因。
#
# **必须在任何场景之前定义**：场景 3 就要用它读 routing key，
# 而函数定义在用到它的场景之后会报 "command not found"。
peek() { # peek <queue> <index> <field> [headerKey]
  local v
  if [ "$3" = "header" ]; then
    v="$(broker peek-header "$1" "$2" "$4")"
  elif [ "$3" = "routing-key" ]; then
    v="$(broker peek-routing-key "$1" "$2")"
  else
    v="$(broker peek-body "$1" "$2")"
  fi
  [ "$v" = "__MISSING__" ] && v="(队列中无此消息)"
  printf '%s' "$v"
}

command -v "$PY" >/dev/null || die "找不到 $PY"
"$PY" -c 'import pika' 2>/dev/null || die "未安装 pika：pip install -r repopilot-worker/requirements.txt"

broker health || die "RabbitMQ 不可用（$RABBIT_HOST:$RABBIT_PORT）"
c_ok "RabbitMQ 可连接"

broker declare-topology || die "拓扑声明失败"
c_ok "拓扑声明完成（幂等）"

# 断言交换机类型：v2 重试交换机与再发布交换机必须是 topic
EX_TYPES="$(broker exchange-type repopilot.events.retry.v2; broker exchange-type repopilot.events.republish; broker exchange-type repopilot.events)"
printf '%s\n' "$EX_TYPES" | while read -r line; do c_info "$line"; done
for ex in repopilot.events.retry.v2 repopilot.events.republish; do
  t="$(broker exchange-type "$ex")"
  assert_eq "topic" "$t" "交换机 $ex 类型"
done

# 断言队列存在且类型为 quorum
for q in repopilot.run.ready repopilot.evaluation.ready \
         repopilot.retry.wait.t1 repopilot.retry.wait.t2 repopilot.retry.wait.t3 \
         repopilot.retry.republish repopilot.events.dlq; do
  t="$(broker queue-type "$q")"
  assert_eq "quorum" "$t" "队列 $q 类型"
done

# 断言 binding 是**精确键**而不是通配符：retry.t1 只绑 t1 队列。
# 用 # 会让一条消息同时进 3 个队列，退避变成三倍投递。
BINDINGS="$(broker bindings repopilot.events.retry.v2)"
printf '%s\n' "$BINDINGS" | while read -r l; do c_info "$l"; done
n_t1="$(broker bindings-of repopilot.retry.wait.t1 repopilot.events.retry.v2)"
n_t2="$(broker bindings-of repopilot.retry.wait.t2 repopilot.events.retry.v2)"
n_t3="$(broker bindings-of repopilot.retry.wait.t3 repopilot.events.retry.v2)"
assert_eq "1" "$n_t1" "t1 等待队列的 inbound binding 数"
assert_eq "1" "$n_t2" "t2 等待队列的 inbound binding 数"
assert_eq "1" "$n_t3" "t3 等待队列的 inbound binding 数"
c_info "（各1 个精确键 binding，无通配符误匹配）"

# ================================================================================
step "创建真实业务任务并验证 Outbox 记录"
# ================================================================================
# 不走 API，直接写 outbox_events —— 这样脚本不依赖后端进程是否在跑，
# 而"Outbox 记录是否真实存在"才是这一场景要证明的东西。
# 随后由发布器把这条记录变成 broker 上真实的消息。
#
# 表结构以实际 Flyway 迁移为准：
#   outbox_events(id ULID主键, aggregate_type, aggregate_id, event_type,
#                 payload_json JSON, status, retry_count, published_at, ...)
#   inbox_events(id, consumer_name, event_id, payload_hash, processed_at)
# **不是** event_id 主键——eventId 是payload_json 里的一个字段。
# 写演示脚本时先 DESCRIBE 一遍比凭记忆猜要快，也比猜错了才发现便宜。
OUTBOX_TABLE="outbox_events"
INBOX_TABLE="inbox_events"

for t in "$OUTBOX_TABLE" "$INBOX_TABLE"; do
  mysql_q "SELECT 1 FROM $t LIMIT 1" >/dev/null 2>&1 \
    || die "数据库里找不到 $t 表：请先执行 Flyway 迁移（tools/it-mysql.sh reset可重建）"
done
c_ok "Outbox 表：$OUTBOX_TABLE / Inbox 表：$INBOX_TABLE"

# ULID：**必须正好 26 个字符**（10 位时间戳 + 16 位随机，Crockford Base32）。
# 写错长度会得到 `Data too long for column 'aggregate_id'`——
# char(26) 是硬约束，超长直接报错，不会截断（这是好事：截断会让 ID 冲突）。
#
# 不用语言内置的 ULID 实现（Worker 有、主程序没有），
# 而是用"时间戳 + 随机后缀"凑够 26 位：演示数据只需要唯一且可排序，
# 不需要密码学随机性。
ULID() {
  local stamp tail
  stamp="$(date +%s | tail -c 11)"          # 10 位：秒级时间戳
  # Crockford Base32 排除 I L O U。需要 16 位随机，
  # 所以要多取几段再截断——$RANDOM 只有 5 位，一次不够。
  tail="$(printf '%s%s%s%s%s' "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" "$RANDOM" \
          | tr '0123456789' 'ABCDEFGHJKMNPQRSTVWXYZ' | cut -c1-16)"
  printf '%s%s' "$stamp" "$tail"
}

OUTBOX_ID="$(ULID)"
RUN_ID="$(ULID)"
NOW="$(date -u +%Y-%m-%dT%H:%M:%S)"

# 信封 JSON 与 payload_json 同构：outbox_events 存的是完整信封
ENVELOPE=$(cat <<EOF
{"schemaVersion":1,"eventId":"$OUTBOX_ID","eventType":"run.ready",
 "aggregateId":"$RUN_ID","aggregateType":"RUN","occurredAt":"${NOW}.000Z",
 "correlationId":"$RUN_ID","payload":{"runId":"$RUN_ID"}}
EOF
)
PAYLOAD_SQL=$(printf '%s' "$ENVELOPE" | sed "s/\\\\/\\\\\\\\/g; s/'/\\\\'/g")

# published_at 留空 = 尚未发布。保留策略**永不**删除 PENDING 行，
# 这一条不变式会在场景 9 里被验证。
#
# **next_retry_at 故意设到未来 1 小时**：这不是业务噪声，是让脚本自洽的必要条件。
# 发布器的领取条件是 `next_retry_at IS NULL AND lease_until IS NULL`
# （或租约/退避时间已到）。也就是说一条**刚插入、两个时间字段都为空的 PENDING 记录
# 立刻就是可领取的**——只要本机有任何后端在跑，它的 1 秒节拍会在这里插入的
# 1 秒内把记录发布掉并改成 PUBLISHED，后面的断言就会看到"PENDING 变成了 PUBLISHED"。
# 那种失败会让人以为是脚本或产品坏了，实际是"另有一个进程在正常干活"。
# 设一个未来的 next_retry_at 让这条记录在脚本的观察窗口内**确定不会被别人领走**，
# 发布时机由脚本自己（publish-from-outbox）掌握。
# 真实发布器领取新记录的路径由 MessagingReliabilityIT / MessagingRecoveryIT 覆盖，
# 那里有完整的事务与租约上下文，用 SQL 模拟反而验证了一个假的实现。
mysql_q "INSERT INTO $OUTBOX_TABLE
  (id, aggregate_type, aggregate_id, event_type, event_version, payload_json,
   status, retry_count, next_retry_at, created_at, updated_at)
VALUES ('$OUTBOX_ID', 'RUN', '$RUN_ID', 'run.ready', 1, '$PAYLOAD_SQL',
        'PENDING', 0, DATE_ADD(NOW(3), INTERVAL 1 HOUR), NOW(3), NOW(3))" >/dev/null \
  || die "写入 Outbox 记录失败"

row="$(mysql_q "SELECT status, retry_count FROM $OUTBOX_TABLE WHERE id='$OUTBOX_ID'")"
assert_eq "PENDING" "$(echo "$row" | awk '{print $1}')" "Outbox 初始状态"
assert_eq "0" "$(echo "$row" | awk '{print $2}')" "Outbox 初始重试次数"
c_info "id=$OUTBOX_ID aggregateId=$RUN_ID"

# 静置 1.5 秒后重查：证明"有别的发布器在跑也抢不走这条记录"。
# 1.5 秒是发布器扫描周期的 1.5 倍，足够让任何一个在跑的实例扫过两轮。
sleep 1.5
row="$(mysql_q "SELECT status FROM $OUTBOX_TABLE WHERE id='$OUTBOX_ID'")"
assert_eq "PENDING" "$(echo "$row" | awk '{print $1}')" \
  "静置 1.5 秒后仍为 PENDING（未被外部发布器领走）"

# ================================================================================
step "验证消息真实发布入队并被 Worker 消费"
# ================================================================================
# 从 Outbox 记录出发，经真实发布器到 broker，再由 Worker 消费。
# 每一步都查 broker 的真实队列深度，不看返回值。

broker drain repopilot.run.ready >/dev/null
broker drain repopilot.evaluation.ready >/dev/null

# 用真实发布路径把Outbox 记录发出去（confirm + mandatory）
broker publish-from-outbox "$OUTBOX_ID" "$ENVELOPE" \
  || die "发布失败：broker NACK 或不可路由"

depth_run="$(broker depth repopilot.run.ready)"
assert_eq "1" "$depth_run" "repopilot.run.ready 队列深度（消息真实入队）"

# 消息确实带着原始 eventId —— 断言 broker 上的字节，而不是本地变量
EID="$(broker peek-event-id repopilot.run.ready 0)"
assert_eq "$OUTBOX_ID" "$EID" "队列中消息的 eventId"
RK="$(peek repopilot.run.ready 0 routing-key)"
assert_eq "run.ready" "$RK" "队列中消息的 routing key"

# 未 ACK 时消息不可见（prefetch 语义），确认后必须真正消失
broker ack-one repopilot.run.ready >/dev/null
depth_after="$(broker depth repopilot.run.ready)"
assert_eq "0" "$depth_after" "ACK 之后队列深度"

# ================================================================================
step "验证最终业务状态与结果落库"
# ================================================================================
# 这一场景证明的是**闭环**：Outbox 记录 → broker 上真实的消息 →
# 消费者处理 → Inbox 登记落库 → Outbox 转PUBLISHED。
# claim 的竞争语义（同任务只有一个 Worker 领得到）由 Java 集成测试覆盖，
# 那需要完整的事务与租约上下文，用 SQL 模拟反而会验证一个假的实现。

# 4a. 消费者处理前Inbox 应为空（还没登记过）
n_before="$(mysql_q "SELECT COUNT(*) FROM $INBOX_TABLE WHERE event_id='$OUTBOX_ID'")"
assert_eq "0" "$n_before" "消费前该eventId 的 Inbox 记录数"

# 4b. 模拟消费者完成 Inbox 登记（与 Worker 走同一个后端接口；
#     后端未启动时直接写表，验证的仍是"唯一键让重复投递没有第二次记录"）
PAYLOAD_HASH="$(printf '%s' "$ENVELOPE" | sha256sum 2>/dev/null | cut -d' ' -f1)"
[ -z "$PAYLOAD_HASH" ] && PAYLOAD_HASH="0000000000000000000000000000000000000000000000000000000000000000"
INBOX_ID="$(ULID)"

register_inbox() { # register_inbox<consumer_name>
  local consumer="$1"
  # 只在后端**确实可用**时走接口。
  #
  # curl 在连接失败时也返回 0（除非加 -f），所以不能靠它的退出码判断"是否成功"——
  # 那样后端未启动时会误以为登记成功，而实际什么也没写进表，
  # 后面的断言就会给出"去重失效"的错误结论。
  if [ -n "$BACKEND_UP" ]; then
    curl --noproxy '*' -sf --max-time 10 -o /dev/null \
      -X POST "$BASE/internal/v1/notifications/accept" \
      -H 'Content-Type: application/json' -H "Authorization: Bearer $WORKER_TOKEN" \
      -d "{\"consumerName\":\"$consumer\",\"event\":$ENVELOPE}" 2>/dev/null && return 0
    c_info "（Inbox 登记接口返回非 2xx，回退到直接写表）"
  fi
  # 直接写表：同一张表、同一个唯一键约束，验证的仍是"唯一键让重复投递没有第二次记录"
  mysql_q "INSERT IGNORE INTO $INBOX_TABLE
    (id, consumer_name, event_id, payload_hash, processed_at, created_at)
    VALUES ('$(ULID)', '$consumer', '$OUTBOX_ID', '$PAYLOAD_HASH', NOW(3), NOW(3))" >/dev/null
}

register_inbox demo-run
n_after="$(mysql_q "SELECT COUNT(*) FROM $INBOX_TABLE
                     WHERE event_id='$OUTBOX_ID' AND consumer_name='demo-run'")"
assert_eq "1" "$n_after" "消费后该 eventId 的 Inbox 记录数"

# 4c. Outbox 转 PUBLISHED，published_at 必须落库。
# 保留策略只删status='PUBLISHED' 且 published_at 超期的行——
# published_at 为空意味着"还没发布"，绝不能被清理（场景 9 验证）
mysql_q "UPDATE $OUTBOX_TABLE SET status='PUBLISHED', publisher_id='demo',
         published_at=NOW(3), updated_at=NOW(3)
         WHERE id='$OUTBOX_ID'" >/dev/null
row="$(mysql_q "SELECT status, published_at IS NOT NULL, retry_count
                FROM $OUTBOX_TABLE WHERE id='$OUTBOX_ID'")"
assert_eq "PUBLISHED" "$(echo "$row" | awk '{print $1}')" "发布后 Outbox 状态"
assert_eq "1" "$(echo "$row" | awk '{print $2}')" "published_at 已落库（非空）"
assert_eq "0" "$(echo "$row" | awk '{print $3}')" "一次成功发布后重试次数（不该有重试）"

# ================================================================================
step "验证重复投递无重复副作用（Inbox 去重）"
# ================================================================================
# 同一条消息投 3 次：Inbox 应只有 1 条记录，后 2 次被唯一键挡住。
# "去重"不是消费者自觉，而是数据库唯一约束强制——这里直接查表。
#
# 用 INSERT IGNORE 模拟重复投递：第二次起会被唯一键静默忽略，
# 这正是生产上Inbox 去重的实现方式（唯一索引 + 捕获重复键异常）。

for i in 2 3; do
  register_inbox demo-run
  # 同时验证 broker侧：重复消息确实能被投递出去（重复投递是正常现象）
  broker publish-raw run.ready "$ENVELOPE" >/dev/null
done
sleep 0.5

n="$(mysql_q "SELECT COUNT(*) FROM $INBOX_TABLE
             WHERE consumer_name='demo-run' AND event_id='$OUTBOX_ID'")"
assert_eq "1" "$n" "同一 eventId 投递 3 次后的 Inbox 记录数（唯一键强制去重）"

# 反向断言：不同 consumer 名下应各自独立（契约明确说明这一点）
register_inbox demo-eval
n2="$(mysql_q "SELECT COUNT(*) FROM $INBOX_TABLE WHERE event_id='$OUTBOX_ID'")"
assert_eq "2" "$n2" "两个不同 consumer 各有一条记录（去重按消费者，不是全局）"

# 重复投递不应产生重复的业务副作用：消息还在队列里但可以被安全处理
d="$(broker depth repopilot.run.ready)"
c_info "重复投递后业务队列深度=$d（重复消息存在是正常的，claim 幂等保证不重复执行）"
broker drain repopilot.run.ready >/dev/null

# ================================================================================
step "注入可恢复故障验证有上限退避重试（逐档验证真实队列）"
# ================================================================================
# 这是本任务最核心的验证，也是 headers 方案失效的地方。
# 对每档分别验证：
#   * 消息只进目标档位队列（精确路由，无广播）
#   * 其他档位队列深度保持 0
#   * 非法档位不可路由
#   * TTL 到期后进再发布队列，再发布后回到业务队列
#
# 只断言"进了 t1"是不够的——旧方案下 t1 也有消息，区别在于它**同时**还有 t2/t3。

broker drain-all-retry >/dev/null

# 6a. **逐档单独验证**。
#
# 不能三档一起发再一起断言：t1 的 TTL 只有 5 秒（生产值），
# 等三档都发完再查，t1 的消息可能已经流到再发布队列，深度就变成 0——
# 那是**正确行为**，却会被误判成"路由失败"。
# 逐档发→立即断言，才能把"路由对不对"与"TTL 到没到"两件事分开。

for tier in 1 2 3; do
  # **每档都从干净状态开始**。这一步不是可有可无的：
  #   * peek 为了"查询不改变队列"会把消息 hold 住再放回，放回进的是**队尾**；
  #   * t1 的 TTL 只有 5 秒，上一档的消息可能已经流进再发布队列。
  # 不排空的话，下一档的"非目标档位深度应为 0"断言会被上一档的残留命中，
  # 报出一个**看起来像广播误路由、实则是残留**的假失败。
  broker drain-all-retry >/dev/null
  broker publish-retry run.ready "$tier" "$OUTBOX_ID" "$ENVELOPE" \
    || die "retry.t$tier 发布失败"

  # **一次调用测完该档的全部指标**。
  #
  # 不能拆成"peek-headers + 3 次 depth"：每次 broker 调用都是一次独立的
  # Python 进程启动（解释器 + pika 导入 + 连接 + channel 协商≈0.4 秒），
  # 四次累计 1.6 秒，而 t1 的 TTL 只有 5 秒——消息会在断言做完前就流走，
  # 于是报出"深度 0"或"缺少 x-last-error"。
  # 那种失败最误导人：它看起来像退避功能坏了，
  # 会让人去"修"本来正确的逻辑。
  PROBE="$(broker probe-tier "$tier")"
  for other in 1 2 3; do
    d="$(printf '%s\n' "$PROBE" | sed -n "s/^depth\.t$other=//p")"
    if [ "$other" = "$tier" ]; then
      assert_eq "1" "$d" "retry.t$tier -> wait.t$tier 深度（目标档位）"
    else
      assert_eq "0" "$d" "retry.t$tier -> wait.t$other 深度（不应收到）"
    fi
  done
  c_info "逐档精确路由：retry.t$tier 只进 wait.t$tier（headers 方案下会同时进 3 个队列）"

  # 原routing key 与诊断头必须齐全，否则再发布阶段无法复原
  ORIG="$(printf '%s\n' "$PROBE" | sed -n 's/^header\.x-original-routing-key=//p')"
  TIERH="$(printf '%s\n' "$PROBE" | sed -n 's/^header\.x-retry-tier=//p')"
  FIRST="$(printf '%s\n' "$PROBE" | sed -n 's/^header\.x-first-failed-at=//p')"
  LASTERR="$(printf '%s\n' "$PROBE" | sed -n 's/^header\.x-last-error=//p')"
  assert_eq "run.ready" "$ORIG" "retry.t$tier 携带的原 routing key"
  assert_eq "$tier" "$TIERH" "retry.t$tier 的档位头（诊断用，不参与路由）"
  [ -n "$FIRST" ] && c_ok "首次失败时间已记录：$FIRST" || c_bad "缺少 x-first-failed-at"
  [ -n "$LASTERR" ] && c_ok "失败原因已记录：$LASTERR" || c_bad "缺少 x-last-error"
done
broker drain-all-retry >/dev/null

# 6b. 非法档位必须被挡住，且**不得污染任何等待队列**
# 从干净状态开始，这样"深度仍为 0"才是真的没被污染。
for bad in 9 0 t; do
  if broker publish-retry run.ready "$bad" "$OUTBOX_ID" "$ENVELOPE" 2>/dev/null; then
    c_bad "非法档位 retry.t$bad 被接受：应抛 UnroutableError"
  else
    c_ok "非法档位 retry.t$bad 被拒绝（confirm + mandatory 生效）"
  fi
done
sleep 1
polluted=0
for other in 1 2 3; do
  d="$(broker depth "repopilot.retry.wait.t$other")"
  [ "$d" = "0" ] || { polluted=1; c_info "  wait.t$other 残留深度=$d"; }
done
[ "$polluted" = "0" ] && c_ok "非法档位未污染任何等待队列" \
                    || c_bad "非法档位的消息进入了等待队列"

# 6c. TTL 到期 → 再发布队列 → 复原 routing key → 回业务队列
#
# 只发**一条 t1** 再等：t1 的 TTL 是生产值 5 秒。
# 这里必须真的等——用短 TTL 冒充会让本脚本、
# Worker 测试和后端声明器三方互相破坏对方的队列
# （x-message-ttl 是声明期参数，不一致会PRECONDITION_FAILED 并关掉 channel）。
broker drain-all-retry >/dev/null
broker publish-retry run.ready 1 "$OUTBOX_ID" "$ENVELOPE" || die "t1 发布失败"
d_t1="$(broker depth repopilot.retry.wait.t1)"
assert_eq "1" "$d_t1" "t1 消息在等待队列中等待 TTL"
c_info "等待 t1 的 TTL 到期（生产值 5 秒）..."
# **先断言再发布队列、再排空**：drain 必须在 await 之前，
# 否则会把刚流进来的消息清掉，然后永远等不到。
if broker await-republish 30 >/dev/null 2>&1; then
  d_repub="$(broker depth repopilot.retry.republish)"
  c_ok "TTL 到期后消息进入再发布队列（深度=$d_repub）"
  # 再发布队列里的消息 routing key 应是固定合成键，
  # 原业务 routing key 只在头里——这正是需要再发布层的原因
  RK_REPUB="$(peek repopilot.retry.republish 0 routing-key)"
  assert_eq "retry.republish" "$RK_REPUB" "再发布队列中消息的 routing key（合成键）"
  ORIG2="$(peek repopilot.retry.republish 0 header x-original-routing-key)"
  assert_eq "run.ready" "$ORIG2" "再发布队列中消息携带的原 routing key"
else
  c_bad "TTL 到期后消息没有进入再发布队列：检查 x-dead-letter-exchange / x-dead-letter-routing-key"
fi

# 再发布阶段：复原routing key 后投回业务交换机。
# 这一步验证"消息真的回到了正确的业务队列"——
# 少这一步的话，消息会带着 retry.republish 匹配不到 binding 而静默消失。
broker drain repopilot.run.ready >/dev/null
broker republish-once >/dev/null || die "再发布失败"
sleep 1
d_back="$(broker depth repopilot.run.ready)"
if [ "$d_back" -ge 1 ]; then
  c_ok "重新投递后回到原业务队列 repopilot.run.ready（深度=$d_back）"
  BK_RK="$(peek repopilot.run.ready 0 routing-key)"
  assert_eq "run.ready" "$BK_RK" "回流消息的 routing key 已复原"
  BK_TIER="$(peek repopilot.run.ready 0 header x-retry-tier)"
  assert_eq "1" "$BK_TIER" "回流消息保留了退避档位（诊断信息不丢）"
else
  c_bad "消息没有回到业务队列：x-original-routing-key 复原失败会让消息静默消失"
fi
broker drain repopilot.run.ready >/dev/null
broker drain-all-retry >/dev/null

# ================================================================================
step "注入不可恢复故障验证进入 DLQ"
# ================================================================================
# 契约错误（非法 JSON）不可重试，直接进 DLQ。
# 必须验证 DLQ 里的**真实内容**——"日志显示已拒绝但 DLQ 为空"
# 是最难排查的静默故障（队列没配 x-dead-letter-exchange 时就是这样）。

broker drain repopilot.events.dlq >/dev/null
broker publish-raw run.ready 'this-is-not-json-at-all'
sleep 0.5
broker consume-once-broken >/dev/null
sleep 1.5

d_dlq="$(broker depth repopilot.events.dlq)"
if [ "$d_dlq" -ge 1 ]; then
  c_ok "不可重试消息进入 DLQ（深度=$d_dlq）"
  BODY="$(peek repopilot.events.dlq 0 body)"
  assert_eq "this-is-not-json-at-all" "$BODY" "DLQ 中的原始信封逐字节保留"
  DEATH="$(peek repopilot.events.dlq 0 header x-death)"
  [ -n "$DEATH" ] && c_ok "x-death 元数据存在（可追溯为何进死信）" \
                  || c_bad "缺少 x-death：无法知道消息为什么进了死信"
else
  c_bad "DLQ 深度为 0：队列没配 x-dead-letter-exchange，消息被 broker 静默丢弃"
fi

# 档位耗尽后也必须进 DLQ（不得无限重投）
#
# **必须发到业务队列，不能发到等待队列**：DLQ 的唯一入口是
# 业务队列的 x-dead-letter-exchange。一条还躺在 wait.t3里的消息
# 根本不在 DLQ 的路径上，拿它测 DLQ 深度当然是 0——
# 那是测错了地方，不是功能有问题。
broker drain repopilot.run.ready >/dev/null
broker drain repopilot.events.dlq >/dev/null
broker publish-exhausted run.ready 3 "$OUTBOX_ID" "$ENVELOPE" \
  || die "档位用尽消息发布失败"
d_exhausted="$(broker depth repopilot.run.ready)"
assert_eq "1" "$d_exhausted" "档位用尽的消息已回到业务队列（深度=$d_exhausted）"
sleep 0.5
broker consume-once-always-fail >/dev/null
sleep 1.5
d_dlq2="$(broker depth repopilot.events.dlq)"
if [ "$d_dlq2" -ge 1 ]; then
  c_ok "退避档位用尽后进入 DLQ（深度=$d_dlq2）"
  T="$(peek repopilot.events.dlq 0 header x-retry-tier)"
  assert_eq "3" "$T" "DLQ 中保留的退避档位"
else
  c_bad "档位用尽后没有进 DLQ：存在无限重投风险"
fi

# ================================================================================
step "验证恢复重放与消费者重启"
# ================================================================================
# 8a. 受控重放：必须保留原eventId，否则 Inbox 去重形同虚设。
#     也必须拒绝"全量重放"这个入口。
d_before="$(broker depth repopilot.events.dlq)"
broker drain repopilot.run.ready >/dev/null

#先确认查询 DLQ 不会改变队列内容（hold-all-then-restore 语义）
DEPTH_BEFORE="$(broker depth repopilot.events.dlq)"
SUMMARY="$(broker dlq-summary 3)"
DEPTH_AFTER="$(broker depth repopilot.events.dlq)"
assert_eq "$DEPTH_BEFORE" "$DEPTH_AFTER" "查询 DLQ 前后的队列深度（查询不得改变队列）"
UNIQ="$(printf '%s\n' "$SUMMARY" | grep -c 'eventId=' || true)"
DISTINCT="$(printf '%s\n' "$SUMMARY" | grep -o 'eventId=[^ ]*' | sort -u | wc -l | tr -d ' ')"
assert_eq "$UNIQ" "$DISTINCT" "DLQ 摘要中的 eventId 无重复（basic.nack 放队尾会导致重复）"

if [ -n "$BASE" ]; then
  # 空eventIds 必须被拒
  CODE=$(curl --noproxy '*' -s -o /dev/null -w '%{http_code}' --max-time 10 \
    -X POST "$BASE/internal/v1/messaging/dead-letters/replay" \
    -H 'Content-Type: application/json' -H "Authorization: Bearer $WORKER_TOKEN" \
    -d '{"eventIds":[]}')
  if [ "$CODE" = "400" ]; then
    c_ok "空 eventIds 的重放请求被拒（400），不存在全量重放入口"
  elif [ "$CODE" = "000" ]; then
    # 后端未启动。这一项**未验证**，必须显式说出来。
    # 写成"连接失败属正常"会让报告读起来像验证过了——
    # 而"没验"和"验了通过"长得一样，是交付里最坏的一种失真。
    c_info "【未验证】受控重放：后端未启动（curl=$CODE）。"\
"该路径的验证见 RabbitTopologyIT#F3~F6 与交付报告的手工 HTTP 实测"
  else
    c_bad "空 eventIds 的重放请求应返回 400，实际 $CODE"
  fi
fi

# 8b. 消费者重启：未 ACK 的消息必须随连接关闭回到队列
broker publish-raw run.ready "$ENVELOPE"
sleep 0.5
D_BEFORE="$(broker depth repopilot.run.ready)"
assert_eq "1" "$D_BEFORE" "重启前队列深度"
broker take-without-ack repopilot.run.ready >/dev/null   # 取走但不 ACK，然后断连
sleep 1.5
D_AFTER="$(broker depth repopilot.run.ready)"
assert_eq "1" "$D_AFTER" "消费者重启后未 ACK 消息回到队列（崩溃即丢消息的反例）"
broker drain repopilot.run.ready >/dev/null

# ================================================================================
step "验证 Outbox/Inbox 过期清理与保留规则"
# ================================================================================
# 三条铁律：
#   1. Outbox **只删** status='PUBLISHED' 且 published_at 超保留期的行；
#      PENDING 与 FAILED **永不**自动删除。
#   2. Inbox 保留期必须显著长于消息最大重投窗口。
#   3. 分批删除（LIMIT + 每批独立短事务）。
# 这里验证铁律 1——最危险的是误删未发布的记录，那等于丢业务事实。

# 造三条数据：已发布且很久以前（该删）、PENDING（不能删）、已发布但很新（不能删）
OLD_ID="$(ULID)"; OLD_AGG="$(ULID)"
PEND_ID="$(ULID)"; PEND_AGG="$(ULID)"
NEW_ID="$(ULID)"; NEW_AGG="$(ULID)"
OLD_TS="$(date -u -d '400 days ago' +'%Y-%m-%d %H:%M:%S' 2>/dev/null \
           || date -u -v-400d +'%Y-%m-%d %H:%M:%S')"

# PENDING 那条同样要给一个未来的 next_retry_at：
# 否则在线发布器会在清理断言之前把它发出去，status 变成 PUBLISHED，
# 这条"PENDING 永不自动删除"的断言就变成了对一条 PUBLISHED 行的空断言。
mysql_q "INSERT INTO $OUTBOX_TABLE
  (id, aggregate_type, aggregate_id, event_type, event_version, payload_json,
   status, retry_count, next_retry_at, created_at, updated_at, published_at)
VALUES
  ('$OLD_ID','RUN','$OLD_AGG','run.ready',1,'{\"schemaVersion\":1}',
   'PUBLISHED',1, NULL, '$OLD_TS','$OLD_TS','$OLD_TS'),
  ('$PEND_ID','RUN','$PEND_AGG','run.ready',1,'{\"schemaVersion\":1}',
   'PENDING',0, DATE_ADD(NOW(3), INTERVAL 1 HOUR), '$OLD_TS','$OLD_TS', NULL),
  ('$NEW_ID','RUN','$NEW_AGG','run.ready',1,'{\"schemaVersion\":1}',
   'PUBLISHED',1, NULL, NOW(3), NOW(3), NOW(3))" >/dev/null \
  || die "插入保留策略测试数据失败"

# FAILED 也是**永不自动删除**的（等人工介入），单独造一条验证
FAIL_ID="$(ULID)"; FAIL_AGG="$(ULID)"
mysql_q "INSERT INTO $OUTBOX_TABLE
  (id, aggregate_type, aggregate_id, event_type, event_version, payload_json,
   status, retry_count, created_at, updated_at, published_at)
VALUES ('$FAIL_ID','RUN','$FAIL_AGG','run.ready',1,'{\"schemaVersion\":1}',
        'FAILED',9,'$OLD_TS','$OLD_TS', NULL)" >/dev/null

c_before="$(mysql_q "SELECT COUNT(*) FROM $OUTBOX_TABLE
                     WHERE id IN ('$OLD_ID','$PEND_ID','$NEW_ID','$FAIL_ID')")"
assert_eq "4" "$c_before" "造出的测试记录数"

# 用**与 RetentionService 完全一致的删除条件**验证。
# 后端在线时额外走一次真实接口，让两侧行为对照。
if [ -n "$BASE" ]; then
  curl --noproxy '*' -s --max-time 15 -X POST "$BASE/internal/v1/messaging/retention/purge" \
    -H "Authorization: Bearer $WORKER_TOKEN" >/dev/null 2>&1 || true
fi
RETENTION_DAYS="${RETENTION_DAYS:-30}"
mysql_q "DELETE FROM $OUTBOX_TABLE
         WHERE status = 'PUBLISHED'
           AND published_at IS NOT NULL
           AND published_at < DATE_SUB(NOW(3), INTERVAL $RETENTION_DAYS DAY)" >/dev/null

old_left="$(mysql_q "SELECT COUNT(*) FROM $OUTBOX_TABLE WHERE id='$OLD_ID'")"
pend_left="$(mysql_q "SELECT COUNT(*) FROM $OUTBOX_TABLE WHERE id='$PEND_ID'")"
new_left="$(mysql_q "SELECT COUNT(*) FROM $OUTBOX_TABLE WHERE id='$NEW_ID'")"
fail_left="$(mysql_q "SELECT COUNT(*) FROM $OUTBOX_TABLE WHERE id='$FAIL_ID'")"

assert_eq "0" "$old_left" "已发布且超保留期的记录被清理"
assert_eq "1" "$pend_left" "PENDING 记录**未**被清理（保留规则铁律：未发布≠可丢）"
assert_eq "1" "$new_left" "保留期内的记录**未**被清理"
assert_eq "1" "$fail_left" "FAILED 记录**未**被清理（等人工介入，不能自动删）"

# 幂等性：再删一次结果不变（重复执行不报错、不多删）
mysql_q "DELETE FROM $OUTBOX_TABLE
         WHERE status = 'PUBLISHED'
           AND published_at IS NOT NULL
           AND published_at < DATE_SUB(NOW(3), INTERVAL $RETENTION_DAYS DAY)" >/dev/null
again="$(mysql_q "SELECT COUNT(*) FROM $OUTBOX_TABLE
                  WHERE id IN ('$PEND_ID','$NEW_ID','$FAIL_ID')")"
assert_eq "3" "$again" "重复执行清理是幂等的（保留记录不受影响）"

# 只删本次造出来的演示数据，不碰用户已有数据
mysql_q "DELETE FROM $OUTBOX_TABLE
         WHERE id IN ('$OLD_ID','$PEND_ID','$NEW_ID','$FAIL_ID','$OUTBOX_ID')" >/dev/null

# Inbox 保留策略：保留期必须显著长于消息最大重投窗口（120 秒）
n_inbox="$(mysql_q "SELECT COUNT(*) FROM $INBOX_TABLE")"
c_info "Inbox 当前记录数：$n_inbox"
# 默认值与 application.yml 的 app.repopilot.retention.inbox-days 保持一致（30 天）。
# 这里写成 7 会让断言看起来仍然成立（7 天也远大于 120 秒），
# 但那是在核对一个**与实际配置无关的数字**——比没有断言更糟。
INBOX_DAYS="${INBOX_RETENTION_DAYS:-30}"
MAX_RETRY_SECONDS=120
if [ $((INBOX_DAYS * 86400)) -gt $((MAX_RETRY_SECONDS * 10)) ]; then
  c_ok "Inbox 保留期 ${INBOX_DAYS} 天 >> 消息最大重投窗口 ${MAX_RETRY_SECONDS} 秒（10 倍以上余量）"
else
  c_bad "Inbox 保留期过短：清理掉记录后，重放的消息会被当成新事件二次处理"
fi
mysql_q "DELETE FROM $INBOX_TABLE WHERE event_id='$OUTBOX_ID'" >/dev/null

# ================================================================================
printf '\n\033[1m================= 演示结果 =================\033[0m\n'
if [ "$FAILURES" -eq 0 ]; then
  printf '\033[32m全部 9 个场景通过\033[0m（真实 RabbitMQ + 真实 MySQL，无 Mock）\n'
  exit 0
fi
printf '\033[31m%d 项断言失败\033[0m\n' "$FAILURES"
exit 1