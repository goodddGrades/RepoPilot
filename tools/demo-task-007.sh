#!/usr/bin/env bash
# TASK-007 端到端演示：评测执行引擎与结果闭环。
#
# 用途：按"评测 Worker 与页面会走的顺序"调用真实接口，证明链路是**真的跑通**的：
#   * Job 被 Worker 领取后拿到的是完整输入（算子配置 + Run 快照 + 轨迹读取契约）；
#   * 提交的是**结论**（verdict/score/evidence），不是"跑完了"；
#   * 结论落库后 Run 与批次按状态机收口，Run 详情页能读到同一份结果；
#   * 非法提交被拒：分数越界、跨 Run 证据、旧令牌、结束后迟到回执；
#   * 重评新建批次，历史结果与 Run 状态不被覆盖；
#   * 取消批次后未执行的任务变 SKIPPED，而不是悄悄消失。
#
# 前置：
#   1. 后端用开发配置启动，且必须显式给 Worker 凭证（留空时 /internal/v1 一律 401）：
#        SERVER_PORT=18080 REPOPILOT_WORKER_TOKEN=local-demo-token ./mvnw spring-boot:run
#   2. 库里已有 TASK-003 的算子种子（output.non_empty / answer.exact_match 等）。
#   3. 前端 `npm run dev`，用于肉眼确认「实验详情 → 评测批次」与「Run 详情 → 评测任务」。
#
# 用法：
#   BASE=http://127.0.0.1:18080/api/v1 WORKER_TOKEN=local-demo-token bash tools/demo-task-007.sh
#
# 每次运行都带随机后缀，可以对同一个库重复执行。
set -u

BASE="${BASE:-http://127.0.0.1:18080/api/v1}"
WORKER_TOKEN="${WORKER_TOKEN:-local-demo-token}"
SUFFIX="${SUFFIX:-$(date +%s)$$}"
ROOT="${BASE%/api/v1}"   # /internal/v1 与 /api/v1 同级

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
post_status() { # post_status <path> <json>：只看状态码与错误码，用于"应当被拒绝"的场景
  curl --noproxy '*' -s -o /tmp/demo7.$$ -w '%{http_code}' --max-time 30 -X POST "$BASE$1" \
       -H 'Content-Type: application/json' -H "Idempotency-Key: $(key)" -d "$2"
}
worker_post_status() { # worker_post_status <path> <json>（走 /internal/v1）
  curl --noproxy '*' -s -o /tmp/demo7.$$ -w '%{http_code}' --max-time 30 -X POST "$ROOT$1" \
       -H 'Content-Type: application/json' -H "Authorization: Bearer $WORKER_TOKEN" -d "$2"
}
field() { grep -o "\"$1\":\"\?[^\",}]*" | head -1 | sed "s/\"$1\":\"\?//"; }
# KV 提取：一次 awk 把响应里的关键字段摊平成 "key:value key:value"，之后只用 bash 内建取值。
# 理由是实测过的：本机起一个子进程约 1s，领取后如果用十来次 field() 解析 JSON，
# 租约（60s）会在解析里耗光；而本机 grep -E 对带引号的 alternation 模式不可靠，所以用 awk。
kv_flatten() { # kv_flatten <json> <key...>
  local json="$1"; shift
  printf '%s' "$json" | awk -v keys=" $* " '
    BEGIN { n = split(keys, ks, " ") }
    {
      for (i = 1; i <= n; i++) {
        k = ks[i]
        if (seen[k] == 1) continue
        pat = "\"" k "\":\"[^\"]*\""
        if (match($0, pat)) {
          v = substr($0, RSTART, RLENGTH)
          gsub(/\042/, "", v)
          printf "%s ", v
          seen[k] = 1
        }
      }
    }'
}
kv() { # kv <flattened> <key>
  case " $1" in
    *" $2:"*) local rest="${1#*"$2:"}"; printf '%s' "${rest%% *}" ;;
    *) printf '' ;;
  esac
}
code_of() { grep -o '"code":"[A-Z_]*"' /tmp/demo7.$$ | head -1 | sed 's/"code":"//;s/"//'; }
ulid() {
  local alphabet=0123456789ABCDEFGHJKMNPQRSTVWXYZ ts out='' i
  ts=$(( $(date +%s%3N) ))
  for ((i = 0; i < 10; i++)); do
    out="${alphabet:$((ts % 32)):1}$out"; ts=$((ts / 32))
  done
  for ((i = 0; i < 16; i++)); do out="$out${alphabet:$((RANDOM % 32)):1}"; done
  printf '%s' "$out"
}
ev() { # ev <事件类型> <sequenceNo> <occurredAt> <payload>
  printf '{"eventId":"%s","eventType":"%s","sequenceNo":%s,"occurredAt":"%s","payload":%s}' \
    "$(ulid)" "$1" "$2" "$3" "$4"
}
trap 'rm -f /tmp/demo7.$$' EXIT

