<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type {
  InterfaceConfigRequest,
  InterfaceConfigResponse,
  OptimisticLockConflictData,
  ProtocolType,
  ScheduleType,
} from '@/api/types'
import { ApiError, PROTOCOL_TYPES, SCHEDULE_TYPES } from '@/api/types'
import {
  createInterface,
  getInterface,
  updateInterface,
} from '@/api/interfaces'
import { useToastStore } from '@/stores/toast'
import OptimisticLockDialog from '@/components/OptimisticLockDialog.vue'

const props = defineProps<{
  open: boolean
  editId: number | null
}>()

const emit = defineEmits<{
  (e: 'update:open', v: boolean): void
  (e: 'closed', changed: boolean): void
}>()

const toast = useToastStore()

const isEdit = computed(() => props.editId !== null)
const loading = ref(false)
const saving = ref(false)
const form = ref<HTMLFormElement | null>(null)

interface FormModel {
  id: number | null
  name: string
  description: string
  protocol: ProtocolType
  endpoint: string
  httpMethod: string
  configJsonText: string
  scheduleType: ScheduleType
  cronExpression: string
  timeoutSeconds: number
  maxRetryCount: number
  version: number | null
}

function emptyModel(): FormModel {
  return {
    id: null,
    name: '',
    description: '',
    protocol: 'REST',
    endpoint: '',
    httpMethod: 'POST',
    configJsonText: '{\n  "headers": {}\n}',
    scheduleType: 'MANUAL',
    cronExpression: '',
    timeoutSeconds: 30,
    maxRetryCount: 3,
    version: null,
  }
}

const model = ref<FormModel>(emptyModel())
const configJsonError = ref<string | null>(null)

const lockDialogOpen = ref(false)
const lockConflict = ref<OptimisticLockConflictData | null>(null)
const pendingSubmission = ref<InterfaceConfigRequest | null>(null)

const requiresHttpMethod = computed(() =>
  model.value.protocol === 'REST' || model.value.protocol === 'SOAP',
)
const requiresCron = computed(() => model.value.scheduleType === 'CRON')

const rules = {
  required: (v: unknown) =>
    (v !== null && v !== undefined && String(v).trim() !== '') || '필수 입력 항목입니다.',
  maxLen: (n: number) => (v: string) =>
    !v || v.length <= n || `${n}자 이하로 입력하세요.`,
  intRange: (min: number, max: number) => (v: number) =>
    (Number.isInteger(Number(v)) && v >= min && v <= max) ||
    `${min}~${max} 사이의 정수만 허용됩니다.`,
}

watch(
  () => [props.open, props.editId] as const,
  async ([open, id]) => {
    if (!open) return
    model.value = emptyModel()
    configJsonError.value = null
    if (id !== null) {
      await loadForEdit(id)
    }
  },
  { immediate: true },
)

async function loadForEdit(id: number) {
  loading.value = true
  try {
    const detail = await getInterface(id)
    applyServerDetail(detail)
  } catch (e) {
    if (e instanceof ApiError) {
      toast.error(`조회 실패: ${e.message}`)
    } else {
      toast.error('조회 실패')
    }
    close(false)
  } finally {
    loading.value = false
  }
}

function applyServerDetail(detail: InterfaceConfigResponse) {
  model.value = {
    id: detail.id,
    name: detail.name,
    description: detail.description ?? '',
    protocol: detail.protocol,
    endpoint: detail.endpoint,
    httpMethod: detail.httpMethod ?? '',
    configJsonText: JSON.stringify(detail.configJson ?? {}, null, 2),
    scheduleType: detail.scheduleType,
    cronExpression: detail.cronExpression ?? '',
    timeoutSeconds: detail.timeoutSeconds,
    maxRetryCount: detail.maxRetryCount,
    version: detail.version,
  }
}

function parseConfigJson(): Record<string, unknown> | null {
  try {
    const parsed = JSON.parse(model.value.configJsonText || '{}')
    if (typeof parsed !== 'object' || Array.isArray(parsed) || parsed === null) {
      configJsonError.value = 'configJson은 객체(JSON Object)여야 합니다.'
      return null
    }
    configJsonError.value = null
    return parsed as Record<string, unknown>
  } catch (e) {
    configJsonError.value = `JSON 파싱 오류: ${(e as Error).message}`
    return null
  }
}

