import { beforeEach, describe, expect, it } from 'vitest'
import { clearSession } from '../auth'
import mainLayoutSource from '../../views/layout/MainLayout.vue?raw'

const IDENTITY_KEYS = ['token', 'username', 'role', 'userId', 'mc-user-id', 'mc-workspace-id']
const DEVICE_PREFS = ['mc-sidebar-collapsed', 'mateclaw_locale', 'mc-onboarding-done']

describe('clearSession', () => {
  beforeEach(() => localStorage.clear())

  it('removes every per-user identity key so the next user cannot inherit them', () => {
    IDENTITY_KEYS.forEach((k) => localStorage.setItem(k, 'x'))

    clearSession()

    IDENTITY_KEYS.forEach((k) =>
      expect(localStorage.getItem(k), `${k} must be cleared on logout`).toBeNull())
  })

  it('keeps device-level UI preferences (not per-user identity)', () => {
    DEVICE_PREFS.forEach((k) => localStorage.setItem(k, 'keep'))

    clearSession()

    DEVICE_PREFS.forEach((k) => expect(localStorage.getItem(k)).toBe('keep'))
  })

  it('is wired into the in-page logout, which also resets the Pinia store (router.push does not reload)', () => {
    expect(mainLayoutSource).toContain('clearSession()')
    expect(mainLayoutSource).toContain('workspaceStore.reset()')
  })
})