echo "== 0. 前置检查：后端可达、Worker 凭证有效 =="
get "/experiments?pageSize=1" | grep -q '"code":"OK"' || { echo "后端不可达：$BASE" >&2; exit 1; }
[ -n "$WORKER_TOKEN" ] || { echo "需要 WORKER_TOKEN（服务端也要配同一个值）" >&2; exit 1; }
echo "BASE=$BASE  SUFFIX=$SUFFIX"

echo
echo "== 1. 准备已发布版本：Agent / 数据集（两个 case，期望值不同） / 两个方案版本 =="
AGENT_ID=$(post "/agents" "{\"code\":\"t7-agent-$SUFFIX\",\"name\":\"TASK-007 演示 Agent\",\"agentType\":\"MOCK\"}" | field id)
AV_ID=$(post "/agents/$AGENT_ID/versions" '{"runnerType":"MOCK","config":{"model":"mock-model"}}' | field id)
post "/agent-versions/$AV_ID/publish" '{}' >/dev/null
DS_ID=$(post "/datasets" "{\"code\":\"t7-dataset-$SUFFIX\",\"name\":\"TASK-007 演示数据集\"}" | field id)
DV_ID=$(post "/datasets/$DS_ID/versions" '{"description":"v1"}' | field id)
post "/dataset-versions/$DV_ID/cases" \
  '{"caseKey":"demo-001","taskType":"AGENT_TASK","input":{"question":"1加1等于多少？"},"expected":{"answer":"2"}}' >/dev/null
post "/dataset-versions/$DV_ID/cases" \
  '{"caseKey":"demo-002","taskType":"AGENT_TASK","input":{"question":"2加2等于多少？"},"expected":{"answer":"4"}}' >/dev/null
curl --noproxy '*' -s --max-time 30 -X POST "$BASE/dataset-versions/$DV_ID/publish" -H "Idempotency-Key: $(key)" >/dev/null

EV_NONEMPTY=$(get "/evaluators?pageSize=100" | grep -o '"id":"[^"]*","evaluatorKey":"output.non_empty"' | sed 's/"id":"//;s/".*//')
EV_EXACT=$(get "/evaluators?pageSize=100" | grep -o '"id":"[^"]*","evaluatorKey":"answer.exact_match"' | sed 's/"id":"//;s/".*//')
[ -n "$EV_NONEMPTY" ] && [ -n "$EV_EXACT" ] || { echo "缺少算子种子：output.non_empty / answer.exact_match" >&2; exit 1; }

PLAN_ID=$(post "/evaluation-plans" "{\"code\":\"t7-plan-$SUFFIX\",\"name\":\"TASK-007 演示方案\"}" | field id)
# v1：先判输出非空，再按 expected 做精确匹配（两段串行）
PV1=$(post "/evaluation-plans/$PLAN_ID/versions" \
  "{\"config\":{},\"items\":[{\"evaluatorDefinitionId\":\"$EV_NONEMPTY\",\"executionOrder\":1,\"required\":true,\"config\":{\"outputPath\":\"answer\"}},{\"evaluatorDefinitionId\":\"$EV_EXACT\",\"executionOrder\":2,\"required\":true,\"config\":{\"outputPath\":\"answer\",\"expectedPath\":\"answer\",\"trim\":true,\"ignoreCase\":true}}]}" | field id)
curl --noproxy '*' -s --max-time 30 -X POST "$BASE/evaluation-plan-versions/$PV1/publish" -H "Idempotency-Key: $(key)" >/dev/null
# v2：只留精确匹配，用于后面"同一批 Run、两套方案"的重评
PV2=$(post "/evaluation-plans/$PLAN_ID/versions" \
  "{\"config\":{},\"items\":[{\"evaluatorDefinitionId\":\"$EV_EXACT\",\"executionOrder\":1,\"required\":true,\"config\":{\"outputPath\":\"answer\",\"expectedPath\":\"answer\"}}]}" | field id)
