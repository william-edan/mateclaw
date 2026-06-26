<template>
  <div class="login-page">
    <div class="login-center">
      <div class="login-logo">
        <img src="/logo/mateclaw_logo_s.png" alt="化帆AI" class="logo-image" />
        <h1 class="logo-title">化帆<span class="logo-title-highlight">AI</span></h1>
      </div>

      <form class="login-form" @submit.prevent="handleSubmit">
        <div class="auth-mode-tabs" role="tablist" :aria-label="t('login.modeLabel')">
          <button
            type="button"
            class="auth-mode-tab"
            :class="{ active: mode === 'login' }"
            role="tab"
            :aria-selected="mode === 'login'"
            @click="switchMode('login')"
          >
            {{ t('login.signIn') }}
          </button>
          <button
            type="button"
            class="auth-mode-tab"
            :class="{ active: mode === 'register' }"
            role="tab"
            :aria-selected="mode === 'register'"
            @click="switchMode('register')"
          >
            {{ t('login.register') }}
          </button>
        </div>

        <div v-if="mode === 'login'" class="input-wrap">
          <input
            v-model="form.username"
            type="text"
            class="form-input"
            :placeholder="t('login.placeholders.username')"
            :aria-label="t('login.fields.username')"
            autocomplete="username"
            required
          />
        </div>

        <div v-else class="input-wrap">
          <input
            v-model="registerForm.phone"
            type="tel"
            class="form-input"
            :placeholder="t('login.placeholders.phone')"
            :aria-label="t('login.fields.phone')"
            autocomplete="tel"
            required
          />
        </div>

        <div v-if="mode === 'register'" class="input-wrap code-row">
          <input
            v-model="registerForm.code"
            type="text"
            inputmode="numeric"
            class="form-input"
            :placeholder="t('login.placeholders.code')"
            :aria-label="t('login.fields.code')"
            autocomplete="one-time-code"
            required
          />
          <button
            type="button"
            class="get-code-btn"
            :disabled="codeCountdown > 0 || sendingCode"
            @click="sendCode"
          >
            {{ codeCountdown > 0 ? t('login.resendCountdown', { n: codeCountdown }) : t('login.getCode') }}
          </button>
        </div>

        <div class="input-wrap">
          <input
            v-model="activePassword"
            :type="showPassword ? 'text' : 'password'"
            class="form-input form-input--has-eye"
            :placeholder="t('login.placeholders.password')"
            :aria-label="t('login.fields.password')"
            autocomplete="current-password"
            required
          />
          <button type="button" class="eye-btn" @click="showPassword = !showPassword">
            <svg v-if="!showPassword" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/>
              <circle cx="12" cy="12" r="3"/>
            </svg>
            <svg v-else width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24"/>
              <line x1="1" y1="1" x2="23" y2="23"/>
            </svg>
          </button>
        </div>

        <template v-if="mode === 'register'">
          <div class="input-wrap">
            <input
              v-model="registerForm.confirmPassword"
              :type="showPassword ? 'text' : 'password'"
              class="form-input"
              :placeholder="t('login.placeholders.confirmPassword')"
              :aria-label="t('login.fields.confirmPassword')"
              autocomplete="new-password"
              required
            />
          </div>
          <div class="input-wrap">
            <input
              v-model="registerForm.nickname"
              type="text"
              class="form-input"
              :placeholder="t('login.placeholders.nickname')"
              :aria-label="t('login.fields.nickname')"
              autocomplete="nickname"
            />
          </div>
        </template>

        <div v-if="errorMsg" class="error-msg">{{ errorMsg }}</div>

        <button type="submit" class="login-btn" :disabled="loading">
          <span v-if="!loading">{{ mode === 'login' ? t('login.signIn') : t('login.register') }}</span>
          <span v-else class="loading-dots">
            <span></span><span></span><span></span>
          </span>
        </button>
      </form>

      <p v-if="t('login.hint')" class="login-hint" v-html="t('login.hint')"></p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onUnmounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { authApi } from '@/api/index'
import { useAccountStore } from '@/stores/useAccountStore'
import { useWorkspaceStore } from '@/stores/useWorkspaceStore'
import type { LoginResponse } from '@/types'

const router = useRouter()
const { t } = useI18n()
const accountStore = useAccountStore()
const workspaceStore = useWorkspaceStore()
const loading = ref(false)
const showPassword = ref(false)
const errorMsg = ref('')
const mode = ref<'login' | 'register'>('login')
const form = reactive({ username: '', password: '' })
const registerForm = reactive({ phone: '', password: '', confirmPassword: '', nickname: '', code: '' })
const codeCountdown = ref(0)
const sendingCode = ref(false)
let countdownTimer: ReturnType<typeof setInterval> | null = null

