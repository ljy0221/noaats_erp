# Skill: SSE (Server-Sent Events) 구현 패턴

## 목적
Spring Boot의 `SseEmitter`로 실시간 실행 상태를 브라우저에 스트리밍한다.
Vue에서 `EventSource`로 구독하여 대시보드에 실시간 반영한다.

---

## 전체 구조

```
[Spring Boot]                        [Vue]
SseEmitterService                    useSseStore (Pinia)
  - emitters: Map<String, SseEmitter>   - EventSource 연결
  - emit(SseEvent)                      - onmessage 핸들러
      ↓ text/event-stream               - 이벤트 타입별 처리
GET /api/monitor/stream ─────────────→ EventSource('/api/monitor/stream')
```

---

## Backend 구현

### SseEmitterService

```java
@Service
@Slf4j
public class SseEmitterService {

    // 연결된 클라이언트 관리 (clientId → SseEmitter)
    private final ConcurrentHashMap<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    private static final long SSE_TIMEOUT = 3 * 60 * 1000L; // 3분

    /**
     * 새 클라이언트 SSE 연결 등록
     */
    public SseEmitter connect(String clientId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT);

        // 연결 종료 시 자동 제거
        emitter.onCompletion(() -> {
            emitters.remove(clientId);
            log.debug("SSE completed: {}", clientId);
        });
        emitter.onTimeout(() -> {
            emitters.remove(clientId);
            log.debug("SSE timeout: {}", clientId);
        });
        emitter.onError((e) -> {
            emitters.remove(clientId);
            log.debug("SSE error: {}", clientId);
        });

        emitters.put(clientId, emitter);

        // 연결 즉시 초기 이벤트 전송 (연결 확인용)
        try {
            emitter.send(SseEmitter.event()
                    .name("CONNECTED")
                    .data("{\"clientId\": \"" + clientId + "\"}"));
        } catch (IOException e) {
            emitters.remove(clientId);
        }

        log.info("SSE connected: {} (total: {})", clientId, emitters.size());
        return emitter;
    }

    /**
     * 모든 연결된 클라이언트에게 이벤트 브로드캐스트
     */
    public void emit(SseEvent event) {
        if (emitters.isEmpty()) return;

        String json = toJson(event);
        List<String> deadClients = new ArrayList<>();

        emitters.forEach((clientId, emitter) -> {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.getType())
                        .data(json));
            } catch (IOException e) {
                deadClients.add(clientId);
            }
        });

        // 끊어진 클라이언트 정리
        deadClients.forEach(emitters::remove);
    }

    public int getConnectionCount() {
        return emitters.size();
    }

    private String toJson(SseEvent event) {
        // ObjectMapper 주입하거나 간단히 직접 구성
        try {
            return new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .writeValueAsString(event);
        } catch (Exception e) {
            return "{}";
        }
    }
}
```

### SseEvent DTO

```java
@Getter
@Builder
public class SseEvent {
    private String type;          // EXECUTION_STARTED | EXECUTION_SUCCESS | EXECUTION_FAILED
    private Long logId;
    private Long interfaceId;
    private String interfaceName;
    private String status;        // RUNNING | SUCCESS | FAILED
    private Long durationMs;
    private String errorMessage;
    private LocalDateTime timestamp;

    // 빌더 후처리로 timestamp 자동 설정
    public static class SseEventBuilder {
        public SseEvent build() {
            if (this.timestamp == null) this.timestamp = LocalDateTime.now();
            return new SseEvent(type, logId, interfaceId, interfaceName,
                                status, durationMs, errorMessage, timestamp);
        }
    }
}
```

### MonitorController

