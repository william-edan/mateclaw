<template>
  <section class="douyin-result" :class="{ 'is-loading': loading }">
    <div class="summary-strip" aria-label="Douyin lead run summary">
      <div v-for="metric in metrics" :key="metric.key" class="summary-metric" :class="metric.tone">
        <span class="summary-metric__label">{{ metric.label }}</span>
        <strong class="summary-metric__value">{{ metric.value }}</strong>
        <span v-if="metric.hint" class="summary-metric__hint">{{ metric.hint }}</span>
      </div>
    </div>

    <div class="result-grid">
      <section class="result-panel result-panel--timeline">
        <header class="panel-header">
          <div>
            <h3>时间线</h3>
            <p>{{ timelineItems.length }} 条事件</p>
          </div>
        </header>

        <details v-if="timelineItems.length" class="panel-collapse">
          <summary>展开完整时间线</summary>
          <ol class="timeline-list panel-scroll-body">
            <li
              v-for="item in timelineItems"
              :key="item.key"
              class="timeline-item"
              :class="[`tone-${item.tone}`, { 'is-lead-event': item.isLeadEvent }]"
            >
              <div class="timeline-node" aria-hidden="true">
                <el-icon v-if="item.icon === 'success'"><CircleCheckFilled /></el-icon>
                <el-icon v-else-if="item.icon === 'error'"><CircleCloseFilled /></el-icon>
                <el-icon v-else-if="item.icon === 'running'"><Clock /></el-icon>
                <span v-else class="timeline-dot" />
              </div>
              <div class="timeline-body">
                <div class="timeline-line">
                  <span class="timeline-title">{{ item.title }}</span>
                  <span class="timeline-time">{{ item.timeLabel }}</span>
                </div>
                <p v-if="item.summary" class="timeline-summary">{{ item.summary }}</p>
                <dl v-if="item.fields.length" class="timeline-fields">
                  <template v-for="field in item.fields" :key="field.key">
                    <dt>{{ field.label }}</dt>
                    <dd>{{ field.value }}</dd>
                  </template>
                </dl>
                <details v-if="item.payloadText" class="timeline-payload">
                  <summary>原始数据</summary>
                  <pre>{{ item.payloadText }}</pre>
                </details>
              </div>
            </li>
          </ol>
        </details>
        <div v-else class="empty-block">暂无时间线事件。</div>
      </section>

      <section class="result-panel">
        <header class="panel-header">
          <div>
            <h3>评论匹配</h3>
            <p>{{ matchedRows.length }} / {{ commentRows.length }} 条评论命中</p>
          </div>
        </header>

        <details v-if="commentRows.length" class="panel-collapse">
          <summary>展开评论明细</summary>
          <div class="table-wrap panel-scroll-body">
            <table class="data-table">
              <thead>
                <tr>
                  <th>作者</th>
                  <th>评论内容</th>
                  <th>匹配结果</th>
                  <th>来源视频</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="comment in commentRows" :key="comment.id || comment.commentKey || comment.text || 'comment'">
                  <td>
                    <a
                      v-if="comment.authorProfileUrl"
                      class="link-quiet"
                      :href="comment.authorProfileUrl"
                      target="_blank"
                      rel="noreferrer"
                    >
                      {{ comment.authorName || '未识别作者' }}
                    </a>
                    <span v-else>{{ comment.authorName || '未识别作者' }}</span>
                  </td>
                  <td>
                    <p class="comment-text">{{ comment.text || '-' }}</p>
                    <span v-if="comment.commentKey" class="muted-code">{{ comment.commentKey }}</span>
                  </td>
                  <td>
                    <span class="status-pill" :class="comment.matched ? 'status-succeeded' : 'status-muted'">
                      {{ comment.matched ? '已命中' : '未命中' }}
                    </span>
                    <span v-if="comment.matchScore != null" class="score-text">
                      {{ formatPercent(comment.matchScore) }}
                    </span>
                    <p v-if="comment.matchReason" class="reason-text">{{ comment.matchReason }}</p>
                  </td>
                  <td><span class="muted-code">{{ comment.videoKey || '-' }}</span></td>
                </tr>
              </tbody>
            </table>
          </div>
        </details>
        <div v-else class="empty-block">暂无评论数据。</div>
      </section>
    </div>

    <div class="result-grid result-grid--bottom">
      <section class="result-panel">
        <header class="panel-header">
          <div>
            <h3>线索资料</h3>
            <p>已保存 {{ profileRows.length }} 个线索资料</p>
          </div>
        </header>

        <div v-if="profileRows.length" class="table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th>昵称</th>
                <th>账号标识</th>
                <th>主页</th>
                <th>简介</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="profile in profileRows" :key="profile.id || profile.profileUrl || profile.displayName || 'profile'">
                <td>{{ profile.displayName || '-' }}</td>
                <td>{{ profile.handle || '-' }}</td>
                <td>
                  <a
                    v-if="profile.profileUrl"
                    class="link-quiet"
                    :href="profile.profileUrl"
                    target="_blank"
                    rel="noreferrer"
                  >
                    打开主页
                  </a>
                  <span v-else>-</span>
                </td>
                <td><p class="comment-text">{{ profile.bio || '-' }}</p></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="empty-block">暂无线索资料。</div>
      </section>

      <section class="result-panel">
        <header class="panel-header">
          <div>
            <h3>触达记录</h3>
            <p>{{ engagementRows.length }} 条触达记录</p>
          </div>
        </header>

        <div v-if="engagementRows.length" class="table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th>动作</th>
                <th>状态</th>
                <th>线索</th>
                <th>草稿或失败原因</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="engagement in engagementRows" :key="engagement.id">
                <td>{{ actionLabel(engagement.actionType || 'engagement') }}</td>
                <td>
                  <span class="status-pill" :class="statusClass(engagement.status, engagement.sent)">
                    {{ engagement.sent ? '已发送' : statusLabel(engagement.status || 'pending') }}
                  </span>
                </td>
                <td>
                  <div>{{ profileLabel(engagement.profileId) }}</div>
                  <div class="muted-code">{{ commentLabel(engagement.commentId) }}</div>
                </td>
                <td>
                  <p v-if="engagement.failureMessage || engagement.failureCode" class="reason-text is-error">
                    {{ engagement.failureCode ? `${reasonLabel(engagement.failureCode)}：` : '' }}{{ engagement.failureMessage }}
                  </p>
                  <p v-else class="comment-text">{{ engagement.draftText || '-' }}</p>
                  <a
                    v-if="engagement.evidenceRef"
                    class="link-quiet"
                    :href="engagement.evidenceRef"
                    target="_blank"
                    rel="noreferrer"
                  >
                    查看凭证
                  </a>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-else class="empty-block">暂无触达记录。</div>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { CircleCheckFilled, CircleCloseFilled, Clock } from '@element-plus/icons-vue'
