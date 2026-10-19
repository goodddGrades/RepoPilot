#!/usr/bin/env bash
# TASK-004 端到端演示脚本：只依赖 curl + sha256sum，按"页面与 Worker 会走的顺序"调用真实接口。
#
# 用途：验收时复现"提交 → 领取 → 事件 → 封口 → 待评测 Job"这条纵向链路；
# 每一步都标注对应的页面/角色，跑完打开前端能看到同样的数据。
#
# 前置：
#   1. 后端已启动（默认 http://127.0.0.1:18080），库里有 TASK-002/003 的算子种子；
#   2. 后端配置了 Worker 凭证：REPOPILOT_WORKER_TOKEN=local-demo-token（空值时 /internal/v1 全部 401）；
#   3. Worker 侧用同一个值：REPOPILOT_WORKER_TOKEN=local-demo-token。
#
# 用法：
#   BASE=http://127.0.0.1:18080/api/v1 WORKER_TOKEN=local-demo-token bash tools/demo-task-004.sh
#
# 每次运行都带随机后缀，可以对同一个库重复执行。
set -u

BASE="${BASE:-http://127.0.0.1:18080/api/v1}"
# Worker 协议不在 /api/v1 下：/internal/v1 是独立的鉴权面，别把业务前缀带过去
ROOT="${BASE%/api/v1}"
WORKER_TOKEN="${WORKER_TOKEN:-local-demo-token}"
SUFFIX="${SUFFIX:-$(date +%s)$$}"

key() { printf 'k%s%s' "$(date +%s%N)" "$RANDOM"; }
post() { # post <path> <json>
  curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' \
       -H "Idempotency-Key: $(key)" -d "$2"
}
worker() { # worker <path> <json>
  curl -s -X POST "$ROOT$1" -H 'Content-Type: application/json' \
       -H "Authorization: Bearer $WORKER_TOKEN" -d "$2"
}
get() { curl -s "$BASE$1"; }
field() { grep -o "\"$1\":\"\?[^\",}]*" | head -1 | sed "s/\"$1\":\"\?//"; }

# 生成 26 位 Crockford Base32 ULID（eventId 由 Worker 首次生成，服务端不代生成）
CROCK="0123456789ABCDEFGHJKMNPQRSTVWXYZ"
ulid() {
  local ts=$(( $(date +%s%N) / 1000000 )) out="" i rand=""
  for i in 1 2 3 4 5 6 7 8 9 10; do
    out="${CROCK:$(( ts % 32 )):1}$out"
    ts=$(( ts / 32 ))
  done
  for i in $(seq 1 16); do rand="$rand${CROCK:$(( RANDOM % 32 )):1}"; done
  printf '%s%s' "$out" "$rand"
}

# 事件清单摘要：eventId 按 ASCII 升序、LF 连接、末尾无 LF，对 UTF-8 求 SHA-256
manifest() {
  local sorted
  sorted=$(printf '%s\n' "$@" | sort)
  if command -v sha256sum >/dev/null 2>&1; then
    printf '%s' "$sorted" | sha256sum | cut -d' ' -f1
  else
    printf '%s' "$sorted" | shasum -a 256 | cut -d' ' -f1
  fi
}

echo "== 1. 创建 Agent 并发布配置版本（Runner=MOCK）— 对应「Agent 管理」页 =="
AGENT_ID=$(post "/agents" "{\"code\":\"t4-agent-$SUFFIX\",\"name\":\"TASK-004 演示 Agent\",\"agentType\":\"MOCK\"}" | field id)
AV_ID=$(post "/agents/$AGENT_ID/versions" '{"runnerType":"MOCK","config":{"model":"mock-model","temperature":0}}' | field id)
curl -s -X POST "$BASE/agent-versions/$AV_ID/publish" -H "Idempotency-Key: $(key)" | grep -o '"status":"[A-Z]*"'
echo "agentVersionId=$AV_ID"

