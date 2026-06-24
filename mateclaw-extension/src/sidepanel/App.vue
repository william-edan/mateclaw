<template>
  <div class="root">
    <h1>MateClaw Browser Agent</h1>

    <!-- Phase 3.1: manual pairing fallback. The admin UI normally pushes these
         via externally_connectable; this panel lets a user pair by hand. -->
    <section class="settings" data-test="settings">
      <div class="row">
        <span class="label">Status</span>
        <span class="pill" :class="statusClass" data-test="status-pill">{{ statusLabel }}</span>
      </div>
      <div class="row">
        <span class="label">Mode</span>
        <span class="seg">
          <button class="seg-btn" :class="{ active: connMode === 'auto' }" data-test="mode-auto" @click="setMode('auto')">自动</button>
          <button class="seg-btn" :class="{ active: connMode === 'web' }" data-test="mode-web" @click="setMode('web')">网页端</button>
          <button class="seg-btn" :class="{ active: connMode === 'client' }" data-test="mode-client" @click="setMode('client')">客户端</button>
        </span>
      </div>
      <label class="field">
        <span class="label">Server URL</span>
        <input
          v-model="serverUrl"
          data-test="server-url"
          type="text"
          placeholder="ws://localhost:18088/api/v1/browser/edge"
        />
      </label>
      <label class="field">
        <span class="label">PAT</span>
        <input v-model="pat" data-test="pat" type="password" placeholder="mc_..." />
      </label>
      <div class="actions">
        <button data-test="save-connect" :disabled="!canConnect" @click="saveAndConnect">
          Save &amp; Connect
        </button>
        <button data-test="disconnect" @click="disconnect">Disconnect</button>
      </div>
    </section>

    <button data-test="ping" @click="ping">Send ping</button>
    <ul class="log">
      <li v-for="(entry, i) in log" :key="i">
        <strong>{{ entry.kind }}</strong> {{ entry.summary }}
      </li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { EdgeMessageKind, makeEdgeMessage } from '../shared/edge-protocol'
import type { EdgeMessage } from '../shared/edge-protocol'

interface LogEntry {
  kind: string
  summary: string
}

type ConnState = 'open' | 'closed' | 'connecting'

const log = ref<LogEntry[]>([])
const serverUrl = ref('ws://localhost:18088/api/v1/browser/edge')
const pat = ref('')
const connState = ref<ConnState>('closed')
const connMode = ref<'auto' | 'web' | 'client'>('auto')

const canConnect = computed(() => serverUrl.value.trim() !== '' && pat.value.trim() !== '')
const statusLabel = computed(() =>
  connState.value === 'open'
    ? 'connected'
    : connState.value === 'connecting'
      ? 'connecting'
      : 'disconnected',
)
const statusClass = computed(() => `pill--${connState.value}`)

function append(e: LogEntry) {
  log.value = [...log.value, e].slice(-100)
}

async function ping() {
  const msg = makeEdgeMessage({
    kind: EdgeMessageKind.Ping,
    payload: { echo: `manual-${Date.now()}` },
  })
  await chrome.runtime.sendMessage({ kind: 'edge.outbound', message: msg })
  append({ kind: 'ping', summary: `echo=${String(msg.payload?.['echo'] ?? '')}` })
}

async function saveAndConnect() {
  connState.value = 'connecting'
  const res = (await chrome.runtime.sendMessage({
    kind: 'bridge.pair',
    serverUrl: serverUrl.value.trim(),
    pat: pat.value.trim(),
  })) as { ok?: boolean; error?: string } | undefined
  if (!res?.ok) {
    connState.value = 'closed'
    append({ kind: 'pair', summary: `failed: ${res?.error ?? 'unknown'}` })
    return
  }
  append({ kind: 'pair', summary: 'stored + connect initiated' })
  // Connect is async on the SW side; poll status until it settles.
  void refreshStatus()
}

async function disconnect() {
  await chrome.runtime.sendMessage({ kind: 'bridge.unpair' })
  connState.value = 'closed'
  append({ kind: 'unpair', summary: 'disconnected' })
}

async function setMode(m: 'auto' | 'web' | 'client') {
  connMode.value = m
  await chrome.runtime.sendMessage({ kind: 'bridge.setMode', mode: m })
  append({ kind: 'mode', summary: m })
  void refreshStatus()
}

async function refreshStatus() {
  try {
    const res = (await chrome.runtime.sendMessage({ kind: 'bridge.status' })) as
      | { connected?: boolean; serverUrl?: string | null; mode?: string }
      | undefined
    if (res?.connected) {
      connState.value = 'open'
    } else if (connState.value !== 'connecting') {
      connState.value = 'closed'
    }
    if (typeof res?.serverUrl === 'string' && res.serverUrl) {
      serverUrl.value = res.serverUrl
    }
    if (res?.mode === 'auto' || res?.mode === 'web' || res?.mode === 'client') {
      connMode.value = res.mode
    }
  } catch {
    // SW asleep / no listener — leave state as-is.
  }
}

let statusTimer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  chrome.runtime.onMessage.addListener((req: unknown) => {
    const r = req as { kind?: string; message?: EdgeMessage }
    if (r?.kind !== 'edge.inbound' || !r.message) return
    const m = r.message
    append({ kind: m.kind, summary: JSON.stringify(m.payload ?? {}) })
    // A HELLO_ACK means the direct transport is live.
    if (m.kind === EdgeMessageKind.HelloAck) {
      connState.value = 'open'
    }
  })
  void refreshStatus()
  statusTimer = setInterval(() => void refreshStatus(), 5000)
})

onUnmounted(() => {
  if (statusTimer !== null) clearInterval(statusTimer)
})
</script>

<style scoped>
.root {
  font: 14px/1.4 system-ui;
  padding: 12px;
}
.settings {
  border: 1px solid #e2e2e2;
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.label {
  font-size: 12px;
  color: #666;
}
.field input {
  padding: 6px 8px;
  border: 1px solid #ccc;
  border-radius: 6px;
  font: inherit;
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.actions {
  display: flex;
  gap: 8px;
}
.actions button {
  flex: 1;
  padding: 6px 8px;
}
.pill {
  font-size: 12px;
  padding: 2px 10px;
  border-radius: 999px;
  color: #fff;
  background: #999;
}
.pill--open {
  background: #2e7d32;
}
.pill--connecting {
  background: #ed6c02;
}
.pill--closed {
  background: #9e9e9e;
}
.seg {
  display: inline-flex;
  gap: 4px;
}
.seg-btn {
  padding: 2px 10px;
  border: 1px solid #ccc;
  border-radius: 999px;
  background: #fff;
  font: inherit;
  cursor: pointer;
}
.seg-btn.active {
  background: #2e7d32;
  border-color: #2e7d32;
  color: #fff;
}
.log {
  list-style: none;
  padding: 0;
}
.log li {
  padding: 4px 0;
  border-bottom: 1px solid #eee;
}
</style>
