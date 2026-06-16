import { describe, expect, it } from 'vitest'
import leadPage from '../LeadAcquisition/index.vue?raw'
import api from '../../api/index.ts?raw'
import livePanel from '../../components/lead/LeadRunLivePanel.vue?raw'
import runResult from '../../components/lead/DouyinLeadRunResult.vue?raw'

describe('lead acquisition realtime execution contract', () => {
  const combined = [leadPage, api, livePanel].join('\n')

  it('mounts a dedicated live run panel after starting acquisition', () => {
    expect(leadPage).toContain('LeadRunLivePanel')
    expect(leadPage).toContain('leadAcquisitionApi.startDouyinRun(payload)')
  })

  it('has an SSE client for run event streaming with resume support', () => {
    expect(combined).toContain('EventSource')
    expect(combined).toContain('/lead-acquisition/runs/')
    expect(combined).toContain('/events/stream')
    expect(combined).toContain('afterEventId')
    expect(api).toContain('listDouyinRuns')
    expect(api).toContain('listDouyinLeads')
    expect(api).toContain('getDouyinStats')
    expect(api).toContain('/lead-acquisition/douyin/runs')
    expect(api).toContain('/lead-acquisition/douyin/leads')
    expect(api).toContain('/lead-acquisition/douyin/stats')
  })

  it('falls back to polling and tells the user when realtime reconnects', () => {
    expect(combined).toContain('leadAcquisitionApi.getDouyinRun')
    expect(combined).toContain('定时刷新')
    expect(combined).toContain('setInterval')
    // 多次重连失败后稳定降级到定时刷新，不再无限重试、不再焦虑提示
    expect(livePanel).toContain('MAX_RECONNECT_ATTEMPTS')
    expect(livePanel).toContain('pollingMode')
  })

  it('renders the live events needed by the execution timeline', () => {
    expect(combined).toContain('lead.video.started')
    expect(combined).toContain('lead.comments.collected')
    expect(combined).toContain('lead.comment.matched')
    expect(combined).toContain('lead.engagement.completed')
    expect(combined).toContain('查看完整时间线')
    expect(combined).toContain('timeline-scroll')
    expect(runResult).toContain('展开评论明细')
    expect(runResult).toContain('panel-scroll-body')
    expect(combined).toContain('评论匹配')
    expect(combined).toContain('触达记录')
    expect(combined).toContain('最近任务')
    expect(combined).toContain('线索池')
    expect(combined).toContain('统计看板')
    expect(combined).toContain('loadLeadStats()')
    expect(combined).toContain('loadLeadPool()')
  })

  it('keeps the stop button in a stopping state until the run is terminal', () => {
    // 取消是协作式的：后端 interrupt 执行线程后仍需等当前步骤安全结束，
    // 因此前端把“停止中”持久化到任务真正进入 terminal，而不是 cancel 接口一返回就复位。
    expect(livePanel).toContain('cancelRequested')
    expect(livePanel).toContain('停止中…')
    expect(livePanel).toContain('正在停止')
    expect(livePanel).toContain('startCancelWatch')
    expect(livePanel).toContain('watch(terminal')
  })
})
