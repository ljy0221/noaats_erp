import { api } from './client'
import type { DashboardResponse } from './types'

/**
 * 대시보드 집계 조회 (api-spec §6.2).
 * since 파라미터가 없으면 백엔드가 기본 집계 윈도우를 적용한다.
 */
export async function getDashboard(since?: string): Promise<DashboardResponse> {
  const res = await api.get<DashboardResponse>('/api/monitor/dashboard', {
    params: { since },
  })
  return res.data
}
