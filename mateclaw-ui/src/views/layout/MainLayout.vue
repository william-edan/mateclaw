<template>
  <div class="app-layout">
    <div class="app-shell" :aria-hidden="accountStore.expired ? 'true' : undefined">
    <!-- 移动端背景遮罩 -->
    <Transition name="fade">
      <div v-if="isMobile && mobileMenuOpen" class="sidebar-backdrop" @click="mobileMenuOpen = false"></div>
    </Transition>

    <!-- 左侧导航栏 -->
    <aside class="sidebar" :class="{ collapsed: sidebarCollapsed && !isMobile, 'mobile-open': mobileMenuOpen }">
      <!-- Logo -->
      <div class="sidebar-logo">
        <div class="logo-icon">
          <img src="/logo/mateclaw_logo_s.png" alt="化帆AI" class="logo-img" />
        </div>
        <transition name="fade">
          <div v-if="!effectiveCollapsed" class="logo-text">
            <span class="logo-name">化帆<span class="logo-name-highlight">AI</span></span>
            <span
              class="logo-expiry"
              :class="{ 'is-expired': accountStore.expired }"
              :title="accountExpiryText"
            >
              {{ accountExpiryText }}
            </span>
          </div>
        </transition>
        <button
          class="collapse-btn"
          :title="sidebarToggleLabel"
          :aria-label="sidebarToggleLabel"
          @click="toggleSidebar"
        >
          <svg v-if="!effectiveCollapsed" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="15 18 9 12 15 6"/>
          </svg>
          <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="9 18 15 12 9 6"/>
          </svg>
        </button>
      </div>

      <!-- 工作区切换 -->
      <WorkspaceSwitcher :collapsed="effectiveCollapsed" />

      <!-- 导航菜单 -->
      <nav class="sidebar-nav">
        <template v-for="group in navGroups" :key="group.key">
          <div class="nav-group">
            <div v-if="!effectiveCollapsed" class="nav-group-title">{{ group.label }}</div>
            <McTooltip
              v-for="item in group.items"
              :key="item.path || item.action"
              :content="item.tooltip || item.label"
              placement="right"
              :disabled="!effectiveCollapsed"
            >
              <router-link
                v-if="item.path"
                :to="item.path"
                class="nav-item"
                :class="{ active: isNavItemActive(item) }"
                :title="effectiveCollapsed ? '' : (item.tooltip || '')"
                @click="onNavClick"
              >
                <span class="nav-icon" v-html="item.icon"></span>
                <span v-if="!effectiveCollapsed" class="nav-label">{{ item.label }}</span>
                <NavBadge
                  v-if="item.path === '/agents' && isAdminRole"
                  :dot="liveAlertActive"
                  tone="warning"
                  :collapsed="effectiveCollapsed"
                  :title="t('live.attention')"
                />
                <NavBadge
                  v-else-if="item.path === '/security' && isAdminRole"
                  :count="pendingApprovals"
                  tone="urgent"
                  :collapsed="effectiveCollapsed"
                  :title="t('notifications.pendingApprovals', { n: pendingApprovals })"
                />
              </router-link>
              <button
                v-else
                type="button"
                class="nav-item nav-item--button"
                :title="effectiveCollapsed ? '' : (item.tooltip || '')"
                @click="onNavAction(item)"
              >
                <span class="nav-icon" v-html="item.icon"></span>
                <span v-if="!effectiveCollapsed" class="nav-label">{{ item.label }}</span>
              </button>
            </McTooltip>
          </div>
        </template>
      </nav>

      <!-- 底部 -->
      <div class="sidebar-footer">
        <template v-if="!sidebarCollapsed || isMobile">
          <!--
            Status row: two side-by-side status cards. Doctor health on the left
            stays always visible; the auto-approve chip on the right only renders
            when at least one grant is active. When the chip is hidden, flex
            naturally lets the doctor card expand to full width again — so the
            row never wastes vertical space the way a stacked banner did.
          -->
          <div class="footer-status-row">
            <button
              class="health-indicator"
              :class="[healthStatus, { 'is-half': autoApproveSummary && autoApproveSummary.count > 0 }]"
              @click="showDoctor = true"
              :title="t('doctor.title')"
            >
              <span class="health-dot"></span>
              <span class="health-label">{{ t('doctor.title') }}</span>
            </button>
            <button
              v-if="autoApproveSummary && autoApproveSummary.count > 0"
              class="auto-approve-chip"
              @click="goAutoApproveSettings"
              :title="t('approval.grant.title')"
            >
              <span class="auto-approve-chip__dot"></span>
              <el-icon :size="13"><Unlock /></el-icon>
              <span class="auto-approve-chip__label">{{ t('approval.grant.chipShort', { count: autoApproveSummary.count }) }}</span>
            </button>
          </div>

          <div class="sidebar-utility-card">
            <div class="compact-utility-row">
              <span class="compact-utility-title">{{ t('nav.languageLabel') }}</span>
              <div class="language-toggle-row language-toggle-row--compact">
                <button
                  v-for="opt in localeOptions"
                  :key="opt.value"
                  class="language-btn language-btn--compact"
                  :class="{ active: currentLocaleValue === opt.value }"
                  @click="changeLocale(opt.value)"
                >
                  <span class="language-abbr">{{ opt.short }}</span>
                </button>
              </div>
            </div>
          </div>

          <div class="user-info">
            <div class="user-avatar">{{ userInitial }}</div>
            <div class="user-detail">
              <div class="user-name">{{ username }}</div>
              <div class="user-meta">
                <span class="user-role">{{ roleLabel }}</span>
              </div>
            </div>
            <button class="change-password-btn" @click="showChangePassword = true" :title="t('auth.changePassword')">
              <el-icon :size="16"><Lock /></el-icon>
            </button>
            <button class="logout-btn" @click="logout" :title="t('nav.logout')">
              <el-icon :size="16"><SwitchButton /></el-icon>
            </button>
          </div>

          <div class="shortcuts-hint" :title="shortcutsHintText">
            <kbd>Ctrl+K</kbd>
            <span>{{ t('nav.shortcutAgents') }}</span>
            <span class="shortcuts-hint__sep">|</span>
            <kbd>Ctrl+N</kbd>
            <span>{{ t('nav.shortcutNew') }}</span>
          </div>
        </template>

        <template v-else>
          <div class="collapsed-footer-actions">
            <button class="footer-icon-btn" :class="healthStatus" @click="showDoctor = true" :title="t('doctor.title')">
              <span class="health-dot"></span>
            </button>
            <button class="footer-icon-btn" :title="t('nav.logout')" @click="logout">
              <el-icon :size="16"><SwitchButton /></el-icon>
            </button>
          </div>
        </template>
      </div>
    </aside>

    <!-- 主内容区 -->
    <main class="main-content">
      <!-- 移动端顶部栏 -->
      <div v-if="isMobile" class="mobile-topbar">
        <button class="mobile-menu-btn" @click="mobileMenuOpen = true" :title="t('common.expandSidebar')">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="3" y1="6" x2="21" y2="6"/>
            <line x1="3" y1="12" x2="21" y2="12"/>
            <line x1="3" y1="18" x2="21" y2="18"/>
          </svg>
        </button>
        <span class="mobile-topbar-title">化帆<span class="logo-name-highlight">AI</span></span>
      </div>
      <!-- RFC-074 PR-1 fix: include route.path in the key so two different
           keepAlive routes (e.g. /channels and /settings/models) don't collide
           on the same vnode slot. Without this, switching between two keep-alive
           routes leaves both component trees mounted because Vue sees identical
           keys and patches in place. The comment must live OUTSIDE <keep-alive>
           — KeepAlive treats comments as children and rejects "more than one". -->
      <router-view v-slot="{ Component, route }">
        <keep-alive>
          <component :is="Component" :key="`${workspaceRouteKey}:${route.path}`" v-if="route.meta?.keepAlive" />
        </keep-alive>
        <component :is="Component" :key="`${workspaceRouteKey}:${route.path}`" v-if="!route.meta?.keepAlive" />
      </router-view>
    </main>

    <OnboardingWizard v-if="showOnboarding" @close="showOnboarding = false" />
    <DoctorDrawer :visible="showDoctor" @close="showDoctor = false" @status="onHealthStatus" />

    <ChangePasswordDialog v-model:visible="showChangePassword" />
    </div>

    <Transition name="fade">
      <div v-if="accountStore.expired" class="expired-overlay">
        <section
          ref="expiredModalRef"
          class="expired-modal"
          role="alertdialog"
          aria-modal="true"
          tabindex="-1"
          :aria-labelledby="expiredDialogTitleId"
          :aria-describedby="expiredDialogDescId"
          @keydown="onExpiredModalKeydown"
        >
          <div class="expired-modal__copy">
            <p class="expired-modal__eyebrow">{{ t('account.expired') }}</p>
            <h2 :id="expiredDialogTitleId">{{ t('account.expiredTitle') }}</h2>
            <p :id="expiredDialogDescId">{{ t('account.expiredDesc') }}</p>
            <p v-if="accountStore.expiresAt" class="expired-modal__time">{{ expiredAtText }}</p>
          </div>
          <div class="expired-modal__qr">
            <img src="/business-qr.svg" :alt="t('account.expiredDesc')" />
          </div>
        </section>
      </div>
    </Transition>

    <Transition name="fade">
      <div v-if="showContactSupport" class="contact-support-overlay" @click.self="closeContactSupport">
        <section
          ref="contactSupportModalRef"
          class="contact-support-modal"
          role="dialog"
          aria-modal="true"
          :aria-labelledby="contactSupportTitleId"
          @keydown.esc="closeContactSupport"
          tabindex="-1"
        >
          <button
            type="button"
            class="contact-support-modal__close"
            :aria-label="t('contactSupport.close')"
            @click="closeContactSupport"
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="18" y1="6" x2="6" y2="18"/>
              <line x1="6" y1="6" x2="18" y2="18"/>
            </svg>
          </button>
          <div class="contact-support-modal__copy">
            <p class="contact-support-modal__eyebrow">{{ t('nav.contactSupport') }}</p>
            <h2 :id="contactSupportTitleId">{{ t('contactSupport.title') }}</h2>
            <p>{{ t('contactSupport.desc') }}</p>
            <p class="contact-support-modal__hint">{{ t('contactSupport.hint') }}</p>
          </div>
          <div class="contact-support-modal__qr">
            <img :src="wechatBusinessQr" :alt="t('contactSupport.qrAlt')" />
          </div>
        </section>
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useIsMobile, useMediaQuery } from '@/composables/useBreakpoint'
import { useI18n } from 'vue-i18n'
import { http, settingsApi, setupApi, approvalApi } from '@/api/index'
import type { ActiveGrantsSummary } from '@/types'
import { useAccountStore } from '@/stores/useAccountStore'
import OnboardingWizard from '@/views/Onboarding/OnboardingWizard.vue'
import DoctorDrawer from '@/views/Doctor/DoctorDrawer.vue'
import WorkspaceSwitcher from '@/components/workspace/WorkspaceSwitcher.vue'
import NavBadge from '@/components/common/NavBadge.vue'
import McTooltip from '@/components/common/McTooltip.vue'
import { useNotificationCenter } from '@/composables/useNotificationCenter'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import { applyLocale, currentLocale, type AppLocale } from '@/i18n'
import wechatBusinessQr from '@/assets/qrcode/wechat.png'
import { SwitchButton, Lock, Unlock } from '@element-plus/icons-vue'
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'

