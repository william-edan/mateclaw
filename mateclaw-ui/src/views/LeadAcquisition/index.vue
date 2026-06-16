<template>
  <div class="mc-page-shell lead-shell">
    <div class="mc-page-frame lead-frame">
      <div class="lead-page">
        <header class="lead-header">
          <div>
            <span class="lead-kicker">获客专家</span>
            <h1>抖音获客</h1>
            <p>输入目标关键词和匹配条件，系统会完成搜索、评论采集、评论匹配和触达结果汇总。</p>
          </div>
          <div class="lead-header__actions">
            <button
              class="ghost-button view-switch-button"
              type="button"
              :aria-pressed="activeView === 'history'"
              @click="toggleLeadView"
            >
              <span>{{ activeView === 'history' ? '启动新任务' : '查看历史任务' }}</span>
            </button>
            <button class="ghost-button" type="button" @click="openChatStarter">
              <el-icon><ChatDotRound /></el-icon>
              <span>用对话启动</span>
            </button>
            <button class="ghost-button" type="button" @click="openBrowserPanel">
              <el-icon><Connection /></el-icon>
              <span>浏览器连接</span>
            </button>
          </div>
        </header>

        <Transition name="browser-scrim">
          <div
            v-if="browserPanelOpen"
            class="browser-pairing-scrim"
            aria-hidden="true"
            @click="closeBrowserPanel"
          ></div>
        </Transition>
        <Transition name="browser-panel">
          <section
            v-if="browserPanelOpen"
            ref="browserPanelRef"
            class="browser-pairing-popover"
            role="dialog"
            aria-modal="true"
            aria-label="浏览器连接"
            tabindex="-1"
            @keydown.esc="closeBrowserPanel"
          >
            <div class="browser-pairing-popover__head">
              <div>
                <h2>浏览器连接</h2>
                <p>扩展配对与连接状态</p>
              </div>
              <button class="text-button" type="button" @click="closeBrowserPanel">关闭</button>
            </div>
            <div class="browser-pairing-body">
              <BrowserPairingPanel embedded compact />
            </div>
          </section>
        </Transition>

        <template v-if="activeView === 'launch'">
          <section class="lead-workbench">
            <form class="launch-panel" @submit.prevent="submitDouyinRun">
              <div class="panel-head">
                <div>
                  <h2>启动任务</h2>
                  <p>默认按综合排序，先采集一级评论并匹配正文。</p>
                </div>
              </div>

              <div class="field-grid">
                <label class="field field--wide">
                  <span>关键词</span>
                  <input
                    v-model.trim="form.keyword"
                    type="text"
                    maxlength="120"
                    placeholder="搜索视频关键词"
                    :disabled="taskLocked"
                    required
                  />
                </label>

                <label class="field">
                  <span>排序</span>
                  <select v-model="form.sort" :disabled="taskLocked">
                    <option value="comprehensive">综合排序</option>
                    <option value="most_liked">最多点赞</option>
                    <option value="latest">最新发布</option>
                  </select>
                </label>

                <label class="field">
                  <span>视频数量</span>
                  <input
                    v-model.number="form.videoLimit"
                    type="number"
                    min="1"
                    max="50"
                    :disabled="taskLocked"
                    @change="normalizeVideoLimit"
                  />
                </label>

                <div class="field field--wide match-target-field">
                  <div class="match-target-switch">
                    <span>
                      <strong>评论匹配目标</strong>
                      <small>描述要找哪些评论，系统会按评论正文语义判断是否值得触达</small>
                    </span>
                  </div>

                  <div class="match-target-workspace">
                    <div class="field-head">
                      <span>默认分类</span>
                      <span class="field-hint">点击后自动填充描述和示例词</span>
                    </div>
                    <div class="match-preset-grid">
                      <button
                        v-for="preset in MATCH_PRESETS"
                        :key="preset.key"
                        type="button"
                        class="match-preset-button"
                        :class="{ active: form.matchProfile === preset.key }"
                        :disabled="taskLocked"
                        @click="applyMatchPreset(preset.key)"
                      >
                        <strong>{{ preset.label }}</strong>
                      </button>
                    </div>

                    <label class="field compact-field">
                      <span>描述匹配哪些评论</span>
                      <textarea
                        v-model.trim="form.matchDescription"
                        rows="3"
                        maxlength="260"
                        placeholder="例如：匹配正在咨询价格、试用、功能、购买渠道或联系方式的评论"
                        :disabled="taskLocked"
                      ></textarea>
                    </label>

                    <div class="field-head">
                      <span>示例词</span>
                      <span class="field-hint">作为模型判断参考，可自行添加</span>
                    </div>
                    <div class="intent-chip-list">
                      <span
                        v-for="(example, index) in form.matchExamples"
                        :key="`${example}-${index}`"
                        class="intent-chip"
                      >
                        {{ example }}
                        <button
                          type="button"
                          :disabled="taskLocked"
                          :aria-label="`删除示例词 ${example}`"
                          @click="removeMatchExample(index)"
                        >
                          ×
                        </button>
                      </span>
                    </div>
                    <div class="intent-example-add">
                      <input
                        v-model.trim="matchExampleDraft"
                        type="text"
                        maxlength="40"
                        placeholder="例如：有没有方案"
                        :disabled="taskLocked"
                        @keydown.enter.prevent="addMatchExample"
                      />
                      <button type="button" :disabled="taskLocked || !matchExampleDraft.trim()" @click="addMatchExample">
                        添加
                      </button>
                    </div>
                  </div>
                </div>

              </div>

              <div class="switch-row">
                <label class="switch-item">
                  <input v-model="form.engage" type="checkbox" :disabled="taskLocked" />
                  <span>关注匹配用户</span>
                  <McTooltip placement="top">
                    <el-icon class="switch-hint-icon" @click.prevent.stop><QuestionFilled /></el-icon>
                    <template #content>
                      仅对命中评论的用户执行关注，不发送私信。
                    </template>
                  </McTooltip>
                </label>
                <label class="switch-item">
                  <input v-model="form.sendDm" type="checkbox" :disabled="taskLocked || !form.engage" />
                  <span>自动发送私信</span>
                  <McTooltip placement="top">
                    <el-icon class="switch-hint-icon" @click.prevent.stop><QuestionFilled /></el-icon>
                    <template #content>
                      <p style="margin: 0 0 6px;">在关注基础上，自动发送上方填写的私信内容。</p>
                      <p style="margin: 0;">抖音平台对私信和关注有每日频次限制，频繁操作可能触发风控，导致私信、关注等功能被临时禁用。为保护账号安全，请合理控制每日关注和私信数量，避免过度使用。</p>
                    </template>
                  </McTooltip>
                </label>
              </div>

              <label v-show="form.engage" class="field field--wide">
                <span>私信内容（草稿）</span>
                <textarea
                  v-model.trim="form.dmDraft"
                  rows="3"
                  maxlength="500"
                  placeholder="你好，看到你的评论，方便简单交流一下吗？"
                  :disabled="taskLocked"
                ></textarea>
                <span class="field-hint">不勾「自动发送私信」时，只把这段内容填入私信框作为草稿、不会发出；勾选后才会自动发送。</span>
              </label>

              <div class="form-actions">
                <button class="primary-button" type="submit" :disabled="!canLaunch">
                  <span v-if="launching" class="mini-spinner" aria-hidden="true"></span>
                  <el-icon v-else><Promotion /></el-icon>
                  <span>{{ launchButtonText }}</span>
                </button>
                <button class="text-button" type="button" :disabled="taskLocked" @click="resetForm">重置</button>
              </div>
            </form>

            <aside class="assist-panel">
              <section class="assist-section">
                <div class="assist-section__head">
                  <h2>常用模板</h2>
                  <button type="button" :disabled="templateSaving" @click="saveCurrentTemplate">
                    {{ templateSaving ? '保存中' : '保存当前' }}
                  </button>
                </div>
                <label class="template-name-field">
                  <span>模板名称</span>
                  <input v-model.trim="templateName" type="text" maxlength="60" placeholder="例如：产品吐槽线索" />
                </label>
                <div class="template-list">
                  <template v-if="savedTemplates.length">
                    <div
                      v-for="template in savedTemplates"
                      :key="template.id"
                      class="template-item template-item--saved"
                    >
                      <button type="button" :disabled="taskLocked" @click="applyTemplate(template)">
                        <strong>{{ template.name }}</strong>
                        <span>{{ template.keyword || '未设置关键词' }} · {{ matchRulesSummary(template.matchRules) }}</span>
                      </button>
                      <button type="button" class="template-delete" @click="deleteTemplate(template.id)">删除</button>
                    </div>
                  </template>
                  <button
                    v-for="template in builtInTemplates"
                    :key="template.name"
                    type="button"
                    class="template-item"
                    :disabled="taskLocked"
                    @click="applyTemplate(template)"
                  >
                    <strong>{{ template.name }}</strong>
                    <span>{{ template.keyword }} · {{ matchProfileLabel(template.matchProfile) }}</span>
                  </button>
                </div>
              </section>
            </aside>
          </section>

          <section v-if="currentRun || launchError" class="result-panel">
            <div v-if="launchError" class="error-block" role="alert">{{ launchError }}</div>
            <LeadRunLivePanel
              v-if="currentRun"
              :run-id="currentRun.runId"
              :task-id="currentRun.taskId"
              :initial-run="currentRun"
              @update:run="handleLiveRunUpdate"
              @terminal="handleRunTerminal"
            />
          </section>
        </template>

        <template v-else>
          <section class="stats-panel">
          <div class="panel-head compact">
            <div>
              <h2>统计看板</h2>
              <p>{{ statsLoading ? '正在更新最近任务表现...' : '汇总最近获客任务的采集、命中和触达效果。' }}</p>
            </div>
            <button class="text-button" type="button" :disabled="statsLoading" @click="loadLeadStats()">
              {{ statsLoading ? '刷新中' : '刷新' }}
            </button>
          </div>

          <div class="stats-toolbar">
            <label>
              <span>关键词筛选</span>
              <input
                v-model.trim="statsKeyword"
                type="text"
                placeholder="默认统计最近 50 个任务"
                @keyup.enter="loadLeadStats()"
              />
            </label>
            <button class="text-button" type="button" @click="clearStatsKeyword">清空</button>
          </div>
          <div v-if="statsError" class="stats-error">{{ statsError }}</div>

          <div class="stats-card-grid">
            <article v-for="card in statsCards" :key="card.label" class="metric-card">
              <span>{{ card.label }}</span>
              <strong>{{ card.value }}</strong>
              <small>{{ card.detail }}</small>
            </article>
          </div>

          <div class="stats-insights">
            <div class="rate-strip">
              <div v-for="rate in statsRates" :key="rate.label" class="rate-item">
                <span>{{ rate.label }}</span>
                <strong>{{ rate.value }}</strong>
                <small>{{ rate.detail }}</small>
              </div>
            </div>
            <div class="failure-card">
              <div class="failure-card__head">
                <h3>失败原因分布</h3>
                <span>{{ failureReasonRows.length }} 类</span>
              </div>
              <div v-if="failureReasonRows.length" class="failure-list">
                <div v-for="reason in failureReasonRows" :key="reason.rawReason" class="failure-row">
                  <span>{{ reason.label }}</span>
                  <strong>{{ reason.count }}</strong>
                  <div class="failure-bar" aria-hidden="true">
                    <i :style="{ width: reason.percent }"></i>
                  </div>
                </div>
              </div>
              <div v-else class="empty-inline">最近任务暂无失败记录。</div>
            </div>
          </div>
          </section>

          <section class="growth-workspace">
            <section class="history-panel">
            <div class="panel-head compact">
              <div>
                <h2>最近任务</h2>
                <p>查看历史获客任务，继续打开实时执行台和结果明细。</p>
              </div>
              <button class="text-button" type="button" :disabled="recentLoading" @click="loadRecentRuns">
                {{ recentLoading ? '刷新中' : '刷新' }}
              </button>
            </div>

            <div v-if="recentRuns.length" class="history-list">
              <button
                v-for="run in recentRuns"
                :key="run.runId || run.taskId || run.createTime || 'run'"
                class="history-item"
                type="button"
                :class="{ 'is-active': run.runId && run.runId === currentRun?.runId }"
                :aria-current="run.runId && run.runId === currentRun?.runId ? 'true' : undefined"
                @click="openRunFromHistory(run)"
              >
                <span class="history-item__main">
                  <strong>{{ run.keyword || '抖音获客任务' }}</strong>
                  <small>{{ sortLabel(run.sort) }} · {{ formatDateTime(run.updateTime || run.createTime) }}</small>
                </span>
                <span class="history-item__stats">
                  <span>{{ statusLabel(run.status) }}</span>
                  <small>{{ run.processedVideos || 0 }}/{{ run.requestedVideoLimit || '-' }} 视频 · {{ run.commentsCollected || 0 }} 评论 · {{ run.matchedComments || 0 }} 命中</small>
                </span>
              </button>
            </div>
            <div v-else class="empty-card">
              {{ recentLoading ? '正在读取最近任务...' : '暂无历史任务。启动一次获客后会显示在这里。' }}
            </div>
            </section>

            <section class="lead-pool-panel">
            <div class="panel-head compact">
              <div>
                <h2>线索池</h2>
                <p>{{ leadPoolLoading ? '正在读取线索...' : leadPoolScopeDescription }}</p>
              </div>
              <button class="text-button" type="button" :disabled="leadPoolLoading" @click="loadLeadPool">
                {{ leadPoolLoading ? '刷新中' : '刷新' }}
              </button>
            </div>

            <div class="lead-pool-filters">
              <label>
                <span>关键词</span>
                <input
                  v-model.trim="leadPoolKeyword"
                  type="text"
                  placeholder="按任务关键词筛选"
                  @keyup.enter="loadLeadPool"
                />
              </label>
              <label>
                <span>状态</span>
                <select v-model="leadPoolStatus" @change="loadLeadPool">
                  <option value="all">全部线索</option>
                  <option value="pending">待触达</option>
                  <option value="engaged">已触达</option>
                  <option value="sent">已发送</option>
                  <option value="failed">失败</option>
                </select>
              </label>
            </div>

            <div v-if="leadPoolRows.length" class="lead-pool-list">
              <article v-for="lead in leadPoolRows" :key="lead.key" class="lead-card">
                <div class="lead-card__top">
                  <a
                    v-if="lead.authorProfileUrl"
                    :href="lead.authorProfileUrl"
                    target="_blank"
                    rel="noreferrer"
                  >
                    {{ lead.authorName }}
                  </a>
                  <strong v-else>{{ lead.authorName }}</strong>
                  <span class="status-pill" :class="lead.sent ? 'status-succeeded' : engagementTone(lead.engagementStatus)">
                    {{ lead.sent ? '已发送' : statusLabel(lead.engagementStatus) }}
                  </span>
                </div>
                <p>{{ lead.text }}</p>
                <div class="lead-card__meta">
                  <span>{{ lead.videoKey || '来源视频未记录' }}</span>
                  <span>{{ lead.keyword || '未知关键词' }}</span>
                  <span v-if="lead.failureReason">{{ lead.failureReason }}</span>
                  <button v-if="lead.runId" class="inline-link" type="button" @click="openRunById(lead.runId)">
                    回看任务
                  </button>
                </div>
              </article>
            </div>
            <div v-else class="empty-card">
              {{ leadPoolLoading ? '正在读取线索池...' : '暂无命中线索。' }}
            </div>
            </section>
          </section>
          <section class="history-final-summary">
            <LeadRunFinalSummary
              v-if="currentRun"
              :run="currentRun"
              :loading="historyRunLoading"
            />
            <div v-else class="empty-card">
              {{ recentLoading ? '正在读取最近任务...' : '选择一条最近任务后展示最终汇总。' }}
            </div>
          </section>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ChatDotRound, Connection, Promotion, QuestionFilled } from '@element-plus/icons-vue'
