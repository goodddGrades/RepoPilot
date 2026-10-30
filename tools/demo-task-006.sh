#!/usr/bin/env bash
# TASK-006 端到端演示：统一 Trace 查询与可视化回放。
#
# 用途：按"页面会走的顺序"调用真实接口，同时造出**在线执行**与**离线导入**两类轨迹，
# 再用同一套查询接口读取它们，证明：
#   * 在线 Run 的轨迹按接受尝试读取、Attempt 可过滤、可翻页；
#   * 离线 Run 的轨迹没有执行尝试，`attemptId` 一律为空（不伪造 Attempt），完整度按保守口径；
#   * 游标是签名游标：篡改、换过滤条件、跨 Run 复用都会被 400 拒绝；
#   * 同一毫秒的事件按数据库行 id 稳定排序，翻页不重不漏。
# 每一步都标注对应的页面位置，跑完打开前端能看到同样的数据。
#
# 前置：
#   1. 后端用开发配置启动，且必须显式给 Worker 凭证（留空时 /internal/v1 一律 401）：
#        SERVER_PORT=18080 REPOPILOT_WORKER_TOKEN=local-demo-token ./mvnw spring-boot:run
#   2. 前端 `npm run dev`（默认 http://127.0.0.1:5173），用于肉眼确认回放面板；
#   3. 库里已有 TASK-002/003 的算子种子（evaluatorKey=output.non_empty）。
#
# 用法：
#   BASE=http://127.0.0.1:18080/api/v1 WORKER_TOKEN=local-demo-token bash tools/demo-task-006.sh
#
# 每次运行都带随机后缀，可以对同一个库重复执行。
set -u

BASE="${BASE:-http://127.0.0.1:18080/api/v1}"
WORKER_TOKEN="${WORKER_TOKEN:-local-demo-token}"
SUFFIX="${SUFFIX:-$(date +%s)$$}"
# /internal/v1 与 /api/v1 同级
ROOT="${BASE%/api/v1}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
HERE_WIN="$(cd "$HERE" && { pwd -W 2>/dev/null || pwd; })"

key() { printf 'k%s%s' "$(date +%s%N)" "$RANDOM"; }
post() { # post <path> <json>
  curl --noproxy '*' -s --max-time 30 -X POST "$BASE$1" -H 'Content-Type: application/json' \
       -H "Idempotency-Key: $(key)" -d "$2"
}
get() { curl --noproxy '*' -s --max-time 30 "$BASE$1"; }
worker_post() { # worker_post <path> <json>
  curl --noproxy '*' -s --max-time 30 -X POST "$ROOT$1" -H 'Content-Type: application/json' \
       -H "Authorization: Bearer $WORKER_TOKEN" -d "$2"
}
# 取第一个字段值（够用的提取器：不引第三方 JSON 工具，缺 jq 也能跑）
field() { grep -o "\"$1\":\"\?[^\",}]*" | head -1 | sed "s/\"$1\":\"\?//"; }
# 只看 HTTP 状态码，用于"应当被拒绝"的场景
status_of() { curl --noproxy '*' -s -o /dev/null -w '%{http_code}' --max-time 30 "$BASE$1"; }
# 26 位 Crockford base32：eventId 由服务端校验为 ULID，随手拼字符串会被 400 拒绝
ulid() {
  local alphabet=0123456789ABCDEFGHJKMNPQRSTVWXYZ ts out='' i
  ts=$(( $(date +%s%3N) ))
  for ((i = 0; i < 10; i++)); do
    out="${alphabet:$((ts % 32)):1}$out"
    ts=$((ts / 32))
  done
  for ((i = 0; i < 16; i++)); do
    out="$out${alphabet:$((RANDOM % 32)):1}"
  done
  printf '%s' "$out"
}
ev() { # ev <事件类型> <sequenceNo 或空> <occurredAt> <payload> [artifactId]
  local seq='' art=''
  [ -n "$2" ] && seq=",\"sequenceNo\":$2"
  [ -n "${5:-}" ] && art=",\"artifactId\":\"$5\""
  printf '{"eventId":"%s","eventType":"%s"%s%s,"occurredAt":"%s","payload":%s}' \
    "$(ulid)" "$1" "$seq" "$art" "$3" "$4"
}