import type {
  DouyinLeadAcquisitionRunResponse,
  DouyinLeadComment,
  DouyinLeadEngagement,
  DouyinLeadProfile,
  DouyinLeadRunSummary,
  DouyinLeadTimelineEvent,
} from '@/api'

type JsonRecord = Record<string, unknown>

const props = withDefaults(defineProps<{
  run: DouyinLeadAcquisitionRunResponse | null
  comments?: DouyinLeadComment[]
  profiles?: DouyinLeadProfile[]
  engagements?: DouyinLeadEngagement[]
  loading?: boolean
}>(), {
  comments: () => [],
  profiles: () => [],
  engagements: () => [],
  loading: false,
})

const events = computed(() => props.run?.events ?? [])
const commentRows = computed(() => props.comments.length ? props.comments : (props.run?.comments ?? []))
const matchedRows = computed(() => {
  const inlineMatches = props.run?.matches ?? []
  if (inlineMatches.length) return inlineMatches
  return commentRows.value.filter((comment) => comment.matched)
})
const profileRows = computed(() => props.profiles)
const engagementRows = computed(() => props.engagements.length ? props.engagements : (props.run?.engagements ?? []))

const parsedEvents = computed(() => events.value.map((event, index) => ({
  event,
  index,
  payload: payloadFor(event),
})))