import { leadAcquisitionApi } from '@/api'
import type {
  DouyinLeadAcquisitionRunResponse,
  DouyinLeadAcquisitionStartPayload,
  DouyinLeadPoolItem,
  DouyinLeadRunListItem,
  DouyinLeadStatsResponse,
  DouyinLeadMatchRule,
  DouyinLeadTemplate,
  DouyinLeadTemplatePayload,
} from '@/api'
import { mcToast } from '@/composables/useMcToast'
import McTooltip from '@/components/common/McTooltip.vue'
import LeadRunLivePanel from '@/components/lead/LeadRunLivePanel.vue'
import LeadRunFinalSummary from '@/components/lead/LeadRunFinalSummary.vue'
import BrowserPairingPanel from '@/views/Settings/Browser/index.vue'
import { ensureExtensionConnected } from '@/views/Settings/Browser/pairing'

type SortMode = 'comprehensive' | 'most_liked' | 'latest'
type LeadPoolStatus = 'all' | 'pending' | 'engaged' | 'sent' | 'failed'
type LeadView = 'launch' | 'history'
type OpenRunOptions = { scroll?: boolean }
type MatchProfile = 'high_intent' | 'bad_experience' | 'consultation' | 'competitor_alternative' | 'custom'

interface MatchPreset {
  key: MatchProfile
  label: string
  description: string
  examples: string[]
  dmDraft: string
}