const activePassword = computed({
  get: () => mode.value === 'login' ? form.password : registerForm.password,
  set: (value: string) => {
    if (mode.value === 'login') {
      form.password = value
    } else {
      registerForm.password = value
    }
  },
})

function switchMode(nextMode: 'login' | 'register') {
  mode.value = nextMode
  errorMsg.value = ''
}

async function sendCode() {
  if (codeCountdown.value > 0 || sendingCode.value) return
  const phone = registerForm.phone.trim()
  if (!phone) {
    errorMsg.value = t('login.phoneRequired')
    return
  }
  sendingCode.value = true
  errorMsg.value = ''
  try {
    await authApi.sendRegisterCode({ phone })
    codeCountdown.value = 60
    countdownTimer = setInterval(() => {
      codeCountdown.value -= 1
      if (codeCountdown.value <= 0 && countdownTimer) {
        clearInterval(countdownTimer)
        countdownTimer = null
      }
    }, 1000)
  } catch (e: any) {
    errorMsg.value = e?.message || t('login.registerFailed')
  } finally {
    sendingCode.value = false
  }
}

onUnmounted(() => {
  if (countdownTimer) clearInterval(countdownTimer)
})

async function finishAuth(data: LoginResponse, fallbackUsername: string) {
  localStorage.setItem('token', data.token)
  localStorage.setItem('userId', String(data.id || '1'))
  localStorage.setItem('username', data.username || fallbackUsername)
  localStorage.setItem('role', data.role || 'user')
  accountStore.applyStatus(data)
  if (data.currentWorkspaceId != null) {
    localStorage.setItem('mc-workspace-id', String(data.currentWorkspaceId))
    workspaceStore.currentWorkspaceId = String(data.currentWorkspaceId)
  }
  // Resolve capabilities before deciding the landing route. Users with chat
  // access enter the recommendation-oriented home page; restricted accounts
  // still fall back to chat through the router guard.
  try {
    await workspaceStore.fetchWorkspaces()
  } catch {
    /* default-deny is fine; router guard will still steer */
  }
  const target = workspaceStore.can('chat') ? '/home' : '/chat'
  router.push(target)
}

async function handleSubmit() {
  if (mode.value === 'login') {
    await handleLogin()
  } else {
    await handleRegister()
  }
}

async function handleLogin() {
  if (!form.username || !form.password) return
  loading.value = true
  errorMsg.value = ''
  try {
    const res = await authApi.login(form)
    const data = (res.data || res) as LoginResponse
    await finishAuth(data, form.username)
  } catch (e: any) {
    errorMsg.value = e?.message || t('login.failed')
  } finally {
    loading.value = false
  }
}