const latestSummaryPayload = computed(() => {
  const item = [...parsedEvents.value].reverse().find(({ event }) => event.type === 'lead.run.summary')
  if (!item) return null
  const nested = recordValue(item.payload.summary)
  return nested ? { ...item.payload, ...nested } : item.payload
})

const structuredSummary = computed<Partial<DouyinLeadRunSummary>>(() => {
  const summary = props.run?.summary ?? props.run?.runSummary
  return summary ?? {}
})

const videoStartedCount = computed(() => countEvents('lead.video.started', 'lead.video.opened'))
const videoCompletedCount = computed(() => countEvents('lead.video.completed'))
const videoFailedCount = computed(() => countEvents('lead.video.failed'))

const runSummary = computed(() => {
  const payload = latestSummaryPayload.value ?? {}
  const structured = structuredSummary.value
  const commentsCount = commentRows.value.length || firstNumber(
    props.run?.commentsCollected,
    structured.commentsCollected,
    payload.commentsCollected,
    0,
  ) || 0
  const matchesCount = matchedRows.value.length || firstNumber(
    props.run?.matchedComments,
    structured.matchedComments,
    payload.matchedComments,
    0,
  ) || 0
  const engagementCount = engagementRows.value.length || firstNumber(
    props.run?.engagementsCreated,
    structured.engagementsCreated,
    payload.engagementsCreated,
    payload.engagements,
    0,
  ) || 0

  return {
    requestedVideoLimit: firstNumber(
      props.run?.requestedVideoLimit,
      structured.requestedVideoLimit,
      payload.requestedVideoLimit,
      payload.videoLimit,
      payload.requestedVideos,
    ),
    processedVideos: firstNumber(
      props.run?.processedVideos,
      structured.processedVideos,
      payload.processedVideos,
      Math.max(videoCompletedCount.value + videoFailedCount.value, videoStartedCount.value),
      0,
    ) || 0,
    succeededVideos: firstNumber(
      props.run?.succeededVideos,
      structured.succeededVideos,
      payload.succeededVideos,
      videoCompletedCount.value,
      0,
    ) || 0,
    failedVideos: firstNumber(
      props.run?.failedVideos,
      structured.failedVideos,
      payload.failedVideos,
      videoFailedCount.value,
      0,
    ) || 0,
    commentsCollected: commentsCount,
    matchedComments: matchesCount,
    engagementsCreated: engagementCount,
  }
})

const metrics = computed(() => [
  {
    key: 'requestedVideoLimit',
    label: '请求视频数',
    value: formatCount(runSummary.value.requestedVideoLimit),
    hint: '上限',
    tone: '',
  },
  {
    key: 'processedVideos',
    label: '已处理视频',
    value: formatCount(runSummary.value.processedVideos),
    hint: '',
    tone: '',
  },
  {
    key: 'succeededVideos',
    label: '成功视频',
    value: formatCount(runSummary.value.succeededVideos),
    hint: '',
    tone: 'tone-success',
  },
  {
    key: 'failedVideos',
    label: '失败视频',
    value: formatCount(runSummary.value.failedVideos),
    hint: '',
    tone: runSummary.value.failedVideos > 0 ? 'tone-danger' : '',
  },
  {
    key: 'commentsCollected',
    label: '采集评论',
    value: formatCount(runSummary.value.commentsCollected),
    hint: '',
    tone: '',
  },
  {
    key: 'matchedComments',
    label: '命中评论',
    value: formatCount(runSummary.value.matchedComments),
    hint: '',
    tone: 'tone-accent',
  },
  {
    key: 'engagementsCreated',
    label: '触达记录',
    value: formatCount(runSummary.value.engagementsCreated),
    hint: '',
    tone: 'tone-accent',
  },
])

