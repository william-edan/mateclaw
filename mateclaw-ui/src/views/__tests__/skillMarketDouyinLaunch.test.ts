import { describe, expect, it } from 'vitest'
import skillMarket from '../SkillMarket.vue?raw'
import api from '../../api/index.ts?raw'

describe('SkillMarket Douyin lead launch', () => {
  it('submits Douyin lead parameters to the dedicated endpoint', () => {
    expect(api).toContain("http.post<DouyinLeadAcquisitionRunResponse>('/lead-acquisition/douyin/runs'")
    expect(skillMarket).toContain('leadAcquisitionApi.startDouyinRun(payload)')
  })

  it('is scoped to the Douyin lead-acquisition skill card', () => {
    expect(skillMarket).toContain('function isDouyinLeadAcquisitionSkill(skill: Skill)')
    expect(skillMarket).toContain('douyin-lead-acquisition')
    expect(skillMarket).toContain('douyin.lead_acquisition')
    expect(skillMarket).toContain('v-if="isDouyinLeadAcquisitionSkill(skill)"')
  })

  it('uses the requested launch defaults and exposes returned ids', () => {
    expect(skillMarket).toContain("sort: 'comprehensive'")
    expect(skillMarket).toContain('videoLimit: 50')
    expect(skillMarket).toContain('preset.dmDraft')
    expect(skillMarket).toContain('engage: true')
    expect(skillMarket).toContain('sendDm: false')
    expect(skillMarket).toContain('matchTarget')
    expect(skillMarket).toContain('matchPresetBadExperience')
    expect(skillMarket).toContain('matchDescription')
    expect(skillMarket).toContain('matchExamples')
    expect(skillMarket).toContain('douyinLaunchResult.runId')
    expect(skillMarket).toContain('douyinLaunchResult.taskId')
  })

  it('opens an existing run-detail route before falling back to modal state', () => {
    expect(skillMarket).toContain('openDouyinRunRouteIfAvailable')
    expect(skillMarket).toContain("'DouyinLeadRunDetail'")
    expect(skillMarket).toContain('router.hasRoute(name)')
  })
})
