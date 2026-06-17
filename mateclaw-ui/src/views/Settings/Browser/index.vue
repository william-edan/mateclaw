<template>
  <div
    class="settings-section browser-pairing-section"
    :class="{ 'is-embedded': embedded, 'is-compact': compact }"
  >
    <div class="section-header">
      <h2 class="section-title">{{ t('settings.browser.title') }}</h2>
      <p class="section-desc">{{ t('settings.browser.description') }}</p>
    </div>

    <div class="settings-card">
      <!-- Status row: pill + the device name when detected -->
      <div class="pairing-status-row">
        <span class="status-pill" :class="`status-pill--${pillTone}`">
          <span class="status-dot"></span>
          {{ pillLabel }}
        </span>
        <button class="btn-secondary refresh-btn" :disabled="busy" @click="refresh">
          <el-icon v-if="probing" class="is-loading"><Loading /></el-icon>
          {{ t('settings.browser.recheck') }}
        </button>
      </div>

      <!-- Not detected: install guidance -->
      <div v-if="status === 'not-detected'" class="pairing-body pairing-body--empty">
        <p class="empty-title">{{ t('settings.browser.notDetected.title') }}</p>
        <p class="empty-hint">
          {{ isDesktopClient ? t('settings.browser.notDetected.desktopHint') : t('settings.browser.notDetected.hint') }}
        </p>
        <!-- Desktop uses Native Messaging auto-connect — no web-pairing button. -->

      </div>

      <!-- Detected, not connected: name + Connect -->
      <div v-else-if="status === 'detected'" class="pairing-body">
        <div v-if="deviceName" class="detected-device">
          {{ t('settings.browser.deviceLabel') }}: <strong>{{ deviceName }}</strong>
        </div>
        <div class="field">
          <label class="field-label">{{ t('settings.browser.deviceNameField') }}</label>
          <input
            v-model="deviceNameInput"
            class="field-input"
            :placeholder="defaultName"
            :disabled="busy"
          />
        </div>
        <button class="btn-primary" :disabled="busy" @click="connect">
          <el-icon v-if="connecting" class="is-loading"><Loading /></el-icon>
          {{ connecting ? t('settings.browser.connecting') : t('settings.browser.connect') }}
        </button>
      </div>

      <!-- Connected: name + Disconnect -->
      <div v-else-if="status === 'connected'" class="pairing-body">
        <div v-if="deviceName" class="detected-device">
          {{ t('settings.browser.deviceLabel') }}: <strong>{{ deviceName }}</strong>
        </div>
        <p class="connected-hint">{{ t('settings.browser.connectedHint') }}</p>
        <button class="btn-secondary btn-danger" :disabled="busy" @click="disconnect">
          <el-icon v-if="disconnecting" class="is-loading"><Loading /></el-icon>
          {{ t('settings.browser.disconnect') }}
        </button>
      </div>

      <!-- Probing for the very first time -->
      <div v-else class="pairing-body pairing-body--empty">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>{{ t('common.loading') }}</span>
      </div>
    </div>

    <p class="section-footer-note">{{ t('settings.browser.footer') }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElIcon } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import { mcToast } from '@/composables/useMcToast'
import { browserPairingApi } from '@/api/index'
import {
  deriveWsUrl,
  defaultDeviceName,
  runConnect,
  sendToExtension,
  statusFromPing,
  PAIRING_TOKEN_KEY,
  type PairingStatus,
  type PingResponse,
  type PairResponse,
} from './pairing'

const { t } = useI18n()

withDefaults(
  defineProps<{
    embedded?: boolean
    compact?: boolean
  }>(),
  {
    embedded: false,
    compact: false,
  },
)

const status = ref<PairingStatus>('unknown')
const deviceName = ref<string | null>(null)
const probing = ref(false)
const connecting = ref(false)
const disconnecting = ref(false)