const timelineItems = computed(() => parsedEvents.value.map(({ event, index, payload }) => {
  const type = event.type || 'event'
  const payloadText = payloadToText(event.payloadJson, payload)
  return {
    key: event.id || `${type}-${index}`,
    event,
    title: eventTitle(type),
    tone: eventTone(event, type),
    icon: eventIcon(event, type),
    isLeadEvent: type.startsWith('lead.'),
    timeLabel: formatTime(event.createTime, index),
    summary: payloadSummary(payload, type),
    fields: payloadFields(payload, type),
    payloadText,
  }
}))

const profilesById = computed(() => {
  const map = new Map<string, DouyinLeadProfile>()
  for (const profile of profileRows.value) {
    if (profile.id != null) map.set(String(profile.id), profile)
  }
  return map
})

const commentsById = computed(() => {
  const map = new Map<string, DouyinLeadComment>()
  for (const comment of commentRows.value) {
    if (comment.id != null) map.set(String(comment.id), comment)
  }
  return map
})

function countEvents(...types: string[]): number {
  const wanted = new Set(types)
  return events.value.filter((event) => wanted.has(event.type)).length
}

function payloadFor(event: DouyinLeadTimelineEvent): JsonRecord {
  if (!event.payloadJson) return {}
  try {
    const parsed = JSON.parse(event.payloadJson)
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed as JsonRecord : {}
  } catch {
    return { payload: event.payloadJson }
  }
}

function payloadToText(raw: string | null, payload: JsonRecord): string {
  if (!raw) return ''
  if ('payload' in payload && payload.payload === raw) return raw
  try {
    return JSON.stringify(JSON.parse(raw), null, 2)
  } catch {
    return raw
  }
}

function recordValue(value: unknown): JsonRecord | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as JsonRecord : null
}

function numberValue(value: unknown): number | null {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && value.trim() !== '') {
    const parsed = Number(value)
    if (Number.isFinite(parsed)) return parsed
  }
  return null
}

function firstNumber(...values: unknown[]): number | null {
  for (const value of values) {
    const numeric = numberValue(value)
    if (numeric != null) return numeric
  }
  return null
}

function stringValue(value: unknown): string {
  if (value == null || value === '') return ''
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return ''
}

function formatCount(value: number | null | undefined): string {
  return value == null ? '-' : value.toLocaleString()
}

function formatPercent(value: number): string {
  const normalized = value > 1 ? value : value * 100
  return `${Math.round(normalized)}%`
}

function formatTime(value: string | null | undefined, index: number): string {
  if (!value) return `#${index + 1}`
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value.replace('T', ' ').slice(0, 19)
  return date.toLocaleString()
}

function titleCase(value: string): string {
  return value
    .replace(/[_-]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/\b\w/g, (letter) => letter.toUpperCase())
}

function statusLabel(value?: string | null): string {
  const normalized = String(value || '').toLowerCase()
  if (normalized === 'running') return '执行中'
  if (normalized === 'created') return '已创建'
  if (normalized === 'succeeded' || normalized === 'success' || normalized === 'completed') return '成功'
  if (normalized === 'failed' || normalized === 'error') return '失败'
  if (normalized === 'aborted' || normalized === 'cancelled' || normalized === 'canceled') return '已停止'
  if (normalized === 'sent') return '已发送'
  if (normalized === 'pending') return '待处理'
  return value || '-'
}

