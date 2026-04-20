<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import type { ExecutionLogResponse, ExecutionStatus } from '@/api/types'
import { ApiError } from '@/api/types'
import { listExecutions, retryExecution, getExecution } from '@/api/executions'
import { useExecutionStream } from '@/composables/useExecutionStream'
import { useToastStore } from '@/stores/toast'
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'
import StatusChip from '@/components/StatusChip.vue'

const toast = useToastStore()
const auth = useAuthStore()
const router = useRouter()

const page = ref(0)
const size = ref(20)
const sort = ref<'startedAt,desc' | 'startedAt,asc'>('startedAt,desc')
const statusFilter = ref<ExecutionStatus | null>(null)

const items = ref<ExecutionLogResponse[]>([])
const total = ref(0)
const loading = ref(false)
const pendingNewCount = ref(0)

const retryDialog = ref<{ open: boolean, row: ExecutionLogResponse | null }>({ open: false, row: null })
const detailDialog = ref<{ open: boolean, row: ExecutionLogResponse | null }>({ open: false, row: null })

const statusOptions: Array<{ title: string, value: ExecutionStatus | null }> = [
  { title: '전체', value: null },
  { title: 'RUNNING', value: 'RUNNING' },
  { title: 'SUCCESS', value: 'SUCCESS' },
  { title: 'FAILED', value: 'FAILED' },
  { title: 'RECOVERED', value: 'RECOVERED' },
]

const sortOptions = [
  { title: '시작 시각 내림차순', value: 'startedAt,desc' as const },
  { title: '시작 시각 오름차순', value: 'startedAt,asc' as const },
]

const isDefaultView = computed(() =>
  page.value === 0 && sort.value === 'startedAt,desc',
)

async function load() {
  loading.value = true
  pendingNewCount.value = 0
  try {
    const res = await listExecutions({
      page: page.value,
      size: size.value,
      sort: sort.value,
      status: statusFilter.value ?? undefined,
    })
    items.value = res.content
    total.value = res.totalElements
  } finally {
    loading.value = false
  }
}

function matchesFilter(e: Partial<ExecutionLogResponse>): boolean {
  if (statusFilter.value == null) return true
  return e.status === statusFilter.value
}

/**
 * 행을 in-place 병합. 존재하지 않으면 no-op.
 * @returns 병합이 실제로 일어난 기존 행의 idx, 없으면 -1
 */
function upsertInPlace(e: Partial<ExecutionLogResponse>): number {
  if (e.id == null) return -1
  const idx = items.value.findIndex(x => x.id === e.id)
  if (idx >= 0) {
    items.value[idx] = { ...items.value[idx], ...e } as ExecutionLogResponse
  }
  return idx
}

/**
 * 리뷰 C4(유령 행 제거): RUNNING 필터 상태에서 SUCCESS 전이 이벤트가 오면
 * 테이블의 기존 RUNNING 행이 SUCCESS로 갱신되는데, 이 행은 필터와 불일치한다.
 * 병합 후 현재 필터와 맞지 않으면 즉시 테이블에서 제거한다.
 */
function handleIncoming(e: Partial<ExecutionLogResponse>) {
  const existingIdx = upsertInPlace(e)

  // 이미 존재하던 행이 필터와 불일치하게 되면 테이블에서 제거.
  if (existingIdx >= 0 && !matchesFilter(e)) {
    items.value.splice(existingIdx, 1)
    return
  }

  if (!matchesFilter(e)) return

  if (isDefaultView.value && e.id != null && existingIdx < 0) {
    items.value = [e as ExecutionLogResponse, ...items.value].slice(0, size.value)
    return
  }
  if (e.id != null && existingIdx < 0) {
    pendingNewCount.value++
  }
}

const stream = useExecutionStream({
  onStarted:   handleIncoming,
  onSuccess:   handleIncoming,
  onFailed:    handleIncoming,
  onRecovered: handleIncoming,
  onFullRefresh: load,
  onUnauthorized: async () => {
    await auth.logout()
    router.push('/login')
  },
})

onMounted(async () => {
  await load()
  stream.connect()
})

watch([page, size, sort, statusFilter], () => { void load() })

function openRetry(row: ExecutionLogResponse) {
  retryDialog.value = { open: true, row }
}

async function confirmRetry() {
  const row = retryDialog.value.row
  retryDialog.value.open = false
  if (!row) return
  try {
    await retryExecution(row.id)
    toast.show('재처리를 시작했습니다', 'success')
  } catch (e) {
    if (e instanceof ApiError) {
      const map: Record<string, string> = {
        RETRY_FORBIDDEN_ACTOR: '타 사용자의 실행 로그는 재처리할 수 없습니다',
        RETRY_LIMIT_EXCEEDED: '재처리 최대 횟수를 초과했습니다',
        RETRY_CHAIN_CONFLICT: '재처리 체인 분기는 허용되지 않습니다',
        RETRY_NOT_LEAF: '체인 최신 리프 로그만 재처리할 수 있습니다',
        RETRY_TRUNCATED_BLOCKED: 'payload가 잘린 로그는 재처리할 수 없습니다',
      }
      toast.show(map[e.code ?? ''] ?? e.message, 'error')
    }
  }
}