```java
@RestController
@RequestMapping("/api/monitor")
@RequiredArgsConstructor
@Tag(name = "Monitor", description = "실시간 모니터링 API")
public class MonitorController {

    private final SseEmitterService sseEmitterService;
    private final MonitorService monitorService;

    /**
     * SSE 스트림 연결
     * 클라이언트는 UUID 기반 clientId를 생성해서 전달
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "실시간 실행 상태 스트림")
    public SseEmitter stream(
            @RequestParam(defaultValue = "") String clientId) {
        String id = clientId.isBlank() ? UUID.randomUUID().toString() : clientId;
        return sseEmitterService.connect(id);
    }

    /**
     * 대시보드 집계 데이터
     */
    @GetMapping("/dashboard")
    @Operation(summary = "대시보드 집계 현황")
    public ApiResponse<DashboardResponse> getDashboard() {
        return ApiResponse.success(monitorService.getDashboard());
    }

    /**
     * 현재 SSE 연결 수 확인 (디버그용)
     */
    @GetMapping("/connections")
    public ApiResponse<Integer> getConnectionCount() {
        return ApiResponse.success(sseEmitterService.getConnectionCount());
    }
}
```

### CORS 설정 (SSE는 별도 주의)

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173")  // Vite dev server
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // SSE는 credentials 없이도 동작하지만 명시
                .allowCredentials(false);
    }
}
```

---

## Frontend 구현

### SSE Composable (`src/composables/useSse.js`)

```javascript
import { ref, onUnmounted } from 'vue'

export function useSse(url) {
  const events = ref([])
  const connected = ref(false)
  const error = ref(null)

  // 클라이언트 고유 ID 생성
  const clientId = crypto.randomUUID()
  const fullUrl = `${url}?clientId=${clientId}`

  let eventSource = null

  function connect() {
    if (eventSource) return

    eventSource = new EventSource(fullUrl)

    eventSource.onopen = () => {
      connected.value = true
      error.value = null
    }

    eventSource.onerror = () => {
      connected.value = false
      error.value = '연결이 끊어졌습니다. 재연결 중...'
      // 브라우저가 자동 재연결하므로 별도 처리 불필요
    }

    // 이벤트 타입별 핸들러 등록
    const eventTypes = [
      'CONNECTED',
      'EXECUTION_STARTED',
      'EXECUTION_SUCCESS',
      'EXECUTION_FAILED',
    ]

    eventTypes.forEach(type => {
      eventSource.addEventListener(type, (e) => {
        try {
          const data = JSON.parse(e.data)
          events.value.unshift({ type, ...data })
          // 최근 100건만 유지
          if (events.value.length > 100) events.value.pop()
        } catch (err) {
          console.error('SSE parse error:', err)
        }
      })
    })
  }

  function disconnect() {
    if (eventSource) {
      eventSource.close()
      eventSource = null
      connected.value = false
    }
  }

  // 컴포넌트 언마운트 시 자동 해제
  onUnmounted(disconnect)

  return { events, connected, error, connect, disconnect }
}
```

### Pinia SSE Store (`src/stores/sseStore.js`)

```javascript
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export const useSseStore = defineStore('sse', () => {
  const recentExecutions = ref([])  // 최근 실행 이벤트

  const runningCount = computed(() =>
    recentExecutions.value.filter(e => e.status === 'RUNNING').length
  )
  const failedCount = computed(() =>
    recentExecutions.value.filter(e => e.status === 'FAILED').length
  )

  function handleEvent(type, data) {
    if (type === 'CONNECTED') return

    // 같은 logId의 기존 이벤트 업데이트 또는 추가
    const idx = recentExecutions.value.findIndex(e => e.logId === data.logId)
    if (idx !== -1) {
      recentExecutions.value[idx] = { type, ...data }
    } else {
      recentExecutions.value.unshift({ type, ...data })
      if (recentExecutions.value.length > 50) recentExecutions.value.pop()
    }
  }

  return { recentExecutions, runningCount, failedCount, handleEvent }
})
```

### Dashboard 페이지에서 SSE 사용 (`src/pages/Dashboard.vue`)

```vue
<template>
  <v-container>
    <!-- 연결 상태 표시 -->
    <v-alert v-if="!connected" type="warning" density="compact" class="mb-4">
      {{ sseError || '실시간 연결 중...' }}
    </v-alert>

    <!-- 요약 카드 -->
    <v-row class="mb-4">
      <v-col cols="12" md="3">
        <v-card color="success" variant="tonal">
          <v-card-text class="text-center">
            <div class="text-h4">{{ dashboard.successCount }}</div>
            <div>오늘 성공</div>
          </v-card-text>
        </v-card>
      </v-col>
      <v-col cols="12" md="3">
        <v-card color="error" variant="tonal">
          <v-card-text class="text-center">
            <div class="text-h4">{{ sseStore.failedCount + dashboard.failedCount }}</div>
            <div>실패</div>
          </v-card-text>
        </v-card>
      </v-col>
      <v-col cols="12" md="3">
        <v-card color="info" variant="tonal">
          <v-card-text class="text-center">
            <div class="text-h4">{{ sseStore.runningCount }}</div>
            <div>실행 중</div>
          </v-card-text>
        </v-card>
      </v-col>
    </v-row>

    <!-- 실시간 이벤트 피드 -->
    <v-card variant="outlined">
      <v-card-title class="d-flex align-center">
        실시간 실행 피드
        <v-chip class="ml-2" :color="connected ? 'success' : 'warning'" size="small">
          {{ connected ? 'LIVE' : '연결 중' }}
        </v-chip>
      </v-card-title>
      <v-list density="compact" max-height="400" style="overflow-y: auto">
        <v-list-item v-for="event in sseStore.recentExecutions" :key="event.logId + event.status">
          <template #prepend>
            <v-icon :color="statusColor(event.status)">
              {{ statusIcon(event.status) }}
            </v-icon>
          </template>
          <v-list-item-title>{{ event.interfaceName }}</v-list-item-title>
          <v-list-item-subtitle>
            {{ event.status }} {{ event.durationMs ? `(${event.durationMs}ms)` : '' }}
          </v-list-item-subtitle>
          <template #append>
            <span class="text-caption text-grey">{{ formatTime(event.timestamp) }}</span>
          </template>
        </v-list-item>
        <v-list-item v-if="!sseStore.recentExecutions.length">
          <v-list-item-title class="text-grey">실행 이벤트 없음</v-list-item-title>
        </v-list-item>
      </v-list>
    </v-card>
  </v-container>