function reasonLabel(value?: string | null): string {
  const normalized = String(value || '').trim()
  if (!normalized) return '-'
  const labels: Record<string, string> = {
    END_OF_LIST: '已到达评论列表底部',
    END_OF_LIST_TOP_LEVEL: '一级评论已触底',
    END_OF_LIST_DECLARED_MISMATCH: '已触底但声明数与采集数不一致',
    COMMENT_COLLECTION_INCOMPLETE: '评论采集未完整',
    COMMENT_PANEL_LOST_DURING_SCROLL: '滚动时评论面板丢失',
    COMMENT_PANEL_LOST_AFTER_SCROLL: '滚动后评论面板丢失',
    COMMENT_EXTRACTION_NOT_ADVANCING: '评论提取没有继续推进',
    COMMENTS_TRIGGER_NOT_FOUND: '未找到评论入口',
    DM_BUTTON_NOT_FOUND: '未找到私信入口',
    DM_PAGE_NOT_CONFIRMED: '未确认进入私信页',
    PROFILE_TAB_NOT_CONTROLLED: '主页标签页未受浏览器扩展控制',
    DM_TAB_NOT_CONTROLLED: '私信标签页未受浏览器扩展控制',
    DEADLINE_EXCEEDED: '执行超时',
    PROFILE_OPEN_FAILED: '用户主页打开失败',
    VIDEO_RESULT_NOT_FOUND: '未找到视频结果',
    DRAFT_NOT_OBSERVED: '未检测到私信草稿',
    RUN_CANCELLED: '任务已取消',
    VIDEO_FAILED: '视频处理失败',
    ALL_VIDEOS_FAILED: '所有视频均失败',
    DOUYIN_RUN_FAILED: '抖音获客任务失败',
  }
  return labels[normalized] ?? normalized
}

function actionLabel(value?: string | null): string {
  const normalized = String(value || '').toLowerCase()
  const labels: Record<string, string> = {
    engagement: '触达',
    dm_draft: '私信草稿',
    follow: '关注',
    dm: '私信',
  }
  return labels[normalized] ?? titleCase(value || 'engagement')
}

function eventTitle(type: string): string {
  const labels: Record<string, string> = {
    'lead.search.started': '开始搜索',
    'lead.search.completed': '搜索完成',
    'lead.sort.started': '开始应用排序',
    'lead.sort.completed': '排序已应用',
    'lead.video.started': '开始处理视频',
    'lead.video.completed': '视频处理完成',
    'lead.video.failed': '视频处理失败',
    'lead.run.summary': '任务汇总',
    'lead.video.opened': '视频已打开',
    'lead.comments.opened': '评论区已打开',
    'lead.comments.region_detected': '评论区已定位',
    'lead.comments.collecting': '正在采集评论',
    'lead.comments.collected': '评论采集完成',
    'lead.comment.matched': '评论匹配完成',
    'lead.comment.match_skipped': '跳过评论匹配',
    'lead.engagement.started': '开始触达线索',
    'lead.engagement.completed': '触达完成',
    'lead.engagement.skipped': '跳过触达',
    'lead.run.failed': '任务失败',
    run_created: '任务已创建',
    run_started: '任务开始执行',
    run_status_changed: '任务状态更新',
    step_started: '步骤开始',
    step_completed: '步骤完成',
    step_failed: '步骤失败',
    policy_decision: '策略决策',
  }
  return labels[type] ?? '任务事件'
}

function eventTone(event: DouyinLeadTimelineEvent, type: string): string {
  const severity = (event.severity || '').toLowerCase()
  if (severity === 'error' || type.endsWith('.failed') || type === 'step_failed') return 'danger'
  if (severity === 'warn') return 'warning'
  if (type.endsWith('.completed') || type === 'step_completed') return 'success'
  if (type.endsWith('.started') || type === 'run_started' || type === 'step_started') return 'running'
  return 'neutral'
}

function eventIcon(event: DouyinLeadTimelineEvent, type: string): 'success' | 'error' | 'running' | 'neutral' {
  const tone = eventTone(event, type)
  if (tone === 'success') return 'success'
  if (tone === 'danger') return 'error'
  if (tone === 'running') return 'running'
  return 'neutral'
}