echo
echo "== 2. 创建数据集（3 个样本）并发布 — 对应「数据集中心」页 =="
DS_ID=$(post "/datasets" "{\"code\":\"t4-dataset-$SUFFIX\",\"name\":\"TASK-004 演示数据集\"}" | field id)
DV_ID=$(post "/datasets/$DS_ID/versions" '{"description":"v1"}' | field id)
for i in 1 2 3; do
  post "/dataset-versions/$DV_ID/cases" \
    "{\"caseKey\":\"case-$i\",\"taskType\":\"AGENT_TASK\",\"input\":{\"prompt\":\"请回答第 $i 题\"},\"expected\":{\"answer\":\"42\"}}" >/dev/null
done
curl -s -X POST "$BASE/dataset-versions/$DV_ID/publish" -H "Idempotency-Key: $(key)" \
  | grep -o '"status":"[A-Z]*"\|"caseCount":[0-9]*'

echo
echo "== 3. 创建评测方案（1 个算子）并发布 — 对应「评测方案」页 =="
EV1=$(get "/evaluators?pageSize=100" | grep -o '"id":"[^"]*","evaluatorKey":"output.non_empty"' | sed 's/"id":"//;s/".*//')
PLAN_ID=$(post "/evaluation-plans" "{\"code\":\"t4-plan-$SUFFIX\",\"name\":\"TASK-004 演示方案\"}" | field id)
PV_ID=$(post "/evaluation-plans/$PLAN_ID/versions" \
  "{\"config\":{},\"items\":[{\"evaluatorDefinitionId\":\"$EV1\",\"executionOrder\":1,\"required\":true,\"config\":{}}]}" | field id)
curl -s -X POST "$BASE/evaluation-plan-versions/$PV_ID/publish" -H "Idempotency-Key: $(key)" | grep -o '"status":"[A-Z]*"'

echo
echo "== 4. 创建实验草稿：2 Target × 3 样本 × 2 重复 = 12 个 Run（此时还没有 Run）— 对应「在线评测」页 =="
EXP_ID=$(post "/experiments" "{\"name\":\"T4 演示实验-$SUFFIX\",\"mode\":\"ONLINE\",\"datasetVersionId\":\"$DV_ID\",\"evaluationPlanVersionId\":\"$PV_ID\",\"config\":{\"budget\":{\"maxCostUsd\":1}},\"targets\":[{\"targetKey\":\"target-1\",\"displayName\":\"Target 1\",\"agentVersionId\":\"$AV_ID\",\"repeatCount\":2},{\"targetKey\":\"target-2\",\"displayName\":\"Target 2\",\"agentVersionId\":\"$AV_ID\",\"repeatCount\":2}]}" | field id)
echo "experimentId=$EXP_ID"
get "/experiments/$EXP_ID" | grep -o '"status":"[A-Z]*"'

echo
echo "== 5. 提交实验：生成 12 个 Run 槽位 + 1 个 INITIAL 批次（同一事务）=="
curl -s -X POST "$BASE/experiments/$EXP_ID/start" -H "Idempotency-Key: $(key)" \
  | grep -o '"status":"[A-Z]*"'
get "/experiments/$EXP_ID/runs?pageSize=50" | grep -o '"total":[0-9]*'

echo
echo "== 6. Worker 领取任务（真实 Worker 协议；无任务时返回空数组）=="
CLAIM=$(worker "/internal/v1/work/claim" '{"workerId":"demo-worker","capabilities":["RUN:MOCK"],"maxTasks":1}')
echo "$CLAIM" | head -c 200; echo
ATTEMPT_ID=$(echo "$CLAIM" | field attemptId)
RUN_ID=$(echo "$CLAIM" | field runId)
TOKEN=$(echo "$CLAIM" | field leaseToken)
echo "attemptId=$ATTEMPT_ID runId=$RUN_ID"