curl --noproxy '*' -s --max-time 30 -X POST "$BASE/evaluation-plan-versions/$PV2/publish" -H "Idempotency-Key: $(key)" >/dev/null
echo "agentVersion=$AV_ID datasetVersion=$DV_ID planV1=$PV1 planV2=$PV2"

echo
echo "== 2. 提交实验：2 个 Run（两个 case × 1 次）— 对应「实验详情」页 =="
EXP_ID=$(post "/experiments" "{\"name\":\"T7 评测演示-$SUFFIX\",\"mode\":\"ONLINE\",\"datasetVersionId\":\"$DV_ID\",\"evaluationPlanVersionId\":\"$PV1\",\"config\":{},\"targets\":[{\"targetKey\":\"demo_agent\",\"displayName\":\"演示 Agent\",\"agentVersionId\":\"$AV_ID\",\"repeatCount\":1}]}" | field id)
post "/experiments/$EXP_ID/start" '{}' | grep -o '"status":"[A-Z]*"'
INIT_BATCH=$(get "/experiments/$EXP_ID/evaluation-batches" | field id)
echo "experimentId=$EXP_ID initialBatch=$INIT_BATCH（初始批次由实验启动事务创建，不走接口）"

echo
echo "== 3. 在线执行：领取 Run、写事件、封口 — Run 进入 EVALUATING，Job 变 PENDING =="
RUNNER="demo-runner-$SUFFIX"
# 领取是全局队列：库里可能还留着别次演示的 QUEUED Run。逐个任务处理，
# 只把属于本演示实验的 Run 做完；不属于自己的原样放回（安排重试，不判失败）。
OWN_RUNS=""
DEFERRED=0
for round in $(seq 1 12); do
  CLAIM=$(worker_post "/internal/v1/work/claim" \
    "{\"workerId\":\"$RUNNER\",\"capabilities\":[\"RUN:MOCK\"],\"maxTasks\":1}")
  RUN_KV=$(kv_flatten "$CLAIM" runId attemptId leaseToken experimentId)
  T_RUN=$(kv "$RUN_KV" runId)
  [ -n "$T_RUN" ] || break
  T_ATTEMPT=$(kv "$RUN_KV" attemptId)
  T_LEASE=$(kv "$RUN_KV" leaseToken)
  T_EXP=$(kv "$RUN_KV" experimentId)
  if [ "$T_EXP" != "$EXP_ID" ]; then
    worker_post "/internal/v1/run-attempts/$T_ATTEMPT/fail" \
      "{\"leaseToken\":\"$T_LEASE\",\"errorCode\":\"DEMO_DEFERRED\",\"errorMessage\":\"不属于本演示的实验，放回队列\",\"retryableHint\":true}" >/dev/null
    DEFERRED=$((DEFERRED + 1))
    continue
  fi
  OWN_RUNS="$OWN_RUNS $T_RUN"
  EVENTS=$(printf '%s,%s,%s,%s' \
    "$(ev RUN_STARTED 1 '2026-10-19T02:00:01.000Z' '{"runnerType":"MOCK"}')" \
    "$(ev MODEL_RESPONSE 2 '2026-10-19T02:00:02.000Z' '{"model":"mock-model","outputSummary":"2"}')" \
    "$(ev OUTPUT_PRODUCED 3 '2026-10-19T02:00:03.000Z' '{"outputSummary":"2"}')" \
    "$(ev RUN_COMPLETED 4 '2026-10-19T02:00:04.000Z' '{"taskOutcome":"SUCCESS"}')")
  APPEND=$(worker_post "/internal/v1/run-attempts/$T_ATTEMPT/events" "{\"leaseToken\":\"$T_LEASE\",\"events\":[$EVENTS]}")
  case "$APPEND" in
    *'"code":"OK"'*) ;;
    *) echo "  事件写入失败：$APPEND" >&2; exit 1 ;;
  esac
  # 封口必须用服务端确认的 eventId 清单：自己拼的会被判成清单不符
  IDS=$(echo "$APPEND" | grep -o '"eventId":"[^"]*"' | sed 's/"eventId":"//;s/"//g' | sort)
  MANIFEST=$(printf '%s' "$IDS" | sha256sum | cut -d' ' -f1)
  COUNT=$(printf '%s\n' "$IDS" | wc -l | tr -d ' ')
  DONE=$(worker_post "/internal/v1/run-attempts/$T_ATTEMPT/complete" \
    "{\"leaseToken\":\"$T_LEASE\",\"taskOutcome\":\"SUCCESS\",\"output\":{\"answer\":\"2\"},\"usage\":{\"totalTokens\":12,\"costUsd\":0.0004,\"currency\":\"USD\"},\"expectedEventCount\":$COUNT,\"eventManifestSha256\":\"$MANIFEST\"}")
  case "$DONE" in
    *'"code":"OK"'*) ;;
    *) echo "  封口失败：$DONE" >&2; exit 1 ;;
  esac
  echo "  run=$T_RUN events=$COUNT status=$(get "/runs/$T_RUN" | field status)（等评测领取）"