interface LeadForm {
  keyword: string
  sort: SortMode
  videoLimit: number
  matchProfile: MatchProfile
  matchDescription: string
  matchExamples: string[]
  dmDraft: string
  engage: boolean
  sendDm: boolean
}

interface LeadTemplate {
  name: string
  keyword: string
  matchRules: DouyinLeadMatchRule[]
  matchEnabled?: boolean
  matchProfile?: MatchProfile | string
  matchDescription?: string
  matchExamples?: string[]
  matchHighIntent?: boolean
  highIntentExamples?: string[]
  dmDraft: string
  videoLimit: number
  sort?: SortMode
  engage?: boolean
  sendDm?: boolean
}

const router = useRouter()
const route = useRoute()
const activeView = ref<LeadView>('launch')
const launching = ref(false)
const currentRun = ref<DouyinLeadAcquisitionRunResponse | null>(null)
const launchError = ref('')
const browserPanelRef = ref<HTMLElement | null>(null)
const browserPanelOpen = ref(false)
const recentRuns = ref<DouyinLeadRunListItem[]>([])
const recentLoading = ref(false)
const historyRunLoading = ref(false)
const leadPool = ref<DouyinLeadPoolItem[]>([])
const leadPoolLoading = ref(false)
const leadPoolKeyword = ref('')
const leadPoolStatus = ref<LeadPoolStatus>('all')
const leadStats = ref<DouyinLeadStatsResponse | null>(null)
const statsLoading = ref(false)
const statsReloadQueued = ref(false)
const statsError = ref('')
const statsKeyword = ref('')
const savedTemplates = ref<DouyinLeadTemplate[]>([])
const templateName = ref('')
const templateSaving = ref(false)
const matchExampleDraft = ref('')

const MATCH_PRESETS: MatchPreset[] = [
  {
    key: 'high_intent',
    label: '有意向的客户',
    description: '匹配正在咨询价格、试用、功能、购买渠道、联系方式，或明确表达想了解解决方案的评论。',
    examples: ['多少钱', '怎么收费', '能试用吗', '怎么联系', '想了解', '有没有方案'],
    dmDraft: '你好，看到你在评论里提到相关需求，方便简单交流一下吗？',
  },
  {
    key: 'bad_experience',
    label: '体验不好的用户',
    description: '匹配抱怨现有工具慢、不好用、出错、买后后悔，或表达想换方案的评论。',
    examples: ['太慢了', '不好用', '用不了', '不推荐', '后悔买了', '一直出问题'],
    dmDraft: '你好，看到你提到使用体验不太顺，方便了解一下你的场景吗？',
  },
  {
    key: 'consultation',
    label: '求教程/功能咨询',
    description: '匹配正在询问怎么做、怎么用、是否支持某功能，或需要教程和落地方法的评论。',
    examples: ['怎么用', '有教程吗', '怎么做', '支持吗', '能不能', '求教学'],
    dmDraft: '你好，看到你在问具体用法，我这边可以给你一个思路。',
  },
  {
    key: 'competitor_alternative',
    label: '竞品替代需求',
    description: '匹配正在寻找替代工具、比较不同产品、求推荐新方案，或表达想从现有工具迁移的评论。',
    examples: ['有没有替代', '哪个更好用', '求推荐', '换一个工具', '类似软件', '平替'],
    dmDraft: '你好，看到你在找替代方案，方便简单交流一下你的需求吗？',
  },
  {
    key: 'custom',
    label: '自定义',
    description: '描述你希望命中的评论类型，系统会按评论正文语义判断是否匹配。',
    examples: ['补充你的示例词'],
    dmDraft: '你好，看到你的评论，方便简单交流一下吗？',
  },
]

const DEFAULT_MATCH_PROFILE: MatchProfile = 'high_intent'

const builtInTemplates: LeadTemplate[] = [
  {
    name: '意向客户挖掘',
    keyword: '化帆AI',
    matchRules: [],
    matchEnabled: true,
    matchProfile: 'high_intent',
    matchDescription: matchPreset('high_intent').description,
    matchExamples: [...matchPreset('high_intent').examples],
    dmDraft: matchPreset('high_intent').dmDraft,
    videoLimit: 5,
    sort: 'latest',
  },
  {
    name: '用户反馈收集',
    keyword: '化帆AI',
    matchRules: [],
    matchEnabled: true,
    matchProfile: 'bad_experience',
    matchDescription: matchPreset('bad_experience').description,
    matchExamples: [...matchPreset('bad_experience').examples],
    dmDraft: matchPreset('bad_experience').dmDraft,
    videoLimit: 5,
    sort: 'latest',
  },
]

const form = ref<LeadForm>(defaultForm())

const canLaunch = computed(() => {
  return !taskLocked.value
    && form.value.keyword.trim().length > 0
    && form.value.matchDescription.trim().length > 0
})

const activeRunRunning = computed(() => {
  return !!currentRun.value && !isTerminalStatus(currentRun.value.status)
})

const taskLocked = computed(() => launching.value || activeRunRunning.value)

const launchButtonText = computed(() => {
  if (launching.value) return '启动中'
  if (activeRunRunning.value) return '执行中'
  return '开始获客'
})

const leadPoolRows = computed(() => {
  return leadPool.value.map((lead, index) => ({
    key: lead.commentId || lead.commentKey || `${lead.text}-${index}`,
    runId: lead.runId,
    keyword: lead.keyword,
    authorName: lead.displayName || lead.authorName || '未识别作者',
    authorProfileUrl: lead.profileUrl || lead.authorProfileUrl,
    text: lead.text || '-',
    videoKey: lead.videoKey,
    engagementStatus: lead.engagementStatus || 'pending',
    sent: lead.sent === true,
    failureReason: reasonLabel(lead.failureCode || lead.failureMessage || ''),
  }))
})

const selectedLeadPoolTaskId = computed(() => {
  if (activeView.value !== 'history') return null
  return currentRun.value?.taskId ?? null
})

const leadPoolScopeDescription = computed(() => {
  const count = leadPoolRows.value.length
  if (selectedLeadPoolTaskId.value) {
    return `展示当前任务的 ${count} 条命中线索。`
  }
  return `展示 ${count} 条跨任务命中线索。`
})

const statsCards = computed(() => {
  const stats = leadStats.value ?? emptyStats()
  return [
    {
      label: '任务',
      value: stats.taskCount,
      detail: `成功 ${stats.succeededTasks} · 执行中 ${stats.runningTasks} · 失败 ${stats.failedTasks}`,
    },
    {
      label: '视频',
      value: `${stats.processedVideos}/${stats.requestedVideos || '-'}`,
      detail: `成功 ${stats.succeededVideos} · 失败 ${stats.failedVideos}`,
    },
    {
      label: '评论',
      value: stats.commentsCollected,
      detail: `命中 ${stats.matchedComments} 条评论`,
    },
    {
      label: '触达',
      value: stats.engagementsCreated,
      detail: `已发送 ${stats.sentMessages} 条私信`,
    },
  ]
})

const statsRates = computed(() => {
  const stats = leadStats.value ?? emptyStats()
  return [
    {
      label: '评论命中率',
      value: formatPercent(stats.matchRate),
      detail: `${stats.matchedComments}/${stats.commentsCollected || 0}`,
    },
    {
      label: '触达率',
      value: formatPercent(stats.engagementRate),
      detail: `${stats.engagementsCreated}/${stats.matchedComments || 0}`,
    },
    {
      label: '发送成功率',
      value: formatPercent(stats.sendSuccessRate),
      detail: `${stats.sentMessages}/${stats.engagementsCreated || 0}`,
    },
  ]
})

