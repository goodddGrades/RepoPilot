#!/usr/bin/env bash
# TASK-005 端到端演示脚本：离线轨迹导入与标准化。
#
# 用途：按"页面会走的顺序"调用真实接口，复现
#   「上传原文件 → 创建批次 → 逐行标准化 → 复用/冲突 → 冻结启动 → 统一 Run/Job」
# 每一步都标注对应的页面位置，跑完打开前端能看到同样的数据。
#
# 前置：
#   1. 后端已启动（默认 http://127.0.0.1:18080），库里有 TASK-002/003 的算子种子；
#   2. 保持默认 repopilot.import.auto-process=true，否则批次会停在 PENDING（只有集成测试才关掉它）。
#
# 用法：
#   BASE=http://127.0.0.1:18080/api/v1 bash tools/demo-task-005.sh
#
# 每次运行都带随机后缀，可以对同一个库重复执行。
set -u

BASE="${BASE:-http://127.0.0.1:18080/api/v1}"
SUFFIX="${SUFFIX:-$(date +%s)$$}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
# curl -F 需要操作系统能直接打开的文件路径：Git Bash 下 /d/... 这种 MSYS 路径
# Windows 版 curl 打不开，因此在 pwd -W 可用时优先取 Windows 形式（D:/...）。
HERE_WIN="$(cd "$HERE" && { pwd -W 2>/dev/null || pwd; })"
RUNS_FILE="${RUNS_FILE:-$HERE_WIN/examples/offline-runs.jsonl}"

key() { printf 'k%s%s' "$(date +%s%N)" "$RANDOM"; }
post() { # post <path> <json>
  curl --noproxy '*' -s --max-time 30 -X POST "$BASE$1" -H 'Content-Type: application/json' \
       -H "Idempotency-Key: $(key)" -d "$2"
}
get() { curl --noproxy '*' -s --max-time 30 "$BASE$1"; }
field() { grep -o "\"$1\":\"\?[^\",}]*" | head -1 | sed "s/\"$1\":\"\?//"; }

# 批次状态：一次取完整 JSON，再用 field 提取，避免多次请求看到不同时刻的状态
batch_status() { get "/imports/$1" | field status; }

echo "== 0. 前置检查：原文件与后端可达性 =="
[ -f "$RUNS_FILE" ] || { echo "找不到轨迹文件：$RUNS_FILE" >&2; exit 1; }
echo "轨迹文件：$RUNS_FILE（$(wc -l < "$RUNS_FILE" | tr -d ' ') 行，$(wc -c < "$RUNS_FILE" | tr -d ' ') 字节）"
get "/experiments?pageSize=1" | grep -q '"code":"OK"' || { echo "后端不可达：$BASE" >&2; exit 1; }

echo
echo "== 1. 准备已发布数据集（样本 caseKey=sum-001）— 对应「数据集中心」页 =="
# taskType 必须命中白名单（AGENT_TASK / LLM_CALL）；示例文件已按同一规则写好
DS_ID=$(post "/datasets" "{\"code\":\"t5-dataset-$SUFFIX\",\"name\":\"TASK-005 演示数据集\"}" | field id)
DV_ID=$(post "/datasets/$DS_ID/versions" '{"description":"v1"}' | field id)
post "/dataset-versions/$DV_ID/cases" \
  "{\"caseKey\":\"sum-001\",\"taskType\":\"AGENT_TASK\",\"input\":{\"question\":\"1加1等于多少？\"},\"expected\":{\"answer\":\"2\"}}" >/dev/null
curl --noproxy '*' -s --max-time 30 -X POST "$BASE/dataset-versions/$DV_ID/publish" -H "Idempotency-Key: $(key)" \
  | grep -o '"status":"[A-Z]*"\|"caseCount":[0-9]*'

echo
echo "== 2. 准备已发布评测方案（1 个算子）— 对应「评测方案」页 =="
EV1=$(get "/evaluators?pageSize=100" | grep -o '"id":"[^"]*","evaluatorKey":"output.non_empty"' | sed 's/"id":"//;s/".*//')
PLAN_ID=$(post "/evaluation-plans" "{\"code\":\"t5-plan-$SUFFIX\",\"name\":\"TASK-005 演示方案\"}" | field id)
PV_ID=$(post "/evaluation-plans/$PLAN_ID/versions" \
  "{\"config\":{},\"items\":[{\"evaluatorDefinitionId\":\"$EV1\",\"executionOrder\":1,\"required\":true,\"config\":{}}]}" | field id)
curl --noproxy '*' -s --max-time 30 -X POST "$BASE/evaluation-plan-versions/$PV_ID/publish" -H "Idempotency-Key: $(key)" \
  | grep -o '"status":"[A-Z]*"'

echo
echo "== 3. 创建离线实验草稿：1 Target × 1 样本 × 2 重复 = 2 个槽位 — 对应「在线 / 离线评测」页 =="
# 离线实验不绑定 AgentVersion：历史轨迹不是由平台执行产生的
EXP_ID=$(post "/experiments" "{\"name\":\"T5 离线演示-$SUFFIX\",\"mode\":\"OFFLINE\",\"datasetVersionId\":\"$DV_ID\",\"evaluationPlanVersionId\":\"$PV_ID\",\"config\":{},\"targets\":[{\"targetKey\":\"historical_a\",\"displayName\":\"历史对象 A\",\"repeatCount\":2}]}" | field id)
echo "experimentId=$EXP_ID"
get "/experiments/$EXP_ID" | grep -o '"status":"[A-Z]*"\|"expectedRunCount":[0-9]*\|"importedRunCount":[0-9]*\|"missingRunCount":[0-9]*'