function payloadSummary(payload: JsonRecord, type: string): string {
  if (type === 'lead.run.summary') {
    const requested = firstNumber(payload.requestedVideoLimit, payload.videoLimit)
    const processed = firstNumber(payload.processedVideos)
    const matched = firstNumber(payload.matchedComments)
    const engagements = firstNumber(payload.engagementsCreated, payload.engagements)
    const pieces = [
      requested != null ? `请求 ${requested} 个视频` : '',
      processed != null ? `已处理 ${processed} 个视频` : '',
      matched != null ? `命中 ${matched} 条评论` : '',
      engagements != null ? `触达 ${engagements} 次` : '',
    ].filter(Boolean)
    if (pieces.length) return pieces.join('，')
  }

  const message = stringValue(payload.message)
  if (message) {
    const translated = reasonLabel(message)
    return translated.length > 240 ? `${translated.slice(0, 240)}...` : translated
  }
  const title = stringValue(payload.title)
  if (title) return title
  const reason = stringValue(payload.reason)
  if (reason) return reasonLabel(reason)
  const status = stringValue(payload.status)
  if (status) return statusLabel(status)
  return ''
}

function payloadFields(payload: JsonRecord, type: string): Array<{ key: string; label: string; value: string }> {
  const preferredKeys = type === 'lead.run.summary'
    ? ['requestedVideoLimit', 'processedVideos', 'succeededVideos', 'failedVideos', 'commentsCollected', 'matchedComments', 'engagementsCreated']
    : ['code', 'status', 'url', 'videoKey', 'commentKey', 'author', 'commentsCollected', 'declaredCommentCount', 'matchedComments', 'sent', 'stopReason']

  const fields: Array<{ key: string; label: string; value: string }> = []
  for (const key of preferredKeys) {
    const value = payload[key]
    if (value == null || value === '') continue
    fields.push({
      key,
      label: fieldLabel(key),
      value: fieldValue(key, value),
    })
    if (fields.length >= 6) break
  }
  return fields
}

function fieldLabel(key: string): string {
  const labels: Record<string, string> = {
    requestedVideoLimit: '请求视频',
    processedVideos: '已处理视频',
    succeededVideos: '成功视频',
    failedVideos: '失败视频',
    commentsCollected: '评论数',
    declaredCommentCount: '声明评论',
    matchedComments: '匹配数',
    engagementsCreated: '触达数',
    videoKey: '视频标识',
    commentKey: '评论标识',
    author: '作者',
    status: '状态',
    stopReason: '停止原因',
    failureCode: '失败代码',
    code: '代码',
    url: '链接',
    sent: '已发送',
  }
  return labels[key] ?? key
}

function fieldValue(key: string, value: unknown): string {
  if (typeof value === 'object') return JSON.stringify(value)
  if (key === 'status') return statusLabel(stringValue(value))
  if (key === 'stopReason' || key === 'failureCode' || key === 'code') return reasonLabel(stringValue(value))
  if (key === 'sent') return value === true ? '是' : '否'
  return String(value)
}

function statusClass(status: string | null, sent: boolean): string {
  const normalized = (status || '').toLowerCase()
  if (sent || normalized === 'succeeded' || normalized === 'success') return 'status-succeeded'
  if (normalized === 'failed' || normalized === 'error') return 'status-failed'
  return 'status-muted'
}

function profileLabel(profileId: string | null): string {
  if (!profileId) return '未关联线索资料'
  const profile = profilesById.value.get(String(profileId))
  return profile?.displayName || profile?.handle || `线索 ${profileId}`
}

function commentLabel(commentId: string | null): string {
  if (!commentId) return '未关联评论'
  const comment = commentsById.value.get(String(commentId))
  const text = comment?.text?.trim()
  if (!text) return `评论 ${commentId}`
  return text.length > 54 ? `${text.slice(0, 54)}...` : text
}
</script>

<style scoped>
.douyin-result {
  display: flex;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
}

.douyin-result.is-loading {
  opacity: 0.78;
}

.summary-strip {
  display: grid;
  grid-template-columns: repeat(7, minmax(112px, 1fr));
  gap: 8px;
}

