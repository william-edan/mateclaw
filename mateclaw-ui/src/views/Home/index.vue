<template>
  <div class="mc-page-shell home-shell">
    <div class="mc-page-frame home-frame">
      <div class="mc-page-inner home-inner">
        <section class="home-banner">
          <button
            class="home-banner__click-layer"
            type="button"
            :aria-label="t('home.banner.openFeature')"
            @click="onBannerClick"
          ></button>
          <div class="home-banner__copy">
            <div class="home-banner__eyebrow">{{ activeBanner.eyebrow || t('home.banner.eyebrow') }}</div>
            <h1>{{ activeBanner.title }}</h1>
            <p>{{ activeBanner.subtitle }}</p>
          </div>
          <div class="home-banner__visual" aria-hidden="true">
            <span class="home-banner__node home-banner__node--primary"></span>
            <span class="home-banner__node home-banner__node--accent"></span>
            <span class="home-banner__line"></span>
          </div>
          <button
            v-if="activeBanner.demoVideoUrl"
            class="home-banner__play"
            type="button"
            @click="openDemoVideo"
          >
            <el-icon><VideoPlay /></el-icon>
            {{ t('home.banner.watchDemo') }}
          </button>
        </section>

        <section class="home-section">
          <div class="home-section__head">
            <div>
              <h2>{{ t('home.market.title') }}</h2>
              <p>{{ t('home.market.subtitle') }}</p>
            </div>
          </div>
          <div v-if="employees.length" class="employee-grid">
            <button
              v-for="agent in employees"
              :key="agent.id"
              class="employee-card"
              type="button"
              @click="startChat(agent)"
            >
              <span class="employee-card__top">
                <span class="employee-card__icon" :style="{ color: agentIconColor(agent.icon) }">
                  <SkillIcon :value="agent.icon" :size="30" fallback="🤖" :title="agent.name" />
                </span>
                <span class="employee-card__name">{{ agent.name }}</span>
              </span>
              <span class="employee-card__role">{{ employeeRole(agent) }}</span>
              <span class="employee-card__goal">{{ employeeGoal(agent) }}</span>
              <span class="employee-card__desc">{{ employeeDesc(agent) }}</span>
              <span class="employee-card__cta">{{ t('home.market.startChat') }}</span>
            </button>
          </div>
          <div v-else class="home-empty">
            {{ loadingEmployees ? t('common.loading') : t('home.market.empty') }}
          </div>
        </section>

        <section class="home-section">
          <div class="home-section__head">
            <div>
              <h2>{{ t('home.runs.title') }}</h2>
              <p>{{ t('home.runs.subtitle') }}</p>
            </div>
          </div>
          <div class="home-runs">
            <table v-if="recentRuns.length">
              <thead>
                <tr>
                  <th>{{ t('home.runs.columns.time') }}</th>
                  <th>{{ t('home.runs.columns.job') }}</th>
                  <th>{{ t('home.runs.columns.status') }}</th>
                  <th>{{ t('home.runs.columns.trigger') }}</th>
                  <th>{{ t('home.runs.columns.duration') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="run in recentRuns" :key="run.id || `${run.cronJobId}-${run.startedAt}`">
                  <td class="home-runs__time">{{ formatTime(run.startedAt) }}</td>
                  <td>{{ run.jobName || `#${run.cronJobId}` }}</td>
                  <td><span class="run-status" :class="`run-status--${run.status}`">{{ run.status || '-' }}</span></td>
                  <td>{{ run.triggerType || '-' }}</td>
                  <td>{{ calcDuration(run) }}</td>
                </tr>
              </tbody>
            </table>
            <div v-else class="home-empty">
              {{ loadingRuns ? t('common.loading') : t('home.runs.empty') }}
            </div>
          </div>
        </section>
      </div>
    </div>

    <Transition name="fade">
      <div v-if="showVideoModal" class="home-modal-backdrop" @click.self="showVideoModal = false">
        <section class="home-modal home-modal--video" role="dialog" aria-modal="true" :aria-label="t('home.banner.watchDemo')">
          <button class="home-modal__close" type="button" @click="showVideoModal = false">{{ t('common.close') }}</button>
          <video v-if="activeBanner.demoVideoUrl" :src="activeBanner.demoVideoUrl" controls autoplay></video>
        </section>
      </div>
    </Transition>

    <Transition name="fade">
      <div v-if="showEnvironmentPrompt" class="home-modal-backdrop" @click.self="showEnvironmentPrompt = false">
        <section class="home-modal home-modal--prompt" role="dialog" aria-modal="true">
          <button class="home-modal__close" type="button" @click="showEnvironmentPrompt = false">{{ t('common.close') }}</button>
          <h2>{{ isClientEnvironment ? t('home.prompt.clientTitle') : t('home.prompt.webTitle') }}</h2>
          <p>{{ isClientEnvironment ? t('home.prompt.clientDesc') : t('home.prompt.webDesc') }}</p>
          <a
            v-if="isClientEnvironment && activeBanner.browserPluginDownloadUrl"
            class="home-modal__action"
            :href="activeBanner.browserPluginDownloadUrl"
            target="_blank"
            rel="noreferrer"
          >{{ t('home.prompt.downloadPlugin') }}</a>
          <a
            v-else-if="!isClientEnvironment && activeBanner.clientDownloadUrl"
            class="home-modal__action"
            :href="activeBanner.clientDownloadUrl"
            target="_blank"
            rel="noreferrer"
          >{{ t('home.prompt.downloadClient') }}</a>
        </section>
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { VideoPlay } from '@element-plus/icons-vue'
import { agentApi, dashboardApi } from '@/api'
import SkillIcon from '@/components/common/SkillIcon.vue'
import type { Agent } from '@/types/index'
import { agentIconColor } from '@/utils/agentIconColor'

interface HomeBanner {
  id: string
  title: string
  subtitle: string
  eyebrow?: string
  demoVideoUrl?: string
  clientDownloadUrl?: string
  browserPluginDownloadUrl?: string
}

const { t } = useI18n()
const router = useRouter()

const banners = ref<HomeBanner[]>([
  {
    id: 'demo',
    eyebrow: 'MateClaw',
    title: t('home.banner.title'),
    subtitle: t('home.banner.subtitle'),
    demoVideoUrl: '',
    clientDownloadUrl: '',
    browserPluginDownloadUrl: '',
  },
])

const activeBannerIndex = ref(0)
const employees = ref<Agent[]>([])
const recentRuns = ref<any[]>([])
const loadingEmployees = ref(false)
const loadingRuns = ref(false)
const showVideoModal = ref(false)
const showEnvironmentPrompt = ref(false)

const activeBanner = computed(() => banners.value[activeBannerIndex.value] || banners.value[0])

const isClientEnvironment = computed(() => {
  const w = window as any
  const ua = navigator.userAgent || ''
  return Boolean(w.electronAPI || w.__TAURI__ || /Electron/i.test(ua))
})

onMounted(async () => {
  await Promise.all([loadEmployees(), loadRecentRuns()])
})

async function loadEmployees() {
  loadingEmployees.value = true
  try {
    const res: any = await agentApi.list({ enabled: true })
    employees.value = (res.data || res || []).slice(0, 8)
  } catch {
    employees.value = []
  } finally {
    loadingEmployees.value = false
  }
}

async function loadRecentRuns() {
  loadingRuns.value = true
  try {
    const res: any = await dashboardApi.recentRuns(8)
    recentRuns.value = res.data || res || []
  } catch {
    recentRuns.value = []
  } finally {
    loadingRuns.value = false
  }
}

function openDemoVideo(event: MouseEvent) {
  event.stopPropagation()
  if (!activeBanner.value.demoVideoUrl) return
  showVideoModal.value = true
}

function onBannerClick() {
  showEnvironmentPrompt.value = true
}

function startChat(agent: Agent) {
  router.push({
    path: '/chat',
    query: {
      agentId: String(agent.id),
      action: 'newChat',
    },
  })
}

function shortText(value: string | undefined, limit: number, fallback = '') {
  const text = (value || fallback || '').trim()
  if (text.length <= limit) return text
  return `${text.slice(0, limit)}...`
}

function employeeRole(agent: Agent) {
  const firstTag = (agent.tags || '').split(',').map((tag) => tag.trim()).filter(Boolean)[0]
  return shortText(firstTag, 10, t('home.market.defaultRole'))
}

function employeeGoal(agent: Agent) {
  return shortText(agent.systemPrompt || agent.description, 14, t('home.market.defaultGoal'))
}

function employeeDesc(agent: Agent) {
  return shortText(agent.description || agent.systemPrompt, 30, t('home.market.defaultDesc'))
}

function formatTime(value: string | undefined) {
  if (!value) return '-'
  return new Date(value).toLocaleString()
}

function calcDuration(run: any) {
  if (!run.startedAt || !run.finishedAt) return '-'
  const ms = new Date(run.finishedAt).getTime() - new Date(run.startedAt).getTime()
  if (!Number.isFinite(ms) || ms < 0) return '-'
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}
</script>

<style scoped>
.home-shell {
  --home-page-gutter: 28px;
  --home-surface-bg: rgba(255, 255, 255, 0.82);
  --home-surface-strong-bg: rgba(255, 255, 255, 0.90);
  --home-surface-border: rgba(155, 181, 255, 0.32);
  --home-surface-border-strong: rgba(155, 181, 255, 0.34);
  --home-surface-shadow: 0 14px 34px rgba(45, 83, 180, 0.10);
  --home-row-border: rgba(155, 181, 255, 0.22);
  --home-table-head-bg: rgba(231, 240, 255, 0.82);
  --home-row-hover-bg: rgba(71, 108, 255, 0.06);
  background: transparent;
  height: 100%;
  min-height: 0;
  overflow: hidden;
}

:global(html.dark) .home-shell {
  --home-surface-bg: rgba(12, 27, 70, 0.82);
  --home-surface-strong-bg: rgba(10, 24, 62, 0.90);
  --home-surface-border: rgba(157, 181, 255, 0.26);
  --home-surface-border-strong: rgba(157, 181, 255, 0.30);
  --home-surface-shadow: 0 18px 42px rgba(0, 0, 0, 0.28);
  --home-row-border: rgba(157, 181, 255, 0.18);
  --home-table-head-bg: rgba(19, 42, 98, 0.78);
  --home-row-hover-bg: rgba(71, 108, 255, 0.16);
}

.home-frame {
  height: min(calc(100vh - 28px), 100%);
  min-height: 0;
  overflow: hidden;
}

.home-inner {
  display: flex;
  flex-direction: column;
  gap: 22px;
  height: 100%;
  min-height: 0;
  overflow-y: auto;
  padding-inline: var(--home-page-gutter);
}

.home-banner {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 260px;
  align-items: center;
  min-height: 224px;
  width: 100%;
  overflow: hidden;
  padding: 30px 34px;
  border: 1px solid rgba(178, 202, 255, 0.38);
  border-radius: var(--mc-radius-md);
  background:
    radial-gradient(circle at 82% 18%, rgba(255, 255, 255, 0.70), transparent 25%),
    linear-gradient(135deg, rgba(18, 44, 132, 0.88), rgba(78, 121, 255, 0.62) 48%, rgba(224, 245, 255, 0.72)),
    linear-gradient(180deg, var(--mc-panel-top), var(--mc-panel-bottom));
  color: #ffffff;
  box-shadow: 0 24px 58px rgba(38, 70, 165, 0.18);
  text-align: left;
  cursor: pointer;
}

.home-banner::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.16) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.16) 1px, transparent 1px);
  background-size: 72px 72px;
  opacity: 0.34;
  pointer-events: none;
}

