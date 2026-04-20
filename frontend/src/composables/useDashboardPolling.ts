import { onBeforeUnmount, ref, shallowRef } from 'vue'
import type { DashboardResponse } from '@/api/types'
import { getDashboard } from '@/api/dashboard'

export interface UseDashboardPollingOptions {
  debounceMs?: number
  fallbackMs?: number
}

/**
 * Dashboard 데이터를 관리하는 composable.
 * - SSE 이벤트 수신 시 `requestDebouncedRefresh()`로 1초 debounce 재조회
 * - SSE 연결이 끊겼을 때만 `startPolling()`으로 60초 폴백 polling 활성화
 * - ADR-007 R4: open 동안 polling OFF
 */
export function useDashboardPolling(options?: UseDashboardPollingOptions) {
  const data = ref<DashboardResponse | null>(null)
  const loading = ref(false)
  const debounceMs = options?.debounceMs ?? 1000
  const fallbackMs = options?.fallbackMs ?? 60_000

  const debounceTimer = shallowRef<number | null>(null)
  const fallbackTimer = shallowRef<number | null>(null)
  const source = ref<'sse' | 'polling' | 'manual'>('manual')

  async function refresh() {
    loading.value = true
    try {
      data.value = await getDashboard()
    } finally {
      loading.value = false
    }
  }

  function requestDebouncedRefresh() {
    if (debounceTimer.value !== null) {
      window.clearTimeout(debounceTimer.value)
    }
    debounceTimer.value = window.setTimeout(() => {
      debounceTimer.value = null
      void refresh()
    }, debounceMs)
  }

  function startPolling() {
    if (fallbackTimer.value !== null) return
    fallbackTimer.value = window.setInterval(() => { void refresh() }, fallbackMs)
    source.value = 'polling'
  }

  function stopPolling() {
    if (fallbackTimer.value !== null) {
      window.clearInterval(fallbackTimer.value)
      fallbackTimer.value = null
    }
    source.value = 'sse'
  }

  onBeforeUnmount(() => {
    if (debounceTimer.value !== null) window.clearTimeout(debounceTimer.value)
    if (fallbackTimer.value !== null) window.clearInterval(fallbackTimer.value)
  })

  return {
    data,
    loading,
    source,
    refresh,
    requestDebouncedRefresh,
    startPolling,
    stopPolling,
  }
}