const failureReasonRows = computed(() => {
  const rows = leadStats.value?.failureReasons ?? []
  const total = rows.reduce((sum, item) => sum + Math.max(0, item.count || 0), 0)
  return rows.map((item) => {
    const count = Math.max(0, item.count || 0)
    return {
      rawReason: item.reason || 'UNKNOWN_FAILURE',
      label: reasonLabel(item.reason),
      count,
      percent: total > 0 ? `${Math.max(6, Math.round((count / total) * 100))}%` : '0%',
    }
  })
})

watch(() => form.value.engage, (engage) => {
  if (!engage) form.value.sendDm = false
})

watch(recentRuns, () => {
  if (activeView.value === 'history') void ensureHistoryRunSelected()
})

onMounted(() => {
  if (route.query.connectBrowser === '1') {
    void openBrowserPanel()
  }
  loadRecentRuns()
  loadLeadPool()
  loadLeadStats()
  loadTemplates()
})

function defaultForm(): LeadForm {
  const preset = matchPreset(DEFAULT_MATCH_PROFILE)
  return {
    keyword: '',
    sort: 'comprehensive',
    videoLimit: 2,
    matchProfile: preset.key,
    matchDescription: preset.description,
    matchExamples: [...preset.examples],
    dmDraft: preset.dmDraft,
    engage: true,
    sendDm: false,
  }
}

function normalizeVideoLimit() {
  const raw = Number(form.value.videoLimit)
  const next = Number.isFinite(raw) ? Math.trunc(raw) : 2
  form.value.videoLimit = Math.min(50, Math.max(1, next))
}

function matchPreset(profile?: string | null): MatchPreset {
  return MATCH_PRESETS.find(preset => preset.key === profile) ?? MATCH_PRESETS[0]!
}

function normalizeMatchProfile(value?: string | null): MatchProfile {
  return matchPreset(value).key
}

function matchProfileLabel(profile?: string | null): string {
  return matchPreset(profile).label
}

function applyMatchPreset(profile: MatchProfile | string) {
  if (taskLocked.value) return
  const preset = matchPreset(profile)
  form.value.matchProfile = preset.key
  form.value.matchDescription = preset.description
  form.value.matchExamples = [...preset.examples]
  if (!form.value.dmDraft.trim() || MATCH_PRESETS.some(item => item.dmDraft === form.value.dmDraft.trim())) {
    form.value.dmDraft = preset.dmDraft
  }
}

function normalizeMatchExamples(examples?: string[] | null): string[] {
  const seen = new Set<string>()
  const normalized: string[] = []
  for (const item of examples ?? []) {
    const value = String(item ?? '').trim()
    if (!value || seen.has(value)) continue
    seen.add(value)
    normalized.push(value)
    if (normalized.length >= 30) break
  }
  return normalized
}

function addMatchExample() {
  const value = matchExampleDraft.value.trim()
  if (!value || taskLocked.value) return
  form.value.matchExamples = normalizeMatchExamples([...form.value.matchExamples, value])
  matchExampleDraft.value = ''
}

function removeMatchExample(index: number) {
  if (taskLocked.value) return
  form.value.matchExamples.splice(index, 1)
}

function normalizeMatchRules(rules?: DouyinLeadMatchRule[] | null): DouyinLeadMatchRule[] {
  const normalized = (rules ?? [])
    .map(rule => ({
      mode: rule?.mode === 'semantic' ? 'semantic' : 'keyword',
      value: String(rule?.value ?? '').trim(),
    }))
    .filter(rule => rule.value.length > 0)
  return normalized.length ? normalized : [{ mode: 'keyword', value: '' }]
}

function isPresetMatchRule(rule: DouyinLeadMatchRule): boolean {
  const value = String(rule.value || '')
  return rule.mode === 'semantic' && (
    value.includes('评论匹配目标')
    || value.includes('高意向客户')
  )
}

function examplesFromPresetRules(rules?: DouyinLeadMatchRule[] | null): string[] {
  const rule = normalizeMatchRules(rules).find(isPresetMatchRule)
  const match = String(rule?.value || '').match(/(?:可参考这些高意向表达或相近说法|可参考这些示例词或相近说法)：(.+?)。/)
  if (!match) return []
  return normalizeMatchExamples(match[1].split('、'))
}

function descriptionFromPresetRules(rules?: DouyinLeadMatchRule[] | null): string {
  const rule = normalizeMatchRules(rules).find(isPresetMatchRule)
  const value = String(rule?.value || '')
  const modern = value.match(/匹配描述：(.+?)。(?:只根据评论正文判断|可参考这些示例词|$)/)
  if (modern?.[1]) return modern[1].trim()
  if (value.includes('高意向客户')) return matchPreset('high_intent').description
  return ''
}

function profileFromPresetRules(rules?: DouyinLeadMatchRule[] | null): MatchProfile {
  const rule = normalizeMatchRules(rules).find(isPresetMatchRule)
  const value = String(rule?.value || '')
  const label = value.match(/评论匹配目标：(.+?)。/)?.[1]
  const preset = MATCH_PRESETS.find(item => item.label === label)
  if (preset) return preset.key
  if (value.includes('高意向客户')) return 'high_intent'
  return 'custom'
}

function templateMatchRulesPayload(): DouyinLeadMatchRule[] {
  return [{ mode: 'semantic', value: matchSemanticRule(form.value.matchProfile, form.value.matchDescription, form.value.matchExamples) }]
}

function matchSemanticRule(profile: MatchProfile | string, description: string, examples: string[]): string {
  const preset = matchPreset(profile)
  const normalizedDescription = description.trim() || preset.description
  const normalizedExamples = normalizeMatchExamples(examples)
  const base = [
    `评论匹配目标：${preset.label}。`,
    `匹配描述：${normalizedDescription}。`,
    '只根据评论正文判断，不要使用作者名称；不要命中与描述无关的泛泛点赞、收藏、路过、开玩笑或无明确需求的评论。',
  ].join('')
  return normalizedExamples.length
    ? `${base} 可参考这些示例词或相近说法：${normalizedExamples.join('、')}。`
    : base
}

function matchRulesSummary(rules?: DouyinLeadMatchRule[] | null): string {
  const normalized = normalizeMatchRules(rules).filter(rule => rule.value.length > 0)
  if (!normalized.length) return '只采集评论'
  const presetRule = normalized.find(isPresetMatchRule)
  if (presetRule) return profileFromPresetRules([presetRule]) === 'custom'
    ? '自定义匹配'
    : matchProfileLabel(profileFromPresetRules([presetRule]))
  return normalized
    .slice(0, 2)
    .map(rule => `${rule.mode === 'semantic' ? '语义' : '关键词'}：${rule.value}`)
    .join(' · ') + (normalized.length > 2 ? ` 等 ${normalized.length} 条` : '')
}

function resetForm() {
  form.value = defaultForm()
  launchError.value = ''
  matchExampleDraft.value = ''
}

function applyTemplate(template: LeadTemplate | DouyinLeadTemplate) {
  const templateRules = normalizeMatchRules(template.matchRules)
  const profile = normalizeMatchProfile(
    'matchProfile' in template && template.matchProfile
      ? String(template.matchProfile)
      : profileFromPresetRules(templateRules),
  )
  const preset = matchPreset(profile)
  const examples = normalizeMatchExamples(
    'matchExamples' in template && Array.isArray(template.matchExamples)
      ? template.matchExamples
      : 'highIntentExamples' in template && Array.isArray(template.highIntentExamples)
      ? template.highIntentExamples
      : examplesFromPresetRules(templateRules),
  )
  const description = 'matchDescription' in template && template.matchDescription
    ? String(template.matchDescription)
    : descriptionFromPresetRules(templateRules) || preset.description
  form.value = {
    keyword: template.keyword || '',
    sort: normalizeSortMode(template.sort),
    videoLimit: template.videoLimit || 2,
    matchProfile: profile,
    matchDescription: description,
    matchExamples: examples.length ? examples : [...preset.examples],
    dmDraft: template.dmDraft || preset.dmDraft,
    engage: template.engage ?? true,
    sendDm: template.sendDm ?? false,
  }
  matchExampleDraft.value = ''
  templateName.value = template.name || ''
}