echo "== 0. 前置检查：后端可达、Worker 凭证有效 =="
get "/experiments?pageSize=1" | grep -q '"code":"OK"' || { echo "后端不可达：$BASE" >&2; exit 1; }
[ -n "$WORKER_TOKEN" ] || { echo "需要 WORKER_TOKEN（服务端也要配同一个值）" >&2; exit 1; }
echo "BASE=$BASE  SUFFIX=$SUFFIX"

echo
echo "== 1. 准备已发布版本：Agent / 数据集 / 评测方案 =="
AGENT_ID=$(post "/agents" "{\"code\":\"t6-agent-$SUFFIX\",\"name\":\"TASK-006 演示 Agent\",\"agentType\":\"MOCK\"}" | field id)
AV_ID=$(post "/agents/$AGENT_ID/versions" "{\"runnerType\":\"MOCK\",\"config\":{\"model\":\"mock-model\"}}" | field id)
post "/agent-versions/$AV_ID/publish" '{}' >/dev/null
DS_ID=$(post "/datasets" "{\"code\":\"t6-dataset-$SUFFIX\",\"name\":\"TASK-006 演示数据集\"}" | field id)
DV_ID=$(post "/datasets/$DS_ID/versions" '{"description":"v1"}' | field id)
post "/dataset-versions/$DV_ID/cases" \
  '{"caseKey":"demo-001","taskType":"AGENT_TASK","input":{"question":"1加1等于多少？"},"expected":{"answer":"2"}}' >/dev/null
curl --noproxy '*' -s --max-time 30 -X POST "$BASE/dataset-versions/$DV_ID/publish" -H "Idempotency-Key: $(key)" >/dev/null
EV1=$(get "/evaluators?pageSize=100" | grep -o '"id":"[^"]*","evaluatorKey":"output.non_empty"' | sed 's/"id":"//;s/".*//')
PLAN_ID=$(post "/evaluation-plans" "{\"code\":\"t6-plan-$SUFFIX\",\"name\":\"TASK-006 演示方案\"}" | field id)
PV_ID=$(post "/evaluation-plans/$PLAN_ID/versions" \
  "{\"config\":{},\"items\":[{\"evaluatorDefinitionId\":\"$EV1\",\"executionOrder\":1,\"required\":true,\"config\":{}}]}" | field id)
curl --noproxy '*' -s --max-time 30 -X POST "$BASE/evaluation-plan-versions/$PV_ID/publish" -H "Idempotency-Key: $(key)" >/dev/null
echo "agentVersion=$AV_ID datasetVersion=$DV_ID planVersion=$PV_ID"

echo
echo "== 2. 在线实验：1 Target × 1 重复 = 1 个 Run，启动后 Worker 领取 — 对应「在线/离线评测」页 =="
EXP_ID=$(post "/experiments" "{\"name\":\"T6 在线演示-$SUFFIX\",\"mode\":\"ONLINE\",\"datasetVersionId\":\"$DV_ID\",\"evaluationPlanVersionId\":\"$PV_ID\",\"config\":{\"budget\":{\"maxCostUsd\":1.0}},\"targets\":[{\"targetKey\":\"demo_agent\",\"displayName\":\"演示 Agent\",\"agentVersionId\":\"$AV_ID\",\"repeatCount\":1}]}" | field id)
curl --noproxy '*' -s --max-time 30 -X POST "$BASE/experiments/$EXP_ID/start" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" -d '{}' | grep -o '"status":"[A-Z]*"'
CLAIM=$(worker_post "/internal/v1/work/claim" "{\"workerId\":\"demo-worker-$SUFFIX\",\"capabilities\":[\"RUN:MOCK\"],\"maxTasks\":1}")
RUN_ID=$(echo "$CLAIM" | field runId)
ATTEMPT_ID=$(echo "$CLAIM" | field attemptId)
LEASE=$(echo "$CLAIM" | field leaseToken)
CLAIMED_EXP=$(echo "$CLAIM" | field experimentId)
[ -n "$RUN_ID" ] || { echo "领取失败，响应：$CLAIM" >&2; exit 1; }
if [ "$CLAIMED_EXP" != "$EXP_ID" ]; then
  echo "领取到的是别的实验（$CLAIMED_EXP）的任务：领取是全局队列语义，" >&2
  echo "请先清掉遗留的 QUEUED/RUNNING Run 或换一个库再跑。" >&2
  exit 1
