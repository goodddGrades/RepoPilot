<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'

import { ApiError } from '@/api/http'
import * as traceApi from '@/api/traces'
import type { AttemptView, RunMode, TraceEventScope, TraceEventView } from '@/types/domain'
import { formatTime, stringifyJson, toastError } from '@/utils/feedback'

/**
 * 轨迹时间线回放。
 *
 * <p>三条约束贯穿这个组件：
 * 1. 所有内容来自 `GET /runs/{id}/events` 的真实响应，没有演示数据；
 * 2. 缺什么就显示什么缺失（缺父 span、没有 sequenceNo、离线没有 Attempt），不补造；
 * 3. 载荷一律以文本插值渲染，绝不 v-html —— 模型输出是不可信数据。
 *
 * <p>分页用服务端游标：换 Attempt 或事件类型时必须清空游标重查，
 * 否则服务端会以 400 拒绝（游标里带过滤条件摘要），这里也主动重置避免无谓往返。
 */
const props = defineProps<{
  runId: string
  mode: RunMode
  attempts: AttemptView[]
  acceptedAttemptId?: string
  traceCoverage?: string
}>()

const EVENT_TYPE_OPTIONS = [
  'RUN_STARTED',
  'MODEL_REQUEST',
  'MODEL_RESPONSE',
  'TOOL_STARTED',
  'TOOL_COMPLETED',
  'OUTPUT_PRODUCED',
  'RUN_COMPLETED',
  'ERROR',
]

const items = ref<TraceEventView[]>([])
const scope = ref<TraceEventScope | null>(null)
const cursor = ref<string>('')
const hasMore = ref(false)
const loading = ref(false)
const loadingMore = ref(false)
const errorText = ref('')
const cursorRejected = ref(false)
const expanded = reactive(new Set<string>())

/** 'DEFAULT' 交给服务端解析（接受尝试 / 执行中尝试 / 离线无尝试） */
const attemptFilter = ref('DEFAULT')
const eventTypeFilter = ref('')

const offline = computed(() => props.mode === 'OFFLINE')
const hasAttempts = computed(() => props.attempts.length > 0)

/** 并发保护：快速切换过滤条件时，只接受最后一次请求的结果。 */
let requestSeq = 0

const scopeText = computed(() => {
  const current = scope.value
  if (!current) {
    return ''
  }
  switch (current.resolvedFrom) {
    case 'ACCEPTED':
      return `默认读取接受尝试 ${shortId(current.attemptId)}`
    case 'ACTIVE':
      return `执行中，读取当前尝试 ${shortId(current.attemptId)}`
    case 'REQUESTED':
      return `显式指定尝试 ${shortId(current.attemptId)}`
    case 'ALL':
      return '排障模式：已合并该 Run 的全部执行尝试，失败尝试的事件也在其中'
    default:
      return offline.value
        ? '离线导入轨迹：没有执行尝试，事件不再按 Attempt 过滤'
        : '该 Run 还没有执行尝试，暂无事件'
  }
})

/** 页面内已加载事件的 span 集合：父 span 不在集合里只能说明"本页未见"，不能断言"不存在"。 */
const spanIds = computed(() => {
  const set = new Set<string>()
  items.value.forEach((item) => {
    if (item.spanId) {
      set.add(item.spanId)
    }
  })
  return set
})

/** 同毫秒且都没有 sequenceNo 的事件：顺序无法确定，界面必须说明。 */
const ambiguousKeys = computed(() => {
  const byTime = new Map<string, TraceEventView[]>()
  items.value.forEach((item) => {
    const list = byTime.get(item.occurredAt) ?? []
    list.push(item)
    byTime.set(item.occurredAt, list)
  })
  const keys = new Set<string>()
  byTime.forEach((list) => {
    if (list.length > 1 && list.every((item) => item.sequenceNo === undefined || item.sequenceNo === null)) {
      list.forEach((item) => keys.add(item.eventId))
    }
  })
  return keys
})

