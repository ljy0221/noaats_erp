# Skill: Vue 페이지 컴포넌트 생성

## 목적
Vue 3 Composition API 기반 페이지 컴포넌트를 일관된 구조로 생성한다.
`<script setup>` + Pinia + Axios + Vuetify 3 조합을 표준 패턴으로 사용한다.

---

## 파일 위치 규칙

```
src/
├── api/
│   └── {도메인}.js         ← API 호출 모듈 (여기서만 Axios 사용)
├── stores/
│   └── {도메인}Store.js    ← Pinia store
└── pages/
    └── {페이지명}.vue      ← 페이지 컴포넌트
```

**규칙**
- Axios 직접 호출은 `src/api/` 모듈에서만
- 페이지는 `src/pages/`에만 위치
- 재사용 컴포넌트는 `src/components/`

---

## API 모듈 패턴 (`src/api/{도메인}.js`)

```javascript
import api from '@/api/index.js'

export const {도메인}Api = {
  getAll: (params) => api.get('/{도메인복수}', { params }),
  getById: (id) => api.get(`/{도메인복수}/${id}`),
  create: (data) => api.post('/{도메인복수}', data),
  update: (id, data) => api.patch(`/{도메인복수}/${id}`, data),
  execute: (id) => api.post(`/{도메인복수}/${id}/execute`),
}
```

### Axios 인스턴스 (`src/api/index.js`)
```javascript
import axios from 'axios'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api',
  timeout: 10000,
  headers: { 'Content-Type': 'application/json' },
})

// 응답 인터셉터: ApiResponse<T> 언래핑
api.interceptors.response.use(
  (response) => response.data,  // { success, data, message } 그대로 반환
  (error) => {
    const message = error.response?.data?.message || '서버 오류가 발생했습니다'
    return Promise.reject(new Error(message))
  }
)

export default api
```

---

## Pinia Store 패턴 (`src/stores/{도메인}Store.js`)

```javascript
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { {도메인}Api } from '@/api/{도메인}.js'

export const use{도메인}Store = defineStore('{도메인}', () => {
  // State
  const items = ref([])
  const currentItem = ref(null)
  const loading = ref(false)
  const error = ref(null)
  const pagination = ref({ page: 0, size: 20, totalElements: 0 })

  // Getters
  const hasItems = computed(() => items.value.length > 0)

  // Actions
  async function fetchAll(params = {}) {
    loading.value = true
    error.value = null
    try {
      const res = await {도메인}Api.getAll({ page: pagination.value.page, size: pagination.value.size, ...params })
      items.value = res.data.content
      pagination.value.totalElements = res.data.totalElements
    } catch (e) {
      error.value = e.message
    } finally {
      loading.value = false
    }
  }

  async function create(data) {
    loading.value = true
    error.value = null
    try {
      const res = await {도메인}Api.create(data)
      items.value.unshift(res.data)
      return res.data
    } catch (e) {
      error.value = e.message
      throw e
    } finally {
      loading.value = false
    }
  }

  return { items, currentItem, loading, error, pagination, hasItems, fetchAll, create }
})
```

---

## 페이지 컴포넌트 패턴 (`src/pages/{페이지명}.vue`)

### 목록 페이지