.home-banner__click-layer:focus-visible,
.home-banner__play:focus-visible,
.employee-card:focus-visible,
.home-modal__action:focus-visible,
.home-modal__close:focus-visible {
  outline: 2px solid var(--mc-primary);
  outline-offset: 3px;
}

.home-banner__click-layer {
  position: absolute;
  inset: 0;
  z-index: 2;
  border: 0;
  background: transparent;
  cursor: pointer;
}

.home-banner__copy {
  position: relative;
  z-index: 1;
  max-width: 640px;
  pointer-events: none;
}

.home-banner__eyebrow {
  color: rgba(211, 250, 255, 0.92);
  font-size: 12px;
  font-weight: 800;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  margin-bottom: 12px;
}

.home-banner h1 {
  margin: 0;
  color: #ffffff;
  font-size: 34px;
  line-height: 1.18;
  font-weight: 850;
  text-shadow: 0 2px 16px rgba(12, 32, 96, 0.22);
}

.home-banner p {
  max-width: 520px;
  margin: 12px 0 0;
  color: rgba(224, 242, 255, 0.88);
  font-size: 15px;
  line-height: 1.7;
}

.home-banner__visual {
  position: relative;
  height: 150px;
  z-index: 1;
  pointer-events: none;
}

.home-banner__node {
  position: absolute;
  display: block;
  border-radius: 50%;
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.42) inset,
    0 18px 34px rgba(31, 72, 190, 0.28),
    0 0 34px rgba(55, 213, 229, 0.34);
}

