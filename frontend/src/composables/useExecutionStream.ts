import { onBeforeUnmount, ref, shallowRef } from 'vue'
import type { ExecutionLogResponse, ExecutionStatus } from '@/api/types'
import { fetchDelta } from '@/api/executions'

const CLIENT_ID_KEY = 'sse.clientId'

type ConnState = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed'

export interface UseExecutionStreamHandlers {
  onStarted?:   (e: Partial<ExecutionLogResponse>) => void
  onSuccess?:   (e: Partial<ExecutionLogResponse>) => void
  onFailed?:    (e: Partial<ExecutionLogResponse>) => void
  onRecovered?: (e: Partial<ExecutionLogResponse>) => void
  onHeartbeat?: () => void
  onResync?:    () => void
  onFullRefresh?: () => void
  onUnauthorized?: () => void
  onOpen?:  () => void
  onError?: () => void
}

type EventKind =
  | 'CONNECTED'
  | 'HEARTBEAT'
  | 'EXECUTION_STARTED'
  | 'EXECUTION_SUCCESS'
  | 'EXECUTION_FAILED'
  | 'EXECUTION_RECOVERED'
  | 'RESYNC_REQUIRED'
  | 'UNAUTHORIZED'

const DEDUP_MAX = 1000

export function useExecutionStream(handlers: UseExecutionStreamHandlers = {}) {
  const state = ref<ConnState>('idle')
  const lastSeenAt = ref<string | null>(null)
  const dedup = new Map<number, { status: ExecutionStatus, createdAt: string }>()
  const source = shallowRef<EventSource | null>(null)

  function clientId(): string {
    let id = sessionStorage.getItem(CLIENT_ID_KEY)
    if (!id) {
      id = crypto.randomUUID()
      sessionStorage.setItem(CLIENT_ID_KEY, id)
    }
    return id
  }

  function touchLastSeen(ts: string | undefined | null) {
    if (!ts) return
    if (!lastSeenAt.value || ts > lastSeenAt.value) {
      lastSeenAt.value = ts
    }
  }

  function trimDedup() {
    if (dedup.size <= DEDUP_MAX) return
    const toDrop = dedup.size - DEDUP_MAX
    let i = 0
    for (const key of dedup.keys()) {
      if (i++ >= toDrop) break
      dedup.delete(key)
    }
  }

  function applyEvent(kind: EventKind, rawPayload: unknown) {
    const payload = (rawPayload ?? {}) as Record<string, unknown>
    const log = (payload.log ?? payload) as Partial<ExecutionLogResponse>
    if (log && typeof log.id === 'number') {
      const prev = dedup.get(log.id)
      const createdAt = log.startedAt ?? new Date().toISOString()
      if (prev && prev.createdAt >= createdAt) return
      if (log.status) {
        dedup.set(log.id, { status: log.status, createdAt })
      }
      trimDedup()
      touchLastSeen(log.startedAt)
    }
    switch (kind) {
      case 'EXECUTION_STARTED':   handlers.onStarted?.(log); break
      case 'EXECUTION_SUCCESS':   handlers.onSuccess?.(log); break
      case 'EXECUTION_FAILED':    handlers.onFailed?.(log); break
      case 'EXECUTION_RECOVERED': handlers.onRecovered?.(log); break
    }
  }

  async function handleResync() {
    handlers.onResync?.()
    try {
      const since = lastSeenAt.value ?? new Date(Date.now() - 5 * 60_000).toISOString()
      const res = await fetchDelta(since)
      if (res.truncated) {
        handlers.onFullRefresh?.()
        return
      }
      for (const log of res.items) {
        const kind: EventKind =
          log.status === 'RUNNING'   ? 'EXECUTION_STARTED'   :
          log.status === 'SUCCESS'   ? 'EXECUTION_SUCCESS'   :
          log.status === 'FAILED'    ? 'EXECUTION_FAILED'    :
                                       'EXECUTION_RECOVERED'
        applyEvent(kind, log)
      }
    } catch {
      handlers.onFullRefresh?.()
    }
  }

  function connect() {
    if (source.value) return
    const es = new EventSource(`/api/monitor/stream?clientId=${clientId()}`, { withCredentials: true })
    state.value = 'connecting'
    es.onopen = () => {
      state.value = 'open'
      handlers.onOpen?.()
    }
    es.onerror = () => {
      state.value = 'reconnecting'
      handlers.onError?.()
    }
    const eventTypes: EventKind[] = [
      'CONNECTED','HEARTBEAT','EXECUTION_STARTED','EXECUTION_SUCCESS',
      'EXECUTION_FAILED','EXECUTION_RECOVERED','RESYNC_REQUIRED','UNAUTHORIZED',
    ]
    for (const t of eventTypes) {
      es.addEventListener(t, (ev: MessageEvent) => {
        try {
          const data = JSON.parse(ev.data)
          if (t === 'HEARTBEAT') { handlers.onHeartbeat?.(); return }
          if (t === 'CONNECTED') { return }
          if (t === 'RESYNC_REQUIRED') { void handleResync(); return }
          if (t === 'UNAUTHORIZED') {
            close()
            handlers.onUnauthorized?.()
            return
          }
          applyEvent(t, data.payload)
        } catch {
          /* ignore parse errors */
        }
      })
    }
    source.value = es
  }

  function close() {
    source.value?.close()
    source.value = null
    state.value = 'closed'
  }

  onBeforeUnmount(close)

  return { state, lastSeenAt, connect, close }
}
