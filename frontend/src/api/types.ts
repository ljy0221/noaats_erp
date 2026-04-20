export type ProtocolType = 'REST' | 'SOAP' | 'MQ' | 'BATCH' | 'SFTP'
export type ScheduleType = 'MANUAL' | 'CRON'
export type InterfaceStatus = 'ACTIVE' | 'INACTIVE'
export type ExecutionStatus = 'RUNNING' | 'SUCCESS' | 'FAILED'
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
  | 'INTERNAL_ERROR'
  | 'NOT_IMPLEMENTED'

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