```vue
<template>
  <v-container>
    <v-row>
      <v-col>
        <div class="d-flex align-center mb-4">
          <h1 class="text-h5 font-weight-bold">인터페이스 목록</h1>
          <v-spacer />
          <v-btn color="primary" prepend-icon="mdi-plus" @click="openCreateDialog">
            등록
          </v-btn>
        </div>

        <!-- 필터 -->
        <v-card class="mb-4" variant="outlined">
          <v-card-text>
            <v-row dense>
              <v-col cols="12" md="4">
                <v-select
                  v-model="filter.protocol"
                  :items="protocolOptions"
                  label="프로토콜"
                  clearable
                  density="compact"
                />
              </v-col>
              <v-col cols="12" md="4">
                <v-select
                  v-model="filter.status"
                  :items="statusOptions"
                  label="상태"
                  clearable
                  density="compact"
                />
              </v-col>
              <v-col cols="12" md="4" class="d-flex align-center">
                <v-btn color="primary" variant="tonal" @click="applyFilter">검색</v-btn>
              </v-col>
            </v-row>
          </v-card-text>
        </v-card>

        <!-- 테이블 -->
        <v-card variant="outlined">
          <v-data-table-server
            :items="store.items"
            :headers="headers"
            :items-length="store.pagination.totalElements"
            :loading="store.loading"
            @update:options="onTableOptions"
          >
            <template #item.protocol="{ item }">
              <v-chip :color="protocolColor(item.protocol)" size="small">
                {{ item.protocol }}
              </v-chip>
            </template>
            <template #item.status="{ item }">
              <v-chip :color="item.status === 'ACTIVE' ? 'success' : 'default'" size="small">
                {{ item.status }}
              </v-chip>
            </template>
            <template #item.actions="{ item }">
              <v-btn icon="mdi-play" size="small" color="success" variant="text"
                     :loading="executing[item.id]" @click="execute(item.id)" />
              <v-btn icon="mdi-pencil" size="small" color="primary" variant="text"
                     @click="openEditDialog(item)" />
            </template>
          </v-data-table-server>
        </v-card>
      </v-col>
    </v-row>

    <!-- 등록/수정 Dialog -->
    <InterfaceFormDialog v-model="dialog.open" :item="dialog.item" @saved="onSaved" />
  </v-container>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { use{도메인}Store } from '@/stores/{도메인}Store.js'
import { {도메인}Api } from '@/api/{도메인}.js'
import InterfaceFormDialog from '@/components/InterfaceFormDialog.vue'

const store = use{도메인}Store()

const filter = ref({ protocol: null, status: null })
const executing = ref({})
const dialog = ref({ open: false, item: null })

const headers = [
  { title: '이름', key: 'name' },
  { title: '프로토콜', key: 'protocol' },
  { title: '엔드포인트', key: 'endpoint' },
  { title: '상태', key: 'status' },
  { title: '최종 수정', key: 'updatedAt' },
  { title: '작업', key: 'actions', sortable: false },
]

const protocolOptions = ['REST', 'SOAP', 'MQ', 'BATCH', 'SFTP']
const statusOptions = ['ACTIVE', 'INACTIVE']

const protocolColor = (p) =>
  ({ REST: 'blue', SOAP: 'orange', MQ: 'purple', BATCH: 'teal', SFTP: 'brown' }[p] || 'grey')

onMounted(() => store.fetchAll())

function applyFilter() {
  store.fetchAll(filter.value)
}

function onTableOptions({ page, itemsPerPage }) {
  store.pagination.page = page - 1
  store.pagination.size = itemsPerPage
  store.fetchAll(filter.value)
}

async function execute(id) {
  executing.value[id] = true
  try {
    await {도메인}Api.execute(id)
  } finally {
    executing.value[id] = false
  }
}

function openCreateDialog() {
  dialog.value = { open: true, item: null }
}

function openEditDialog(item) {
  dialog.value = { open: true, item }
}

function onSaved() {
  dialog.value.open = false
  store.fetchAll(filter.value)
}
</script>
```

---

## 폼 다이얼로그 패턴 (`src/components/{도메인}FormDialog.vue`)

```vue
<template>
  <v-dialog v-model="model" max-width="600" persistent>
    <v-card>
      <v-card-title>{{ isEdit ? '수정' : '등록' }}</v-card-title>
      <v-card-text>
        <v-form ref="formRef" @submit.prevent="submit">
          <v-text-field v-model="form.name" label="이름" :rules="[required]" />
          <v-select v-model="form.protocol" :items="protocols" label="프로토콜" :rules="[required]" />
          <!-- 필드 추가 -->
        </v-form>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" @click="model = false">취소</v-btn>
        <v-btn color="primary" :loading="loading" @click="submit">저장</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { {도메인}Api } from '@/api/{도메인}.js'

const props = defineProps({ item: Object })
const emit = defineEmits(['saved'])
const model = defineModel()  // v-model

const formRef = ref(null)
const loading = ref(false)
const isEdit = computed(() => !!props.item)

const defaultForm = { name: '', protocol: 'REST', endpoint: '' }
const form = ref({ ...defaultForm })

watch(() => props.item, (val) => {
  form.value = val ? { ...val } : { ...defaultForm }
}, { immediate: true })

const required = (v) => !!v || '필수 입력 항목입니다'
const protocols = ['REST', 'SOAP', 'MQ', 'BATCH', 'SFTP']

async function submit() {
  const { valid } = await formRef.value.validate()
  if (!valid) return
  loading.value = true
  try {
    if (isEdit.value) {
      await {도메인}Api.update(props.item.id, form.value)
    } else {
      await {도메인}Api.create(form.value)
    }
    emit('saved')
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}
</script>
```

---

## 체크리스트

페이지 생성 시 아래를 확인한다:

- [ ] Axios 직접 호출이 `src/api/` 외부에 없음
- [ ] 로딩 상태 (`loading`) UI 반영
- [ ] 에러 상태 (`error`) 사용자에게 표시
- [ ] 페이지네이션 서버사이드 처리 (`v-data-table-server`)
- [ ] `defineModel()` 사용 (Vue 3.4+)
- [ ] 폼 제출 시 `v-form` validate 먼저 호출