async function loadTemplates() {
  try {
    const response = await leadAcquisitionApi.listDouyinTemplates()
    savedTemplates.value = unwrapApiData<DouyinLeadTemplate[]>(response, [])
  } catch (error) {
    savedTemplates.value = savedTemplates.value ?? []
  }
}

async function saveCurrentTemplate() {
  if (templateSaving.value) return
  const name = templateName.value.trim() || form.value.keyword.trim() || '抖音获客模板'
  templateSaving.value = true
  try {
    const payload = templatePayload(name)
    const existing = savedTemplates.value.find(item => item.name === name)
    if (existing?.id) {
      await leadAcquisitionApi.updateDouyinTemplate(existing.id, payload)
    } else {
      await leadAcquisitionApi.createDouyinTemplate(payload)
    }
    await loadTemplates()
    templateName.value = name
    mcToast.success('模板已保存')
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    mcToast.error(message)
  } finally {
    templateSaving.value = false
  }
}

async function deleteTemplate(id: string | number) {
  try {
    await leadAcquisitionApi.deleteDouyinTemplate(id)
    savedTemplates.value = savedTemplates.value.filter(item => item.id !== String(id))
    mcToast.success('模板已删除')
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    mcToast.error(message)
  }
}

function templatePayload(name: string): DouyinLeadTemplatePayload {
  normalizeVideoLimit()
  if (!form.value.engage) form.value.sendDm = false
  return {
    name,
    keyword: form.value.keyword.trim(),
    sort: form.value.sort,
    videoLimit: form.value.videoLimit,
    matchRules: templateMatchRulesPayload(),
    dmDraft: form.value.dmDraft.trim() || '你好',
    engage: form.value.engage,
    sendDm: form.value.sendDm,
  }
}

function normalizeSortMode(value?: string | null): SortMode {
  if (value === 'latest') return 'latest'
  if (value === 'most_liked') return 'most_liked'
  return 'comprehensive'
}

async function submitDouyinRun() {
  if (!canLaunch.value) return
  normalizeVideoLimit()
  if (!form.value.engage) form.value.sendDm = false
  launching.value = true
  launchError.value = ''
  currentRun.value = null
  try {
    // 自动确保浏览器扩展已连接：ping 唤醒可能休眠的扩展 SW + 触发重连，避免发起即 NO_SESSION。
    const conn = await ensureExtensionConnected()
    if (!conn.connected) {
      throw new Error(conn.reason === 'not-detected'
        ? '未检测到浏览器扩展，请确认已安装并启用 MateClaw 扩展后重试'
        : '浏览器扩展连接超时，请确认浏览器已打开、扩展已启用后重试')
    }
    const payload: DouyinLeadAcquisitionStartPayload = {
      keyword: form.value.keyword.trim(),
      sort: form.value.sort,
      videoLimit: form.value.videoLimit,
      matchRules: [],
      matchHighIntent: false,
      highIntentExamples: [],
      matchProfile: form.value.matchProfile,
      matchDescription: form.value.matchDescription.trim(),
      matchExamples: normalizeMatchExamples(form.value.matchExamples),
      dmDraft: form.value.dmDraft.trim() || '你好',
      engage: form.value.engage,
      sendDm: form.value.sendDm,
    }
    const response = await leadAcquisitionApi.startDouyinRun(payload)
    const run = unwrapApiData<DouyinLeadAcquisitionRunResponse | null>(response, null)
    if (!run) throw new Error('获客任务没有返回结果')
    currentRun.value = normalizeRun(run)
    await loadRecentRuns()
    await loadLeadPool()
    await loadLeadStats({ force: true })
    mcToast.success('抖音获客任务已启动')
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    launchError.value = message
    mcToast.error(message)
  } finally {
    launching.value = false
  }
}

function handleLiveRunUpdate(run: DouyinLeadAcquisitionRunResponse) {
  currentRun.value = normalizeRun(run)
}

function handleRunTerminal(run: DouyinLeadAcquisitionRunResponse) {
  currentRun.value = normalizeRun(run)
  loadRecentRuns()
  loadLeadPool()
  loadLeadStats({ force: true })
}

function normalizeRun(run: DouyinLeadAcquisitionRunResponse): DouyinLeadAcquisitionRunResponse {
  return {
    ...run,
    comments: run.comments ?? [],
    matches: run.matches ?? [],
    engagements: run.engagements ?? [],
    events: run.events ?? [],
  }
}

function unwrapApiData<T>(response: unknown, fallback: T): T {
  if (!response || typeof response !== 'object') return fallback
  const candidate = response as { data?: unknown }
  if ('data' in candidate) {
    const data = candidate.data as { data?: unknown } | unknown
    if (data && typeof data === 'object' && 'data' in (data as Record<string, unknown>)) {
      return ((data as { data?: unknown }).data ?? fallback) as T
    }
    return (candidate.data ?? fallback) as T
  }
  return response as T
}

function isTerminalStatus(status?: string | null): boolean {
  return ['succeeded', 'success', 'completed', 'failed', 'aborted', 'cancelled', 'canceled'].includes(String(status || '').toLowerCase())
}

async function toggleLeadView() {
  if (activeView.value === 'history') {
    activeView.value = 'launch'
    return
  }
  activeView.value = 'history'
  await nextTick()
  await ensureHistoryRunSelected()
}

async function ensureHistoryRunSelected() {
  if (recentLoading.value || historyRunLoading.value) return
  const currentId = currentRun.value?.runId == null ? '' : String(currentRun.value.runId)
  if (currentId && recentRuns.value.some(run => run.runId != null && String(run.runId) === currentId)) return
  const firstRun = recentRuns.value.find(run => run.runId)
  if (firstRun?.runId) await openRunById(firstRun.runId, { scroll: false })
}

async function openBrowserPanel() {
  browserPanelOpen.value = true
  await nextTick()
  browserPanelRef.value?.focus({ preventScroll: true })
}

function closeBrowserPanel() {
  browserPanelOpen.value = false
}

function openRunDetail() {
  if (!currentRun.value?.runId) return
  router.push({
    name: 'DouyinLeadRunDetail',
    params: { runId: currentRun.value.runId },
    query: currentRun.value.taskId ? { taskId: currentRun.value.taskId } : undefined,
  })
}

async function loadRecentRuns() {
  if (recentLoading.value) return
  recentLoading.value = true
  try {
    const response = await leadAcquisitionApi.listDouyinRuns(20)
    recentRuns.value = unwrapApiData<DouyinLeadRunListItem[]>(response, [])
  } catch (error) {
    recentRuns.value = recentRuns.value ?? []
  } finally {
    recentLoading.value = false
    if (activeView.value === 'history') void ensureHistoryRunSelected()
  }
}

async function openRunFromHistory(item: DouyinLeadRunListItem) {
  if (!item.runId) return
  await openRunById(item.runId, { scroll: false })
}

