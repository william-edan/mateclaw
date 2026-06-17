import { describe, expect, it } from 'vitest'
import settingsLayout from '../Layout.vue?raw'
import workspacesPage from '../../Security/Workspaces/index.vue?raw'

/**
 * Regression guards for two product rules on the settings surface:
 *  1. 「系统设置」是全局(system-wide)配置、写入仅全局 admin。普通注册用户(工作区 owner,
 *     非全局 admin)不应在设置侧栏看到该项 —— 否则点击会被路由守卫弹回 /chat。
 *  2. 工作区管理页不再提供「新建工作区 / 删除工作区」(普通用户每人一个自动创建的工作区)。
 */
describe('系统设置菜单仅全局 admin 可见', () => {
  it('Layout 给 system 项打 requiresGlobalAdmin 标记', () => {
    // requiresGlobalAdmin 仅用于 system 这一项,故出现即代表系统设置被标记为全局 admin 专属。
    expect(settingsLayout).toContain("id: 'system'")
    expect(settingsLayout).toContain('requiresGlobalAdmin: true')
  })

  it('Layout 用 isGlobalAdmin 过滤侧栏项', () => {
    expect(settingsLayout).toContain('isGlobalAdmin')
    expect(settingsLayout).toContain('visibleSections')
    // 模板渲染过滤后的列表,而非原始 sections
    expect(settingsLayout).toContain('section in visibleSections')
  })
})

describe('工作区管理页移除新建/删除工作区', () => {
  it('无新建工作区入口', () => {
    expect(workspacesPage).not.toContain('openCreateDialog')
    expect(workspacesPage).not.toContain('workspaceTeamApi.create')
  })

  it('无删除工作区入口', () => {
    expect(workspacesPage).not.toContain('confirmDelete')
    expect(workspacesPage).not.toContain('deleteWorkspace')
    expect(workspacesPage).not.toContain('workspaceTeamApi.delete')
  })

  it('保留编辑工作区能力(回归保护:不要误删 edit)', () => {
    expect(workspacesPage).toContain('openEditDialog')
    expect(workspacesPage).toContain('workspaceTeamApi.update')
  })
})
