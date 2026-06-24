<template>
  <div class="root">
    <header class="hdr">
      <span class="logo">🦾</span>
      <span class="title">化帆AI 浏览器助手</span>
    </header>

    <!-- 连接状态 -->
    <div class="status" :class="`status--${connState}`" data-test="status-pill">
      <span class="dot"></span>
      <span class="status-text">{{ statusText }}</span>
    </div>

    <!-- 连接方式:网页端 / 客户端 二选一 -->
    <div class="modes">
      <button
        class="mode"
        :class="{ active: connMode === 'web' }"
        data-test="mode-web"
        @click="setMode('web')"
      >
        <span class="mode-name">网页端</span>
        <span class="mode-desc">登录网页点连接即可,免客户端</span>
      </button>
      <button
        class="mode"
        :class="{ active: connMode === 'client' }"
        data-test="mode-client"
        @click="setMode('client')"
      >
        <span class="mode-name">客户端</span>
        <span class="mode-desc">配合桌面客户端使用</span>
      </button>
    </div>

    <!-- 自定义配置(默认折叠) -->
    <button class="link" data-test="toggle-advanced" @click="showAdvanced = !showAdvanced">
      {{ showAdvanced ? '收起自定义配置' : '自定义配置' }}
    </button>

    <section v-if="showAdvanced" class="advanced" data-test="advanced">
      <label class="field">
        <span class="lbl">服务器地址</span>
        <input
          v-model="serverUrl"
          data-test="server-url"
          type="text"
          placeholder="wss://你的域名/api/v1/browser/edge"
        />
      </label>
      <label class="field">
        <span class="lbl">访问令牌</span>
        <input v-model="pat" data-test="pat" type="password" placeholder="mc_..." />
      </label>
      <div class="actions">
        <button class="btn primary" data-test="save-connect" :disabled="!canConnect" @click="saveAndConnect">
          保存并连接
        </button>
        <button class="btn" data-test="disconnect" @click="disconnect">断开</button>
      </div>
      <p class="hint">一般无需手动填写:选「网页端」后到网页点「连接」即可自动完成。</p>
    </section>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { EdgeMessageKind } from '../shared/edge-protocol'
import type { EdgeMessage } from '../shared/edge-protocol'

type ConnState = 'open' | 'closed' | 'connecting'

const connState = ref<ConnState>('closed')
const connMode = ref<'web' | 'client'>('web')
const serverUrl = ref('')
const pat = ref('')
const showAdvanced = ref(false)

const canConnect = computed(() => serverUrl.value.trim() !== '' && pat.value.trim() !== '')
const statusText = computed(() =>
  connState.value === 'open' ? '已连接' : connState.value === 'connecting' ? '连接中…' : '未连接',
)

async function setMode(m: 'web' | 'client') {
  connMode.value = m
  await chrome.runtime.sendMessage({ kind: 'bridge.setMode', mode: m })
  void refreshStatus()
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
    return
  }
  void refreshStatus()
}

async function disconnect() {
  await chrome.runtime.sendMessage({ kind: 'bridge.unpair' })
  connState.value = 'closed'
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
    if (res?.mode === 'web' || res?.mode === 'client') {
      connMode.value = res.mode
    }
  } catch {
    // SW 休眠 / 无监听器 — 保持现状。
  }
}

let statusTimer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  chrome.runtime.onMessage.addListener((req: unknown) => {
    const r = req as { kind?: string; message?: EdgeMessage }
    if (r?.kind !== 'edge.inbound' || !r.message) return
    // 收到 HELLO_ACK 表示直连已建立。
    if (r.message.kind === EdgeMessageKind.HelloAck) {
      connState.value = 'open'
    }
  })
  void refreshStatus()
  statusTimer = setInterval(() => void refreshStatus(), 3000)
})

onUnmounted(() => {
  if (statusTimer !== null) clearInterval(statusTimer)
})
</script>

<style scoped>
.root {
  width: 300px;
  box-sizing: border-box;
  padding: 16px;
  font:
    14px/1.5 -apple-system, 'PingFang SC', 'Microsoft YaHei', system-ui, sans-serif;
  color: #1f2329;
  background: #fff;
}
.hdr {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 14px;
}
.logo {
  font-size: 20px;
}
.title {
  font-size: 15px;
  font-weight: 600;
}
.status {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border-radius: 10px;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 14px;
}
.status .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: currentColor;
  flex: none;
}
.status--open {
  color: #16a34a;
  background: #eafaf0;
}
.status--connecting {
  color: #d97706;
  background: #fff7ea;
}
.status--closed {
  color: #8a9099;
  background: #f3f4f6;
}
.modes {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.mode {
  display: flex;
  flex-direction: column;
  gap: 3px;
  text-align: left;
  padding: 12px 14px;
  border: 1.5px solid #e5e7eb;
  border-radius: 12px;
  background: #fff;
  cursor: pointer;
  transition:
    border-color 0.15s,
    background 0.15s;
}
.mode:hover {
  border-color: #c7d2fe;
}
.mode.active {
  border-color: #2563eb;
  background: #f5f8ff;
}
.mode-name {
  font-size: 14px;
  font-weight: 600;
}
.mode.active .mode-name {
  color: #2563eb;
}
.mode-desc {
  font-size: 12px;
  color: #8a9099;
}
.link {
  display: inline-block;
  margin: 14px 0 0;
  padding: 0;
  border: none;
  background: none;
  color: #2563eb;
  font-size: 12px;
  cursor: pointer;
}
.advanced {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid #f0f1f3;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.lbl {
  font-size: 12px;
  color: #8a9099;
}
.field input {
  padding: 8px 10px;
  border: 1px solid #d9dce1;
  border-radius: 8px;
  font: inherit;
  outline: none;
}
.field input:focus {
  border-color: #2563eb;
}
.actions {
  display: flex;
  gap: 8px;
}
.btn {
  flex: 1;
  padding: 8px;
  border: 1px solid #d9dce1;
  border-radius: 8px;
  background: #fff;
  font: inherit;
  cursor: pointer;
}
.btn.primary {
  background: #2563eb;
  border-color: #2563eb;
  color: #fff;
}
.btn.primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.hint {
  margin: 2px 0 0;
  font-size: 12px;
  color: #a4a9b0;
}
</style>