async function openRunById(runId: string | number | null, options: OpenRunOptions = {}) {
  if (!runId) return
  const shouldMarkHistoryLoading = activeView.value === 'history'
  if (shouldMarkHistoryLoading) historyRunLoading.value = true
  try {
    const response = await leadAcquisitionApi.getDouyinRun(runId)
    const run = unwrapApiData<DouyinLeadAcquisitionRunResponse | null>(response, null)
    if (!run) throw new Error('未找到任务详情')
    currentRun.value = normalizeRun(run)
    if (activeView.value === 'history') {
      await loadLeadPool()
    }
    if (options.scroll !== false) {
      await nextTick()
      const selector = activeView.value === 'history' ? '.history-final-summary' : '.result-panel'
      document.querySelector(selector)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    mcToast.error(message)
  } finally {
    if (shouldMarkHistoryLoading) historyRunLoading.value = false
  }
}

async function loadLeadPool() {
  if (leadPoolLoading.value) return
  leadPoolLoading.value = true
  try {
    const response = await leadAcquisitionApi.listDouyinLeads({
      limit: 50,
      status: leadPoolStatus.value,
      keyword: leadPoolKeyword.value.trim() || undefined,
      taskId: selectedLeadPoolTaskId.value ?? undefined,
    })
    leadPool.value = unwrapApiData<DouyinLeadPoolItem[]>(response, [])
  } catch (error) {
    leadPool.value = leadPool.value ?? []
  } finally {
    leadPoolLoading.value = false
  }
}

async function loadLeadStats(options: { force?: boolean } = {}) {
  if (statsLoading.value) {
    if (options.force) statsReloadQueued.value = true
    return
  }
  statsLoading.value = true
  statsError.value = ''
  try {
    const response = await leadAcquisitionApi.getDouyinStats({
      limit: 50,
      keyword: statsKeyword.value.trim() || undefined,
    })
    leadStats.value = unwrapApiData<DouyinLeadStatsResponse>(response, emptyStats())
  } catch (error) {
    statsError.value = '统计读取失败，点击刷新重试'
    leadStats.value = leadStats.value ?? emptyStats()
  } finally {
    statsLoading.value = false
    if (statsReloadQueued.value) {
      statsReloadQueued.value = false
      loadLeadStats()
    }
  }
}

function clearStatsKeyword() {
  statsKeyword.value = ''
  loadLeadStats({ force: true })
}

function emptyStats(): DouyinLeadStatsResponse {
  return {
    taskCount: 0,
    runningTasks: 0,
    succeededTasks: 0,
    failedTasks: 0,
    requestedVideos: 0,
    processedVideos: 0,
    succeededVideos: 0,
    failedVideos: 0,
    commentsCollected: 0,
    matchedComments: 0,
    engagementsCreated: 0,
    sentMessages: 0,
    matchRate: 0,
    engagementRate: 0,
    sendSuccessRate: 0,
    failureReasons: [],
  }
}

function sortLabel(sort?: string | null): string {
  if (sort === 'comprehensive') return '综合排序'
  if (sort === 'most_liked') return '最多点赞'
  if (sort === 'latest') return '最新发布'
  return sort || '默认排序'
}

function statusLabel(status?: string | null): string {
  const normalized = String(status || '').toLowerCase()
  if (normalized === 'running') return '执行中'
  if (normalized === 'created') return '已创建'
  if (normalized === 'succeeded' || normalized === 'success' || normalized === 'completed') return '成功'
  if (normalized === 'failed') return '失败'
  if (normalized === 'aborted' || normalized === 'cancelled' || normalized === 'canceled') return '已停止'
  if (normalized === 'pending') return '待触达'
  if (normalized === '待触达') return '待触达'
  return status || '-'
}

function reasonLabel(value?: string | null): string {
  const normalized = String(value || '').trim()
  if (!normalized) return ''
  const labels: Record<string, string> = {
    ALL_VIDEOS_FAILED: '所有视频处理失败',
    DM_BUTTON_NOT_FOUND: '未找到私信入口',
    DM_PAGE_NOT_CONFIRMED: '未确认进入私信页',
    PROFILE_TAB_NOT_CONTROLLED: '主页标签页未受浏览器扩展控制',
    DM_TAB_NOT_CONTROLLED: '私信标签页未受浏览器扩展控制',
    DEADLINE_EXCEEDED: '执行超时',
    PROFILE_OPEN_FAILED: '用户主页打开失败',
    VIDEO_RESULT_NOT_FOUND: '未找到视频结果',
    DRAFT_NOT_OBSERVED: '未检测到私信草稿',
    ENGAGEMENT_FAILED: '触达执行失败',
    COMMENT_COLLECTION_INCOMPLETE: '评论采集未完整',
    COMMENT_PANEL_LOST_DURING_SCROLL: '滚动时评论区丢失',
    END_OF_LIST_DECLARED_MISMATCH: '评论数量与页面声明不一致',
    RUN_FAILED: '任务执行失败',
    RUN_CANCELLED: '任务已取消',
    UNKNOWN_FAILURE: '未知失败',
    VIDEO_FAILED: '视频处理失败',
  }
  return labels[normalized] ?? normalized
}

function engagementTone(status?: string | null): string {
  const normalized = String(status || '').toLowerCase()
  if (normalized === 'succeeded' || normalized === 'success' || normalized === 'completed' || normalized === 'sent') {
    return 'status-succeeded'
  }
  if (normalized === 'failed') return 'status-danger'
  return 'status-muted'
}

function formatDateTime(value?: string | null): string {
  if (!value) return '-'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value.replace('T', ' ').slice(0, 16)
  return date.toLocaleString()
}

function formatPercent(value?: number | null): string {
  const safe = Number.isFinite(Number(value)) ? Number(value) : 0
  return `${(Math.max(0, Math.min(1, safe)) * 100).toFixed(1)}%`
}

function openChatStarter() {
  router.push({
    path: '/chat',
    query: {
      action: 'newChat',
      prompt: buildChatPrompt(),
    },
  })
}

function buildChatPrompt(): string {
  return [
    '我要执行抖音获客。',
    '请先向我确认这些参数：关键词、排序方式、视频数量、关键词匹配规则、语义匹配规则、私信模板、是否关注、是否发送私信。',
    '确认后执行抖音获客，并在结束时汇总每个视频的评论声明数、实际采集数、匹配数，以及每个 engagement 的状态。',
  ].join('\n')
}
</script>

<style scoped>
.lead-shell {
  overflow: auto;
}

.lead-frame {
  min-height: 100%;
}

.lead-page {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 22px;
}

.lead-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 18px;
  padding: 4px 0 10px;
  border-bottom: 1px solid var(--mc-border);
}

.lead-header > div:first-child {
  min-width: 0;
}

.lead-kicker {
  display: inline-flex;
  margin-bottom: 8px;
  color: var(--mc-text-secondary);
  font-size: 13px;
  font-weight: 600;
}

.lead-header h1 {
  margin: 0;
  color: var(--mc-text-primary);
  font-size: 28px;
  line-height: 1.2;
}

.lead-header p {
  max-width: 680px;
  margin: 8px 0 0;
  color: var(--mc-text-secondary);
  font-size: 14px;
}

.lead-header__actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  flex: 0 0 auto;
  flex-wrap: nowrap;
  padding-top: 22px;
}

.form-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.template-item,
.ghost-button,
.primary-button,
.text-button {
  border: 1px solid var(--mc-border);
  background: var(--mc-bg-container);
  color: var(--mc-text-primary);
  cursor: pointer;
  transition: border-color .18s ease, background .18s ease, color .18s ease, transform .18s ease;
}

.view-switch-button {
  min-width: 108px;
}

.view-switch-button[aria-pressed="true"] {
  border-color: color-mix(in srgb, var(--mc-primary) 48%, var(--mc-border));
  background: color-mix(in srgb, var(--mc-primary) 9%, var(--mc-bg-container));
  color: var(--mc-primary);
}

.template-item strong {
  display: block;
  font-size: 14px;
}

.template-item span {
  display: block;
  margin-top: 4px;
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.lead-workbench {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(280px, 320px);
  gap: 16px;
  align-items: start;
}

.browser-pairing-scrim {
  position: fixed;
  inset: 0;
  z-index: 28;
  background: rgba(15, 23, 42, .32);
}

.browser-pairing-popover {
  position: fixed;
  top: 136px;
  right: 32px;
  z-index: 29;
  display: flex;
  width: 560px;
  max-width: calc(100vw - 64px);
  max-height: calc(100vh - 160px);
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg);
  box-shadow: 0 18px 48px rgba(15, 23, 42, .18);
  outline: none;
}

.browser-pairing-popover:focus-visible {
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--mc-primary) 24%, transparent), 0 18px 48px rgba(15, 23, 42, .18);
}

.browser-pairing-popover__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex: 0 0 auto;
  padding: 16px 18px;
  background: var(--mc-bg);
}

.browser-pairing-popover__head h2 {
  margin: 0;
  color: var(--mc-text-primary);
  font-size: 16px;
}

.browser-pairing-popover__head p {
  margin: 5px 0 0;
  color: var(--mc-text-secondary);
  font-size: 13px;
}

.browser-pairing-body {
  min-height: 0;
  overflow: auto;
  padding: 14px 18px 18px;
  border-top: 1px solid var(--mc-border);
  background: var(--mc-bg);
}