done
[ -n "$OWN_RUNS" ] || { echo "没有领到本演示的 Run" >&2; exit 1; }
[ "$DEFERRED" -gt 0 ] && echo "（另外 $DEFERRED 个别次演示遗留的 Run 已放回队列）"
OLDEST_RUN=$(echo "$OWN_RUNS" | awk '{print $1}')

# 证据要引用本 Run 的事件 id。**领取之前**先取好：领取后只有租约时限，
# 真实 Worker 会心跳续租，而这个脚本是"一次性 Worker"，不能在租约期内做慢查询。
EVENT_EVIDENCE=""
for R in $OWN_RUNS; do
  E=$(get "/runs/$R/events?limit=1" | grep -o '"eventId":"[^"]*"' | head -1 | sed 's/"eventId":"//;s/"//')
  EVENT_EVIDENCE="$EVENT_EVIDENCE $R=$E"
done
echo "  预取证据事件：$(echo "$EVENT_EVIDENCE" | tr ' ' '\n' | grep '=' | paste -sd' ')"

echo
echo "== 4. 评测 Worker 领取 Job：输入含算子配置、Run 快照与轨迹读取契约 =="
EVALER="demo-eval-$SUFFIX"
claim_job() { # 只返回属于本演示的 Job；别人的任务放回队列（安排重试，不判失败）
  local resp kv jid jattempt jlease jrun n
  for n in $(seq 1 15); do
    resp=$(worker_post "/internal/v1/work/claim" \
      "{\"workerId\":\"$EVALER\",\"capabilities\":[\"EVAL:output.non_empty\",\"EVAL:answer.exact_match\"],\"maxTasks\":1}")
    kv=$(kv_flatten "$resp" jobId attemptId leaseToken runId)
    jid=$(kv "$kv" jobId)
    [ -n "$jid" ] || return 1
    jattempt=$(kv "$kv" attemptId)
    jlease=$(kv "$kv" leaseToken)
    jrun=$(kv "$kv" runId)
    case " $OWN_RUNS " in
      *" $jrun "*) CLAIM_JOB="$resp"; CLAIM_KV="$kv"; DEFERRED_JOBS=$((DEFERRED_JOBS + 1)); return 0 ;;
      *) worker_post "/internal/v1/job-attempts/$jattempt/fail" \
           "{\"leaseToken\":\"$jlease\",\"errorCode\":\"DEMO_DEFERRED\",\"errorMessage\":\"不属于本演示的 Run，放回队列\",\"retryableHint\":true}" >/dev/null ;;
    esac
  done
  return 1
}
DEFERRED_JOBS=0
claim_job || { echo "没有领到本演示的评测任务" >&2; exit 1; }