async function handleRegister() {
  if (!registerForm.phone || !registerForm.password || !registerForm.confirmPassword) return
  if (!registerForm.code.trim()) {
    errorMsg.value = t('login.codeRequired')
    return
  }
  if (registerForm.password.length < 6) {
    errorMsg.value = t('login.passwordTooShort')
    return
  }
  if (registerForm.password !== registerForm.confirmPassword) {
    errorMsg.value = t('login.passwordMismatch')
    return
  }
  loading.value = true
  errorMsg.value = ''
  try {
    const payload = {
      phone: registerForm.phone,
      password: registerForm.password,
      code: registerForm.code.trim(),
      nickname: registerForm.nickname || undefined,
    }
    const res = await authApi.register(payload)
    const data = (res.data || res) as LoginResponse
    await finishAuth(data, registerForm.phone)
  } catch (e: any) {
    errorMsg.value = e?.message || t('login.registerFailed')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(160deg, #FAF5F0 0%, #F5EDE5 100%);
  padding: 24px;
}

:root.dark .login-page,
html.dark .login-page {
  background: linear-gradient(160deg, var(--mc-bg) 0%, #1A1210 100%);
}

.login-center {
  width: 100%;
  max-width: 380px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 40px;
  animation: fadeUp 0.6s ease-out both;
}

/* Logo */
.login-logo {
  text-align: center;
}

.logo-image {
  display: block;
  margin: 0 auto 16px;
  width: 100px;
  height: 100px;
  object-fit: contain;
  filter: drop-shadow(0 6px 20px rgba(217, 119, 87, 0.3));
  animation: breathe 3.5s ease-in-out infinite;
}

.logo-title {
  font-size: 36px;
  font-weight: 800;
  color: var(--mc-text-primary);
  margin: 0;
  letter-spacing: -0.04em;
}

.logo-title-highlight {
  color: var(--mc-primary);
}

/* Form */
.login-form {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.auth-mode-tabs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 4px;
  padding: 4px;
  background: var(--mc-bg-sunken);
  border: 1px solid var(--mc-border);
  border-radius: 12px;
}

.auth-mode-tab {
  height: 36px;
  border: 0;
  border-radius: 8px;
  color: var(--mc-text-secondary);
  background: transparent;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: color 0.18s, background 0.18s, box-shadow 0.18s;
}

.auth-mode-tab.active {
  color: var(--mc-text-primary);
  background: var(--mc-bg-elevated);
  box-shadow: 0 2px 8px rgba(39, 31, 27, 0.08);
}

.auth-mode-tab:focus-visible {
  outline: 2px solid var(--mc-primary);
  outline-offset: 2px;
}

.input-wrap {
  position: relative;
  display: flex;
  align-items: center;
}

.form-input {
  width: 100%;
  padding: 14px 16px;
  border: 1.5px solid var(--mc-border);
  border-radius: 12px;
  font-size: 15px;
  color: var(--mc-text-primary);
  background: var(--mc-bg-sunken);
  outline: none;
  transition: border-color 0.2s, box-shadow 0.2s, background 0.2s;
}

.form-input--has-eye {
  padding-right: 44px;
}

.form-input:focus {
  border-color: var(--mc-primary);
  background: var(--mc-bg-elevated);
  box-shadow: 0 0 0 3px rgba(217, 119, 87, 0.08);
}

.eye-btn {
  position: absolute;
  right: 12px;
  width: 28px;
  height: 28px;
  border: none;
  background: none;
  cursor: pointer;
  color: var(--mc-text-tertiary);
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
}

.eye-btn:hover {
  color: var(--mc-primary);
}

/* Error */
.error-msg {
  padding: 10px 14px;
  background: var(--mc-danger-bg);
  border: 1px solid var(--mc-danger);
  border-radius: 10px;
  font-size: 13px;
  color: var(--mc-danger);
}

/* Button */
.login-btn {
  width: 100%;
  padding: 12px;
  background: linear-gradient(135deg, var(--mc-primary), var(--mc-primary-hover));
  color: white;
  border: none;
  border-radius: 12px;
  font-size: 15px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s;
  margin-top: 4px;
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.login-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  box-shadow: 0 8px 20px rgba(217, 119, 87, 0.3);
}

.login-btn:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

/* Loading */
.loading-dots {
  display: flex;
  gap: 5px;
  align-items: center;
}

.loading-dots span {
  width: 6px;
  height: 6px;
  background: white;
  border-radius: 50%;
  animation: bounce 1.2s infinite;
}

.loading-dots span:nth-child(2) { animation-delay: 0.2s; }
.loading-dots span:nth-child(3) { animation-delay: 0.4s; }

@keyframes bounce {
  0%, 60%, 100% { transform: translateY(0); }
  30% { transform: translateY(-5px); }
}

/* Hint */
.login-hint {
  text-align: center;
  font-size: 12px;
  color: var(--mc-text-tertiary);
  margin: 0;
  opacity: 0.7;
}

.login-hint :deep(code) {
  background: var(--mc-inline-code-bg);
  padding: 1px 6px;
  border-radius: 4px;
  color: var(--mc-inline-code-color);
  font-size: 12px;
}

/* Breathing animation */
@keyframes breathe {
  0%, 100% {
    transform: scale(1);
    filter: drop-shadow(0 6px 20px rgba(217, 119, 87, 0.3));
  }
  50% {
    transform: scale(1.06);
    filter: drop-shadow(0 8px 28px rgba(217, 119, 87, 0.45));
  }
}

/* Entrance animation */
@keyframes fadeUp {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

/* Mobile */
@media (max-width: 480px) {
  .login-page {
    padding: 16px;
  }
}

/* 同时带 .input-wrap，用更高特异性覆盖其 align-items: center，
   让获取验证码按钮拉伸到与输入框等高。 */
.input-wrap.code-row {
  display: flex;
  align-items: stretch;
  gap: 8px;
}

.code-row .form-input {
  flex: 1;
}

.get-code-btn {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 16px;
  border: 1.5px solid var(--mc-border);
  border-radius: 12px;
  background: var(--mc-bg-sunken);
  color: var(--mc-primary);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  white-space: nowrap;
  transition: border-color 0.2s, color 0.2s, opacity 0.2s;
}

.get-code-btn:hover:not(:disabled) {
  border-color: var(--mc-primary);
}

.get-code-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
</style>