function buildPayload(version: number | null): InterfaceConfigRequest | null {
  const configJson = parseConfigJson()
  if (configJson === null) return null

  const payload: InterfaceConfigRequest = {
    name: model.value.name.trim(),
    description: model.value.description.trim() || null,
    protocol: model.value.protocol,
    endpoint: model.value.endpoint.trim(),
    httpMethod: requiresHttpMethod.value ? model.value.httpMethod.trim() : null,
    configJson,
    scheduleType: model.value.scheduleType,
    cronExpression: requiresCron.value ? model.value.cronExpression.trim() : null,
    timeoutSeconds: Number(model.value.timeoutSeconds),
    maxRetryCount: Number(model.value.maxRetryCount),
  }
  if (version !== null) payload.version = version
  return payload
}

async function onSubmit() {
  const valid = await form.value?.validate?.()
  if (valid && valid.valid === false) return
  if (requiresCron.value && !model.value.cronExpression.trim()) {
    toast.warning('CRON 스케줄 선택 시 cronExpression은 필수입니다.')
    return
  }
  const payload = buildPayload(isEdit.value ? model.value.version : null)
  if (!payload) return
  await submit(payload)
}

async function submit(payload: InterfaceConfigRequest) {
  saving.value = true
  try {
    if (isEdit.value && model.value.id !== null) {
      const updated = await updateInterface(model.value.id, payload)
      toast.success(`수정 완료 (v${updated.version})`)
    } else {
      const created = await createInterface(payload)
      toast.success(`등록 완료 — id=${created.id}`)
    }
    close(true)
  } catch (e) {
    if (e instanceof ApiError) {
      await handleApiError(e, payload)
    } else {
      toast.error('저장 실패')
    }
  } finally {
    saving.value = false
  }
}

async function handleApiError(e: ApiError, payload: InterfaceConfigRequest) {
  switch (e.code) {
    case 'OPTIMISTIC_LOCK_CONFLICT':
      lockConflict.value = e.data as unknown as OptimisticLockConflictData
      pendingSubmission.value = payload
      lockDialogOpen.value = true
      break
    case 'DUPLICATE_NAME':
      toast.error('동일한 이름의 인터페이스가 이미 존재합니다.')
      break
    case 'CONFIG_JSON_INVALID':
      toast.error(`configJson 검증 실패: ${e.message}`)
      break
    case 'VALIDATION_FAILED':
      toast.error(`검증 실패: ${e.message}`)
      break
    default:
      toast.error(e.message || '저장 실패')
  }
}

function onKeepServer() {
  if (!lockConflict.value) return
  const snap = lockConflict.value.serverSnapshot
  model.value.name = snap.name
  model.value.description = snap.description ?? ''
  model.value.endpoint = snap.endpoint
  model.value.configJsonText = JSON.stringify(snap.configJson ?? {}, null, 2)
  model.value.version = lockConflict.value.currentVersion
  lockConflict.value = null
  pendingSubmission.value = null
  toast.info('서버 값을 불러왔습니다. 필요시 재편집 후 저장하세요.')
}

async function onKeepMine() {
  if (!lockConflict.value || !pendingSubmission.value) return
  const retryPayload: InterfaceConfigRequest = {
    ...pendingSubmission.value,
    version: lockConflict.value.currentVersion,
  }
  model.value.version = lockConflict.value.currentVersion
  lockConflict.value = null
  pendingSubmission.value = null
  await submit(retryPayload)
}

function onCancelLockDialog() {
  lockConflict.value = null
  pendingSubmission.value = null
}

function close(changed: boolean) {
  emit('update:open', false)
  emit('closed', changed)
}
</script>

