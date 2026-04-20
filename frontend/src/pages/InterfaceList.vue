<script setup lang="ts">
import { onMounted, ref } from 'vue'
import type {
  InterfaceConfigResponse,
  InterfaceStatus,
  ProtocolType,
} from '@/api/types'
import { PROTOCOL_TYPES, INTERFACE_STATUSES, ApiError } from '@/api/types'
import { executeInterface, listInterfaces } from '@/api/interfaces'
import { useToastStore } from '@/stores/toast'
import InterfaceFormDialog from '@/components/InterfaceFormDialog.vue'

const toast = useToastStore()

const loading = ref(false)
const items = ref<InterfaceConfigResponse[]>([])
const total = ref(0)
const page = ref(1)
const itemsPerPage = ref(20)

const filterName = ref('')
const filterStatus = ref<InterfaceStatus | null>(null)
const filterProtocol = ref<ProtocolType | null>(null)

const formOpen = ref(false)
const editingId = ref<number | null>(null)
const executingId = ref<number | null>(null)

const headers = [
  { title: 'ID', key: 'id', width: 80 },
  { title: '이름', key: 'name' },
  { title: '프로토콜', key: 'protocol', width: 110 },
  { title: '상태', key: 'status', width: 110 },
  { title: '스케줄', key: 'scheduleType', width: 110 },
  { title: '엔드포인트', key: 'endpoint' },
  { title: '버전', key: 'version', width: 80 },
  { title: '수정', key: 'updatedAt', width: 180 },
  { title: '작업', key: 'actions', width: 200, sortable: false },
]

async function fetchList() {
  loading.value = true
  try {
    const result = await listInterfaces({
      page: page.value - 1,
      size: itemsPerPage.value,
      sort: 'createdAt,desc',
      name: filterName.value || undefined,
      status: filterStatus.value || undefined,
      protocol: filterProtocol.value || undefined,
    })
    items.value = result.content
    total.value = result.totalElements
  } catch (e) {
    if (e instanceof ApiError) {
      toast.error(`목록 조회 실패: ${e.message}`)
    } else {
      toast.error('목록 조회 실패')
    }
  } finally {
    loading.value = false
  }
}

function onSearch() {
  page.value = 1
  fetchList()
}

function onResetFilter() {
  filterName.value = ''
  filterStatus.value = null
  filterProtocol.value = null
  page.value = 1
  fetchList()
}

function onCreate() {
  editingId.value = null
  formOpen.value = true
}

function onEdit(item: InterfaceConfigResponse) {
  editingId.value = item.id
  formOpen.value = true
}

async function onExecute(item: InterfaceConfigResponse) {
  if (item.status !== 'ACTIVE') {
    toast.warning('INACTIVE 인터페이스는 실행할 수 없습니다.')
    return
  }
  executingId.value = item.id
  try {
    const res = await executeInterface(item.id)
    toast.success(`실행 요청됨 — logId=${res.logId}`)
  } catch (e) {
    if (e instanceof ApiError) {
      if (e.code === 'DUPLICATE_RUNNING') {
        toast.warning(`이미 실행 중입니다 (logId=${(e.data as { runningLogId?: number })?.runningLogId ?? '?'})`)
      } else if (e.code === 'INTERFACE_INACTIVE') {
        toast.warning('INACTIVE 인터페이스는 실행할 수 없습니다.')
      } else if (e.code === 'NOT_IMPLEMENTED') {
        toast.info('실행 API는 Day 3 이후 동작합니다.')
      } else {
        toast.error(`실행 실패: ${e.message}`)
      }
    } else {
      toast.error('실행 실패')
    }
  } finally {
    executingId.value = null
  }
}

function onFormClosed(changed: boolean) {
  formOpen.value = false
  editingId.value = null
  if (changed) {
    fetchList()
  }
}

function statusColor(s: InterfaceStatus): string {
  return s === 'ACTIVE' ? 'success' : 'secondary'
}

function protocolColor(p: ProtocolType): string {
  const map: Record<ProtocolType, string> = {
    REST: 'info',
    SOAP: 'deep-purple',
    MQ: 'orange',
    BATCH: 'teal',
    SFTP: 'brown',
  }
  return map[p] ?? 'grey'
}

function formatDate(iso: string): string {
  const d = new Date(iso)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

onMounted(() => {
  fetchList()
})
</script>

<template>
  <div>
    <div class="d-flex align-center mb-4">
      <h1 class="text-h5">인터페이스 관리</h1>
      <v-spacer />
      <v-btn color="primary" prepend-icon="mdi-plus" @click="onCreate">
        인터페이스 등록
      </v-btn>
    </div>

    <v-card class="mb-4">
      <v-card-text>
        <v-row dense>
          <v-col cols="12" sm="6" md="4">
            <v-text-field
              v-model="filterName"
              label="이름 검색"
              prepend-inner-icon="mdi-magnify"
              clearable
              @keyup.enter="onSearch"
            />
          </v-col>
          <v-col cols="6" sm="3" md="2">
            <v-select
              v-model="filterStatus"
              label="상태"
              :items="[...INTERFACE_STATUSES]"
              clearable
            />
          </v-col>
          <v-col cols="6" sm="3" md="2">
            <v-select
              v-model="filterProtocol"
              label="프로토콜"
              :items="[...PROTOCOL_TYPES]"
              clearable
            />
          </v-col>
          <v-col cols="12" md="4" class="d-flex align-center">
            <v-btn color="primary" variant="tonal" class="mr-2" @click="onSearch">
              <v-icon start icon="mdi-filter" />
              검색
            </v-btn>
            <v-btn variant="text" @click="onResetFilter">
              초기화
            </v-btn>
          </v-col>
        </v-row>
      </v-card-text>
    </v-card>

    <v-card>
      <v-data-table-server
        v-model:page="page"
        v-model:items-per-page="itemsPerPage"
        :headers="headers"
        :items="items"
        :items-length="total"
        :loading="loading"
        :items-per-page-options="[10, 20, 50, 100]"
        item-value="id"
        @update:page="fetchList"
        @update:items-per-page="fetchList"
      >
        <template #item.protocol="{ item }">
          <v-chip :color="protocolColor(item.protocol)" size="small" variant="tonal">
            {{ item.protocol }}
          </v-chip>
        </template>
        <template #item.status="{ item }">
          <v-chip :color="statusColor(item.status)" size="small" variant="tonal">
            {{ item.status }}
          </v-chip>
        </template>
        <template #item.endpoint="{ item }">
          <span class="text-body-2" style="word-break: break-all;">{{ item.endpoint }}</span>
        </template>
        <template #item.updatedAt="{ item }">
          <span class="text-caption">{{ formatDate(item.updatedAt) }}</span>
        </template>
        <template #item.actions="{ item }">
          <v-btn
            size="small"
            variant="text"
            icon="mdi-pencil"
            @click="onEdit(item)"
          />
          <v-btn
            size="small"
            variant="text"
            icon="mdi-play"
            color="success"
            :loading="executingId === item.id"
            :disabled="item.status !== 'ACTIVE'"
            @click="onExecute(item)"
          />
        </template>
        <template #no-data>
          <div class="text-center pa-6 text-medium-emphasis">
            등록된 인터페이스가 없습니다.
          </div>
        </template>
      </v-data-table-server>
    </v-card>

    <InterfaceFormDialog
      v-model:open="formOpen"
      :edit-id="editingId"
      @closed="onFormClosed"
    />
  </div>
</template>