.browser-pairing-body :deep(.browser-pairing-section.is-embedded .section-header),
.browser-pairing-body :deep(.browser-pairing-section.is-embedded .section-footer-note) {
  display: none;
}

.browser-pairing-body :deep(.browser-pairing-section.is-compact .settings-card) {
  background: var(--mc-surface-strong, var(--mc-bg-elevated));
  box-shadow: none;
}

.browser-panel-enter-active,
.browser-panel-leave-active,
.browser-scrim-enter-active,
.browser-scrim-leave-active {
  transition: opacity .16s ease, transform .16s ease;
}

.browser-panel-enter-from,
.browser-panel-leave-to,
.browser-scrim-enter-from,
.browser-scrim-leave-to {
  opacity: 0;
}

.browser-panel-enter-from,
.browser-panel-leave-to {
  transform: translateY(-4px);
}

.launch-panel,
.assist-panel,
.result-panel,
.stats-panel,
.history-panel,
.lead-pool-panel {
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg-container);
}

.launch-panel,
.result-panel {
  padding: 18px;
}

.result-panel {
  border: 0;
  background: transparent;
  padding: 0;
}

.assist-panel {
  display: flex;
  flex-direction: column;
}

.assist-section {
  padding: 16px;
  border-bottom: 1px solid var(--mc-border);
}

.assist-section:last-child {
  border-bottom: 0;
}

.assist-section__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.assist-section__head button {
  min-height: 34px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  cursor: pointer;
  padding: 0 10px;
  font-size: 12px;
  font-weight: 700;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.panel-head.compact {
  align-items: center;
  margin-bottom: 12px;
}

.panel-head h2,
.assist-section h2 {
  margin: 0;
  color: var(--mc-text-primary);
  font-size: 16px;
}

.panel-head p {
  margin: 5px 0 0;
  color: var(--mc-text-secondary);
  font-size: 13px;
}

.field-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 7px;
  color: var(--mc-text-secondary);
  font-size: 13px;
  font-weight: 600;
}

.field--wide {
  grid-column: 1 / -1;
}

.field input,
.field select,
.field textarea {
  width: 100%;
  min-height: 42px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  font: inherit;
  font-weight: 400;
  padding: 9px 11px;
  outline: none;
}

.field textarea {
  resize: vertical;
  line-height: 1.5;
}

.field input:focus,
.field select:focus,
.field textarea:focus {
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--mc-primary) 14%, transparent);
}

.field-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.field-hint {
  color: var(--mc-text-tertiary);
  font-size: 12px;
  font-weight: 500;
}

.match-target-field {
  gap: 12px;
}

.match-target-switch {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 12px;
  border: 1px solid color-mix(in srgb, var(--mc-primary) 22%, var(--mc-border));
  border-radius: 8px;
  background: color-mix(in srgb, var(--mc-primary) 5%, var(--mc-bg));
}

.match-target-switch span {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 3px;
}

.match-target-switch strong {
  color: var(--mc-text-primary);
  font-size: 13px;
}

.match-target-switch small {
  color: var(--mc-text-tertiary);
  font-size: 12px;
  line-height: 1.45;
}

.match-target-workspace {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.match-preset-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
}

.match-preset-button {
  min-height: 38px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg-container);
  color: var(--mc-text-secondary);
  font: inherit;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  padding: 0 8px;
  transition: border-color .18s ease, background .18s ease, color .18s ease;
}

.match-preset-button.active {
  border-color: color-mix(in srgb, var(--mc-primary) 56%, var(--mc-border));
  background: color-mix(in srgb, var(--mc-primary) 10%, var(--mc-bg-container));
  color: var(--mc-primary);
}

.match-preset-button:not(:disabled):hover {
  border-color: var(--mc-primary);
  color: var(--mc-primary);
}

.compact-field {
  gap: 7px;
}

.intent-chip-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.intent-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 30px;
  border: 1px solid var(--mc-border);
  border-radius: 999px;
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  font-size: 12px;
  font-weight: 650;
  padding: 0 8px 0 11px;
}

.intent-chip button {
  display: inline-grid;
  width: 18px;
  height: 18px;
  place-items: center;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--mc-text-tertiary);
  cursor: pointer;
}

.intent-chip button:hover {
  background: var(--mc-bg-muted);
  color: var(--mc-danger);
}

.intent-example-add {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 76px;
  gap: 8px;
}

.intent-example-add button {
  min-height: 38px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg);
  color: var(--mc-text-secondary);
  font-size: 12px;
  font-weight: 650;
  cursor: pointer;
}

.intent-example-add button:not(:disabled):hover {
  border-color: color-mix(in srgb, var(--mc-primary) 42%, var(--mc-border));
  color: var(--mc-primary);
}

.match-rule-list {
  display: flex;
  max-height: 184px;
  flex-direction: column;
  gap: 8px;
  overflow: auto;
  padding-right: 3px;
}

.match-rule-row {
  display: grid;
  grid-template-columns: 136px minmax(0, 1fr) 64px;
  gap: 10px;
  align-items: center;
  padding: 8px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg);
}

.match-rule-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.match-rule-actions button,
.icon-text-button {
  min-height: 38px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg);
  color: var(--mc-text-secondary);
  font-size: 12px;
  font-weight: 650;
  padding: 0 10px;
  cursor: pointer;
}

.match-rule-actions button:hover,
.icon-text-button:hover {
  border-color: color-mix(in srgb, var(--mc-primary) 42%, var(--mc-border));
  color: var(--mc-primary);
}

.template-name-field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 12px;
  color: var(--mc-text-secondary);
  font-size: 12px;
  font-weight: 650;
}

.template-name-field input {
  min-height: 40px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  padding: 7px 9px;
  outline: none;
}

.template-name-field input:focus {
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--mc-primary) 14%, transparent);
}

.switch-row {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin: 16px 0;
}

.switch-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  min-height: 36px;
  color: var(--mc-text-primary);
  font-size: 13px;
}

.switch-item input {
  width: 18px;
  height: 18px;
}

.switch-item.disabled {
  color: var(--mc-text-tertiary);
}

.switch-hint-icon {
  color: var(--mc-text-tertiary);
  font-size: 15px;
  cursor: help;
  transition: color 0.15s ease;
}

.switch-hint-icon:hover {
  color: var(--mc-text-primary);
}

.primary-button,
.ghost-button,
.text-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 40px;
  padding: 0 15px;
  border-radius: 6px;
  font-weight: 650;
}

.primary-button {
  border-color: var(--mc-primary);
  background: var(--mc-primary);
  color: white;
}

.ghost-button:hover,
.template-item:hover {
  border-color: var(--mc-primary);
}

.ghost-button:active,
.primary-button:active,
.text-button:active,
.template-item:active,
.history-item:active {
  transform: translateY(1px);
}

.ghost-button:focus-visible,
.primary-button:focus-visible,
.text-button:focus-visible,
.template-item:focus-visible,
.template-delete:focus-visible,
.icon-text-button:focus-visible,
.inline-link:focus-visible,
.history-item:focus-visible,
.match-rule-actions button:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--mc-primary) 20%, transparent);
}

.text-button {
  border-color: transparent;
  background: transparent;
  color: var(--mc-text-secondary);
}

button:disabled {
  cursor: not-allowed;
  opacity: .58;
}

.template-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-top: 12px;
  max-height: 420px;
  overflow: auto;
  padding-right: 3px;
}

.template-item {
  min-height: 68px;
  padding: 12px;
  border-radius: 8px;
  text-align: left;
}

.template-item--saved {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 8px;
  align-items: center;
}

.template-item--saved > button:first-child {
  min-width: 0;
  border: 0;
  background: transparent;
  color: inherit;
  cursor: pointer;
  padding: 0;
  text-align: left;
}

.template-delete {
  min-height: 34px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--mc-danger, #dc2626);
  cursor: pointer;
  padding: 0 6px;
  font-size: 12px;
  font-weight: 700;
}

.template-delete:hover {
  background: color-mix(in srgb, var(--mc-danger, #dc2626) 10%, transparent);
}

.error-block {
  margin-bottom: 12px;
  padding: 12px;
  border: 1px solid color-mix(in srgb, var(--mc-danger) 40%, var(--mc-border));
  border-radius: 8px;
  color: var(--mc-danger);
  background: color-mix(in srgb, var(--mc-danger) 8%, transparent);
}

.stats-panel {
  padding: 16px;
}

.stats-toolbar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  margin-bottom: 12px;
}

.stats-toolbar label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--mc-text-secondary);
  font-size: 12px;
  font-weight: 650;
}