/** 工具调用配对：只有一侧时标记为不完整，而不是补一个"成功"。 */
const toolCalls = computed(() => {
  const map = new Map<string, traceApi.ToolCallPair>()
  items.value.forEach((item) => {
    if (!traceApi.isToolEvent(item.eventType)) {
      return
    }
    const callId = traceApi.payloadText(item.payload, 'toolCallId')
    // 没有 toolCallId 的事件无法配对：用 eventId 单列一条，避免把两次调用合并
    const key = callId ? `call:${callId}` : `event:${item.eventId}`
    const pair = map.get(key) ?? { key, incomplete: true }
    const toolName = traceApi.payloadText(item.payload, 'toolName')
    if (toolName) {
      pair.toolName = toolName
    }
    if (item.eventType === 'TOOL_STARTED') {
      pair.startedAt = item.occurredAt
    } else {
      pair.finishedAt = item.occurredAt
      pair.outcome = traceApi.payloadText(item.payload, 'outcome')
      const duration = traceApi.payloadField(item.payload, 'durationMs')
      if (typeof duration === 'number') {
        pair.durationMs = duration
      }
      if (pair.outcome === 'FAILURE') {
        pair.failureMessage =
          traceApi.payloadText(item.payload, 'errorMessage') ??
          traceApi.payloadText(item.payload, 'message')
      }
    }
    pair.incomplete = !(pair.startedAt && pair.finishedAt)
    map.set(key, pair)
  })
  return Array.from(map.values())
})

function shortId(value?: string): string {
  return value ? `${value.slice(0, 8)}…` : '-'
}

function queryParams() {
  return {
    attemptId: attemptFilter.value !== 'DEFAULT' && attemptFilter.value !== 'ALL'
      ? attemptFilter.value
      : undefined,
    allAttempts: attemptFilter.value === 'ALL' ? true : undefined,
    eventType: eventTypeFilter.value || undefined,
  }
}

async function loadFirstPage(): Promise<void> {
  const seq = ++requestSeq
  loading.value = true
  errorText.value = ''
  cursorRejected.value = false
  try {
    const page = await traceApi.listTraceEvents(props.runId, {
      ...queryParams(),
      limit: 100,
    })
    if (seq !== requestSeq) {
      return
    }
    items.value = page.items
    cursor.value = page.nextCursor ?? ''
    hasMore.value = page.hasMore
    scope.value = page.scope
    expanded.clear()
  } catch (error) {
    if (seq !== requestSeq) {
      return
    }
    handleLoadError(error)
  } finally {
    if (seq === requestSeq) {
      loading.value = false
    }
  }
}

async function loadMore(): Promise<void> {
  if (!cursor.value || !hasMore.value) {
    return
  }
  const seq = requestSeq
  loadingMore.value = true
  errorText.value = ''
  try {
    const page = await traceApi.listTraceEvents(props.runId, {
      ...queryParams(),
      limit: 100,
      cursor: cursor.value,
    })
    if (seq !== requestSeq) {
      return
    }
    // 键集分页保证不重复；这里仍按 eventId 去重，防止服务端异常时把重复行渲染两次
    const known = new Set(items.value.map((item) => item.eventId))
    items.value = [...items.value, ...page.items.filter((item) => !known.has(item.eventId))]
    cursor.value = page.nextCursor ?? ''
    hasMore.value = page.hasMore
    scope.value = page.scope
  } catch (error) {
    if (seq !== requestSeq) {
      return
    }
    handleLoadError(error)
  } finally {
    if (seq === requestSeq) {
      loadingMore.value = false
    }
  }
}

/**
 * 游标被拒（400 + field=cursor）单独提示：这不是"加载失败"，而是翻页上下文失效，
 * 正确动作是从第一页重查，而不是让用户反复点重试。
 */
function handleLoadError(error: unknown): void {
  if (error instanceof ApiError) {
    const cursorField = error.errors.some((item) => item.field === 'cursor')
    if (cursorField) {
      cursorRejected.value = true
      errorText.value = error.message
      cursor.value = ''
      hasMore.value = false
      return
    }
  }
  errorText.value = error instanceof Error ? error.message : '加载轨迹失败'
  toastError(error, '加载轨迹失败')
}