<template>
  <v-dialog
    :model-value="open"
    @update:model-value="(v: boolean) => { if (!v) close(false) }"
    max-width="760"
    persistent
    scrollable
  >
    <v-card>
      <v-card-title class="d-flex align-center">
        <v-icon :icon="isEdit ? 'mdi-pencil' : 'mdi-plus-box'" class="mr-2" />
        {{ isEdit ? `인터페이스 수정 (id=${model.id}, v${model.version ?? '?'})` : '인터페이스 등록' }}
      </v-card-title>

      <v-divider />

      <v-card-text style="max-height: 70vh;">
        <v-skeleton-loader v-if="loading" type="article" />
        <v-form v-else ref="form" @submit.prevent="onSubmit">
          <v-row dense>
            <v-col cols="12" md="8">
              <v-text-field
                v-model="model.name"
                label="이름 *"
                :rules="[rules.required, rules.maxLen(100)]"
                maxlength="100"
                counter
              />
            </v-col>
            <v-col cols="12" md="4">
              <v-select
                v-model="model.protocol"
                label="프로토콜 *"
                :items="[...PROTOCOL_TYPES]"
                :rules="[rules.required]"
              />
            </v-col>
            <v-col cols="12">
              <v-textarea
                v-model="model.description"
                label="설명"
                :rules="[rules.maxLen(500)]"
                rows="2"
                auto-grow
              />
            </v-col>
            <v-col cols="12" md="8">
              <v-text-field
                v-model="model.endpoint"
                label="엔드포인트 *"
                :rules="[rules.required, rules.maxLen(500)]"
                placeholder="https://example.com/api/..."
              />
            </v-col>
            <v-col cols="12" md="4">
              <v-text-field
                v-if="requiresHttpMethod"
                v-model="model.httpMethod"
                label="HTTP Method"
                :rules="[rules.maxLen(10)]"
                placeholder="GET / POST / PUT ..."
              />
            </v-col>
            <v-col cols="12" md="4">
              <v-select
                v-model="model.scheduleType"
                label="스케줄 타입 *"
                :items="[...SCHEDULE_TYPES]"
                :rules="[rules.required]"
              />
            </v-col>
            <v-col cols="12" md="8">
              <v-text-field
                v-if="requiresCron"
                v-model="model.cronExpression"
                label="Cron 표현식 *"
                :rules="[rules.required, rules.maxLen(100)]"
                placeholder="0 0 6 * * *"
                hint="Spring 6-field cron (초 분 시 일 월 요일)"
                persistent-hint
              />
            </v-col>
            <v-col cols="6" md="3">
              <v-text-field
                v-model.number="model.timeoutSeconds"
                label="타임아웃(초) *"
                type="number"
                :rules="[rules.required, rules.intRange(1, 600)]"
              />
            </v-col>
            <v-col cols="6" md="3">
              <v-text-field
                v-model.number="model.maxRetryCount"
                label="최대 재시도 *"
                type="number"
                :rules="[rules.required, rules.intRange(0, 10)]"
              />
            </v-col>
            <v-col cols="12">
              <v-textarea
                v-model="model.configJsonText"
                label="configJson (JSON 객체) *"
                rows="6"
                auto-grow
                :error-messages="configJsonError ? [configJsonError] : []"
                @blur="parseConfigJson"
                class="font-mono"
              />
              <div class="text-caption text-medium-emphasis mt-1">
                password·JWT 평문 금지. 시크릿은 <code>secretRef</code> 키 사용
                (예: <code>vault://ifms/rest/token</code>)
              </div>
            </v-col>
          </v-row>
        </v-form>
      </v-card-text>

      <v-divider />

      <v-card-actions>
        <v-btn variant="text" @click="close(false)">취소</v-btn>
        <v-spacer />
        <v-btn
          color="primary"
          variant="flat"
          :loading="saving"
          :disabled="loading"
          @click="onSubmit"
        >
          {{ isEdit ? '저장' : '등록' }}
        </v-btn>
      </v-card-actions>
    </v-card>

    <OptimisticLockDialog
      v-if="lockConflict && pendingSubmission"
      v-model:open="lockDialogOpen"
      :submitted="pendingSubmission"
      :server-snapshot="lockConflict.serverSnapshot"
      :current-version="lockConflict.currentVersion"
      :warnings="lockConflict.warnings"
      @keep-server="onKeepServer"
      @keep-mine="onKeepMine"
      @cancel="onCancelLockDialog"
    />
  </v-dialog>
</template>

<style scoped>
.font-mono :deep(textarea) {
  font-family: 'Menlo', 'Consolas', 'Courier New', monospace;
  font-size: 13px;
}
</style>