</template>

<script setup>
import { onMounted } from 'vue'
import { useSse } from '@/composables/useSse.js'
import { useSseStore } from '@/stores/sseStore.js'

const sseStore = useSseStore()
const { connected, error: sseError, events, connect } = useSse(
  `${import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api'}/monitor/stream`
)

// SSE 이벤트 → Pinia store 연동
watch(events, (newEvents) => {
  if (newEvents.length > 0) {
    const latest = newEvents[0]
    sseStore.handleEvent(latest.type, latest)
  }
})

const statusColor = (status) =>
  ({ RUNNING: 'info', SUCCESS: 'success', FAILED: 'error' }[status] || 'grey')

const statusIcon = (status) =>
  ({ RUNNING: 'mdi-loading mdi-spin', SUCCESS: 'mdi-check-circle', FAILED: 'mdi-alert-circle' }[status] || 'mdi-circle')

const formatTime = (ts) => ts ? new Date(ts).toLocaleTimeString() : ''

onMounted(() => connect())
</script>
```

---

## 체크리스트

### Backend
- [ ] `SseEmitter` onCompletion / onTimeout / onError 모두 처리
- [ ] `ConcurrentHashMap` 으로 thread-safe 관리
- [ ] 끊어진 emitter 즉시 제거 (`deadClients` 정리)
- [ ] CORS 설정에 SSE 엔드포인트 포함
- [ ] `produces = MediaType.TEXT_EVENT_STREAM_VALUE` 명시
- [ ] 연결 직후 CONNECTED 이벤트 전송 (브라우저 연결 확인)

### Frontend
- [ ] `onUnmounted`에서 `EventSource.close()` 호출
- [ ] 이벤트 타입별 `addEventListener` 등록 (단일 `onmessage` 금지)
- [ ] 같은 `logId` 이벤트 업데이트 (중복 방지)
- [ ] 연결 상태 UI 표시 (connected 상태)
- [ ] 최대 이벤트 수 제한 (메모리 누수 방지)