const defaultName = defaultDeviceName()
const deviceNameInput = ref(defaultName)
const isDesktopClient = computed(() => {
  const ua = navigator.userAgent || ''
  return Boolean(window.mateclawDesktop || /Electron/i.test(ua))
})
const externalPairingUrl = computed(() => {
  const url = new URL('/lead-acquisition', location.origin)
  url.searchParams.set('connectBrowser', '1')
  return url.toString()
})

const busy = computed(() => probing.value || connecting.value || disconnecting.value)

// Status pill tone + label. grey = not detected, yellow = detected-not-connected,
// green = connected. 'unknown' shows grey until the first ping resolves.
const pillTone = computed(() => {
  switch (status.value) {
    case 'connected':
      return 'green'
    case 'detected':
      return 'yellow'
    default:
      return 'grey'
  }
})
const pillLabel = computed(() => {
  switch (status.value) {
    case 'connected':
      return t('settings.browser.pill.connected')
    case 'detected':
      return t('settings.browser.pill.detected')
    case 'not-detected':
      return t('settings.browser.pill.notDetected')
    default:
      return t('settings.browser.pill.checking')
  }
})

/** Refresh connection state. In the desktop client the extension connects via
 *  Native Messaging (never through this page), so pinging it from inside Electron
 *  always fails — read the real state from the server's live edge sessions
 *  instead. In a normal browser, ping the extension as before. Never throws. */
async function refresh() {
  probing.value = true
  try {
    if (isDesktopClient.value) {
      const resp = await browserPairingApi.listSessions()
      const connected = Array.isArray(resp?.data) && resp.data.length > 0
      status.value = connected ? 'connected' : 'not-detected'
      deviceName.value = null
      return
    }
    const resp = await sendToExtension<PingResponse>({ type: 'ping' })
    status.value = statusFromPing(resp)
    deviceName.value = resp?.deviceName ?? null
  } catch {
    status.value = 'not-detected'
    deviceName.value = null
  } finally {
    probing.value = false
  }
}

async function connect() {
  connecting.value = true
  try {
    const result = await runConnect(
      {
        mintToken: async (name) => {
          const r = await browserPairingApi.mintToken(name)
          return r.data
        },
        revokeToken: (tokenId) => browserPairingApi.revokeToken(tokenId),
        pair: (pat, serverUrl, name) =>
          sendToExtension<PairResponse>({ type: 'pair', pat, serverUrl, deviceName: name }),
        ping: () => sendToExtension<PingResponse>({ type: 'ping' }),
        wsUrl: () => deriveWsUrl(),
      },
      { deviceName: (deviceNameInput.value || defaultName).trim() },
    )

    if (result.ok) {
      localStorage.setItem(PAIRING_TOKEN_KEY, result.tokenId)
      status.value = 'connected'
      deviceName.value = (deviceNameInput.value || defaultName).trim()
      mcToast.success(t('settings.browser.toast.connected'))
    } else {
      // Actionable error per failure reason; PAT already revoked best-effort
      // inside runConnect for the pair/timeout paths.
      const key =
        result.reason === 'mint'
          ? 'settings.browser.toast.mintFailed'
          : result.reason === 'pair'
            ? 'settings.browser.toast.pairFailed'
            : 'settings.browser.toast.timeout'
      mcToast.error(t(key, { error: result.error }))
      // Re-probe so the pill reflects reality (e.g. still 'detected').
      await refresh()
    }
  } finally {
    connecting.value = false
  }
}

async function disconnect() {
  disconnecting.value = true
  try {
    await sendToExtension<PairResponse>({ type: 'unpair' })
    // Best-effort revoke of the remembered PAT; unpair the extension regardless.
    const tokenId = localStorage.getItem(PAIRING_TOKEN_KEY)
    if (tokenId) {
      try {
        await browserPairingApi.revokeToken(tokenId)
      } catch {
        /* contract §3: revoke may be a stub — degrade gracefully */
      }
      localStorage.removeItem(PAIRING_TOKEN_KEY)
    }
    mcToast.success(t('settings.browser.toast.disconnected'))
    await refresh()
  } finally {
    disconnecting.value = false
  }
}

