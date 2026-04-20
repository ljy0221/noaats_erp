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

/* ─── 모듈 스코프 싱글턴 상태 ──────────────────────────────────────────────
 *
 * 리뷰 C3: Dashboard·ExecutionHistory가 독립적으로 `useExecutionStream()`을 호출하면
 * `sessionStorage` clientId를 공유한 채로 EventSource가 2개 열리고, 서버 Registry가
 * "동일 clientId 두 세션"을 감지해 ADR-007 R3 재할당 루프가 발생, 결국 UNAUTHORIZED
 * 오발로 강제 로그아웃된다.
 *
 * 해결책: 모듈 스코프에 싱글턴 상태(상태·EventSource·dedup·lastSeenAt)를 두고,
 * 각 호출은 자신의 `handlers`만 등록하며 모든 구독자에게 fan-out. 구독자 refCount가
 * 0이 되면 EventSource 정리.
 * ────────────────────────────────────────────────────────────────────────── */

const state = ref<ConnState>('idle')
const lastSeenAt = ref<string | null>(null)
const dedup = new Map<number, { status: ExecutionStatus, createdAt: string }>()
const source = shallowRef<EventSource | null>(null)

type SubscriberSet = Set<UseExecutionStreamHandlers>
const subscribers: SubscriberSet = new Set()

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

/** 이벤트를 모든 구독자에게 fan-out (dedup 우선 체크). */
function applyEvent(kind: EventKind, rawPayload: unknown) {
  const payload = (rawPayload ?? {}) as Record<string, unknown>
  const log = (payload.log ?? payload) as Partial<ExecutionLogResponse>
  if (log && typeof log.id === 'number') {
    const prev = dedup.get(log.id)
    const createdAt = log.startedAt ?? new Date().toISOString()
    // 터미널 상태(SUCCESS/FAILED/RECOVERED)가 항상 RUNNING을 이긴다 — Devils 쟁점 A.
    const statusRank = (s?: ExecutionStatus): number => s === 'RUNNING' ? 0 : 1
    if (prev) {
      const prevRank = statusRank(prev.status)
      const curRank = statusRank(log.status)
      if (curRank < prevRank) return // 더 낮은 랭크는 버림
      if (curRank === prevRank && prev.createdAt >= createdAt) return
    }
    if (log.status) {
      dedup.set(log.id, { status: log.status, createdAt })
    }
    trimDedup()
    touchLastSeen(log.startedAt)
  }
  for (const h of subscribers) {
    switch (kind) {
      case 'EXECUTION_STARTED':   h.onStarted?.(log); break
      case 'EXECUTION_SUCCESS':   h.onSuccess?.(log); break
      case 'EXECUTION_FAILED':    h.onFailed?.(log); break
      case 'EXECUTION_RECOVERED': h.onRecovered?.(log); break
    }
  }
}

async function handleResync() {
  for (const h of subscribers) h.onResync?.()
  try {
    const since = lastSeenAt.value ?? new Date(Date.now() - 5 * 60_000).toISOString()
    const res = await fetchDelta(since)
    if (res.truncated) {
      for (const h of subscribers) h.onFullRefresh?.()
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
    for (const h of subscribers) h.onFullRefresh?.()
  }
}

function ensureConnected() {
  if (source.value) return
  const es = new EventSource(`/api/monitor/stream?clientId=${clientId()}`, { withCredentials: true })
  state.value = 'connecting'
  es.onopen = () => {
    state.value = 'open'
    for (const h of subscribers) h.onOpen?.()
  }
  es.onerror = () => {
    state.value = 'reconnecting'
    for (const h of subscribers) h.onError?.()
  }
  const eventTypes: EventKind[] = [
    'CONNECTED','HEARTBEAT','EXECUTION_STARTED','EXECUTION_SUCCESS',
    'EXECUTION_FAILED','EXECUTION_RECOVERED','RESYNC_REQUIRED','UNAUTHORIZED',
  ]
  for (const t of eventTypes) {
    es.addEventListener(t, (ev: MessageEvent) => {
      try {
        const data = JSON.parse(ev.data)
        if (t === 'HEARTBEAT') { for (const h of subscribers) h.onHeartbeat?.(); return }
        if (t === 'CONNECTED') { return }
        if (t === 'RESYNC_REQUIRED') { void handleResync(); return }
        if (t === 'UNAUTHORIZED') {
          shutdown()
          for (const h of subscribers) h.onUnauthorized?.()
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

function shutdown() {
  source.value?.close()
  source.value = null
  state.value = 'closed'
}

/**
 * 현 컴포넌트의 핸들러를 싱글턴 스트림에 등록한다.
 * 첫 구독자 등록 시 EventSource를 연결하고, 마지막 구독자 해제 시 close.
 */
export function useExecutionStream(handlers: UseExecutionStreamHandlers = {}) {
  subscribers.add(handlers)

  function connect() {
    ensureConnected()
  }

  function close() {
    subscribers.delete(handlers)
    if (subscribers.size === 0) {
      shutdown()
      // 싱글턴 상태 중 dedup/lastSeenAt는 유지 — 탭 전환 후 재진입 시 연속성 보존.
      // 필요 시 명시 리셋은 로그아웃 플로우에서 sessionStorage clientId 제거로 대체.
    }
  }

  onBeforeUnmount(close)

  return { state, lastSeenAt, connect, close }
}
