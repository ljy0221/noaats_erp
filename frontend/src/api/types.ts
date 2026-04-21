export type ProtocolType = 'REST' | 'SOAP' | 'MQ' | 'BATCH' | 'SFTP'
export type ScheduleType = 'MANUAL' | 'CRON'
export type InterfaceStatus = 'ACTIVE' | 'INACTIVE'
export type ExecutionStatus = 'RUNNING' | 'SUCCESS' | 'FAILED' | 'RECOVERED'
export type TriggerType = 'MANUAL' | 'SCHEDULER' | 'RETRY'

export const PROTOCOL_TYPES: ProtocolType[] = ['REST', 'SOAP', 'MQ', 'BATCH', 'SFTP']
export const SCHEDULE_TYPES: ScheduleType[] = ['MANUAL', 'CRON']
export const INTERFACE_STATUSES: InterfaceStatus[] = ['ACTIVE', 'INACTIVE']

export type ErrorCode =
  | 'VALIDATION_FAILED'
  | 'CONFIG_JSON_INVALID'
  | 'INTERFACE_INACTIVE'
  | 'QUERY_PARAM_CONFLICT'
  | 'UNAUTHENTICATED'
  | 'FORBIDDEN'
  | 'RETRY_FORBIDDEN_ACTOR'
  | 'INTERFACE_NOT_FOUND'
  | 'EXECUTION_NOT_FOUND'
  | 'DUPLICATE_NAME'
  | 'DUPLICATE_RUNNING'
  | 'OPTIMISTIC_LOCK_CONFLICT'
  | 'RETRY_CHAIN_CONFLICT'
  | 'RETRY_LIMIT_EXCEEDED'
  | 'RETRY_NOT_LEAF'
  | 'RETRY_TRUNCATED_BLOCKED'
  | 'PAYLOAD_TOO_LARGE'
  | 'TOO_MANY_CONNECTIONS'
  | 'DELTA_RATE_LIMITED'
  | 'INTERNAL_ERROR'
  | 'NOT_IMPLEMENTED'
  | 'DELTA_SINCE_TOO_OLD'

export interface ApiResponse<T> {
  success: boolean
  data: T | null
  message: string | null
  timestamp: string
}

export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface InterfaceConfigResponse {
  id: number
  name: string
  description: string | null
  protocol: ProtocolType
  endpoint: string
  httpMethod: string | null
  configJson: Record<string, unknown>
  scheduleType: ScheduleType
  cronExpression: string | null
  timeoutSeconds: number
  maxRetryCount: number
  status: InterfaceStatus
  version: number
  createdAt: string
  updatedAt: string
}

export interface InterfaceConfigRequest {
  name: string
  description?: string | null
  protocol: ProtocolType
  endpoint: string
  httpMethod?: string | null
  configJson: Record<string, unknown>
  scheduleType: ScheduleType
  cronExpression?: string | null
  timeoutSeconds: number
  maxRetryCount: number
  version?: number
}

export interface InterfaceListParams {
  page?: number
  size?: number
  sort?: string
  status?: InterfaceStatus
  protocol?: ProtocolType
  name?: string
}

export interface OptimisticLockServerSnapshot {
  name: string
  description: string | null
  endpoint: string
  configJson: Record<string, unknown>
  status: InterfaceStatus
  updatedAt: string
}

export interface OptimisticLockConflictData {
  errorCode: 'OPTIMISTIC_LOCK_CONFLICT'
  submittedVersion: number
  currentVersion: number
  serverSnapshot: OptimisticLockServerSnapshot
  warnings?: string[]
}

export interface ApiErrorData {
  errorCode: ErrorCode
  [key: string]: unknown
}

export class ApiError extends Error {
  readonly status: number
  readonly code: ErrorCode | null
  readonly data: ApiErrorData | null

  constructor(
    status: number,
    code: ErrorCode | null,
    data: ApiErrorData | null,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.data = data
  }
}

export interface ExecuteResponse {
  logId: number
  status: ExecutionStatus
  interfaceConfigId: number
  triggeredBy: TriggerType
  startedAt: string
}

// ============================================================
// Day 6 — Execution / Delta / Dashboard 타입 (append-only)
// ============================================================

/**
 * 실행 로그 응답 — 리스트/델타/상세 공통.
 * 백엔드 ExecutionLogResponse 필드에 1:1 매핑.
 */
export interface ExecutionLogResponse {
  id: number
  interfaceConfigId: number | null
  interfaceName: string
  status: ExecutionStatus
  triggeredBy: TriggerType
  startedAt: string
  finishedAt: string | null
  durationMs: number | null
  retryCount: number
  parentLogId: number | null
  errorMessage: string | null
}

export interface ExecutionListParams {
  page?: number
  size?: number
  sort?: string
  status?: ExecutionStatus
  interfaceConfigId?: number
}

/**
 * 델타 응답 (api-spec §5.4) — SSE 재연결 갭 보충.
 */
export interface DeltaResponse {
  items: ExecutionLogResponse[]
  truncated: boolean
  nextCursor: string | null
}

// ---- Dashboard (백엔드 monitor/dto record와 1:1 매핑) ----

/** 백엔드 TotalStats record 매핑. */
export interface TotalStats {
  success: number
  failed: number
  running: number
  total: number
}

/** 백엔드 ProtocolStats record 매핑. */
export interface ProtocolStats {
  protocol: string
  success: number
  failed: number
  running: number
}

/** 백엔드 RecentFailure record 매핑. */
export interface RecentFailure {
  id: number
  interfaceName: string
  errorCode: string
  startedAt: string
}

/**
 * 백엔드 DashboardResponse record 매핑 (api-spec §6.2).
 * 필드: generatedAt, since, totals, byProtocol, recentFailures, sseConnections
 */
export interface DashboardResponse {
  generatedAt: string
  since: string
  totals: TotalStats
  byProtocol: ProtocolStats[]
  recentFailures: RecentFailure[]
  sseConnections: number
}
