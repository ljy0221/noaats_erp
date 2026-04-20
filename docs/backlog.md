# 백로그 — Day 3 이후 이월 항목

> Day 2 완료 시점(2026-04-20) 기준. 4-에이전트 회의에서 "수렴 가능 + 이월 가능"으로 판정된 항목을
> 추적 가능한 형태로 모아둔다. 각 항목은 관련 Day, 예상 작업량, 차단 여부를 병기.

---

## Day 3·4 (완료) — 이력만 보관, 다음 정리 시 삭제

| 항목 | 상태 |
|---|---|
| ~~`execute` 엔드포인트 501 → 201~~ | ✅ Day 3 완료 |
| ~~ADR-005 회의~~ | ✅ Day 3 확정 (Q1=C/Q2=C/Q3=A/Q4=A) |
| ~~`execution_log` schema.sql~~ | ✅ Day 3 완료 |
| ~~`OrphanRunningWatchdog` + `@EnableScheduling`~~ | ✅ Day 3 완료 |
| ~~`MockExecutor` 5종~~ | ✅ Day 3 완료 |
| ~~`RetryService` + `RetryGuard` (ADR-005)~~ | ✅ Day 3 완료 |
| ~~`SecurityConfig` 완전판 교체~~ | ✅ Day 4 완료 (세션+CSRF+필터 순서+401/403 handler) |
| ~~`SseEmitterService` + `Last-Event-ID` + 링버퍼 1,000건/5분~~ | ✅ Day 4 완료 (실 기동 검증) |
| ~~`ConnectionLimitFilter` (세션 3 · 계정 10)~~ | ✅ Day 4 완료 (429 경계 검증) |
| ~~ArchUnit 3종 규칙 (ADR-006 후속)~~ | ✅ Day 4 완료 (Controller Repository 주입 정리) |
| ~~`traceId` 통합 필터 (`OncePerRequestFilter`)~~ | ✅ Day 4 완료 (X-Trace-Id 헤더 확인) |
| ~~`ExecutionEventPublisher` stub → SseEmitterService 교체~~ | ✅ Day 4 완료 |
| ~~`ActorContext.resolveActor` 세션 기반 실 구현~~ | ✅ Day 4 완료 |

## Day 4 이후 이월 (운영·후속 품질)

| 항목 | 출처 | 비고 |
|---|---|---|
| `SaltValidator` 운영 모드 검증 | api-spec §2.3 | 현재 prod 프로파일 미사용 |
| `DefensiveMaskingFilter` XML StAX 단계 3 추가 (SOAP 대응) | erd.md §3.2 | ExecutionLog XML payload 도입 시 |
| `@PreAuthorize` Role 기반 접근 제어 초안 | api-spec.md §2.4 | 현재 단일 OPERATOR만, 분리는 설계만 |
| `RetryGuard` Role=ADMIN 분기 추가 | api-spec §5.3 | 운영 전환 시 actor 무관 재처리 허용 |
| ~~SSE `UNAUTHORIZED` 이벤트 + `SSE_DROPPED_ON_SESSION_EXPIRY` 감사 로그~~ | api-spec §6.1 | ✅ Day 6 회수 (ADR-007 R5) |
| ~~SSE `clientIdBoundToOtherSession` 스푸핑 차단 Controller 호출~~ | Day 4 Registry 구현 | ✅ Day 6 재할당 방식 채택 (ADR-007 R3) |
| ~~`?since=` 폴백 쿼리 (`GET /api/executions?since=`)~~ | api-spec §3.3 | ✅ Day 6 /api/executions/delta 신설 (ADR-007 R1) |
| `@ConfigurationProperties` 레코드 빈 이름 SpEL 패턴 문서화 | Day 4 Bug A 후속 | 향후 `@Scheduled` 추가 시 실수 방지 |

## Day 5 (완료) — 이력만 보관, 다음 정리 시 삭제

| 항목 | 상태 |
|---|---|
| ~~Vuetify 3 스파이크 + 프런트 프로젝트 scaffold~~ | ✅ Day 5 완료 (Vite 8 + TS 6 + Vuetify 3) |
| ~~Axios withCredentials + XSRF 자동 동봉 + `ApiResponse<T>` 언래핑 인터셉터~~ | ✅ Day 5 완료 |
| ~~세션 인증(Login.vue) + auth guard + 401 전역 핸들링~~ | ✅ Day 5 완료 |
| ~~`InterfaceList.vue` (v-data-table-server + 필터 3종 + 페이지네이션)~~ | ✅ Day 5 완료 |
| ~~`InterfaceFormDialog.vue` (등록·수정, configJson JSON 검증, 프로토콜·스케줄 조건부 필드)~~ | ✅ Day 5 완료 |
| ~~`OptimisticLockDialog.vue` (`OPTIMISTIC_LOCK_CONFLICT` diff + 3옵션)~~ | ✅ Day 5 완료 (api-spec §3.5) |
| ~~인터페이스 수동 실행 버튼 + 에러 코드 4종 분기~~ | ✅ Day 5 완료 |

## Day 5~6 (프론트 Vue) — 진행 중

