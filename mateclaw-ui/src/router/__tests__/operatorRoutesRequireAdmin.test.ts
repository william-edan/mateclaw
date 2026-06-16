import { describe, expect, it } from 'vitest'
import routerSource from '../index.ts?raw'
import mainLayoutSource from '../../views/layout/MainLayout.vue?raw'

/**
 * Pages whose write endpoints were tightened to @RequireGlobalAdmin on the
 * backend (system settings, feature flags, plugins, tool/file guard) must be
 * gated to global admins on the client too — otherwise a workspace admin is
 * shown the page and only discovers it is forbidden when "save" returns 403.
 * The router guard enforces `meta.requireAdmin` (redirects non-global-admins).
 */
describe('operator-only pages require global admin', () => {
  const OPERATOR_ROUTE_METAS = [
    "meta: { title: 'Plugins', requireAdmin: true }",
    "meta: { title: 'Settings - System', requireAdmin: true }",
    "meta: { title: 'Settings - Feature Flags', requireAdmin: true }",
    "meta: { title: 'Security - Tool Guard', requireAdmin: true }",
    "meta: { title: 'Security - File Guard', requireAdmin: true }",
  ]

  it.each(OPERATOR_ROUTE_METAS)('gates route meta %s', (meta) => {
    expect(routerSource).toContain(meta)
  })

  it('hides the top-level Plugins nav entry from non-global-admins', () => {
    expect(mainLayoutSource).toContain("path: '/plugins'")
    expect(mainLayoutSource).toMatch(/path: '\/plugins'[\s\S]{0,200}?globalAdmin: true/)
  })

  it('does NOT gate workspace-level pages — members / workspaces stay workspace-admin', () => {
    expect(routerSource).toContain(
      "meta: { title: 'Settings - Workspaces', requiredCapability: 'manage:settings' }",
    )
    expect(routerSource).toContain(
      "meta: { title: 'Settings - Members', requiredCapability: 'manage:settings' }",
    )
  })
})