fi
echo "runId=$RUN_ID attemptId=$ATTEMPT_ID"

echo
echo "== 3. 上传一个执行产物，并把它挂到 OUTPUT_PRODUCED 事件上 — 对应详情页的「查看附件」 =="
ART_ID=$(curl --noproxy '*' -s --max-time 60 -X POST "$BASE/artifacts" -H "Idempotency-Key: $(key)" \
  -F "runId=$RUN_ID" -F "artifactType=OUTPUT" -F "file=@$HERE_WIN/examples/offline-runs.jsonl;type=application/x-ndjson" \
  | field id)
[ -n "$ART_ID" ] || { echo "附件上传失败" >&2; exit 1; }
echo "artifactId=$ART_ID（响应不含 objectKey，存储路径不外泄）"

echo
echo "== 4. 写入 10 条事件：含工具调用配对、只有开始没有结束的工具、失败事件、同毫秒平局 =="
EVENTS=$(printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s' \
  "$(ev RUN_STARTED 1 '2026-10-18T00:00:01.000Z' '{"runnerType":"MOCK"}')" \
  "$(ev MODEL_REQUEST 2 '2026-10-18T00:00:02.000Z' '{"model":"mock-model","requestSummary":"1加1等于多少？"}')" \
  "$(ev TOOL_STARTED 3 '2026-10-18T00:00:03.000Z' '{"toolCallId":"call-1","toolName":"search_web","argumentsSummary":"q=1+1"}')" \
  "$(ev TOOL_COMPLETED 4 '2026-10-18T00:00:04.000Z' '{"toolCallId":"call-1","toolName":"search_web","outcome":"SUCCESS","durationMs":120}')" \
  "$(ev TOOL_STARTED 5 '2026-10-18T00:00:05.000Z' '{"toolCallId":"call-2","toolName":"fetch_url","argumentsSummary":"url=https://example.com"}')" \
  "$(ev ERROR 6 '2026-10-18T00:00:06.000Z' '{"errorCode":"UPSTREAM_TIMEOUT","message":"演示：fetch_url 超时"}')" \
  "$(ev OUTPUT_PRODUCED 7 '2026-10-18T00:00:07.000Z' '{"outputSummary":"2"}' "$ART_ID")" \
  "$(ev RUN_COMPLETED 8 '2026-10-18T00:00:08.000Z' '{"taskOutcome":"SUCCESS"}')" \
  "$(ev OUTPUT_PRODUCED '' '2026-10-18T00:00:09.000Z' '{"outputSummary":"迟到事件 A（与下一条同毫秒、都没有 sequenceNo）"}')" \
  "$(ev OUTPUT_PRODUCED '' '2026-10-18T00:00:09.000Z' '{"outputSummary":"迟到事件 B"}')")
APPEND=$(worker_post "/internal/v1/run-attempts/$ATTEMPT_ID/events" "{\"leaseToken\":\"$LEASE\",\"events\":[$EVENTS]}")
echo "$APPEND" | grep -o '"status":"[A-Z]*"' | sort | uniq -c
case "$APPEND" in
  *'"code":"OK"'*) ;;
  *) echo "事件写入失败：$APPEND" >&2; exit 1 ;;