.home-banner__node--primary {
  right: 86px;
  top: 24px;
  width: 76px;
  height: 76px;
  background:
    radial-gradient(circle at 30% 28%, rgba(255, 255, 255, 0.88), rgba(194, 208, 255, 0.58) 28%, rgba(71, 108, 255, 0.96) 70%);
}

.home-banner__node--accent {
  right: 20px;
  top: 76px;
  width: 48px;
  height: 48px;
  background:
    radial-gradient(circle at 35% 30%, rgba(255, 255, 255, 0.92), rgba(55, 213, 229, 0.72) 34%, rgba(25, 191, 209, 0.94) 76%);
}

.home-banner__line {
  position: absolute;
  right: 52px;
  top: 60px;
  width: 126px;
  height: 2px;
  transform: rotate(24deg);
  background: linear-gradient(90deg, rgba(255, 255, 255, 0.14), rgba(173, 238, 255, 0.86), rgba(157, 181, 255, 0.70));
  box-shadow: 0 0 18px rgba(55, 213, 229, 0.42);
}

.home-banner__play {
  position: absolute;
  left: 34px;
  bottom: 28px;
  z-index: 3;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border: 0;
  border-radius: var(--mc-radius-sm);
  color: #fff;
  background: linear-gradient(135deg, var(--mc-primary-solid), var(--mc-primary-hover));
  font-size: 14px;
  font-weight: 700;
  box-shadow: 0 14px 28px rgba(29, 68, 190, 0.28);
  cursor: pointer;
}