| 항목 | 출처 |
|---|---|
| **Day 5 E2E 수동 검증** (백엔드 재기동 포함, 기존 PID 20716 상태 불일치 확인) | DAY5-SUMMARY §6 |
| `ExecutionHistory.vue` + 상세 다이얼로그 + 재처리 버튼 <!-- Day 5/6 진행 중 --> | planning.md §8, api-spec §5 |
| `Dashboard.vue` (totals·byProtocol·recentFailures·sseConnections) <!-- Day 5/6 진행 중 --> | api-spec.md §6.2 |
| SSE `EventSource` + `Last-Event-ID` 자동 처리 + `?since=` 폴백 <!-- Day 6 진행 중 --> | api-spec.md §6.1, §3.3 |
| `OPTIMISTIC_LOCK_CONFLICT` 재충돌 재귀 동작 E2E 검증 | api-spec.md §3.5 |
| `v-data-table-server` 정렬 헤더 클릭 → `sort` 파라미터 동기화 | DAY5-SUMMARY §6 |
| `configJson` 금지 키 클라이언트 선검증 | DAY5-SUMMARY §6 UX 개선 |

## Day 7 (통합 테스트 · 문서 정리 · 제출)

| 항목 | 출처 |
|---|---|
| **Testcontainers PostgreSQL** 통합 테스트 — JSONB `@>`, GIN, advisory lock 검증 | erd.md §13 체크리스트 |
| `RetryService` 통합 테스트 — FAILED 시드 후 retry 시퀀스, 5종 에러 코드 분기 (ADR-005 §5) | DAY3-SUMMARY §6 |
| ADR-005 Q1 단위 테스트 — `max_retry_count` PATCH 후 진행 중 체인이 옛 `max_retry_snapshot` 유지 | erd §13 ADR-005 항목 |
| ADR-005 Q2 단위 테스트 — 멀티홉 체인에서 루트 actor 기준 `RETRY_FORBIDDEN_ACTOR` | erd §13 ADR-005 항목 |
| `OrphanRunningWatchdog` 5분 sweep 동작 검증 — Mock 무한 sleep 주입 후 회수 확인 | DAY3-SUMMARY §6 |
| `SnapshotFieldParityTest` — `InterfaceConfigDetailResponse` ↔ `InterfaceConfigSnapshot` 필드 일치 (configJson만 차이) | Architect Day 2-B SHOULD |
| `DefensiveMaskingFilter` p95 < 50ms 벤치 (size=100 × 64KB) | api-spec.md §3.4 |
| Rate limit (actor·IP 기준) — 트리거 API 오남용 방어 | Security Day 2-B SHOULD · ADR-004 |
| Swagger UI 전체 엔드포인트 try-it-out 회귀 | api-spec.md §10 체크리스트 |
| Jackson `null` inclusion 정책(`spring.jackson.default-property-inclusion`) `ALWAYS` 명시 | DevilsAdvocate Day 2-A Q |
| `ApiResponse` 불변식 Javadoc (`success=true`일 때만 `data` 경로 마스킹) | DevilsAdvocate Day 2-A 경미 |
| **문서 정합 정리** — `ErrorCode` 18종 (NOT_IMPLEMENTED 포함), api-spec.md §1.3 표 보강 | Day 2-B 수렴 시 이월 |
| **Repository Method 캐시화** — PSQLException 리플렉션 성능 (선택적) | DBA Day 2-A 경미 |

## 운영 전환 (범위 밖, 기록만)

| 항목 | 트리거 |
|---|---|
| `MockExecutor` → `RealExecutor` 전환 (프로토콜별 구현체) | planning.md §12.1 |
| Flyway · Liquibase 마이그레이션 도입 (ddl-auto=validate) | erd.md §12.2 |
| `execution_log` 월 파티션 도입 | erd.md §7.3 |
| `pg_trgm` GIN 인덱스 도입 (name 부분 일치) | erd.md §4.1 · api-spec.md §4.1 — 인터페이스 1만 건 초과 시 |
| 감사 로그 해시 체인/무결성 서명 | planning.md §12.2 |
| Vault/Secrets Manager 연동 — `config_json.secretRef` 실동작 | erd.md §3.3 |
| Role 분리 `ADMIN`/`OPERATOR`/`AUDITOR` | api-spec.md §2.4 |
| 분산 SSE (Redis Pub/Sub) | planning.md §12.2 |
| OFFSET → Keyset(cursor) 페이지네이션 전환 — 100만 건 초과 임계 | erd.md §7.2 |
| EntityListener 복원 재검토 — 팀 규모 3명 이상 전환 | ADR-006 수용 리스크 |
| advisory lock 네임스페이스 주입형 실제 운영 값 부여 | api-spec.md §9.1 · ADR-004 |
| `MASK` 리터럴 write 경로 reject | Security Day 2-A SHOULD |
| advisory lock SQL `(int,int)` → 1-arg bigint 인코딩 전환 (`(namespace<<32)\|(id&0xFFFFFFFF)`) | Day 3 Bug A 후속, IDENTITY가 INT 범위(~21억) 임계 접근 시 |
| INACTIVE 격리와 별개의 `MAINTENANCE` 상태 분리 — "신규 차단 + 재처리 허용" | ADR-005 Q3 Future Work |
| `max_retry_snapshot` 정책 변경의 활성 체인 가시화 대시보드 패널 | ADR-005 Q1 수용 리스크 |

---

## 원칙

- 본 파일은 **이월 추적 전용**. 구현된 항목은 해당 Day 완료 시 삭제
- 각 항목은 관련 문서 섹션(ADR, erd §, api-spec §)을 링크해 역추적 가능하도록 유지
- 4-에이전트 회의에서 A 이상 수렴된 항목만 여기에 들어옴 (단독 의견은 배제)