const router = useRouter()
const route = useRoute()
const { t } = useI18n()
const workspaceStore = useWorkspaceStore()
const accountStore = useAccountStore()
const sidebarCollapsed = ref(localStorage.getItem('mc-sidebar-collapsed') === 'true')
const footerPanelOpen = ref(false)
const expiredModalRef = ref<HTMLElement | null>(null)
const contactSupportModalRef = ref<HTMLElement | null>(null)
const showContactSupport = ref(false)

// Workspace 切换时通过 key 变化让 router-view 重新挂载，避免 hard reload 破坏运行状态
const workspaceRouteKey = computed(() => `ws-${workspaceStore.currentWorkspaceId ?? 'none'}`)
const showOnboarding = ref(false)
const showDoctor = ref(false)
const healthStatus = ref('unknown')

function onHealthStatus(status: string) {
  healthStatus.value = status
}

async function fetchHealthStatus() {
  try {
    const res: any = await http.get('/system/health')
    const data = res?.data || res
    healthStatus.value = data?.overall || 'healthy'
  } catch {
    healthStatus.value = 'unknown'
  }
}

// Active auto-approve grants summary — drives the red "auto-approve active (N)"
// chip in the sidebar footer. Red (not green) is intentional: this is a
// security-reducing setting and the UI should keep reminding the user it's on.
const autoApproveSummary = ref<ActiveGrantsSummary | null>(null)
async function fetchAutoApproveSummary() {
  try {
    const res: any = await approvalApi.activeSummary()
    autoApproveSummary.value = res?.data || res
  } catch {
    autoApproveSummary.value = null
  }
}
function goAutoApproveSettings() {
  router.push('/security/auto-approve')
}