esac
# 封口要用"服务端确认的 eventId 排序后的清单"：自己拼一份会被判成清单不符
IDS=$(echo "$APPEND" | grep -o '"eventId":"[^"]*"' | sed 's/"eventId":"//;s/"//g' | sort)
MANIFEST=$(printf '%s' "$IDS" | sha256sum | cut -d' ' -f1)
COUNT=$(printf '%s\n' "$IDS" | wc -l | tr -d ' ')
worker_post "/internal/v1/run-attempts/$ATTEMPT_ID/complete" \
  "{\"leaseToken\":\"$LEASE\",\"taskOutcome\":\"SUCCESS\",\"output\":{\"answer\":\"2\"},\"usage\":{\"totalTokens\":12},\"expectedEventCount\":$COUNT,\"eventManifestSha256\":\"$MANIFEST\"}" \
  | grep -o '"status":"[A-Z]*"\|"traceEventCount":[0-9]*'

echo
echo "== 5. Run 详情：执行来源、状态、尝试数、事件数 — 对应「Run 详情」页头部 =="
DETAIL=$(get "/runs/$RUN_ID")
echo "mode=$(echo "$DETAIL" | field mode)  status=$(echo "$DETAIL" | field status)  taskOutcome=$(echo "$DETAIL" | field taskOutcome)  attemptCount=$(echo "$DETAIL" | field attemptCount)  traceEventCount=$(echo "$DETAIL" | field traceEventCount)"

echo
echo "== 6. 第 1 页（limit=4）：默认读取「接受尝试」 — 对应「轨迹回放」面板首屏 =="
PAGE1=$(get "/runs/$RUN_ID/events?limit=4")
echo "$PAGE1" | grep -o '"resolvedFrom":"[A-Z_]*"\|"hasMore":[a-z]*' | sort -u
echo "本页事件类型：$(echo "$PAGE1" | grep -o '"eventType":"[A-Z_]*"' | sed 's/"eventType":"//;s/"//' | paste -sd, -)"
CURSOR=$(echo "$PAGE1" | field nextCursor)
if [ -z "$CURSOR" ]; then
  echo "第 1 页没有 nextCursor（事件不足一页或写入失败）：首屏内容如下" >&2
  echo "$PAGE1" | head -c 400 >&2
  exit 1
fi
echo "nextCursor=$CURSOR"

echo
echo "== 7. 第 2 页（带签名游标）：与第 1 页无重叠 — 对应「加载下一页」 =="
PAGE2=$(get "/runs/$RUN_ID/events?limit=4&cursor=$CURSOR")
echo "第 2 页事件类型：$(echo "$PAGE2" | grep -o '"eventType":"[A-Z_]*"' | sed 's/"eventType":"//;s/"//' | paste -sd, -)"
P1_IDS=$(echo "$PAGE1" | grep -o '"eventId":"[^"]*"' | sed 's/"eventId":"//;s/"//g' | sort)
P2_IDS=$(echo "$PAGE2" | grep -o '"eventId":"[^"]*"' | sed 's/"eventId":"//;s/"//g' | sort)
OVERLAP=$(comm -12 <(printf '%s\n' "$P1_IDS") <(printf '%s\n' "$P2_IDS") | grep -c . || true)
echo "两页重叠条数：$OVERLAP（应为 0）"

echo
echo "== 8. 显式指定 Attempt 与「全部尝试」：区分为排障而展开，而不是默认混在一起 =="
get "/runs/$RUN_ID/events?limit=2&attemptId=$ATTEMPT_ID" | grep -o '"resolvedFrom":"[A-Z_]*"' | head -1
get "/runs/$RUN_ID/events?limit=2&allAttempts=true" | grep -o '"resolvedFrom":"[A-Z_]*"' | head -1