echo "== 5. 提交结论（verdict/score/evidence）：先是非空判定 PASS =="
JOB_ID=$(kv "$CLAIM_KV" jobId)
JOB_ATTEMPT=$(kv "$CLAIM_KV" attemptId)
JOB_LEASE=$(kv "$CLAIM_KV" leaseToken)
CLAIMED_RUN=$(kv "$CLAIM_KV" runId)
EVENT_ID=$(echo "$EVENT_EVIDENCE" | tr ' ' '\n' | grep "^$CLAIMED_RUN=" | cut -d= -f2)
# 领取后立刻提交：这段之间不允许再起慢查询，租约只有 60s
COMPLETE=$(worker_post "/internal/v1/job-attempts/$JOB_ATTEMPT/complete" \
  "{\"leaseToken\":\"$JOB_LEASE\",\"verdict\":\"PASS\",\"score\":1.00000,\"reason\":\"输出非空\",\"evidence\":[{\"type\":\"event\",\"eventId\":\"$EVENT_ID\"}],\"result\":{\"outputPath\":\"answer\",\"outputPresent\":true},\"generatedBy\":\"RULE\"}")
case "$COMPLETE" in
  *'"code":"OK"'*) echo "回执：$(echo "$COMPLETE" | grep -o '"attemptId":"[^"]*","status":"[A-Z_]*"' | head -1)" ;;
  *) echo "提交结论失败：$COMPLETE" >&2; exit 1 ;;
esac
echo "证据引用的是该 Run 的事件：$EVENT_ID"

echo
echo "== 4'. 刚才那份任务输入长什么样（领取后的内容在提交完成后回看） =="
echo "kind=$(kv "$CLAIM_KV" kind)  jobId=$JOB_ID  attemptNo=$(kv "$CLAIM_KV" attemptNo) run=$CLAIMED_RUN"
[ "$DEFERRED_JOBS" -gt 1 ] && echo "（另有 $((DEFERRED_JOBS - 1)) 个别次演示遗留的 Job 已放回队列）"
echo "  算子与配置：$(echo "$CLAIM_JOB" | grep -o '"key":"[^"]*","version":[0-9]*,"type":"[^"]*"' | head -1)"
echo "  轨迹契约：$(echo "$CLAIM_JOB" | grep -o '"sealed":[a-z]*,"eventsUrl":"[^"]*","eventCount":[0-9]*' | head -1)"
echo "  输出快照：$(echo "$CLAIM_JOB" | grep -o '"output":{"answer":"[^"]*"}' | head -1)"
echo "  数据集期望：$(echo "$CLAIM_JOB" | grep -o '"expected":{"answer":"[^"]*"}' | head -1)"

echo

echo
echo "== 6. 剩余 Job 逐个领取执行：回答 2 的 Run 对上期望 4 的 case 会判 FAIL =="
while claim_job; do
  NA=$(kv "$CLAIM_KV" attemptId)
  NL=$(kv "$CLAIM_KV" leaseToken)
  NID=$(kv "$CLAIM_KV" jobId)
  NRUN=$(kv "$CLAIM_KV" runId)
  KEY=$(echo "$CLAIM_JOB" | grep -o '"key":"[^"]*"' | head -1 | sed 's/"key":"//;s/"//')
  if [ "$KEY" = "answer.exact_match" ]; then
    EXP=$(echo "$CLAIM_JOB" | grep -o '"expected":{"answer":"[^"]*"}' | head -1 | grep -o '[0-9]*"$' | tr -d '"')
    ACT=$(echo "$CLAIM_JOB" | grep -o '"output":{"answer":"[^"]*"}' | head -1 | grep -o '[0-9]*"$' | tr -d '"')
    if [ "$EXP" = "$ACT" ]; then V=PASS; S=1.00000; R="与期望值一致"; else V=FAIL; S=0.00000; R="与期望值不一致：期望 $EXP，实际 $ACT"; fi
  else
    V=PASS; S=1.00000; R="输出非空"
  fi
  REC=$(worker_post "/internal/v1/job-attempts/$NA/complete" \
    "{\"leaseToken\":\"$NL\",\"verdict\":\"$V\",\"score\":$S,\"reason\":\"$R\",\"evidence\":[{\"type\":\"casePath\",\"path\":\"expected.answer\"}],\"result\":{\"evaluator\":\"$KEY\"},\"generatedBy\":\"RULE\"}")
  case "$REC" in
    *'"code":"OK"'*) echo "  job=$NID run=$NRUN 算子=$KEY → $V $S（$R）" ;;
    *) echo "  提交结论失败：$REC" >&2; exit 1 ;;
  esac
done
echo "（本演示的 Job 已全部执行完）"

