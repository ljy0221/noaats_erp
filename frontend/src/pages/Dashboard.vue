<script setup lang="ts">
import { onMounted } from 'vue'
import { useExecutionStream } from '@/composables/useExecutionStream'
import { useDashboardPolling } from '@/composables/useDashboardPolling'
import { useAuthStore } from '@/stores/auth'
import { useRouter } from 'vue-router'
import StatusChip from '@/components/StatusChip.vue'

const auth = useAuthStore()
const router = useRouter()
const poll = useDashboardPolling()

const stream = useExecutionStream({
  onStarted:   () => poll.requestDebouncedRefresh(),
  onSuccess:   () => poll.requestDebouncedRefresh(),
  onFailed:    () => poll.requestDebouncedRefresh(),
  onRecovered: () => poll.requestDebouncedRefresh(),
  onFullRefresh: () => poll.refresh(),
  onOpen:  () => poll.stopPolling(),
  onError: () => poll.startPolling(),
  onUnauthorized: async () => {
    await auth.logout()
    router.push('/login')
  },
})

onMounted(async () => {
  await poll.refresh()
  stream.connect()
})
</script>

<template>
  <v-container fluid>
    <h1 class="text-h5 mb-4">대시보드</h1>

    <v-row v-if="poll.data.value">
      <v-col cols="12" md="3">
        <v-card color="primary" variant="tonal">
          <v-card-text>
            <div class="text-overline">전체</div>
            <div class="text-h3 font-weight-bold">{{ poll.data.value.totals.total }}</div>
          </v-card-text>
        </v-card>
      </v-col>
      <v-col cols="12" md="3">
        <v-card color="info" variant="tonal">
          <v-card-text>
            <div class="text-overline">실행 중</div>
            <div class="text-h3 font-weight-bold">{{ poll.data.value.totals.running }}</div>
          </v-card-text>
        </v-card>
      </v-col>
      <v-col cols="12" md="3">
        <v-card color="success" variant="tonal">
          <v-card-text>
            <div class="text-overline">성공</div>
            <div class="text-h3 font-weight-bold">{{ poll.data.value.totals.success }}</div>
          </v-card-text>
        </v-card>
      </v-col>
      <v-col cols="12" md="3">
        <router-link :to="{ name: 'history', query: { status: 'FAILED' } }"
                     class="text-decoration-none">
          <v-card color="error" variant="tonal" hover>
            <v-card-text>
              <div class="text-overline">실패</div>
              <div class="text-h3 font-weight-bold">{{ poll.data.value.totals.failed }}</div>
              <div class="text-caption mt-1">실행 이력으로 이동 →</div>
            </v-card-text>
          </v-card>
        </router-link>
      </v-col>
    </v-row>

    <v-row class="mt-4" v-if="poll.data.value">
      <v-col cols="12" md="7">
        <v-card>
          <v-card-title>프로토콜별 현황</v-card-title>
          <v-table>
            <thead>
              <tr>
                <th>프로토콜</th>
                <th>실행 중</th>
                <th>성공</th>
                <th>실패</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in poll.data.value.byProtocol" :key="p.protocol">
                <td>{{ p.protocol }}</td>
                <td>{{ p.running }}</td>
                <td>{{ p.success }}</td>
                <td>{{ p.failed }}</td>
              </tr>
              <tr v-if="poll.data.value.byProtocol.length === 0">
                <td colspan="4" class="text-disabled">데이터 없음</td>
              </tr>
            </tbody>
          </v-table>
        </v-card>
      </v-col>
      <v-col cols="12" md="5">
        <v-card>
          <v-card-title class="d-flex align-center">
            <span>최근 실패</span>
            <v-spacer />
            <v-chip size="x-small" :color="stream.state.value === 'open' ? 'success' : 'warning'">
              SSE {{ stream.state.value }}
            </v-chip>
          </v-card-title>
          <v-list>
            <v-list-item
              v-for="f in poll.data.value.recentFailures"
              :key="f.id"
              :to="{ name: 'history', query: { status: 'FAILED' } }"
              link>
              <template #prepend>
                <StatusChip status="FAILED" />
              </template>
              <v-list-item-title>{{ f.interfaceName }}</v-list-item-title>
              <v-list-item-subtitle class="text-truncate">{{ f.errorCode }}</v-list-item-subtitle>
            </v-list-item>
            <v-list-item v-if="poll.data.value.recentFailures.length === 0">
              <v-list-item-title class="text-disabled">최근 실패 없음</v-list-item-title>
            </v-list-item>
          </v-list>
        </v-card>
      </v-col>
    </v-row>

    <div class="mt-4 text-caption text-disabled">
      SSE 연결 수: {{ poll.data.value?.sseConnections ?? 0 }}
    </div>
  </v-container>
</template>
