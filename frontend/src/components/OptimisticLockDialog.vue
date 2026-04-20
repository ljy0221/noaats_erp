<script setup lang="ts">
import { computed } from 'vue'
import type { InterfaceConfigRequest, OptimisticLockServerSnapshot } from '@/api/types'

const props = defineProps<{
  open: boolean
  submitted: InterfaceConfigRequest
  serverSnapshot: OptimisticLockServerSnapshot
  currentVersion: number
  warnings?: string[]
}>()

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  (e: 'keep-server'): void
  (e: 'keep-mine'): void
  (e: 'cancel'): void
}>()

function close(reason: 'cancel' | 'server' | 'mine') {
  emit('update:open', false)
  if (reason === 'server') emit('keep-server')
  else if (reason === 'mine') emit('keep-mine')
  else emit('cancel')
}

interface DiffRow {
  field: string
  mine: string
  server: string
  changed: boolean
}

function str(v: unknown): string {
  if (v === null || v === undefined) return '(빈 값)'
  if (typeof v === 'object') return JSON.stringify(v, null, 2)
  return String(v)
}

const diffRows = computed<DiffRow[]>(() => {
  const mine = props.submitted
  const srv = props.serverSnapshot
  const rows: DiffRow[] = [
    { field: 'name', mine: str(mine.name), server: str(srv.name), changed: mine.name !== srv.name },
    { field: 'description', mine: str(mine.description), server: str(srv.description), changed: str(mine.description) !== str(srv.description) },
    { field: 'endpoint', mine: str(mine.endpoint), server: str(srv.endpoint), changed: mine.endpoint !== srv.endpoint },
    { field: 'configJson', mine: str(mine.configJson), server: str(srv.configJson), changed: str(mine.configJson) !== str(srv.configJson) },
  ]
  return rows
})

const hasStormWarning = computed(() =>
  (props.warnings ?? []).includes('CONCURRENT_EDIT_STORM'),
)
</script>

<template>
  <v-dialog :model-value="open" @update:model-value="close('cancel')" max-width="900" persistent>
    <v-card>
      <v-card-title class="text-h6 bg-warning-lighten-4">
        <v-icon icon="mdi-alert" color="warning" class="mr-2" />
        편집 충돌 — 다른 사용자가 먼저 수정했습니다
      </v-card-title>
      <v-card-text>
        <v-alert
          v-if="hasStormWarning"
          type="warning"
          density="compact"
          class="mb-3"
          variant="tonal"
        >
          다른 사용자와 편집 경합이 반복되고 있습니다. 잠시 후 다시 시도하거나 담당자와 조율하세요.
        </v-alert>

        <p class="text-body-2 mb-3">
          제출한 버전과 현재 서버 버전이 다릅니다.
          <strong>서버 최신 값(v{{ currentVersion }})</strong>과 비교 후 선택하세요.
        </p>

        <v-table density="compact">
          <thead>
            <tr>
              <th style="width: 140px;">필드</th>
              <th>내가 편집한 값</th>
              <th>서버 최신 값</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in diffRows" :key="row.field">
              <td class="font-weight-medium">{{ row.field }}</td>
              <td :class="{ 'bg-warning-lighten-4': row.changed }">
                <pre class="ma-0 text-caption" style="white-space: pre-wrap; word-break: break-all;">{{ row.mine }}</pre>
              </td>
              <td :class="{ 'bg-info-lighten-4': row.changed }">
                <pre class="ma-0 text-caption" style="white-space: pre-wrap; word-break: break-all;">{{ row.server }}</pre>
              </td>
            </tr>
          </tbody>
        </v-table>
      </v-card-text>

      <v-divider />

      <v-card-actions>
        <v-btn variant="text" @click="close('cancel')">취소</v-btn>
        <v-spacer />
        <v-btn variant="tonal" color="secondary" @click="close('server')">
          서버 값으로 불러오기
        </v-btn>
        <v-btn color="warning" variant="flat" @click="close('mine')">
          내 값으로 재시도 (v{{ currentVersion }})
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