.home-section {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.home-section__head h2 {
  margin: 0;
  color: var(--mc-text-primary);
  font-size: 19px;
  font-weight: 800;
}

.home-section__head p {
  margin: 4px 0 0;
  color: var(--mc-text-secondary);
  font-size: 13px;
}

.employee-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.employee-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 9px;
  min-height: 180px;
  padding: 17px;
  border: 1px solid var(--home-surface-border);
  border-radius: var(--mc-radius-md);
  background: var(--home-surface-bg);
  box-shadow: var(--home-surface-shadow);
  text-align: left;
  cursor: pointer;
  transition: transform 0.16s ease, border-color 0.16s ease, box-shadow 0.16s ease;
}

.employee-card:hover {
  transform: translateY(-2px);
  border-color: var(--mc-primary-light);
  box-shadow: 0 20px 48px rgba(45, 83, 180, 0.18);
}

.employee-card__top {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.employee-card__icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 42px;
  height: 42px;
  border-radius: var(--mc-radius-sm);
  background:
    linear-gradient(135deg, rgba(71, 108, 255, 0.13), rgba(25, 191, 209, 0.16));
  box-shadow: inset 0 0 0 1px rgba(157, 181, 255, 0.22);
  flex-shrink: 0;
}

.employee-card__name {
  color: var(--mc-text-primary);
  font-size: 16px;
  font-weight: 800;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.employee-card__role {
  align-self: flex-start;
  max-width: 100%;
  padding: 4px 8px;
  border-radius: var(--mc-radius-sm);
  color: var(--mc-accent);
  background: rgba(25, 191, 209, 0.12);
  box-shadow: inset 0 0 0 1px rgba(25, 191, 209, 0.14);
  font-size: 12px;
  font-weight: 700;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.employee-card__goal {
  color: var(--mc-text-primary);
  font-size: 14px;
  font-weight: 700;
  line-height: 1.45;
}

.employee-card__desc {
  color: var(--mc-text-secondary);
  font-size: 13px;
  line-height: 1.55;
}

.employee-card__cta {
  margin-top: auto;
  color: var(--mc-primary);
  font-size: 13px;
  font-weight: 800;
  opacity: 0;
  transform: translateY(4px);
  transition: opacity 0.16s ease, transform 0.16s ease;
}

.employee-card:hover .employee-card__cta,
.employee-card:focus-visible .employee-card__cta {
  opacity: 1;
  transform: translateY(0);
}

.home-runs {
  overflow: hidden;
  border: 1px solid var(--home-surface-border-strong);
  border-radius: var(--mc-radius-md);
  background: var(--home-surface-strong-bg);
  box-shadow: var(--home-surface-shadow);
}

.home-runs table {
  width: 100%;
  border-collapse: collapse;
}

.home-runs th,
.home-runs td {
  padding: 12px 15px;
  border-bottom: 1px solid var(--home-row-border);
  color: var(--mc-text-secondary);
  font-size: 13px;
  text-align: left;
  white-space: nowrap;
}

.home-runs th {
  color: var(--mc-text-tertiary);
  background: var(--home-table-head-bg);
  font-size: 12px;
  font-weight: 800;
}

.home-runs tbody tr {
  transition: background 0.16s ease;
}

.home-runs tbody tr:hover {
  background: var(--home-row-hover-bg);
}

.home-runs tr:last-child td {
  border-bottom: 0;
}

.home-runs__time {
  color: var(--mc-text-primary);
}

.run-status {
  display: inline-flex;
  align-items: center;
  padding: 3px 8px;
  border-radius: var(--mc-radius-sm);
  color: var(--mc-text-secondary);
  background: var(--mc-bg-muted);
  font-size: 12px;
  font-weight: 700;
}

.run-status--SUCCESS,
.run-status--success {
  color: var(--mc-success);
  background: rgba(90, 138, 90, 0.14);
}

.run-status--FAILED,
.run-status--failed {
  color: var(--mc-danger);
  background: var(--mc-danger-bg);
}

.home-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 96px;
  border: 1px dashed var(--mc-border);
  border-radius: var(--mc-radius-md);
  color: var(--mc-text-tertiary);
  background: var(--mc-panel-raised);
  font-size: 14px;
}

.home-modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1200;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(0, 0, 0, 0.42);
}

