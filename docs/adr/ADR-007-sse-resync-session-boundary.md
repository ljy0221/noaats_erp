# ADR-007: SSE 재동기화 및 세션 경계 프로토콜

**상태**: 결정됨
**날짜**: 2026-04-20
**관련**: ADR-002(SSE 선택), api-spec §3.3·§6.1, DAY6 spec

## 컨텍스트
Day 4까지 SSE 링버퍼(1000건/5분)와 clientId 메서드(`clientIdBoundToOtherSession`)는 구현됐으나, 실 재연결 경로·장시간 단절 복구·세션 만료 처리가 프런트와 묶여 미완. Day 6에 이 4개 쟁점을 통합 결정.

## 결정

### R1. delta 커서
- `GET /api/executions/delta?since=ISO8601|cursor=base64(ISO8601)&limit=500` (max 1000)
- startedAt 단독 커서, base64 캡슐화
- `limit+1` 조회 → `truncated=true` 판정, 마지막 1건 drop, `nextCursor = last.startedAt` base64

### R2. delta 보안·감사
- since 하한 "지금 - 24시간", 초과 시 400 DELTA_SINCE_TOO_OLD
- actor 60초/10회 in-memory rate limit (ConcurrentHashMap 슬라이딩 윈도우), 초과 시 429 DELTA_RATE_LIMITED
- 성공/실패 모두 감사 로그 1줄(actor·since·returned·truncated·limit)

### R3. clientId 재할당
- `subscribe` 진입 시 동일 clientId가 타 세션에 바인딩 중이면 → 이전 emitter 2초 delayed complete + 새 세션에 즉시 재할당
- grace 2초 동안 이벤트는 새 emitter에만 라우팅
- 감사 1줄: `event=CLIENT_ID_REASSIGNED`

### R5. SSE UNAUTHORIZED
- 세션 만료 감지 시(HttpSessionListener) 해당 세션의 모든 emitter에 `event: UNAUTHORIZED` 송출 후 complete
- 프런트 onmessage에서 close+logout+router push, router.beforeEach에서 보조 close
- 감사 1줄: `event=SSE_DROPPED_ON_SESSION_EXPIRY`

## 근거
- 명세 정합성: EventSource.onerror가 HTTP 상태 미노출이라 401 기반 설계는 구조적 결함
- 1주일 일정: 복합 커서·신규 인덱스를 Day 6 범위에서 제외(인덱스 신설은 운영 이관), 그 여유로 R5를 Day 7 → Day 6로 당김
- append-only 감사 무결성: 조회 감사 1줄, 재할당 감사 1줄, 만료 감사 1줄로 추적 보장

## 트레이드오프
- startedAt 마이크로초 경계 행 1건 유실 가능 — 원본 DB append-only 보존으로 수용
- in-memory rate limit은 단일 인스턴스 전제 — 분산 전환은 운영 이관
- grace 2초 동안 이중 emitter 존재 — Registry 내부에서 새 emitter로만 라우팅 강제

## 기각된 대안
- DBA 복합 커서(startedAt,id) + idx_log_started_at_id_asc 신설: Day 6 범위 초과
- 409 CLIENT_ID_BOUND_TO_OTHER_SESSION 엄격 거절: F5 UX 저하, 세션 하이재킹 탐지 불가
- 프런트 dedup Set: 상태 전이 손실로 감사 표시 일관성 훼손

## 후속
- 복합 커서·분산 rate limit은 운영 이관 backlog
- UNAUTHORIZED 송출 경로의 WAF/프록시 호환성은 Day 7 통합 테스트에서 검증