echo
echo "== 7. 落库结果与收口：Run=COMPLETED、批次=COMPLETED，前端读到的是同一份数据 =="
DETAIL=$(get "/runs/$OLDEST_RUN")
echo "run.status=$(echo "$DETAIL" | field status)  finishedAt=$(echo "$DETAIL" | field finishedAt)"
echo "评测任务："
echo "$DETAIL" | grep -o '"evaluatorKey":"[^"]*","evaluatorVersion":[0-9]*,"evaluatorType":"[A-Z_]*"' | sed 's/"//g;s/evaluatorKey:/  /;s/,evaluatorVersion/ v/;s/,evaluatorType:/ /'
echo "$DETAIL" | grep -o '"verdict":"[A-Z_]*"' | sed 's/"verdict":"/  结论 /;s/"//g'
echo "$DETAIL" | grep -o '"score":[0-9.]*' | sed 's/"score":/  分数 /' | head -4
echo "$DETAIL" | grep -o '"reason":"[^"]*"' | sed 's/"reason":"/  依据 /;s/"//g' | head -4
BATCH=$(get "/evaluation-batches/$INIT_BATCH")
echo "批次状态=$(echo "$BATCH" | field status)  runCount=$(echo "$BATCH" | field runCount)  expectedJobCount=$(echo "$BATCH" | field expectedJobCount)"

echo
echo "== 8. 四类非法提交全部被拒（不是 500，而是带错误码的 4xx） =="
BAD_ATTEMPT=$(get "/runs/$OLDEST_RUN/evaluation-jobs" | grep -o '"id":"[^"]*"' | head -1 | sed 's/"id":"//;s/"//')
printf '分数越界(1.5)      → HTTP %s %s\n' \
  "$(worker_post_status "/internal/v1/job-attempts/$JOB_ATTEMPT/complete" "{\"leaseToken\":\"$JOB_LEASE\",\"verdict\":\"PASS\",\"score\":1.5,\"reason\":\"x\",\"evidence\":[],\"result\":{},\"generatedBy\":\"RULE\"}")" "$(code_of)"
printf '分数精度超限       → HTTP %s %s\n' \
  "$(worker_post_status "/internal/v1/job-attempts/$JOB_ATTEMPT/complete" "{\"leaseToken\":\"$JOB_LEASE\",\"verdict\":\"PASS\",\"score\":0.123456,\"reason\":\"x\",\"evidence\":[],\"result\":{},\"generatedBy\":\"RULE\"}")" "$(code_of)"
printf 'generatedBy 不匹配 → HTTP %s %s\n' \
  "$(worker_post_status "/internal/v1/job-attempts/$JOB_ATTEMPT/complete" "{\"leaseToken\":\"$JOB_LEASE\",\"verdict\":\"PASS\",\"reason\":\"x\",\"evidence\":[],\"result\":{},\"generatedBy\":\"LLM\"}")" "$(code_of)"
printf '旧令牌重放         → HTTP %s %s\n' \
  "$(worker_post_status "/internal/v1/job-attempts/$JOB_ATTEMPT/complete" "{\"leaseToken\":\"tampered-token\",\"verdict\":\"PASS\",\"score\":1.0,\"reason\":\"x\",\"evidence\":[],\"result\":{},\"generatedBy\":\"RULE\"}")" "$(code_of)"
printf '完成后迟到失败回执 → HTTP %s %s\n' \
  "$(worker_post_status "/internal/v1/job-attempts/$JOB_ATTEMPT/fail" "{\"leaseToken\":\"$JOB_LEASE\",\"errorCode\":\"EVALUATOR_CRASHED\",\"retryableHint\":true}")" "$(code_of)"

echo
echo "== 9. 重评：同一批 Run 换方案版本 → 新批次，历史结果与 Run 状态不动 =="
NEW_BATCH=$(post "/experiments/$EXP_ID/evaluation-batches" \
  "{\"planVersionId\":\"$PV2\",\"triggerType\":\"REEVALUATION\",\"parentBatchId\":\"$INIT_BATCH\",\"runIds\":[\"$OLDEST_RUN\"]}")
