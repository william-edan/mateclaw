<template>
  <div class="enterprise-shell">
    <header class="enterprise-head">
      <div class="enterprise-head-main">
        <div class="enterprise-eyebrow">{{ t('enterprise.eyebrow') }}</div>
        <h1 class="enterprise-title">{{ t('enterprise.title') }}</h1>
        <p class="enterprise-subtitle">{{ t('enterprise.subtitle') }}</p>
      </div>
      <button class="enterprise-contact-cta" @click="showContactDialog = true">
        {{ t('enterprise.contactCta') }}
      </button>
    </header>

    <nav class="enterprise-tabs">
      <button
        v-for="tab in tabs" :key="tab.key"
        class="enterprise-tab" :class="{ active: activeTab === tab.key }"
        @click="activeTab = tab.key"
      >
        <span class="tab-label">{{ tab.label }}</span>
        <span v-if="tab.count != null" class="tab-count">{{ tab.count }}</span>
      </button>
    </nav>

    <section class="enterprise-body">
      <Overview v-if="activeTab === 'overview'" @open-case="onOpenCase" />
      <ContractReview v-else-if="activeTab === 'contract'" :focus="caseFocus" />
      <AccountIntel v-else-if="activeTab === 'account'" />
      <Approvals v-else-if="activeTab === 'approvals'" />
      <Audit v-else-if="activeTab === 'audit'" />
    </section>

    <Teleport to="body">
      <div v-if="showContactDialog" class="modal-overlay" @click.self="showContactDialog = false">
        <div class="modal business-modal" role="dialog" aria-modal="true">
          <div class="modal-header">
            <h3>{{ t('enterprise.contactModal.title') }}</h3>
            <button class="modal-close" @click="showContactDialog = false">&times;</button>
          </div>
          <div class="modal-body business-modal-body">
            <p>{{ t('enterprise.contactModal.desc') }}</p>
            <img :src="wechatBusinessQr" :alt="t('enterprise.contactModal.desc')" class="business-qr" />
          </div>
          <div class="modal-footer">
            <button class="btn-primary" @click="showContactDialog = false">
              {{ t('enterprise.contactModal.close') }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import Overview from './Overview.vue'
import ContractReview from './ContractReview.vue'
import AccountIntel from './AccountIntel.vue'
import Approvals from './Approvals.vue'
import Audit from './Audit.vue'
import wechatBusinessQr from '@/assets/qrcode/wechat.png'

const { t } = useI18n()
type TabKey = 'overview' | 'contract' | 'account' | 'approvals' | 'audit'
const activeTab = ref<TabKey>('overview')
const caseFocus = ref<string | null>(null)
const showContactDialog = ref(false)

const tabs = computed<{ key: TabKey; label: string; count: number | null }[]>(() => [
  { key: 'overview', label: t('enterprise.tabs.overview'), count: null },
  { key: 'contract', label: t('enterprise.tabs.contract'), count: 23 },
  { key: 'account',  label: t('enterprise.tabs.account'),  count: 14 },
  { key: 'approvals', label: t('enterprise.tabs.approvals'), count: 5 },
  { key: 'audit', label: t('enterprise.tabs.audit'), count: null },
])

function onOpenCase(id: string) {
  caseFocus.value = id
  activeTab.value = 'contract'
}
</script>

<style scoped>
.enterprise-shell {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
  padding: 28px 32px 24px;
  gap: 18px;
  background: var(--mc-bg);
  /* Constrain the inner body so each tab can decide how to overflow:
     vertical-stack pages scroll the body itself, three-pane grid pages
     keep each pane scrolling independently. Without this, content that
     exceeds the viewport falls outside the layout box and is unreachable. */
  overflow: hidden;
}

.enterprise-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}
.enterprise-head-main { display: flex; flex-direction: column; gap: 4px; }
.enterprise-contact-cta {
  flex-shrink: 0;
  border: 1px solid var(--mc-primary);
  background: var(--mc-primary-bg);
  color: var(--mc-primary-hover);
  padding: 8px 16px;
  border-radius: 10px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
}
.enterprise-contact-cta:hover {
  background: var(--mc-primary);
  color: #fff;
}
.enterprise-eyebrow {
  font-size: var(--mc-text-xs);
  color: var(--mc-primary);
  text-transform: uppercase;
  letter-spacing: 0.12em;
  font-weight: 600;
}
.enterprise-title {
  margin: 0;
  font-size: 26px;
  line-height: 1.2;
  font-weight: 700;
  color: var(--mc-text-primary);
}
.enterprise-subtitle {
  margin: 0;
  font-size: 14px;
  color: var(--mc-text-secondary);
  max-width: 720px;
  line-height: 1.55;
}

.enterprise-tabs {
  display: inline-flex;
  align-self: flex-start;
  padding: 4px;
  background: var(--mc-bg-muted);
  border: 1px solid var(--mc-border-light);
  border-radius: 14px;
  gap: 2px;
}
.enterprise-tab {
  border: none;
  background: none;
  padding: 8px 14px;
  border-radius: 10px;
  cursor: pointer;
  font-size: 13px;
  font-weight: 500;
  color: var(--mc-text-secondary);
  display: inline-flex;
  align-items: center;
  gap: 8px;
  transition: all 0.15s;
}
.enterprise-tab:hover { color: var(--mc-text-primary); }
.enterprise-tab.active {
  background: var(--mc-bg-elevated);
  color: var(--mc-primary);
  font-weight: 600;
  box-shadow: 0 1px 4px rgba(0,0,0,0.06);
}
.tab-count {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--mc-primary-bg);
  color: var(--mc-primary-hover);
}
.enterprise-tab.active .tab-count { background: var(--mc-primary); color: white; }

/* The body fills the remaining height and gives its single child a strict
   height contract via flex. Page roots must declare `flex: 1; min-height: 0;`
   so they consume that height and can choose their own overflow strategy. */
.enterprise-body {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* Contact-us business QR dialog */
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.modal {
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 12px;
  width: 520px;
  max-height: 80vh;
  overflow-y: auto;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
}
.modal-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  border-bottom: 1px solid var(--mc-border-light);
}
.modal-header h3 { font-size: 16px; font-weight: 600; color: var(--mc-text-primary); margin: 0; }
.modal-close {
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  color: var(--mc-text-tertiary);
  font-size: 18px;
  cursor: pointer;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.modal-close:hover { background: var(--mc-bg-hover); }
.modal-body { padding: 20px; }
.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 12px 20px;
  border-top: 1px solid var(--mc-border-light);
}
.btn-primary {
  padding: 8px 16px;
  background: var(--mc-primary, #D97757);
  color: white;
  border: none;
  border-radius: 6px;
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
}
.btn-primary:hover { opacity: 0.9; }
.business-modal { width: 360px; max-width: 360px; }
.business-modal-body { text-align: center; }
.business-modal-body p {
  margin: 0;
  color: var(--mc-text-secondary);
  line-height: 1.6;
}
.business-qr {
  width: 180px;
  height: 180px;
  object-fit: contain;
  margin-top: 12px;
}
</style>