// Sidebar attention signals — admin-only. Both `/agents` (stuck agents in the
// Live view) and `/security` (pending approvals) read from a shared 15s poller
// so multiple consumers don't multiply HTTP traffic.
const isAdminRole = computed(() => (localStorage.getItem('role') || 'user') === 'admin')
const { stuckAgents, pendingApprovals } = useNotificationCenter()
const liveAlertActive = computed(() => isAdminRole.value && stuckAgents.value > 0)

// 移动端状态
const mobileMenuOpen = ref(false)
const userExplicitCollapse = ref(localStorage.getItem('mc-sidebar-collapsed') === 'true')

const isMobile = useIsMobile()
// 中等屏幕自动折叠（≤1024px）
const compactViewport = useMediaQuery('(max-width: 1024px)')

// Mobile breakpoint side effects: close the drawer / footer panel when the
// layout flips between mobile and desktop.
watch(isMobile, (mobile) => {
  if (!mobile) mobileMenuOpen.value = false
  if (mobile) footerPanelOpen.value = false
})

// Auto-collapse the sidebar on narrow desktop unless the user set it explicitly.
watch(compactViewport, (compact) => {
  if (!userExplicitCollapse.value) sidebarCollapsed.value = compact
}, { immediate: true })

const shortcutsHintText = computed(() =>
  `Ctrl+K ${t('nav.shortcutAgents')} | Ctrl+N ${t('nav.shortcutNew')}`,
)

function openAgentsMenu() {
  if (!workspaceStore.can('manage:agents' as never)) return
  if (route.path !== '/agents') router.push('/agents')
}

function fireNewChatShortcut() {
  if (route.path === '/chat') {
    window.dispatchEvent(new CustomEvent('mc:chat-shortcut', { detail: 'newChat' }))
  } else {
    router.push({ path: '/chat', query: { action: 'newChat' } })
  }
}

function isEditableTarget(el: EventTarget | null): boolean {
  if (!(el instanceof HTMLElement)) return false
  if (el.isContentEditable) return true
  const tag = el.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true
  return false
}

function onGlobalKeydown(e: KeyboardEvent) {
  if (showContactSupport.value && e.key === 'Escape') {
    closeContactSupport()
    return
  }
  if (accountStore.expired) {
    e.preventDefault()
    expiredModalRef.value?.focus()
    return
  }
  const mod = e.metaKey || e.ctrlKey
  if (!mod || e.altKey) return
  const key = e.key.toLowerCase()
  if (key !== 'k' && key !== 'n') return
  // Ctrl+N within an editable field should keep its native behavior; Ctrl+K
  // is rarely used by browsers (Firefox uses it for search-bar focus), but we
  // still want to let the chat input handle native paste / undo unblocked.
  if (key === 'n' && isEditableTarget(e.target)) return
  e.preventDefault()
  if (key === 'k') {
    openAgentsMenu()
  } else {
    fireNewChatShortcut()
  }
}

function onExpiredModalKeydown(e: KeyboardEvent) {
  if (e.key !== 'Tab') return
  e.preventDefault()
  expiredModalRef.value?.focus()
}

function openContactSupport() {
  showContactSupport.value = true
  if (isMobile.value) mobileMenuOpen.value = false
}

function closeContactSupport() {
  showContactSupport.value = false
}

onMounted(async () => {
  window.addEventListener('keydown', onGlobalKeydown)

  if (localStorage.getItem('token')) {
    accountStore.fetchAccount().catch(() => {})
  }

  // Check onboarding status
  if (!localStorage.getItem('mc-onboarding-done')) {
    try {
      const res: any = await setupApi.onboardingStatus()
      if (res?.data && !res.data.hasDefaultModel) {
        showOnboarding.value = true
      }
    } catch {
      // If endpoint doesn't exist yet, skip onboarding
    }
  }

  // Fetch initial health status for sidebar indicator
  fetchHealthStatus()
  // Auto-approve chip count. Cheap query (single SELECT COUNT) so we just
  // fetch on mount and on workspace switch (handled by router-view key change
  // which re-mounts the route subtree).
  fetchAutoApproveSummary()
  // Sidebar attention counts (live / security) are driven by
  // useNotificationCenter — it polls when admins are mounted.
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onGlobalKeydown)
})

watch(() => accountStore.expired, async (expired) => {
  if (!expired) return
  await nextTick()
  expiredModalRef.value?.focus()
})

watch(showContactSupport, async (visible) => {
  if (!visible) return
  await nextTick()
  contactSupportModalRef.value?.focus()
})

function onNavClick() {
  if (isMobile.value) mobileMenuOpen.value = false
}