.home-modal {
  position: relative;
  width: min(620px, 100%);
  padding: 28px;
  border: 1px solid var(--mc-border);
  border-radius: var(--mc-radius-md);
  background: var(--mc-bg-elevated);
  box-shadow: var(--mc-shadow-strong);
}

.home-modal--video {
  width: min(880px, 100%);
}

.home-modal video {
  display: block;
  width: 100%;
  max-height: 70vh;
  border-radius: var(--mc-radius-sm);
  background: #000;
}

.home-modal__close {
  position: absolute;
  right: 14px;
  top: 14px;
  border: 0;
  border-radius: var(--mc-radius-sm);
  padding: 6px 9px;
  color: var(--mc-text-secondary);
  background: var(--mc-bg-muted);
  cursor: pointer;
}

.home-modal--prompt h2 {
  margin: 0 0 10px;
  color: var(--mc-text-primary);
  font-size: 22px;
}

.home-modal--prompt p {
  margin: 0 0 20px;
  color: var(--mc-text-secondary);
  line-height: 1.7;
}

.home-modal__action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 38px;
  padding: 0 15px;
  border-radius: var(--mc-radius-sm);
  color: #fff;
  background: var(--mc-primary);
  font-size: 14px;
  font-weight: 800;
  text-decoration: none;
}

@media (max-width: 1180px) {
  .employee-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .home-shell {
    --home-page-gutter: 18px;
  }
}

@media (max-width: 760px) {
  .home-inner {
    gap: 18px;
  }

  .home-banner {
    grid-template-columns: 1fr;
    min-height: 230px;
    padding: 24px;
  }

  .home-banner h1 {
    font-size: 27px;
  }

  .home-banner__visual {
    display: none;
  }

  .home-banner__play {
    left: 24px;
    bottom: 22px;
  }

  .employee-grid {
    grid-template-columns: 1fr;
  }

  .home-runs {
    overflow-x: auto;
  }

  .home-runs table {
    min-width: 680px;
  }
}

@media (max-width: 480px) {
  .home-shell {
    --home-page-gutter: 12px;
  }
}
</style>