function openExternalPairingPage() {
  window.open(externalPairingUrl.value, '_blank', 'noopener,noreferrer')
}

onMounted(refresh)
</script>

<style scoped>
.settings-section { width: 100%; }
.browser-pairing-section.is-embedded .section-header {
  margin-bottom: 14px;
}

.browser-pairing-section.is-embedded .settings-card {
  border-radius: 8px;
  box-shadow: none;
}

.browser-pairing-section.is-compact .section-title {
  font-size: 18px;
}

.browser-pairing-section.is-compact .settings-card {
  padding: 16px;
}

.browser-pairing-section.is-compact .section-footer-note {
  margin-top: 12px;
}

.section-header { display: flex; flex-direction: column; gap: 6px; margin-bottom: 20px; }
.section-title { margin: 0; font-size: 22px; font-weight: 700; color: var(--mc-text-primary); }
.section-desc { margin: 0; font-size: 14px; color: var(--mc-text-secondary); }

.settings-card {
  background: var(--mc-bg-elevated);
  border: 1px solid var(--mc-border);
  border-radius: 16px;
  padding: 18px;
  box-shadow: 0 8px 24px rgba(124, 63, 30, 0.04);
  width: 100%;
}

.pairing-status-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--mc-border-light);
}

.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  padding: 6px 12px;
  border-radius: 999px;
}
.status-dot { width: 8px; height: 8px; border-radius: 50%; }

.status-pill--grey { background: var(--mc-bg-sunken); color: var(--mc-text-tertiary); }
.status-pill--grey .status-dot { background: var(--mc-text-tertiary); }
.status-pill--yellow { background: rgba(217, 164, 6, 0.12); color: #b7860b; }
.status-pill--yellow .status-dot { background: #d9a406; }
.status-pill--green { background: rgba(34, 167, 93, 0.12); color: #1f9254; }
.status-pill--green .status-dot { background: #22a75d; }

.pairing-body { padding-top: 16px; display: flex; flex-direction: column; gap: 14px; align-items: flex-start; }
.pairing-body--empty { align-items: center; gap: 8px; color: var(--mc-text-secondary); text-align: center; }
.empty-title { margin: 0; font-size: 15px; font-weight: 600; color: var(--mc-text-primary); }
.empty-hint { margin: 0; font-size: 13px; color: var(--mc-text-secondary); line-height: 1.5; }

.detected-device { font-size: 13px; color: var(--mc-text-secondary); }
.connected-hint { margin: 0; font-size: 13px; color: var(--mc-text-secondary); }

.field { display: flex; flex-direction: column; gap: 6px; width: 100%; max-width: 320px; }
.field-label { font-size: 12px; font-weight: 600; color: var(--mc-text-secondary); }
.field-input {
  border: 1px solid var(--mc-border);
  border-radius: 10px;
  padding: 8px 12px;
  font-size: 14px;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
}
.field-input:focus { outline: none; border-color: var(--mc-primary); }

.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border: none;
  border-radius: 10px;
  padding: 9px 18px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  background: var(--mc-primary);
  color: #fff;
  transition: opacity 0.15s;
}
.btn-primary:disabled { opacity: 0.55; cursor: not-allowed; }

.btn-secondary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--mc-border);
  border-radius: 10px;
  padding: 7px 14px;
  font-size: 13px;
  cursor: pointer;
  background: var(--mc-bg-elevated);
  color: var(--mc-text-primary);
  transition: all 0.15s;
}
.btn-secondary:hover:not(:disabled) { background: var(--mc-bg-sunken); }
.btn-secondary:disabled { opacity: 0.55; cursor: not-allowed; }
.btn-danger { color: var(--el-color-danger); border-color: var(--el-color-danger); }

.refresh-btn { flex-shrink: 0; }

.section-footer-note {
  font-size: 12px;
  color: var(--mc-text-tertiary);
  line-height: 1.5;
  margin-top: 16px;
}

@media (max-width: 900px) {
  .pairing-status-row { flex-direction: column; align-items: flex-start; }
}
</style>