echo
echo "== 9. 事件类型过滤：只取工具调用 =="
FILTERED=$(get "/runs/$RUN_ID/events?eventType=TOOL_STARTED")
echo "过滤后条数：$(echo "$FILTERED" | grep -o '"eventId":"' | wc -l | tr -d ' ')"
echo "过滤后类型：$(echo "$FILTERED" | grep -o '"eventType":"[A-Z_]*"' | sed 's/"eventType":"//;s/"//' | sort -u | paste -sd, -)（应只有 TOOL_STARTED）"

echo
echo "== 10. 游标的三种误用都被拒绝（400，且返回统一错误码而不是 500） =="
TAMPERED="${CURSOR%?}X"
printf '篡改游标      → HTTP %s  ' "$(status_of "/runs/$RUN_ID/events?cursor=$TAMPERED")"
get "/runs/$RUN_ID/events?cursor=$TAMPERED" | grep -o '"code":"[A-Z_]*"' | head -1
printf '换过滤条件复用 → HTTP %s  ' "$(status_of "/runs/$RUN_ID/events?cursor=$CURSOR&eventType=TOOL_STARTED")"
get "/runs/$RUN_ID/events?cursor=$CURSOR&eventType=TOOL_STARTED" | grep -o '"message":"[^"]*"' | head -1
printf '未知 Run      → HTTP %s\n' "$(status_of "/runs/01NOSUCHRUN00000000000000/events")"

echo
echo "== 11. 离线导入：2 个槽位（第 2 条轨迹没有事件） — 对应「离线轨迹导入」页 =="
OEXP_ID=$(post "/experiments" "{\"name\":\"T6 离线演示-$SUFFIX\",\"mode\":\"OFFLINE\",\"datasetVersionId\":\"$DV_ID\",\"evaluationPlanVersionId\":\"$PV_ID\",\"config\":{},\"targets\":[{\"targetKey\":\"demo_import\",\"displayName\":\"历史轨迹\",\"repeatCount\":2}]}" | field id)
JSONL=$(mktemp)
printf '%s\n' \
  '{"schemaVersion":1,"targetKey":"demo_import","caseKey":"demo-001","repeatIndex":1,"sourceSystem":"manual","sourceRecordId":"rec-1","taskOutcome":"SUCCESS","output":{"answer":"2"},"usage":{"costKnown":false},"events":[{"sourceEventId":"evt-1","sequenceNo":1,"eventType":"MODEL_REQUEST","occurredAt":"2026-10-17T04:00:00.000Z","payload":{"model":"legacy-chat","requestSummary":"1加1"}},{"sourceEventId":"evt-2","sequenceNo":2,"eventType":"MODEL_RESPONSE","occurredAt":"2026-10-17T04:00:02.000Z","payload":{"model":"legacy-chat","outputSummary":"2"}},{"sourceEventId":"evt-3","sequenceNo":3,"eventType":"OUTPUT_PRODUCED","occurredAt":"2026-10-17T04:00:03.000Z","payload":{"outputSummary":"2"}}]}' \
  '{"schemaVersion":1,"targetKey":"demo_import","caseKey":"demo-001","repeatIndex":2,"sourceSystem":"manual","sourceRecordId":"rec-2","taskOutcome":"UNKNOWN","usage":{"costKnown":false},"events":[]}' \
  > "$JSONL"
IMPORT_ART=$(curl --noproxy '*' -s --max-time 60 -X POST "$BASE/artifacts" -H "Idempotency-Key: $(key)" \
  -F "artifactType=IMPORT_FILE" -F "file=@$JSONL;type=application/x-ndjson" | field id)