const username = computed(() => localStorage.getItem('username') || 'User')
const role = computed(() => localStorage.getItem('role') || 'user')
const userInitial = computed(() => username.value.charAt(0).toUpperCase())
const roleLabel = computed(() => role.value === 'admin' ? t('nav.roleAdmin') : t('nav.roleUser'))
const expiredDialogTitleId = 'account-expired-title'
const expiredDialogDescId = 'account-expired-desc'
const contactSupportTitleId = 'contact-support-title'
function formatAccountDateTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value

  return new Intl.DateTimeFormat(currentLocale.value, {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(date)
}
const accountExpiryText = computed(() => {
  if (accountStore.expired && !accountStore.expiresAt) return t('account.expired')
  if (!accountStore.expiresAt) return t('account.permanent')
  return t('account.validUntil', { time: formatAccountDateTime(accountStore.expiresAt) })
})
const expiredAtText = computed(() => accountStore.expiresAt
  ? t('account.validUntil', { time: formatAccountDateTime(accountStore.expiresAt) })
  : t('account.expired'))
const effectiveCollapsed = computed(() => sidebarCollapsed.value && !isMobile.value)
const sidebarToggleLabel = computed(() => sidebarCollapsed.value ? t('common.expandSidebar') : t('common.collapseSidebar'))
const currentLocaleValue = computed(() => currentLocale.value)

const localeOptions = computed<{ value: AppLocale; label: string; short: string }[]>(() => [
  { value: 'zh-CN', label: t('settings.languageOptions.zhCN'), short: '中' },
  { value: 'en-US', label: t('settings.languageOptions.enUS'), short: 'EN' },
])

// Capability-gated nav. Each item declares a capability or globalAdmin flag;
// useWorkspaceStore.can() decides visibility from the backend access set so
// the sidebar can't drift from the route guard or controller annotations.
type NavItem = {
  path?: string
  action?: 'contactSupport'
  label: string
  icon: string
  tooltip?: string
  requiredCapability?:
    | 'chat'
    | 'view:wiki'
    | 'view:memory'
    | 'view:dashboard'
    | 'manage:wiki'
    | 'manage:agents'
    | 'manage:skills'
    | 'manage:channels'
    | 'manage:models'
    | 'manage:security'
    | 'manage:settings'
  globalAdmin?: boolean
}

function onNavAction(item: NavItem) {
  if (item.action === 'contactSupport') {
    openContactSupport()
  }
}

function filterNav(items: NavItem[]): NavItem[] {
  // Default deny while access is still loading — render an empty group rather
  // than flashing the full menu before refreshAccess() returns.
  if (!workspaceStore.accessLoaded) return []
  return items.filter((item) => {
    if (item.globalAdmin) return workspaceStore.isGlobalAdmin
    if (item.requiredCapability && !workspaceStore.can(item.requiredCapability as never)) return false
    return true
  })
}

const navGroups = computed(() => [
  {
    key: 'core',
    label: t('nav.core'),
    items: filterNav([
      {
        path: '/home',
        label: t('nav.home', 'Home'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M3 11.5 12 4l9 7.5"/><path d="M5 10.5V20h14v-9.5"/><path d="M9 20v-6h6v6"/></svg>`,
        requiredCapability: 'chat',
      },
      {
        path: '/chat',
        label: t('nav.chat'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>`,
        requiredCapability: 'chat',
      },
      {
        path: '/lead-acquisition',
        label: t('nav.leadAcquisition'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="8"/><circle cx="12" cy="12" r="3"/><path d="M12 2v4"/><path d="M12 18v4"/><path d="M2 12h4"/><path d="M18 12h4"/></svg>`,
        requiredCapability: 'chat',
      },
      {
        path: '/agents',
        label: t('nav.agents'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="8" r="4"/><path d="M20 21a8 8 0 1 0-16 0"/></svg>`,
        requiredCapability: 'manage:agents',
      },
      {
        path: '/wiki',
        label: t('nav.wiki'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/><line x1="8" y1="7" x2="16" y2="7"/><line x1="8" y1="11" x2="14" y2="11"/></svg>`,
        requiredCapability: 'view:wiki',
      },
      {
        path: '/memory',
        label: t('nav.memory'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2a4 4 0 0 1 4 4v2a4 4 0 0 1-8 0V6a4 4 0 0 1 4-4z"/><path d="M16 14H8a4 4 0 0 0-4 4v2h16v-2a4 4 0 0 0-4-4z"/><line x1="12" y1="11" x2="12" y2="14"/></svg>`,
        requiredCapability: 'view:memory',
      },
      {
        path: '/enterprise',
        label: t('nav.enterprise'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 21h18"/><path d="M5 21V7l7-4 7 4v14"/><path d="M9 9h.01"/><path d="M9 12h.01"/><path d="M9 15h.01"/><path d="M9 18h.01"/><path d="M15 9h.01"/><path d="M15 12h.01"/><path d="M15 15h.01"/><path d="M15 18h.01"/></svg>`,
        requiredCapability: 'manage:agents',
      },
    ] as NavItem[]),
  },
  {
    key: 'connect',
    label: t('nav.connect'),
    items: filterNav([
      {
        action: 'contactSupport',
        label: t('nav.contactSupport'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z"/><path d="M9 10h6"/><path d="M9 14h4"/></svg>`,
      },
      {
        path: '/channels',
        label: t('nav.channels'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 16.92v3a2 2 0 0 1-2.18 2 19.79 19.79 0 0 1-8.63-3.07A19.5 19.5 0 0 1 4.69 12a19.79 19.79 0 0 1-3.07-8.67A2 2 0 0 1 3.6 1.18h3a2 2 0 0 1 2 1.72c.127.96.361 1.903.7 2.81a2 2 0 0 1-.45 2.11L7.91 8.73a16 16 0 0 0 6.29 6.29l1.62-1.62a2 2 0 0 1 2.11-.45c.907.339 1.85.573 2.81.7A2 2 0 0 1 22 16.92z"/></svg>`,
        requiredCapability: 'manage:channels',
      },
      {
        path: '/skills',
        label: t('nav.skills'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polygon points="12 2 15.09 8.26 22 9.27 17 14.14 18.18 21.02 12 17.77 5.82 21.02 7 14.14 2 9.27 8.91 8.26 12 2"/></svg>`,
        requiredCapability: 'manage:skills',
      },
      {
        path: '/plugins',
        label: t('nav.plugins'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="7" width="20" height="14" rx="2" ry="2"/><path d="M16 3h-8v4h8V3z"/></svg>`,
        requiredCapability: 'manage:settings',
      },
      // RFC-090 Phase 4: Activity 提升到顶层
      {
        path: '/activity',
        label: t('nav.activity'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg>`,
        requiredCapability: 'manage:security',
      },
    ] as NavItem[]),
  },
  {
    key: 'system',
    label: t('nav.system'),
    items: filterNav([
      {
        path: '/settings/models',
        label: t('nav.settings'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.07 4.93a10 10 0 0 1 0 14.14M4.93 4.93a10 10 0 0 0 0 14.14"/></svg>`,
        requiredCapability: 'manage:models',
      },
      {
        path: '/security',
        label: t('nav.security'),
        icon: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>`,
        requiredCapability: 'manage:security',
      },
    ] as NavItem[]),
  },
].filter((group) => group.items.length > 0))

function toggleSidebar() {
  sidebarCollapsed.value = !sidebarCollapsed.value
  userExplicitCollapse.value = sidebarCollapsed.value
  localStorage.setItem('mc-sidebar-collapsed', String(sidebarCollapsed.value))
  if (!sidebarCollapsed.value) {
    footerPanelOpen.value = false
  }
}

function isNavItemActive(item: NavItem) {
  if (!item.path) return false
  if (item.path.startsWith('/settings')) {
    return route.path.startsWith('/settings')
  }
  if (item.path === '/security') {
    return route.path.startsWith('/security')
  }
  return route.path === item.path
}

const showChangePassword = ref(false)

function logout() {
  localStorage.removeItem('token')
  localStorage.removeItem('username')
  localStorage.removeItem('role')
  router.push('/login')
}

async function changeLocale(locale: AppLocale) {
  await applyLocale(locale)
  footerPanelOpen.value = false
  try {
    await settingsApi.update({ language: locale })
  } catch {
    // keep local preference even if backend persistence fails
  }
}

watch(() => route.fullPath, () => {
  footerPanelOpen.value = false
  if (isMobile.value) mobileMenuOpen.value = false
})

watch(() => sidebarCollapsed.value, (collapsed) => {
  if (!collapsed) footerPanelOpen.value = false
})

watch(() => workspaceStore.currentWorkspaceId, () => {
  footerPanelOpen.value = false
})
</script>

<style scoped>
.app-layout {
  height: 100vh;
  background: var(--mc-bg);
  overflow: hidden;
  position: relative;
}

.app-shell {
  position: relative;
  z-index: 1;
  height: 100%;
  display: flex;
}

.app-layout::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at 12% 8%, rgba(71, 108, 255, 0.18), transparent 28%),
    radial-gradient(circle at 86% 78%, rgba(25, 191, 209, 0.14), transparent 24%),
    radial-gradient(circle at 50% 0%, rgba(157, 181, 255, 0.12), transparent 34%);
  pointer-events: none;
}

:global(html.dark) .app-layout::before {
  background:
    radial-gradient(circle at 12% 8%, rgba(71, 108, 255, 0.24), transparent 30%),
    radial-gradient(circle at 86% 76%, rgba(25, 191, 209, 0.16), transparent 26%),
    radial-gradient(circle at 50% 0%, rgba(157, 181, 255, 0.10), transparent 36%);
}

/* ===== 侧边栏 ===== */
.sidebar {
  width: 236px;
  min-width: 236px;
  margin: 14px 0 14px 14px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.08), rgba(255, 255, 255, 0.025)),
    var(--mc-sidebar-bg);
  border: 1px solid var(--mc-sidebar-border);
  border-radius: 28px;
  box-shadow:
    0 22px 50px rgba(5, 17, 54, 0.22),
    inset 0 1px 0 rgba(255, 255, 255, 0.10);
  backdrop-filter: blur(22px) saturate(140%);
  -webkit-backdrop-filter: blur(22px) saturate(140%);
  display: flex;
  flex-direction: column;
  transition: width 0.2s ease, min-width 0.2s ease;
  overflow: hidden;
  position: relative;
  z-index: 1;
}

.sidebar.collapsed {
  width: 74px;
  min-width: 74px;
}

.sidebar::before {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 0;
  background:
    radial-gradient(circle at 18% 0%, rgba(25, 191, 209, 0.18), transparent 28%),
    linear-gradient(120deg, rgba(255, 255, 255, 0.12), transparent 30%, transparent 70%, rgba(71, 108, 255, 0.12)),
    repeating-linear-gradient(90deg, rgba(157, 181, 255, 0.045) 0 1px, transparent 1px 34px),
    repeating-linear-gradient(180deg, rgba(157, 181, 255, 0.035) 0 1px, transparent 1px 34px);
  opacity: 0.54;
  pointer-events: none;
}

.sidebar > * {
  position: relative;
  z-index: 1;
}

.sidebar-logo {
  display: flex;
  align-items: center;
  padding: 14px 14px 12px;
  border-bottom: 1px solid rgba(197, 215, 255, 0.14);
  gap: 12px;
  min-height: 64px;
}

.sidebar.collapsed .sidebar-logo {
  flex-direction: column;
  justify-content: center;
  padding: 12px 10px;
  gap: 6px;
  min-height: 92px;
}

.logo-icon {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  overflow: hidden;
  background:
    radial-gradient(circle at 30% 18%, rgba(25, 191, 209, 0.34), transparent 38%),
    linear-gradient(135deg, rgba(71, 108, 255, 0.28), rgba(157, 181, 255, 0.10));
  border: 1px solid rgba(157, 181, 255, 0.30);
  box-shadow:
    0 10px 24px rgba(25, 191, 209, 0.20),
    inset 0 0 0 1px rgba(255, 255, 255, 0.10);
}

.logo-img {
  width: 34px;
  height: 34px;
  object-fit: contain;
  filter: drop-shadow(0 8px 18px rgba(25, 191, 209, 0.28));
}

.logo-emoji { font-size: 16px; }

.logo-text { flex: 1; overflow: hidden; }

.logo-name {
  display: block;
  font-size: 16px;
  font-weight: 800;
  color: var(--mc-sidebar-logo-name);
  white-space: nowrap;
  letter-spacing: -0.03em;
}

.logo-name-highlight {
  color: var(--mc-primary);
}

.logo-expiry {
  display: block;
  min-width: 0;
  max-width: 100%;
  font-size: 10px;
  color: rgba(192, 210, 255, 0.66);
  line-height: 1.45;
  letter-spacing: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.logo-expiry.is-expired {
  color: var(--mc-danger, #C0392B);
  font-weight: 700;
}

.collapse-btn {
  width: 28px;
  height: 28px;
  border: 1px solid rgba(197, 215, 255, 0.18);
  background: rgba(255, 255, 255, 0.08);
  cursor: pointer;
  color: var(--mc-sidebar-text);
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  flex-shrink: 0;
  padding: 0;
  margin-left: auto;
}

.collapse-btn:hover {
  background: var(--mc-sidebar-hover);
  border-color: rgba(255, 255, 255, 0.26);
  color: var(--mc-sidebar-text-active);
}

.sidebar.collapsed .collapse-btn {
  width: 32px;
  height: 32px;
  margin-left: 0;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(197, 215, 255, 0.18);
  color: var(--mc-sidebar-text-active);
}

.sidebar.collapsed .collapse-btn:hover {
  background: var(--mc-sidebar-hover);
  border-color: rgba(255, 255, 255, 0.26);
}

/* 导航 */
.sidebar-nav {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0 4px;
  scrollbar-width: none;
}

.sidebar-nav::-webkit-scrollbar { width: 4px; }
.sidebar-nav::-webkit-scrollbar-thumb { background: rgba(197, 215, 255, 0.22); border-radius: 2px; }
.sidebar-nav::-webkit-scrollbar { display: none; }

.nav-group { margin-bottom: 2px; }

.nav-group-title {
  padding: 8px 18px 4px;
  font-size: 10px;
  font-weight: 600;
  color: var(--mc-sidebar-group-title);
  text-transform: uppercase;
  letter-spacing: 0.1em;
  white-space: nowrap;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  color: var(--mc-sidebar-text);
  text-decoration: none;
  font-size: 13px;
  border-radius: 13px;
  margin: 2px 10px;
  transition: all 0.15s ease;
  white-space: nowrap;
  overflow: hidden;
  position: relative;
}

.nav-item--button {
  border: none;
  background: transparent;
  cursor: pointer;
  font: inherit;
  text-align: left;
}

.nav-item:hover {
  background: var(--mc-sidebar-hover);
  color: var(--mc-sidebar-text-active);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.12);
}

.nav-item.active {
  background: var(--mc-sidebar-active);
  color: var(--mc-sidebar-text-active);
  font-weight: 700;
  box-shadow:
    0 10px 24px rgba(71, 108, 255, 0.26),
    inset 0 0 0 1px rgba(255, 255, 255, 0.22);
}

/* Active indicator bar removed — active state uses bg color + font weight only */

/* Collapsed rail: the label is hidden, so center the lone icon on the
   sidebar's vertical axis instead of leaving it left-aligned by the
   nav-item's horizontal padding. */
.sidebar.collapsed .nav-item {
  justify-content: center;
  gap: 0;
}

.sidebar.collapsed .nav-item:hover,
.sidebar.collapsed .nav-item.active {
  color: var(--mc-sidebar-text-active);
}

.nav-icon { display: flex; align-items: center; flex-shrink: 0; }
.nav-label { overflow: hidden; text-overflow: ellipsis; }

/* 底部 */
.sidebar-footer {
  border-top: 1px solid rgba(197, 215, 255, 0.14);
  padding: 10px 12px 12px;
  background: var(--mc-sidebar-footer-bg);
  backdrop-filter: blur(14px);
  position: relative;
}
/* Status row: doctor health + (optional) auto-approve chip side by side, so
   the footer never gives up a whole banner-row for a single state badge. When
   the chip is hidden, .health-indicator naturally expands back to full width. */
.footer-status-row {
  display: flex;
  gap: 8px;
  width: 100%;
  margin-bottom: 8px;
}
.health-indicator {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1 1 auto;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid rgba(197, 215, 255, 0.18);
  background: rgba(255, 255, 255, 0.08);
  border-radius: 12px;
  cursor: pointer;
  color: var(--mc-sidebar-text);
  font-size: 12px;
}
.health-indicator:hover {
  background: var(--mc-sidebar-hover);
  border-color: rgba(255, 255, 255, 0.24);
  color: var(--mc-sidebar-text-active);
}
.health-indicator .health-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* When the chip is showing, the health card yields half of the row so the
   two states stay visually balanced. */
.health-indicator.is-half { flex: 1 1 50%; }

/*
  Auto-approve chip — persistent indicator that this workspace currently has
  at least one active auto-approve rule. Designed to be informative, not
  alarming: a soft danger-tinted pill with a steady pulse on the dot, sized
  to fit beside the doctor indicator on one row. Colors come from the
  mateclaw token system (`var(--mc-danger-*)`), so dark mode picks up the
  appropriate dim variants automatically.
*/
.auto-approve-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: 1 1 50%;
  min-width: 0;
  padding: 8px 10px;
  border: 1px solid var(--mc-danger-border, rgba(192, 57, 43, 0.4));
  background: var(--mc-danger-bg, rgba(192, 57, 43, 0.12));
  color: var(--mc-danger, #C0392B);
  border-radius: 12px;
  cursor: pointer;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.2;
  transition: background-color 0.15s, border-color 0.15s;
}
.auto-approve-chip:hover {
  background: var(--mc-danger-bg, rgba(192, 57, 43, 0.18));
  border-color: var(--mc-danger, #C0392B);
}
.auto-approve-chip__label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* Small live-status dot that gently pulses to suggest "this is active right
   now" without being aggressive about it. The animation pauses when the user
   prefers reduced motion. */
.auto-approve-chip__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--mc-danger, #C0392B);
  box-shadow: 0 0 0 0 var(--mc-danger, #C0392B);
  animation: auto-approve-pulse 2.4s ease-in-out infinite;
  flex-shrink: 0;
}
@keyframes auto-approve-pulse {
  0%   { box-shadow: 0 0 0 0 var(--mc-danger, #C0392B); opacity: 1; }
  60%  { box-shadow: 0 0 0 6px transparent; opacity: 0.6; }
  100% { box-shadow: 0 0 0 0 transparent; opacity: 1; }
}
@media (prefers-reduced-motion: reduce) {
  .auto-approve-chip__dot { animation: none; }
}
.health-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.health-indicator.healthy .health-dot { background: var(--mc-success); }
.health-indicator.warning .health-dot { background: var(--mc-primary); }
.health-indicator.error .health-dot { background: var(--mc-danger); }
.health-indicator.unknown .health-dot { background: var(--mc-text-tertiary); }

.sidebar-utility-card {
  margin-bottom: 8px;
  padding: 8px 10px;
  border-radius: 16px;
  border: 1px solid rgba(197, 215, 255, 0.18);
  background: rgba(255, 255, 255, 0.08);
  color: var(--mc-sidebar-text);
}

.compact-utility-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.compact-utility-title {
  font-size: 10px;
  font-weight: 700;
  color: rgba(226, 236, 255, 0.78);
  letter-spacing: 0.04em;
  white-space: nowrap;
}

.utility-label { font-size: 10px; font-weight: 700; text-transform: uppercase; letter-spacing: 0.12em; color: var(--mc-text-tertiary); margin: 0 0 8px; padding-left: 2px; }

.shortcuts-hint {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 4px 6px;
  margin-top: 8px;
  padding: 4px 6px;
  font-size: 10px;
  color: rgba(226, 236, 255, 0.62);
  letter-spacing: 0.02em;
  user-select: none;
}
.shortcuts-hint kbd {
  display: inline-flex;
  align-items: center;
  padding: 1px 5px;
  border-radius: 4px;
  border: 1px solid rgba(197, 215, 255, 0.18);
  background: rgba(255, 255, 255, 0.08);
  color: var(--mc-sidebar-text-active);
  font-family: inherit;
  font-size: 9.5px;
  font-weight: 600;
  line-height: 1.4;
}
.shortcuts-hint__sep {
  opacity: 0.45;
}

.language-toggle-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 6px;
}

.language-toggle-row--compact {
  display: flex;
  gap: 6px;
}

.language-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  justify-content: flex-start;
  width: 100%;
  padding: 10px 12px;
  border-radius: 14px;
  border: 1px solid rgba(197, 215, 255, 0.18);
  background: rgba(255, 255, 255, 0.08);
  color: var(--mc-sidebar-text);
  cursor: pointer;
  transition: all 0.15s ease;
  font-size: 12px;
  font-weight: 600;
}

.language-btn:hover {
  background: var(--mc-sidebar-hover);
  border-color: rgba(255, 255, 255, 0.24);
  color: var(--mc-sidebar-text-active);
}

.language-btn.active {
  border-color: rgba(157, 181, 255, 0.44);
  background: rgba(71, 108, 255, 0.26);
  color: var(--mc-sidebar-text-active);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.14);
}

.language-abbr {
  width: 24px;
  height: 24px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.10);
  color: inherit;
  font-size: 11px;
  font-weight: 800;
  flex-shrink: 0;
}

.language-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.language-btn--compact {
  width: 34px;
  min-width: 34px;
  justify-content: center;
  padding: 4px 0;
  border-radius: 999px;
}

.language-btn--compact .language-abbr {
  width: 20px;
  height: 20px;
  font-size: 10px;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 9px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(197, 215, 255, 0.18);
  color: var(--mc-sidebar-text);
}

.user-avatar {
  width: 30px;
  height: 30px;
  background: linear-gradient(135deg, var(--mc-primary), var(--mc-accent));
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: white;
  font-size: 13px;
  font-weight: 600;
  flex-shrink: 0;
}

.user-detail { flex: 1; overflow: hidden; }

.user-name {
  font-size: 12px;
  font-weight: 500;
  color: var(--mc-sidebar-text-active);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.user-meta {
  display: flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
  margin-top: 2px;
}

.user-role {
  font-size: 10px;
  color: rgba(226, 236, 255, 0.62);
  white-space: nowrap;
  flex-shrink: 0;
}

.account-expiry-badge {
  display: inline-flex;
  align-items: center;
  min-width: 0;
  max-width: 100%;
  padding: 1px 6px;
  border-radius: 999px;
  border: 1px solid var(--mc-border-light);
  background: var(--mc-bg-elevated);
  color: var(--mc-text-secondary);
  font-size: 9.5px;
  font-weight: 700;
  line-height: 1.45;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.account-expiry-badge.is-expired {
  border-color: var(--mc-danger-border, rgba(192, 57, 43, 0.32));
  background: var(--mc-danger-bg, rgba(192, 57, 43, 0.10));
  color: var(--mc-danger, #C0392B);
}

.change-password-btn,
.logout-btn {
  width: 26px;
  height: 26px;
  border: none;
  background: none;
  cursor: pointer;
  color: rgba(226, 236, 255, 0.62);
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  padding: 0;
  flex-shrink: 0;
}

.change-password-btn:hover {
  background: var(--mc-primary-bg);
  color: var(--mc-primary);
}

.logout-btn:hover {
  background: var(--mc-danger-bg);
  color: var(--mc-danger);
}

.collapsed-footer-actions {
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: center;
}

.footer-icon-btn {
  width: 42px;
  height: 42px;
  border-radius: 14px;
  border: 1px solid rgba(197, 215, 255, 0.18);
  background: rgba(255, 255, 255, 0.08);
  color: var(--mc-sidebar-text);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s ease;
}

.footer-icon-btn:hover {
  background: var(--mc-sidebar-hover);
  border-color: rgba(255, 255, 255, 0.24);
  color: var(--mc-sidebar-text-active);
}

.footer-icon-btn.healthy .health-dot { background: var(--mc-success); }
.footer-icon-btn.warning .health-dot { background: var(--mc-primary); }
.footer-icon-btn.error .health-dot { background: var(--mc-danger); }
.footer-icon-btn.unknown .health-dot { background: var(--mc-text-tertiary); }

.footer-icon-btn--accent {
  color: var(--mc-sidebar-text-active);
  background: rgba(71, 108, 255, 0.26);
  border-color: rgba(157, 181, 255, 0.44);
  box-shadow: 0 10px 24px rgba(71, 108, 255, 0.20);
}

.sidebar-utility-panel {
  position: absolute;
  left: calc(100% + 14px);
  bottom: 16px;
  width: 236px;
  padding: 14px;
  border-radius: 22px;
  background: var(--mc-sidebar-floating-bg);
  border: 1px solid var(--mc-sidebar-border);
  box-shadow: 0 18px 46px rgba(5, 17, 54, 0.28);
  display: flex;
  flex-direction: column;
  gap: 14px;
  backdrop-filter: blur(18px);
}

.panel-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.panel-option-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.panel-option-btn {
  width: 100%;
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 14px;
  border: 1px solid rgba(197, 215, 255, 0.18);
  background: rgba(255, 255, 255, 0.08);
  color: var(--mc-sidebar-text);
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  transition: all 0.15s ease;
}

.panel-option-btn:hover {
  background: var(--mc-sidebar-hover);
  border-color: rgba(255, 255, 255, 0.24);
  color: var(--mc-sidebar-text-active);
}

.panel-option-btn.active {
  background: var(--mc-sidebar-active);
  color: var(--mc-sidebar-text-active);
  border-color: rgba(157, 181, 255, 0.44);
  box-shadow:
    0 10px 24px rgba(71, 108, 255, 0.24),
    inset 0 0 0 1px rgba(255, 255, 255, 0.18);
}

.panel-option-icon {
  width: 18px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.panel-user {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(197, 215, 255, 0.18);
}

.panel-user-meta {
  min-width: 0;
  flex: 1;
}

.logout-btn--panel {
  flex-shrink: 0;
}

/* ===== 主内容区 ===== */
.main-content {
  flex: 1;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  min-width: 0;
  position: relative;
  z-index: 1;
  padding: 14px 14px 14px 18px;
}

/* ===== 移动端元素（桌面端隐藏） ===== */
.sidebar-backdrop {
  display: none;
}

.mobile-topbar {
  display: none;
}

.expired-overlay {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(15, 23, 42, 0.55);
  backdrop-filter: blur(10px);
}

.expired-modal {
  width: min(520px, 100%);
  display: grid;
  grid-template-columns: minmax(0, 1fr) 150px;
  gap: 22px;
  align-items: center;
  padding: 24px;
  border-radius: 16px;
  border: 1px solid var(--mc-border);
  background: var(--mc-bg-elevated);
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.24);
}

.expired-modal__copy {
  min-width: 0;
}

.expired-modal__eyebrow {
  margin: 0 0 8px;
  color: var(--mc-danger, #C0392B);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.expired-modal h2 {
  margin: 0;
  color: var(--mc-text-primary);
  font-size: 22px;
  line-height: 1.25;
}

.expired-modal p {
  margin: 10px 0 0;
  color: var(--mc-text-secondary);
  font-size: 14px;
  line-height: 1.6;
}

.expired-modal__time {
  color: var(--mc-text-tertiary) !important;
  font-size: 12px !important;
}

.expired-modal__qr {
  width: 150px;
  padding: 10px;
  border-radius: 12px;
  border: 1px solid var(--mc-border-light);
  background: #fff;
}

.expired-modal__qr img {
  display: block;
  width: 100%;
  aspect-ratio: 1;
  object-fit: contain;
}

.contact-support-overlay {
  position: fixed;
  inset: 0;
  z-index: 2000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
  background: rgba(10, 15, 20, 0.42);
  backdrop-filter: blur(12px);
}

.contact-support-modal {
  position: relative;
  width: min(430px, 100%);
  padding: 28px;
  border-radius: 24px;
  border: 1px solid var(--mc-border);
  background:
    radial-gradient(circle at top left, rgba(71, 108, 255, 0.14), transparent 34%),
    radial-gradient(circle at bottom right, rgba(25, 191, 209, 0.10), transparent 30%),
    var(--mc-bg-elevated);
  box-shadow: var(--mc-shadow-large);
  text-align: center;
}

.contact-support-modal__close {
  position: absolute;
  top: 14px;
  right: 14px;
  width: 34px;
  height: 34px;
  border: 1px solid var(--mc-border-light);
  border-radius: 12px;
  background: var(--mc-bg-muted);
  color: var(--mc-text-tertiary);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
}

.contact-support-modal__close:hover {
  background: var(--mc-bg-sunken);
  color: var(--mc-text-primary);
}

.contact-support-modal__eyebrow {
  margin: 0 0 8px;
  color: var(--mc-primary);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.contact-support-modal h2 {
  margin: 0;
  color: var(--mc-text-primary);
  font-size: 22px;
  line-height: 1.25;
}

.contact-support-modal p {
  margin: 10px 0 0;
  color: var(--mc-text-secondary);
  font-size: 14px;
  line-height: 1.6;
}

.contact-support-modal__hint {
  color: var(--mc-text-tertiary) !important;
  font-size: 12px !important;
}

.contact-support-modal__qr {
  width: 176px;
  margin: 20px auto 0;
  padding: 12px;
  border-radius: 16px;
  border: 1px solid var(--mc-border-light);
  background: #fff;
  box-shadow: 0 12px 30px rgba(15, 23, 42, 0.12);
}

.contact-support-modal__qr img {
  display: block;
  width: 100%;
  aspect-ratio: 1;
  object-fit: contain;
}

/* 动画 */
.fade-enter-active,
.fade-leave-active { transition: opacity 0.15s ease; }
.fade-enter-from,
.fade-leave-to { opacity: 0; }

/* ===== 移动端适配 ===== */
@media (max-width: 768px) {
  .sidebar {
    position: fixed;
    left: 0;
    top: 0;
    bottom: 0;
    z-index: 1000;
    width: 260px;
    min-width: 260px;
    margin: 10px;
    transform: translateX(-100%);
    transition: transform 0.25s ease;
    box-shadow: var(--mc-shadow-medium);
  }

  .sidebar.mobile-open {
    transform: translateX(0);
    box-shadow: 4px 0 24px rgba(0, 0, 0, 0.15);
  }

  .sidebar.collapsed {
    width: 260px;
    min-width: 260px;
  }

  .collapse-btn {
    display: none;
  }

  .sidebar-backdrop {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 999;
    background: rgba(0, 0, 0, 0.3);
  }

  .mobile-topbar {
    display: flex;
    align-items: center;
    gap: 10px;
    margin: 0 0 12px;
    padding: 12px 14px;
    background: var(--mc-surface-overlay);
    border: 1px solid var(--mc-border);
    border-radius: 18px;
    box-shadow: var(--mc-shadow-soft);
    flex-shrink: 0;
  }

  .mobile-topbar-title {
    font-size: 16px;
    font-weight: 700;
    color: var(--mc-text-primary);
  }

  .mobile-menu-btn {
    width: 36px;
    height: 36px;
    border: 1px solid var(--mc-border);
    background: var(--mc-bg-elevated);
    border-radius: 8px;
    cursor: pointer;
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--mc-text-primary);
    flex-shrink: 0;
  }

  .mobile-menu-btn:hover {
    background: var(--mc-bg-sunken);
  }

  .sidebar-utility-panel {
    display: none;
  }

  .sidebar-utility-card {
    padding: 10px;
  }

  .compact-utility-row {
    flex-direction: column;
    align-items: stretch;
  }

  .language-toggle-row--compact {
    width: 100%;
    justify-content: stretch;
  }

  .language-btn--compact {
    flex: 1;
    width: auto;
  }

  .sidebar-footer {
    background: transparent;
    backdrop-filter: none;
  }

  .expired-modal {
    grid-template-columns: 1fr;
    justify-items: center;
    text-align: center;
    padding: 22px;
  }

  .expired-modal__qr {
    width: 144px;
  }

  .contact-support-modal {
    padding: 24px 20px;
  }

  .contact-support-modal__qr {
    width: 160px;
  }
}

@media (max-width: 480px) {
  .mobile-topbar {
    padding: 8px 10px;
    margin: 0 0 8px;
    border-radius: 12px;
    gap: 8px;
  }

  .mobile-topbar-title {
    font-size: 14px;
  }

  .mobile-menu-btn {
    width: 32px;
    height: 32px;
  }

  .main-content {
    padding: 8px;
  }

  .expired-overlay {
    padding: 16px;
  }

  .expired-modal h2 {
    font-size: 20px;
  }
}
</style>
