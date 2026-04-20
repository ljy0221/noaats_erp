import { api } from './client'
import type {
  DeltaResponse,
  ExecuteResponse,
  ExecutionListParams,
  ExecutionLogResponse,
  Page,
} from './types'

/**
 * 실행 이력 리스트 (api-spec §5.1).
 * 정렬 기본 startedAt,desc.
 */
export async function listExecutions(
  params: ExecutionListParams = {},
): Promise<Page<ExecutionLogResponse>> {
  const res = await api.get<Page<ExecutionLogResponse>>('/api/executions', {
    params: {
      page: params.page ?? 0,
      size: params.size ?? 20,
      sort: params.sort ?? 'startedAt,desc',
      status: params.status,
      interfaceConfigId: params.interfaceConfigId,
    },
  })
  return res.data
}

/** 실행 로그 단건 조회 (api-spec §5.2). */
export async function getExecution(id: number): Promise<ExecutionLogResponse> {
  const res = await api.get<ExecutionLogResponse>(`/api/executions/${id}`)
  return res.data
}

/**
 * 실패 로그 재처리 (api-spec §5.3).
 * 백엔드는 ExecutionTriggerResponse를 반환 — executeInterface와 동일 DTO.
 * 프런트는 기존 ExecuteResponse 타입을 재사용하여 일관성을 유지한다.
 */
export async function retryExecution(id: number): Promise<ExecuteResponse> {
  const res = await api.post<ExecuteResponse>(`/api/executions/${id}/retry`)
  return res.data
}

/**
 * 델타 조회 (api-spec §5.4) — SSE 재연결 갭 보충용.
 * since와 cursor는 상호 배타적: SSE 재연결 후 최초 since 제공, 이후 truncated 시 cursor 페이징.
 */
export async function fetchDelta(
  since?: string,
  cursor?: string,
  limit = 500,
): Promise<DeltaResponse> {
  const res = await api.get<DeltaResponse>('/api/executions/delta', {
    params: { since, cursor, limit },
  })
  return res.data
}