BATCH_ID=$(post "/imports" "{\"importType\":\"OFFLINE_RUNS\",\"datasetVersionId\":\"$DV_ID\",\"experimentId\":\"$OEXP_ID\",\"fileArtifactId\":\"$IMPORT_ART\"}" | field id)
for _ in $(seq 1 30); do
  ST=$(get "/imports/$BATCH_ID" | field status)
  case "$ST" in COMPLETED|PARTIAL_FAILED|FAILED|CANCELLED) break ;; esac
  sleep 1
done
get "/imports/$BATCH_ID" | grep -o '"status":"[A-Z]*"\|"successCount":[0-9]*\|"failedCount":[0-9]*'
curl --noproxy '*' -s --max-time 30 -X POST "$BASE/experiments/$OEXP_ID/start" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" -d '{}' | grep -o '"status":"[A-Z]*"'
OFF_RUNS=$(get "/experiments/$OEXP_ID/runs?pageSize=50")
# 列表行里没有 traceEventCount：逐个读 Run 详情，按事件数把"有轨迹/空轨迹"分开
OFF_RUN=''; OFF_EMPTY=''
for rid in $(echo "$OFF_RUNS" | grep -o '"id":"[^"]*"' | sed 's/"id":"//;s/"//'); do
  detail=$(get "/runs/$rid")
  cnt=$(echo "$detail" | grep -o '"traceEventCount":[0-9]*' | head -1 | sed 's/.*://')
  echo "离线 Run $rid：mode=$(echo "$detail" | grep -o '"mode":"[A-Z]*"' | head -1 | sed 's/.*:"//;s/"//')  traceEventCount=${cnt:-?}"
  if [ "${cnt:-0}" = "0" ]; then OFF_EMPTY=$rid; else OFF_RUN=$rid; fi
done
[ -n "$OFF_RUN" ] || { echo "没有找到带事件的离线 Run" >&2; exit 1; }

echo
echo "== 12. 离线轨迹查询：没有 Attempt、完整度保守、事件照常可读 — 对应离线 Run 的回放面板 =="
ODETAIL=$(get "/runs/$OFF_RUN")
echo "mode=$(echo "$ODETAIL" | field mode)  attemptCount=$(echo "$ODETAIL" | field attemptCount)  traceEventCount=$(echo "$ODETAIL" | field traceEventCount)  traceCoverage=$(echo "$ODETAIL" | field traceCoverage)  acceptedAttemptId=$(echo "$ODETAIL" | field acceptedAttemptId)"
OFF_PAGE=$(get "/runs/$OFF_RUN/events?limit=10")
echo "scope.resolvedFrom=$(echo "$OFF_PAGE" | field resolvedFrom)  hasMore=$(echo "$OFF_PAGE" | field hasMore)"
echo "事件类型：$(echo "$OFF_PAGE" | grep -o '"eventType":"[A-Z_]*"' | sed 's/"eventType":"//;s/"//' | paste -sd, -)"
echo "事件里的 attemptId 字段出现次数：$(echo "$OFF_PAGE" | grep -o '"attemptId"' | wc -l | tr -d ' ')（离线应为 0）"
echo "无事件的离线 Run：$(get "/runs/$OFF_EMPTY/events?limit=10" | grep -o '"items":\[\]\|"hasMore":[a-z]*' | paste -sd' ' -)"

echo
echo "== 13. 回放页面入口（数据与上面接口一致，页面不写死任何演示内容） =="
echo "  在线 Run：http://127.0.0.1:5173/#/runs/$RUN_ID"
echo "  离线 Run：http://127.0.0.1:5173/#/runs/$OFF_RUN"
echo "  Run 列表：http://127.0.0.1:5173/#/experiments/$EXP_ID"
echo
echo "演示完成：在线 10 条事件（工具配对 1 组 + 只有开始 1 组 + 1 条失败事件 + 同毫秒 2 条），"
echo "离线 2 条轨迹（1 条有 3 条事件、1 条为 0 条且完整度 NONE）。"
rm -f "$JSONL" 2>/dev/null || true