echo
echo "== 4. 上传原始 JSONL 作为 Artifact — 对应「离线轨迹导入」页的上传 =="
# 响应刻意不含 objectKey：存储路径不外泄
ARTIFACT=$(curl --noproxy '*' -s --max-time 60 -X POST "$BASE/artifacts" \
  -H "Idempotency-Key: $(key)" \
  -F "artifactType=IMPORT_FILE" \
  -F "file=@$RUNS_FILE;type=application/x-ndjson")
echo "$ARTIFACT" | grep -o '"id":"[^"]*"\|"contentType":"[^"]*"\|"sizeBytes":[0-9]*\|"sha256":"[^"]*"\|"status":"[A-Z]*"'
ARTIFACT_ID=$(echo "$ARTIFACT" | field id)
if [ -z "$ARTIFACT_ID" ]; then
  echo "上传失败，响应：$ARTIFACT" >&2
  exit 1
fi
echo "artifact=$ARTIFACT_ID（响应中没有 objectKey，这是刻意的）"

echo
echo "== 5. 创建导入批次：先整文件校验，再返回 202 — 对应页面的「上传并导入」 =="
CREATED=$(post "/imports" "{\"importType\":\"OFFLINE_RUNS\",\"datasetVersionId\":\"$DV_ID\",\"experimentId\":\"$EXP_ID\",\"fileArtifactId\":\"$ARTIFACT_ID\"}")
echo "$CREATED" | grep -o '"status":"[A-Z]*"\|"importType":"[A-Z_]*"'
BATCH_ID=$(echo "$CREATED" | field id)
if [ -z "$BATCH_ID" ]; then
  echo "创建批次失败，响应：$CREATED" >&2
  exit 1
fi
echo "batch=$BATCH_ID"

echo
echo "== 6. 轮询批次直到终态（逐行处理在后台进行）=="
for _ in $(seq 1 30); do
  STATUS=$(batch_status "$BATCH_ID")
  case "$STATUS" in
    COMPLETED|PARTIAL_FAILED|FAILED|CANCELLED) break ;;
  esac
  sleep 1
done
get "/imports/$BATCH_ID" \
  | grep -o '"status":"[A-Z]*"\|"totalCount":[0-9]*\|"totalKnown":[a-z]*\|"processedCount":[0-9]*\|"successCount":[0-9]*\|"failedCount":[0-9]*\|"duplicateCount":[0-9]*\|"lastLineNo":[0-9]*'
echo "错误明细（正常应为空）："
get "/imports/$BATCH_ID/errors?page=1&pageSize=20" | grep -o '"total":[0-9]*\|"errorCode":"[A-Z_]*"'

echo
echo "== 7. 实验详情：已导入 1 条 / 计划 2 个槽位 / 缺 1 个（不补造）— 对应实验详情页头部 =="
get "/experiments/$EXP_ID" | grep -o '"importedRunCount":[0-9]*\|"missingRunCount":[0-9]*\|"expectedRunCount":[0-9]*'

echo
echo "== 8. 重复导入同一文件：复用原 Run，duplicate_count 是 success 的子集 =="
SECOND=$(post "/imports" "{\"importType\":\"OFFLINE_RUNS\",\"datasetVersionId\":\"$DV_ID\",\"experimentId\":\"$EXP_ID\",\"fileArtifactId\":\"$ARTIFACT_ID\"}" | field id)
for _ in $(seq 1 30); do
  STATUS=$(batch_status "$SECOND")
  case "$STATUS" in
    COMPLETED|PARTIAL_FAILED|FAILED|CANCELLED) break ;;
  esac
  sleep 1
done
get "/imports/$SECOND" | grep -o '"status":"[A-Z]*"\|"successCount":[0-9]*\|"duplicateCount":[0-9]*\|"failedCount":[0-9]*'
echo "实验里仍然只有 1 个 Run："
get "/experiments/$EXP_ID/runs?pageSize=50" | grep -o '"total":[0-9]*'

echo
echo "== 9. 启动实验：冻结已导入的 Run，生成 INITIAL 批次与 PENDING Job（不写任何评分）=="
curl --noproxy '*' -s --max-time 30 -X POST "$BASE/experiments/$EXP_ID/start" -H "Idempotency-Key: $(key)" -H 'Content-Type: application/json' -d '{}' \
  | grep -o '"status":"[A-Z]*"'
RUN_JSON=$(get "/experiments/$EXP_ID/runs?pageSize=50")
RUN_ID=$(echo "$RUN_JSON" | field id)
echo "runId=$RUN_ID"
echo "Run 详情（离线：直接 EVALUATING，attemptCount=0，事件数=1，输出来自原始轨迹）："
get "/runs/$RUN_ID" \
  | grep -o '"mode":"[A-Z]*"\|"status":"[A-Z]*"\|"attemptCount":[0-9]*\|"traceEventCount":[0-9]*\|"taskOutcome":"[A-Z]*"'
echo "评测任务（全部 PENDING，没有任何 evaluation_results）："
get "/runs/$RUN_ID/evaluation-jobs" | grep -o '"status":"[A-Z]*"\|"total":[0-9]*'

echo
echo "== 10. 已开始的实验不再接受导入：返回 409 且不建批次 =="
curl --noproxy '*' -s --max-time 30 -o /dev/null -w 'HTTP %{http_code}\n' -X POST "$BASE/imports" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" \
  -d "{\"importType\":\"OFFLINE_RUNS\",\"datasetVersionId\":\"$DV_ID\",\"experimentId\":\"$EXP_ID\",\"fileArtifactId\":\"$ARTIFACT_ID\"}"

echo
echo "演示完成：实验 $EXP_ID。前端「离线轨迹导入」页可看到两个批次（成功 1 / 重复 1），"
echo "「在线 / 离线评测」→ 详情页可看到 1 个 EVALUATING 的 Run 与待评测 Job。"