.stats-toolbar input {
  min-height: 40px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  padding: 7px 9px;
  outline: none;
}

.stats-toolbar input:focus {
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--mc-primary) 14%, transparent);
}

.stats-toolbar .text-button {
  align-self: end;
  min-height: 40px;
}

.stats-error {
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid color-mix(in srgb, var(--mc-danger, #dc2626) 32%, var(--mc-border));
  border-radius: 8px;
  background: color-mix(in srgb, var(--mc-danger, #dc2626) 7%, transparent);
  color: var(--mc-danger, #dc2626);
  font-size: 13px;
}

.stats-card-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.metric-card {
  position: relative;
  min-width: 0;
  padding: 12px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg);
}

.metric-card::before,
.rate-item::before {
  content: "";
  display: block;
  width: 32px;
  height: 3px;
  margin-bottom: 10px;
  border-radius: 999px;
  background: var(--mc-primary);
}

.metric-card:nth-child(3)::before,
.rate-item:nth-child(1)::before {
  background: #16a34a;
}

.metric-card:nth-child(4)::before,
.rate-item:nth-child(2)::before {
  background: #7c3aed;
}

.metric-card span,
.rate-item span {
  display: block;
  color: var(--mc-text-secondary);
  font-size: 12px;
  font-weight: 650;
}

.metric-card strong,
.rate-item strong {
  display: block;
  margin-top: 6px;
  color: var(--mc-text-primary);
  font-size: 22px;
  line-height: 1.2;
}

.metric-card small,
.rate-item small {
  display: block;
  margin-top: 6px;
  color: var(--mc-text-secondary);
  font-size: 12px;
  line-height: 1.45;
}

.stats-insights {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(280px, .72fr);
  gap: 12px;
  margin-top: 12px;
}

.rate-strip {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.rate-item,
.failure-card {
  min-width: 0;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg);
  padding: 12px;
}

.failure-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}

.failure-card__head h3 {
  margin: 0;
  color: var(--mc-text-primary);
  font-size: 14px;
}

.failure-card__head span {
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.failure-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 180px;
  overflow: auto;
  padding-right: 4px;
}

.failure-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 6px 10px;
  align-items: center;
  color: var(--mc-text-primary);
  font-size: 13px;
}

.failure-row span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.failure-row strong {
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.failure-bar {
  grid-column: 1 / -1;
  height: 6px;
  overflow: hidden;
  border-radius: 999px;
  background: var(--mc-bg-muted);
}

.failure-bar i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: color-mix(in srgb, var(--mc-danger, #dc2626) 65%, var(--mc-primary));
}

.empty-inline {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 86px;
  border: 1px dashed var(--mc-border);
  border-radius: 8px;
  color: var(--mc-text-secondary);
  font-size: 13px;
}

.growth-workspace {
  display: grid;
  grid-template-columns: minmax(0, .9fr) minmax(0, 1.1fr);
  gap: 16px;
}

.history-panel,
.lead-pool-panel {
  min-width: 0;
  padding: 16px;
}

.history-list,
.lead-pool-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 360px;
  overflow: auto;
  padding-right: 4px;
}

.lead-pool-filters {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 150px;
  gap: 10px;
  margin-bottom: 12px;
}

.lead-pool-filters label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: var(--mc-text-secondary);
  font-size: 12px;
  font-weight: 650;
}

.lead-pool-filters input,
.lead-pool-filters select {
  min-height: 40px;
  border: 1px solid var(--mc-border);
  border-radius: 6px;
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  padding: 7px 9px;
  outline: none;
}

.lead-pool-filters input:focus,
.lead-pool-filters select:focus {
  border-color: var(--mc-primary);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--mc-primary) 14%, transparent);
}

.history-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 12px;
  width: 100%;
  min-height: 72px;
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg);
  color: var(--mc-text-primary);
  cursor: pointer;
  padding: 12px;
  text-align: left;
}

.history-item:hover,
.history-item.is-active {
  border-color: var(--mc-primary);
  background: color-mix(in srgb, var(--mc-primary) 7%, var(--mc-bg));
}

.history-item.is-active {
  box-shadow: inset 3px 0 0 var(--mc-primary);
}

.history-item__main,
.history-item__stats {
  min-width: 0;
}

.history-item__main strong,
.history-item__main small,
.history-item__stats span,
.history-item__stats small {
  display: block;
}

.history-item__main strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
}

.history-item__main small,
.history-item__stats small {
  margin-top: 5px;
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.history-item__stats {
  text-align: right;
}

.history-item__stats span {
  font-size: 13px;
  font-weight: 700;
}

.lead-card {
  border: 1px solid var(--mc-border);
  border-radius: 8px;
  background: var(--mc-bg);
  padding: 12px;
}

.lead-card__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.lead-card__top a,
.lead-card__top strong {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--mc-primary);
  font-size: 14px;
  font-weight: 700;
  text-decoration: none;
}

.lead-card p {
  margin: 8px 0 0;
  color: var(--mc-text-primary);
  font-size: 13px;
  line-height: 1.55;
  word-break: break-word;
}

.lead-card__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 9px;
  color: var(--mc-text-secondary);
  font-size: 12px;
}

.inline-link {
  min-height: 32px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--mc-primary);
  cursor: pointer;
  padding: 0 4px;
  font-size: 12px;
  font-weight: 700;
}

.inline-link:hover {
  background: color-mix(in srgb, var(--mc-primary) 9%, transparent);
}

.status-pill {
  flex-shrink: 0;
  border-radius: 999px;
  background: var(--mc-bg-muted);
  color: var(--mc-text-secondary);
  padding: 4px 9px;
  font-size: 12px;
  font-weight: 700;
}

.status-succeeded {
  background: rgba(22, 163, 74, .12);
  color: #15803d;
}

.status-danger {
  background: rgba(220, 38, 38, .1);
  color: #dc2626;
}

.status-muted {
  background: var(--mc-bg-muted);
  color: var(--mc-text-secondary);
}

.history-final-summary {
  min-width: 0;
  scroll-margin-top: 18px;
}

.history-final-summary > .empty-card {
  min-height: 180px;
  background: var(--mc-bg-container);
}

.empty-card {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 120px;
  border: 1px dashed var(--mc-border);
  border-radius: 8px;
  color: var(--mc-text-secondary);
  font-size: 13px;
  text-align: center;
  padding: 16px;
}

.mini-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, .55);
  border-top-color: white;
  border-radius: 999px;
  animation: spin .8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@media (max-width: 1080px) {
  .lead-workbench,
  .stats-insights,
  .growth-workspace {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .lead-page {
    padding: 14px;
  }

  .lead-header {
    align-items: stretch;
    flex-direction: column;
  }

  .lead-header__actions,
  .form-actions {
    width: 100%;
  }

  .lead-header__actions {
    justify-content: flex-start;
    flex-wrap: wrap;
    padding-top: 0;
  }

  .ghost-button,
  .primary-button {
    flex: 1;
  }

  .field-grid,
  .assist-panel,
  .stats-card-grid,
  .stats-toolbar,
  .rate-strip,
  .lead-pool-filters {
    grid-template-columns: 1fr;
  }

  .assist-section {
    border-right: 0;
    border-bottom: 1px solid var(--mc-border);
  }

  .browser-pairing-popover {
    top: 12px;
    right: 12px;
    left: 12px;
    width: auto;
    max-height: calc(100vh - 24px);
  }

  .match-rule-row {
    grid-template-columns: 1fr;
  }

  .field-head {
    align-items: flex-start;
    flex-direction: column;
  }

  .history-item {
    align-items: stretch;
    grid-template-columns: 1fr;
  }

  .history-item__stats {
    text-align: left;
  }
}

@media (prefers-reduced-motion: reduce) {
  .template-item,
  .ghost-button,
  .primary-button,
  .text-button,
  .browser-panel-enter-active,
  .browser-panel-leave-active,
  .browser-scrim-enter-active,
  .browser-scrim-leave-active {
    transition: none;
  }
}
</style>