.summary-metric {
  min-width: 0;
  min-height: 92px;
  padding: 12px;
  border: 1px solid var(--mc-border-light, rgba(0, 0, 0, 0.08));
  border-radius: 8px;
  background: var(--mc-bg-elevated, #fff);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  box-shadow: 0 10px 24px rgba(15, 23, 42, 0.04);
}

.summary-metric::before {
  content: "";
  display: block;
  width: 28px;
  height: 3px;
  border-radius: 999px;
  background: #2563eb;
}

.summary-metric.tone-success::before { background: #16a34a; }
.summary-metric.tone-danger::before { background: #dc2626; }
.summary-metric.tone-accent::before { background: #7c3aed; }

.summary-metric__label {
  margin-top: 8px;
  font-size: 12px;
  line-height: 1.35;
  color: var(--mc-text-secondary);
}

.summary-metric__value {
  margin-top: 8px;
  font-size: 24px;
  line-height: 1;
  color: var(--mc-text-primary);
  font-variant-numeric: tabular-nums;
}

.summary-metric__hint {
  margin-top: 6px;
  font-size: 11px;
  color: var(--mc-text-tertiary);
}

.result-grid {
  display: grid;
  grid-template-columns: minmax(320px, 0.88fr) minmax(380px, 1.12fr);
  gap: 16px;
  align-items: start;
}

.result-grid--bottom {
  grid-template-columns: repeat(2, minmax(320px, 1fr));
}

.result-panel {
  min-width: 0;
  padding: 14px;
  border: 1px solid var(--mc-border-light, rgba(0, 0, 0, 0.08));
  border-radius: 8px;
  background: var(--mc-bg-elevated, #fff);
}

.panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 10px;
  border-bottom: 1px solid var(--mc-border-light, rgba(0, 0, 0, 0.08));
}

.panel-header h3 {
  margin: 0;
  font-size: 14px;
  color: var(--mc-text-primary);
}

.panel-header p {
  margin: 3px 0 0;
  font-size: 12px;
  color: var(--mc-text-tertiary);
}

.timeline-list {
  position: relative;
  list-style: none;
  margin: 0;
  padding: 12px 0 0;
}

.panel-collapse {
  padding-top: 12px;
}

.panel-collapse summary {
  width: fit-content;
  max-width: 100%;
  cursor: pointer;
  border-radius: 6px;
  padding: 2px 4px;
  color: var(--mc-text-secondary);
  font-size: 13px;
  font-weight: 700;
}

.panel-collapse summary:focus {
  outline: none;
}

.panel-collapse summary:focus-visible {
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--mc-primary) 22%, transparent);
}

.panel-scroll-body {
  max-height: 420px;
  overflow: auto;
}

.timeline-list::before {
  content: "";
  position: absolute;
  left: 14px;
  top: 22px;
  bottom: 12px;
  width: 1px;
  background: var(--mc-border-light, rgba(0, 0, 0, 0.08));
}

.timeline-item {
  position: relative;
  display: grid;
  grid-template-columns: 30px minmax(0, 1fr);
  gap: 10px;
  padding: 0 0 14px;
}

.timeline-node {
  position: relative;
  z-index: 1;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--mc-bg-elevated, #fff);
  border: 1px solid var(--mc-border, rgba(0, 0, 0, 0.12));
  color: var(--mc-text-secondary);
}

.timeline-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: currentColor;
}

.timeline-item.tone-success .timeline-node { color: #16a34a; border-color: rgba(22, 163, 74, 0.36); }
.timeline-item.tone-danger .timeline-node { color: #dc2626; border-color: rgba(220, 38, 38, 0.36); }
.timeline-item.tone-warning .timeline-node { color: #d97706; border-color: rgba(217, 119, 6, 0.38); }
.timeline-item.tone-running .timeline-node { color: #2563eb; border-color: rgba(37, 99, 235, 0.34); }

.timeline-body {
  min-width: 0;
  padding: 9px 10px;
  border: 1px solid var(--mc-border-light, rgba(0, 0, 0, 0.07));
  border-radius: 8px;
  background: var(--mc-bg-sunken, rgba(0, 0, 0, 0.02));
}

.timeline-item.is-lead-event .timeline-body {
  border-left: 3px solid #2563eb;
}

.timeline-item.tone-success.is-lead-event .timeline-body { border-left-color: #16a34a; }
.timeline-item.tone-danger.is-lead-event .timeline-body { border-left-color: #dc2626; }
.timeline-item.tone-warning.is-lead-event .timeline-body { border-left-color: #d97706; }

.timeline-line {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.timeline-title {
  font-size: 13px;
  font-weight: 700;
  color: var(--mc-text-primary);
  white-space: nowrap;
}

.timeline-type {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: var(--mc-text-tertiary);
  background: transparent;
}

.timeline-time {
  margin-left: auto;
  flex: 0 0 auto;
  font-size: 11px;
  color: var(--mc-text-tertiary);
}

.timeline-summary {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--mc-text-secondary);
  word-break: break-word;
}

.timeline-fields {
  display: grid;
  grid-template-columns: minmax(90px, auto) minmax(0, 1fr);
  gap: 4px 8px;
  margin: 8px 0 0;
  font-size: 11px;
}

.timeline-fields dt {
  color: var(--mc-text-tertiary);
}

.timeline-fields dd {
  margin: 0;
  min-width: 0;
  color: var(--mc-text-secondary);
  word-break: break-word;
}

.timeline-payload {
  margin-top: 8px;
  font-size: 11px;
  color: var(--mc-text-tertiary);
}

.timeline-payload summary {
  cursor: pointer;
}

.timeline-payload pre {
  max-height: 220px;
  margin: 6px 0 0;
  padding: 8px;
  overflow: auto;
  border-radius: 6px;
  background: color-mix(in srgb, var(--mc-bg-sunken, #f6f7f8) 86%, #000 4%);
  color: var(--mc-text-secondary);
  white-space: pre-wrap;
  word-break: break-word;
}

.table-wrap {
  overflow: auto;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  table-layout: fixed;
}

.data-table th,
.data-table td {
  padding: 10px 8px;
  text-align: left;
  vertical-align: top;
  border-bottom: 1px solid var(--mc-border-light, rgba(0, 0, 0, 0.07));
}

.data-table th {
  position: sticky;
  top: 0;
  z-index: 1;
  font-size: 11px;
  color: var(--mc-text-tertiary);
  background: var(--mc-bg-elevated, #fff);
}

.data-table td {
  font-size: 12px;
  color: var(--mc-text-primary);
}

.comment-text {
  margin: 0;
  line-height: 1.5;
  color: var(--mc-text-primary);
  word-break: break-word;
}

.reason-text {
  margin: 5px 0 0;
  font-size: 11px;
  line-height: 1.45;
  color: var(--mc-text-secondary);
  word-break: break-word;
}

.reason-text.is-error {
  color: var(--mc-danger, #dc2626);
}

.score-text {
  display: block;
  margin-top: 5px;
  font-size: 11px;
  color: var(--mc-text-secondary);
}

.muted-code {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 11px;
  color: var(--mc-text-tertiary);
}

.link-quiet {
  color: var(--mc-primary, #2563eb);
  text-decoration: none;
}

.link-quiet:hover {
  text-decoration: underline;
}

.status-pill {
  display: inline-flex;
  align-items: center;
  min-height: 22px;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 700;
  white-space: nowrap;
}

.status-succeeded {
  background: rgba(22, 163, 74, 0.12);
  color: #15803d;
}

.status-failed {
  background: rgba(220, 38, 38, 0.12);
  color: #b91c1c;
}

.status-muted {
  background: var(--mc-bg-sunken, rgba(0, 0, 0, 0.05));
  color: var(--mc-text-secondary);
}

.empty-block {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 112px;
  padding: 18px;
  font-size: 13px;
  color: var(--mc-text-tertiary);
  text-align: center;
}

@media (max-width: 1280px) {
  .summary-strip {
    grid-template-columns: repeat(4, minmax(112px, 1fr));
  }
}

@media (max-width: 980px) {
  .result-grid,
  .result-grid--bottom {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 680px) {
  .summary-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .timeline-line {
    flex-wrap: wrap;
  }

  .timeline-time {
    margin-left: 0;
  }

  .data-table {
    min-width: 620px;
  }
}
</style>
