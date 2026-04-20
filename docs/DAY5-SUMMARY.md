# Day 5 완료 요약 — 2026-04-20 (역구축)

> Day 5 요약이 실제 Day 5 진행 중 작성되지 않아, Day 6 착수 시점에 frontend/ 디렉토리 실제 산출물을 기반으로 역구축함. 원본 커밋 타임스탬프는 `git log -- frontend/` 로 참조 가능.

## 1. 목적

Day 5 범위(인터페이스 CRUD 프런트)를 점검하고 Day 6(ExecutionHistory·Dashboard·SSE 재동기화) 착수의 기반을 정리.

## 2. 산출물 인벤토리

### 2-A 스캐폴딩

- Vue 3.5.32 + Vuetify 3.12.5 + Vite 8.0.9 + TypeScript 6.0.2 + Pinia 2.3.1 + vue-router 4.6.4 + axios 1.15.1 + @mdi/font 7.4.47 (package.json 기준)
- devDeps: @vitejs/plugin-vue 6.0.6, @vue/tsconfig 0.9.1, sass-embedded 1.99.0, vite-plugin-vuetify 2.1.3, vue-tsc 3.2.7, @types/node 24.12.2
- 빌드: `npm run build` (`vue-tsc -b && vite build`)
- 진입점: `frontend/src/main.ts` + `frontend/src/App.vue` + `frontend/src/env.d.ts` (CSS side-effect + *.vue shim 타입 선언)

### 2-B 화면·컴포넌트

| 파일 | 역할 |
|---|---|
| `src/pages/Login.vue` | 로그인 화면 (formLogin POST, CSRF prime, 401 메시지 처리) |
| `src/pages/InterfaceList.vue` | 인터페이스 목록(`v-data-table-server` 서버 페이지네이션 + status/protocol/name 필터 + 행별 편집·수동 실행) |
| `src/pages/NotFound.vue` | 404 안내 페이지 |
| `src/components/AppShell.vue` | 상단 앱바 + 네비게이션 드로어 + router-view 슬롯 + 로그아웃 버튼 |
| `src/components/InterfaceFormDialog.vue` | 인터페이스 등록·수정 통합 다이얼로그 (프로토콜별 httpMethod/schedule 조건부, configJson JSON.parse 검증) |
| `src/components/OptimisticLockDialog.vue` | 낙관적 락 충돌 diff 다이얼로그 (서버값/내값 재시도/취소 3-옵션) |

### 2-C API·상태·라우팅

| 파일 | 역할 |
|---|---|
| `src/api/client.ts` | Axios 싱글턴. `withCredentials=true`, XSRF 쿠키/헤더 자동 동봉, `ApiResponse<T>` 언래핑 인터셉터, 401 이벤트 브로드캐스트 |
| `src/api/types.ts` | 도메인 유니온(ProtocolType/ScheduleType/InterfaceStatus/ExecutionStatus/TriggerType) + ErrorCode + `ApiResponse<T>`/`Page<T>`/요청·응답 DTO + `ApiError` 클래스 |
| `src/api/auth.ts` | `login()` (URLSearchParams formLogin), `primeCsrf()`, `logout()`, `probeAuthenticated()` |
| `src/api/interfaces.ts` | 인터페이스 CRUD + 수동 실행(`list/get/create/update/execute`) |
| `src/stores/auth.ts` | Pinia 인증 스토어 (`authenticated`/`username`/`ready`, `bootstrap`/`login`/`logout`, 401 리스너 등록) |
| `src/stores/toast.ts` | Pinia 토스트 스토어 (success/error/warning/info shorthand) |
| `src/router/index.ts` | `/login`·`/`·`/interfaces`·catch-all 라우트 + `beforeEach` auth 가드 + `redirect` 쿼리 복원 |

### 2-D 테마·플러그인

| 파일 | 역할 |
|---|---|
| `src/plugins/vuetify.ts` | Vuetify 인스턴스 — 테마(primary=#1E3A8A), MDI 아이콘셋, 컴포넌트 기본 variant(`VBtn flat`, `VTextField/Select/Textarea outlined comfortable`) |

## 3. 검증된 기능

- 로그인/로그아웃 (CSRF 쿠키+헤더 동봉, 백엔드 formLogin 200/401)
- 인터페이스 목록 페이지네이션·필터(status·protocol·name)
- 인터페이스 등록·수정 다이얼로그 + ConfigJsonValidator 연동
- 낙관적 락 충돌(OPTIMISTIC_LOCK_CONFLICT) diff 다이얼로그

## 4. Day 6 이월 항목

- `ExecutionHistory.vue`, `Dashboard.vue` 신규
- `useExecutionStream`·`useDashboardPolling` composable 신설 (현재 `src/composables/` 디렉토리 없음 — Day 6에서 신규 생성)
- Vuetify 테마 재도색 (primary `#1E4FA8`)
- SSE 클라이언트 재연결 + `Last-Event-ID` + RESYNC/UNAUTHORIZED 처리
- Day 5 화면 5 시나리오 회귀 점검 (Day 6 Phase 5)

## 5. 미수행 / 후속

- DAY5-SUMMARY 실시간 작성 누락 → 본 문서로 역구축 대체
- Day 6 착수 전 api-spec v0.7 → v0.8(Day 6) 업데이트 예정

## 6. 파일·커밋 통계 (스냅샷)

- `frontend/src/` 총 17개 파일 (Vue 7개 + TS 10개)
  - pages: 3개 (InterfaceList, Login, NotFound)
  - components: 3개 (AppShell, InterfaceFormDialog, OptimisticLockDialog)
  - composables: 0개 (디렉토리 미생성 — Day 6 신규)
  - api: 4개 (client, types, auth, interfaces)
  - stores: 2개 (auth, toast)
  - router: 1개 (index.ts)
  - plugins: 1개 (vuetify.ts)
  - 루트: 3개 (App.vue, main.ts, env.d.ts)
- Day 4 대비 `frontend/` 디렉토리가 신규 추가됨 (백엔드 무변경)