async function openDetail(row: ExecutionLogResponse) {
  detailDialog.value = { open: true, row }
  try {
    const detail = await getExecution(row.id)
    detailDialog.value.row = detail
  } catch { /* noop */ }
}

function applyBanner() {
  pendingNewCount.value = 0
  void load()
}
</script>

<template>
  <v-container fluid>
    <h1 class="text-h5 mb-4">실행 이력</h1>

    <v-row class="mb-2" dense>
      <v-col cols="12" md="3">
        <v-select v-model="statusFilter" :items="statusOptions" item-title="title" item-value="value" label="상태" />
      </v-col>
      <v-col cols="12" md="3">
        <v-select v-model="sort" :items="sortOptions" item-title="title" item-value="value" label="정렬" />
      </v-col>
    </v-row>

    <v-alert v-if="pendingNewCount > 0" type="info" closable class="mb-2" @click="applyBanner">
      새 실행 {{ pendingNewCount }}건 — 클릭하여 새로고침
    </v-alert>

    <v-card>
      <v-data-table
        :headers="[
          { title: 'ID', key: 'id', width: 80 },
          { title: '인터페이스', key: 'interfaceName' },
          { title: '상태', key: 'status' },
          { title: '트리거', key: 'triggeredBy' },
          { title: '시작', key: 'startedAt' },
          { title: '소요(ms)', key: 'durationMs' },
          { title: '재시도', key: 'retryCount', width: 80 },
          { title: '', key: 'actions', sortable: false, width: 100 },
        ]"
        :items="items"
        :loading="loading"
        hide-default-footer
        @click:row="(_: unknown, ctx: { item: ExecutionLogResponse }) => openDetail(ctx.item)">
        <template #[`item.status`]="{ item }">
          <StatusChip :status="(item as ExecutionLogResponse).status" />
        </template>
        <template #[`item.actions`]="{ item }">
          <v-btn v-if="(item as ExecutionLogResponse).status === 'FAILED'" size="x-small" color="warning"
                 @click.stop="openRetry(item as ExecutionLogResponse)">재처리</v-btn>
        </template>
      </v-data-table>
      <div class="pa-2 d-flex align-center">
        <v-btn :disabled="page === 0" size="small" @click="page--">이전</v-btn>
        <span class="mx-2">{{ page + 1 }} / {{ Math.max(1, Math.ceil(total / size)) }}</span>
        <v-btn :disabled="(page + 1) * size >= total" size="small" @click="page++">다음</v-btn>
        <v-spacer />
        <v-chip size="x-small" :color="stream.state.value === 'open' ? 'success' : 'warning'">
          SSE {{ stream.state.value }}
        </v-chip>
      </div>
    </v-card>

    <v-dialog v-model="retryDialog.open" max-width="480">
      <v-card>
        <v-card-title>재처리 확인</v-card-title>
        <v-card-text>
          실행 #{{ retryDialog.row?.id }} ({{ retryDialog.row?.interfaceName }})을 재처리하시겠습니까?
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="retryDialog.open = false">취소</v-btn>
          <v-btn color="primary" @click="confirmRetry">재처리</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>

    <v-dialog v-model="detailDialog.open" max-width="720">
      <v-card v-if="detailDialog.row">
        <v-card-title>실행 상세 #{{ detailDialog.row.id }}</v-card-title>
        <v-card-text>
          <div><strong>인터페이스:</strong> {{ detailDialog.row.interfaceName }}</div>
          <div><strong>상태:</strong>
            <StatusChip :status="detailDialog.row.status" />
          </div>
          <div><strong>시작:</strong> {{ detailDialog.row.startedAt }}</div>
          <div><strong>종료:</strong> {{ detailDialog.row.finishedAt ?? '-' }}</div>
          <div><strong>소요:</strong> {{ detailDialog.row.durationMs ?? '-' }} ms</div>
          <div><strong>재시도 횟수:</strong> {{ detailDialog.row.retryCount }}</div>
          <div v-if="detailDialog.row.parentLogId">
            <strong>부모 로그:</strong> #{{ detailDialog.row.parentLogId }}
          </div>
          <v-divider class="my-2" />
          <div><strong>에러:</strong></div>
          <pre class="text-body-2">{{ detailDialog.row.errorMessage ?? '-' }}</pre>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="detailDialog.open = false">닫기</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-container>
</template>