watch(() => [props.runId, attemptFilter.value, eventTypeFilter.value], loadFirstPage)
onMounted(loadFirstPage)

function togglePayload(eventId: string): void {
  if (expanded.has(eventId)) {
    expanded.delete(eventId)
  } else {
    expanded.add(eventId)
  }
}

function artifactHref(artifactId: string): string {
  const base = import.meta.env.VITE_API_BASE_URL || '/api'
  return `${base}/v1/artifacts/${artifactId}/download`
}

function payloadPreview(item: TraceEventView): string {
  const text = stringifyJson(item.payload)
  if (!text) {
    return '（无载荷）'
  }
  return text.length > 160 ? `${text.slice(0, 160)}…` : text
}

function isFailure(item: TraceEventView): boolean {
  return item.eventType === 'ERROR'
    || (item.eventType === 'TOOL_COMPLETED'
      && traceApi.payloadText(item.payload, 'outcome') === 'FAILURE')
}
</script>

<template>
  <el-card class="page__card" shadow="never">
    <template #header>
      <div class="trace-header">
        <div>
          <span>轨迹回放</span>
          <span class="muted"> · {{ scopeText || '加载中…' }}</span>
        </div>
        <div class="trace-controls">
          <el-select
            v-if="hasAttempts || offline"
            v-model="attemptFilter"
            size="small"
            style="width: 210px"
            :disabled="offline"
          >
            <el-option label="默认（服务端解析）" value="DEFAULT" />
            <el-option
              v-for="attempt in attempts"
              :key="attempt.id"
              :label="`尝试 #${attempt.attemptNo} · ${attempt.status}`"
              :value="attempt.id"
            />
            <el-option label="全部尝试（排障）" value="ALL" />
          </el-select>
          <el-select
            v-model="eventTypeFilter"
            size="small"
            placeholder="全部事件类型"
            clearable
            style="width: 170px"
          >
            <el-option v-for="type in EVENT_TYPE_OPTIONS" :key="type" :label="type" :value="type" />
          </el-select>
          <el-button size="small" :icon="Refresh" :loading="loading" @click="loadFirstPage">
            刷新
          </el-button>
        </div>
      </div>
    </template>

    <el-alert
      v-if="offline"
      type="info"
      show-icon
      :closable="false"
      style="margin-bottom: 12px"
      title="离线导入轨迹"
      description="这条 Run 由历史记录导入：没有执行尝试，事件本身也不归属任何 Attempt。轨迹完整度以导入时声明的为准，未声明时按保守口径记为 PARTIAL 或 NONE。"
    />

    <el-alert
      v-if="traceCoverage && traceCoverage !== 'COMPLETE'"
      type="warning"
      show-icon
      :closable="false"
      style="margin-bottom: 12px"
      :title="`轨迹完整度：${traceCoverage}`"
      description="该轨迹未经验证为完整采集：缺少的事件不会被补造，依赖工具轨迹的算子会返回不适用，而不是判为通过。"
    />

    <el-alert
      v-if="cursorRejected"
      type="error"
      show-icon
      :closable="false"
      style="margin-bottom: 12px"
      title="翻页上下文已失效"
      :description="errorText"
    >
      <el-button size="small" style="margin-top: 8px" @click="loadFirstPage">
        从第一页重新加载
      </el-button>
    </el-alert>
    <el-alert
      v-else-if="errorText"
      type="error"
      show-icon
      :closable="false"
      style="margin-bottom: 12px"
      :title="errorText"
    />

    <div v-if="toolCalls.length" class="tool-block">
      <div class="tool-block__title">工具调用（{{ toolCalls.length }}）</div>
      <el-table :data="toolCalls" size="small" style="width: 100%">
        <el-table-column label="工具" min-width="160">
          <template #default="{ row }">{{ row.toolName ?? '（未声明工具名）' }}</template>
        </el-table-column>
        <el-table-column label="结果" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.outcome === 'FAILURE'" type="danger" size="small">FAILURE</el-tag>
            <el-tag v-else-if="row.outcome" type="success" size="small">{{ row.outcome }}</el-tag>
            <el-tag v-else type="info" size="small">未声明</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="110">
          <template #default="{ row }">
            {{ row.durationMs === undefined ? '未知' : `${row.durationMs} ms` }}
          </template>
        </el-table-column>
        <el-table-column label="完整性" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.incomplete" type="warning" size="small">仅一侧事件</el-tag>
            <span v-else class="muted">开始 + 结束</span>
          </template>
        </el-table-column>
        <el-table-column label="失败信息" min-width="200">
          <template #default="{ row }">
            <span v-if="row.failureMessage" class="mono">{{ row.failureMessage }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div v-loading="loading" class="timeline-wrap">
      <el-timeline v-if="items.length">
        <el-timeline-item
          v-for="item in items"
          :key="item.eventId"
          :timestamp="formatTime(item.occurredAt)"
          placement="top"
          :type="isFailure(item) ? 'danger' : undefined"
        >
          <div class="event-head">
            <el-tag :type="traceApi.eventTypeTag(item.eventType)" size="small">
              {{ traceApi.EVENT_TYPE_TEXT[item.eventType] ?? item.eventType }}
            </el-tag>
            <span class="mono muted">{{ item.eventType }}</span>
            <span v-if="item.sequenceNo !== undefined && item.sequenceNo !== null" class="muted">
              seq {{ item.sequenceNo }}
            </span>
            <span v-if="item.attemptId" class="muted mono">attempt {{ shortId(item.attemptId) }}</span>
            <el-tag v-else-if="!offline" type="info" size="small">无 Attempt</el-tag>
            <span v-if="item.traceId" class="muted mono">trace {{ shortId(item.traceId) }}</span>
            <el-tag v-else type="info" size="small">未知 trace</el-tag>
            <el-tag v-if="item.parentSpanId && !spanIds.has(item.parentSpanId)" type="warning" size="small">
              本页未见父 span
            </el-tag>
            <el-tag v-if="ambiguousKeys.has(item.eventId)" type="warning" size="small">顺序不确定</el-tag>
            <a
              v-if="item.artifactId"
              :href="artifactHref(item.artifactId)"
              target="_blank"
              rel="noopener noreferrer"
              class="evidence-link"
            >查看附件</a>
          </div>
          <div class="payload">
            <pre v-if="expanded.has(item.eventId)" class="json-block">{{ stringifyJson(item.payload) || '（无载荷）' }}</pre>
            <div v-else class="payload-preview">{{ payloadPreview(item) }}</div>
            <el-button link size="small" @click="togglePayload(item.eventId)">
              {{ expanded.has(item.eventId) ? '收起载荷' : '展开载荷' }}
            </el-button>
          </div>
        </el-timeline-item>
      </el-timeline>
      <el-empty
        v-else-if="!loading"
        :description="eventTypeFilter ? '当前过滤条件下没有事件' : '这条 Run 还没有轨迹事件'"
      />
    </div>

    <div class="trace-footer">
      <span class="muted">
        已加载 {{ items.length }} 条{{ hasMore ? '，还有更多' : '，已到末尾' }}
      </span>
      <el-button v-if="hasMore" size="small" :loading="loadingMore" @click="loadMore">
        加载下一页
      </el-button>
    </div>
  </el-card>
</template>

<style scoped>
.trace-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.trace-controls {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.tool-block {
  margin-bottom: 16px;
}

.tool-block__title {
  font-weight: 600;
  margin-bottom: 8px;
}

.timeline-wrap {
  min-height: 80px;
}

.event-head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 13px;
}

.payload {
  margin-top: 6px;
}

.payload-preview {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  color: var(--el-text-color-regular);
  word-break: break-all;
}

.evidence-link {
  font-size: 12px;
  color: var(--el-color-primary);
  text-decoration: none;
}

.trace-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 12px;
}
</style>