NEW_BATCH_ID=$(echo "$NEW_BATCH" | field id)
echo "新批次=$NEW_BATCH_ID triggerType=$(echo "$NEW_BATCH" | field triggerType) expectedJobCount=$(echo "$NEW_BATCH" | field expectedJobCount)"
claim_job || { echo "重评批次没有领到任务" >&2; exit 1; }
RA=$(echo "$CLAIM_JOB" | field attemptId); RL=$(echo "$CLAIM_JOB" | field leaseToken)
RE_RECHECK=$(worker_post "/internal/v1/job-attempts/$RA/complete" \
  "{\"leaseToken\":\"$RL\",\"verdict\":\"PASS\",\"score\":1.00000,\"reason\":\"重评：与期望值一致\",\"evidence\":[{\"type\":\"casePath\",\"path\":\"expected.answer\"}],\"result\":{\"reevaluated\":true},\"generatedBy\":\"RULE\"}")
case "$RE_RECHECK" in
  *'"code":"OK"'*) ;;
  *) echo "重评提交失败：$RE_RECHECK" >&2; exit 1 ;;
esac
SUMMARY=$(get "/runs/$OLDEST_RUN/evaluation-jobs")
BATCH_IDS=$(echo "$SUMMARY" | grep -o '"batchId":"[^"]*"' | sed 's/"batchId":"//;s/"//' | sort -u)
echo "该 Run 关联的批次数：$(printf '%s\n' "$BATCH_IDS" | grep -c .)（初始 1 个 + 重评 1 个）"
for B in $BATCH_IDS; do
  V=$(get "/evaluation-batches/$B")
  echo "  批次 $B no=$(echo "$V" | field batchNo) trigger=$(echo "$V" | field triggerType) status=$(echo "$V" | field status) jobs=$(echo "$V" | grep -o '"total":[0-9]*' | head -1 | cut -d: -f2)"
done
echo "  判定与分数（按批次号倒序，批次 2 是重评）："
echo "$SUMMARY" | grep -o '"batchNo":[0-9]*,"evaluatorKey":"[^"]*"' | sed 's/"batchNo":/    批次 /;s/,"evaluatorKey":"/ /;s/"//'
echo "$SUMMARY" | grep -o '"verdict":"[A-Z_]*"' | sed 's/"verdict":"/    结论 /;s/"//g'
echo "run.status=$(get "/runs/$OLDEST_RUN" | field status)（重评不回写已完成的历史 Run）"

echo
echo "== 10. 取消未终态批次：PENDING Job 变 SKIPPED，不是悄悄消失 =="
CANCEL_BATCH=$(post "/experiments/$EXP_ID/evaluation-batches" \
  "{\"planVersionId\":\"$PV2\",\"triggerType\":\"MANUAL\",\"runIds\":[\"$OLDEST_RUN\"]}")
CANCEL_BATCH_ID=$(echo "$CANCEL_BATCH" | field id)
CANCELLED=$(post "/evaluation-batches/$CANCEL_BATCH_ID/cancel" '{"reason":"演示：手动取消"}')
echo "取消后 status=$(echo "$CANCELLED" | field status) jobs.total=$(echo "$CANCELLED" | grep -o '"total":[0-9]*' | head -1 | cut -d: -f2) skipped=$(echo "$CANCELLED" | grep -o '"skipped":[0-9]*' | head -1 | cut -d: -f2)"
# 不去抢队列（会碰到别的实验的任务），直接查这个批次的任务状态

echo "取消后的任务状态：$(get "/runs/$OLDEST_RUN/evaluation-jobs" | grep -o '"status":"SKIPPED"' | wc -l | tr -d ' ') 个 SKIPPED"
# 再取一次详情，确认它是终态而不是"看起来没有任务"
echo "再次查询批次详情 status=$(get "/evaluation-batches/$CANCEL_BATCH_ID" | field status)（应仍是 CANCELLED，不会被领取改写）"

echo
echo "== 11. 批次列表（实验详情页「评测批次」卡片的数据来源） =="
get "/experiments/$EXP_ID/evaluation-batches" | grep -o '"batchNo":[0-9]*,"triggerType":"[A-Z_]*"' | sed 's/"batchNo":/  批次 /;s/,"triggerType":"/ /;s/"//g'
get "/experiments/$EXP_ID/evaluation-batches" | grep -o '"status":"[A-Z_]*"' | sed 's/"status":"/  状态 /;s/"//g'
echo
echo "演示完成。打开前端：实验详情页看批次与重评入口，Run 详情页看判定、分数、证据与 Attempt 历史。"