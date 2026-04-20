import { api } from './client'
import type {
  ExecuteResponse,
  InterfaceConfigRequest,
  InterfaceConfigResponse,
  InterfaceListParams,
  Page,
} from './types'

export async function listInterfaces(
  params: InterfaceListParams = {},
): Promise<Page<InterfaceConfigResponse>> {
  const res = await api.get<Page<InterfaceConfigResponse>>('/api/interfaces', {
    params: {
      page: params.page ?? 0,
      size: params.size ?? 20,
      sort: params.sort ?? 'createdAt,desc',
      status: params.status,
      protocol: params.protocol,
      name: params.name,
    },
  })
  return res.data
}

export async function getInterface(id: number): Promise<InterfaceConfigResponse> {
  const res = await api.get<InterfaceConfigResponse>(`/api/interfaces/${id}`)
  return res.data
}

export async function createInterface(
  payload: InterfaceConfigRequest,
): Promise<InterfaceConfigResponse> {
  const res = await api.post<InterfaceConfigResponse>('/api/interfaces', payload)
  return res.data
}

export async function updateInterface(
  id: number,
  payload: InterfaceConfigRequest,
): Promise<InterfaceConfigResponse> {
  const res = await api.patch<InterfaceConfigResponse>(`/api/interfaces/${id}`, payload)
  return res.data
}

export async function executeInterface(id: number): Promise<ExecuteResponse> {
  const res = await api.post<ExecuteResponse>(`/api/interfaces/${id}/execute`)
  return res.data
}