echo
echo "== 7. 追加 5 条轨迹事件（eventId 由 Worker 生成；重发同 ID 同正文返回 DUPLICATE）=="
E1=$(ulid); E2=$(ulid); E3=$(ulid); E4=$(ulid); E5=$(ulid)
EVENTS=$(cat <<JSON
{"leaseToken":"$TOKEN","events":[
 {"eventId":"$E1","eventType":"RUN_STARTED","sequenceNo":1,"payload":{"runnerType":"MOCK"}},
 {"eventId":"$E2","eventType":"MODEL_REQUEST","sequenceNo":2,"payload":{"model":"mock-model","requestSummary":"请回答第 1 题"}},
 {"eventId":"$E3","eventType":"MODEL_RESPONSE","sequenceNo":3,"payload":{"model":"mock-model","outputSummary":"42"}},
 {"eventId":"$E4","eventType":"OUTPUT_PRODUCED","sequenceNo":4,"payload":{"outputSummary":"answer=42"}},
 {"eventId":"$E5","eventType":"RUN_COMPLETED","sequenceNo":5,"payload":{"taskOutcome":"SUCCESS"}}]}
JSON
)
EVENT_IDS=$(worker "/internal/v1/run-attempts/$ATTEMPT_ID/events" "$EVENTS" | grep -o '"eventId":"[^"]*"' | sed 's/"eventId":"//;s/"//')
echo "$EVENT_IDS" | tr '\n' ' '; echo
# shellcheck disable=SC2086
SHA=$(manifest $EVENT_IDS)
echo "manifestSha256=$SHA"

echo
echo "== 8. 完成回调：Run 进入 EVALUATING，并生成 PENDING 评测任务（不写任何评分）=="
COMPLETE="{\"leaseToken\":\"$TOKEN\",\"taskOutcome\":\"SUCCESS\",\"output\":{\"mock\":true,\"answer\":\"42\"},\"usage\":{\"totalTokens\":12},\"expectedEventCount\":5,\"eventManifestSha256\":\"$SHA\"}"
worker "/internal/v1/run-attempts/$ATTEMPT_ID/complete" "$COMPLETE" | grep -o '"attemptId":"[^"]*"\|"status":"[A-Z]*"'

echo
echo "== 9. 读取执行证据：Run 状态 / 尝试 / 事件数 / 评测任务 — 对应「Run 执行证据」页 =="
get "/runs/$RUN_ID" | grep -o '"status":"[A-Z]*"\|"taskOutcome":"[A-Z]*"\|"traceEventCount":[0-9]*\|"attemptCount":[0-9]*'
get "/runs/$RUN_ID/evaluation-jobs" | grep -o '"status":"[A-Z]*"\|"total":[0-9]*'

echo
echo "== 10. 重试示例：可重试失败不新增 Run，只追加 Attempt =="
CLAIM2=$(worker "/internal/v1/work/claim" '{"workerId":"demo-worker","capabilities":["RUN:MOCK"],"maxTasks":1}')
A2=$(echo "$CLAIM2" | field attemptId); T2=$(echo "$CLAIM2" | field leaseToken)
worker "/internal/v1/run-attempts/$A2/fail" \
  "{\"leaseToken\":\"$T2\",\"errorCode\":\"UPSTREAM_UNAVAILABLE\",\"errorMessage\":\"模拟上游不可用\",\"retryableHint\":true}" \
  | grep -o '"status":"[A-Z]*"\|"retryScheduled":[a-z]*'
get "/experiments/$EXP_ID/runs?pageSize=50" | grep -o '"total":[0-9]*'

echo
echo "== 11. 取消实验：Run / Attempt 终态，迟到的完成回调返回 409 =="
curl -s -X POST "$BASE/experiments/$EXP_ID/cancel" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(key)" -d '{"reason":"演示取消"}' | grep -o '"status":"[A-Z]*"'
get "/experiments/$EXP_ID" | grep -o '"status":"[A-Z]*"'

echo
echo "演示完成：实验 $EXP_ID。前端「在线 / 离线评测」→ 详情页可看到 12 个 Run 的状态与待评测任务。"
