#!/usr/bin/env bash
# TASK-003 端到端演示脚本：只依赖 curl，按"页面会走的顺序"调用真实接口。
#
# 用途：交付验收时复现，或确认某个环境里的接口是否可用。
# 与页面操作一一对应：每个小节标注对应的页面，跑完再打开页面能看到同样的数据。
#
# 用法：
#   BASE=http://127.0.0.1:18080/api/v1 bash tools/demo-task-003.sh
#
# 每次运行用的 code 都带一个随机后缀，因此可以对同一个库重复执行，
# 不会因为 code 重复而在第 2 步就 409（早期版本有这个毛病，第一次跑通、第二次全红）。
set -u

BASE="${BASE:-http://127.0.0.1:18080/api/v1}"
SUFFIX="${SUFFIX:-$(date +%s)$$}"
TMP="${TMPDIR:-/tmp}"
key() { printf 'k%s%s' "$(date +%s%N)" "$RANDOM"; }

post() { # post <path> <json>
  curl -s -X POST "$BASE$1" -H 'Content-Type: application/json' \
       -H "Idempotency-Key: $(key)" -d "$2"
}
get() { curl -s "$BASE$1"; }

# 从 JSON 里取顶层 data.<field>，避免依赖 jq
field() { grep -o "\"$2\":\"\?[^\",}]*" | head -1 | sed "s/\"$2\":\"\?//"; }

echo "== 1. 算子清单（启动时由 seed/evaluators.json 幂等导入）— 对应「算子清单」页 =="
EV=$(get "/evaluators?pageSize=100")
echo "$EV" | grep -o '"total":[0-9]*'
EV1=$(echo "$EV" | grep -o '"id":"[^"]*","evaluatorKey":"output.non_empty"' | sed 's/"id":"//;s/".*//')
EV2=$(echo "$EV" | grep -o '"id":"[^"]*","evaluatorKey":"judge.rubric"' | sed 's/"id":"//;s/".*//')
echo "output.non_empty=$EV1 judge.rubric=$EV2"

echo
echo "== 2. 创建 Agent 并发布配置版本 — 对应「Agent 管理」页 =="
AGENT=$(post "/agents" "{\"code\":\"demo-agent-$SUFFIX\",\"name\":\"演示 Agent\",\"agentType\":\"MOCK\"}")
AGENT_ID=$(echo "$AGENT" | field x id)
echo "agentId=$AGENT_ID"
VER=$(post "/agents/$AGENT_ID/versions" '{"runnerType":"MOCK","config":{"model":"mock-1","seed":7}}')
VER_ID=$(echo "$VER" | field x id)
echo "$VER" | grep -o '"version":[0-9]*'
curl -s -X POST "$BASE/agent-versions/$VER_ID/publish" -H "Idempotency-Key: $(key)" | grep -o '"status":"[A-Z]*"'

echo
echo "== 3. 创建数据集与草稿版本 — 对应「数据集中心」页 =="
DS=$(post "/datasets" "{\"code\":\"demo-dataset-$SUFFIX\",\"name\":\"演示数据集\"}")
DS_ID=$(echo "$DS" | field x id)
echo "datasetId=$DS_ID"
DV=$(post "/datasets/$DS_ID/versions" '{"description":"首版"}')
DV_ID=$(echo "$DV" | field x id)
echo "versionId=$DV_ID"

echo
echo "== 4. 单条新增样本 — 对应数据集详情页「新增样本」 =="
post "/dataset-versions/$DV_ID/cases" \
  '{"caseKey":"case-001","taskType":"AGENT_TASK","input":{"prompt":"修复空指针"},"expected":{"answer":"ok"}}' \
  | grep -o '"caseKey":"[^"]*"'

echo
echo "== 5. 上传 JSONL 批量导入（整批原子）— 对应详情页「导入 JSONL」 =="
cat > "$TMP/demo-cases.jsonl" <<'JSONL'
{"schemaVersion":1,"caseKey":"case-002","taskType":"AGENT_TASK","input":{"prompt":"补充单测"},"expected":{"answer":"ok"}}
{"schemaVersion":1,"caseKey":"case-003","taskType":"LLM_CALL","input":{"prompt":"总结 diff"}}
JSONL
curl -s -X POST "$BASE/dataset-versions/$DV_ID/cases:import" \
  -H "Idempotency-Key: $(key)" -F "file=@$TMP/demo-cases.jsonl;type=application/x-ndjson" \
  | grep -o '"status":"[A-Z]*"\|"importedCount":[0-9]*\|"sha256":"[a-f0-9]\{64\}"'

echo
echo "== 6. 导入失败示例：坏行整批拒绝（库内零写入）=="
printf '%s\n' '{"schemaVersion":1,"caseKey":"case-004","taskType":"CHAT","input":{}}' > "$TMP/demo-bad.jsonl"
curl -s -X POST "$BASE/dataset-versions/$DV_ID/cases:import" \
  -H "Idempotency-Key: $(key)" -F "file=@$TMP/demo-bad.jsonl;type=application/x-ndjson" \
  | head -c 320
echo

echo
echo "== 7. 发布数据集版本并查看内容摘要 =="
curl -s -X POST "$BASE/dataset-versions/$DV_ID/publish" -H "Idempotency-Key: $(key)" \
  | grep -o '"status":"[A-Z]*"\|"contentHash":"[a-f0-9]\{64\}"\|"caseCount":[0-9]*'

echo
echo "== 8. 创建评测方案、版本并发布（算子必须已发布）— 对应「评测方案」页 =="
PLAN=$(post "/evaluation-plans" "{\"code\":\"demo-plan-$SUFFIX\",\"name\":\"演示方案\"}")
PLAN_ID=$(echo "$PLAN" | field x id)
PV=$(post "/evaluation-plans/$PLAN_ID/versions" \
  "{\"config\":{\"gate\":{\"maxRegressions\":0}},\"items\":[{\"evaluatorDefinitionId\":\"$EV1\",\"executionOrder\":1,\"required\":true,\"config\":{}},{\"evaluatorDefinitionId\":\"$EV2\",\"executionOrder\":2,\"required\":false,\"config\":{\"rubricKey\":\"r1\",\"model\":\"mock-judge\"}}]}")
PV_ID=$(echo "$PV" | field x id)
echo "$PV" | grep -o '"version":[0-9]*\|"executionOrder":[0-9]*'
curl -s -X POST "$BASE/evaluation-plan-versions/$PV_ID/publish" -H "Idempotency-Key: $(key)" \
  | grep -o '"status":"[A-Z]*"\|"contentHash":"[a-f0-9]\{64\}"'

echo
echo "== 9. 只读校验：已发布数据集版本不能再改样本 =="
curl -s -X POST "$BASE/dataset-versions/$DV_ID/cases" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(key)" \
  -d '{"caseKey":"case-999","taskType":"AGENT_TASK","input":{}}' | head -c 200
echo
