# Day 2 완료 요약 — 2026-04-20

> Day 1·2 통합 산출물 대시보드. Day 3 착수 전 참조용.

---

## 1. 문서 산출물

| 문서 | 최종 버전 | 4-에이전트 합의 |
|---|---|---|
| [planning.md](planning.md) | **v0.4** | 3회 라운드 A 수렴 |
| [erd.md](erd.md) | **v0.9** | 5회 라운드 A 수렴 (v0.1 → v0.9) |
| [api-spec.md](api-spec.md) | **v0.6** | 3회 라운드 A 수렴 |
| [backlog.md](backlog.md) | v0.1 | Day 3 이후 이월 항목 |
| [adr/ADR-001-execution-log-transaction.md](adr/ADR-001-execution-log-transaction.md) | 확정 | B안 (2-TX 분리) |
| [adr/ADR-004-concurrent-execution-prevention.md](adr/ADR-004-concurrent-execution-prevention.md) | 확정 | C안 (advisory lock + uk_log_running) |
| [adr/ADR-006-config-json-validator-call-site.md](adr/ADR-006-config-json-validator-call-site.md) | 확정 | A안 (Service 호출 + ArchUnit) |

**본문 편입 결정** (별도 ADR 없음):

- ADR-002 SSE 브로드캐스트 → [planning §5.4](planning.md)
- ADR-003 JSONB 저장 → [erd §10.1·10.2](erd.md) `@JdbcTypeCode(SqlTypes.JSON)`

---

## 2. 코드 산출물

### 프로젝트 초기화 (Day 1)

- [docker-compose.yml](../docker-compose.yml) v0.2 — PostgreSQL 16-alpine + Adminer (dev-tools profile)
- [.env.example](../.env.example) — 로컬 환경변수 템플릿
- [.gitignore](../.gitignore) — `.env` 변종·시크릿·IDE 메타 차단
- [docker/postgres/init/01-extensions.sql](../docker/postgres/init/01-extensions.sql) — `pg_trgm` 확장

### 백엔드 코드 (Day 2)

**설정·엔트리포인트** (6파일)

- [backend/build.gradle](../backend/build.gradle) / [settings.gradle](../backend/settings.gradle)
- [backend/src/main/java/com/noaats/ifms/IfmsApplication.java](../backend/src/main/java/com/noaats/ifms/IfmsApplication.java)
- [backend/src/main/resources/application.yml](../backend/src/main/resources/application.yml) (+ local, test)
- [backend/src/main/resources/schema.sql](../backend/src/main/resources/schema.sql) — CHECK 7종 + 인덱스 2종

**도메인 코어** (9파일, Day 2 1단계)

- `domain/interface_/domain/` — `InterfaceConfig.java`, `ProtocolType.java`, `ScheduleType.java`, `InterfaceStatus.java`
- `domain/interface_/repository/InterfaceConfigRepository.java`
- `domain/interface_/dto/` — `InterfaceConfigRequest.java`, `InterfaceConfigListView.java`, `InterfaceConfigDetailResponse.java`
- `global/audit/BaseTimeEntity.java`

**2-A global 인프라** (16파일)

| 카테고리 | 파일 |
|---|---|
| response | `ApiResponse.java`, `ErrorDetail.java` (record) |
| exception | `ErrorCode.java` (enum 18종), `ApiException.java` (sealed), `BusinessException.java`, `ConflictException.java`, `NotFoundException.java`, `GlobalExceptionHandler.java` |
| validation | `SensitiveKeyRegistry.java` (16종), `ConfigJsonViolation.java` (record), `ConfigJsonValidator.java` |
| masking | `MaskingRule.java` (정규식 5종 + Luhn), `DefensiveMaskingFilter.java` (ResponseBodyAdvice) |
| config | `PaginationConstants.java`, `SaltValidator.java`, `WebMvcConfig.java`, `SecurityConfig.java` (임시 permitAll) |

**2-B 도메인 API** (5신규 + 3수정 + 1삭제)

- 신규: `InterfaceConfigService.java`, `InterfaceConfigSpecification.java`, `InterfaceController.java`, `InterfaceFilterParams.java`, `InterfaceConfigSnapshot.java` (record)
- 수정: `InterfaceConfigRequest.java` (statusChange + @AssertTrue), `InterfaceConfigDetailResponse.java` (Masker 호출 제거), `GlobalExceptionHandler.java` (ObjectProvider<Service> 전환), `InterfaceConfig.java` (`@UniqueConstraint(name="uk_ifc_name")`), `ErrorCode.java` (`NOT_IMPLEMENTED(501)`)
- 삭제: `ConfigJsonMasker.java` (잠정 유틸 → MaskingRule + DefensiveMaskingFilter로 대체)

### 빌드 상태

**BUILD SUCCESSFUL** (Gradle 8.10.2 + JDK 17 + Spring Boot 3.3.5 + Hibernate 6.5.3).

---

## 3. 주요 설계 결정 요약

| 영역 | 결정 | 근거 |
|---|---|---|
| Java 버전 | **17 LTS** | Virtual Thread 사용처 없음, 환경 JDK 17 기본 |
| JSON 매핑 | `@JdbcTypeCode(SqlTypes.JSON)` | hypersistence-utils 아티팩트 부재로 Hibernate 네이티브 채택 |
| ExecutionLog 트랜잭션 | **2-TX 분리** (TX1 RUNNING + 커밋 → @Async TX2) | ADR-001, JVM crash 시 감사 누락 0건 |
| 동시 실행 차단 | advisory lock + `uk_log_running` 부분 UNIQUE 2+1중 | ADR-004 |
| ConfigJsonValidator 호출 | **Service 수동 호출 + ArchUnit 정적 차단** | ADR-006, EntityListener silent null 방어 |
| 예외 계층 | sealed `ApiException` → `Business`/`Conflict`/`NotFound` 3종 | HTTP status 컴파일 타임 보증 |
| 민감정보 방어 | 2단 마스킹 (Service `MaskingRule` 1차 + `DefensiveMaskingFilter` 2차) | Defense in Depth |
| PATCH 통합 | `statusChange` body 필드로 activate/deactivate 통합 | RESTful 일관성 |

---

## 4. 아직 실행되지 않은 검증

| 검증 | 상태 | 책임 |
|---|---|---|
| `./gradlew bootRun` 실 기동 | ❌ 미수행 | Day 3 착수 전 수동 확인 |
| Swagger UI `/swagger-ui.html` 접근 | ❌ 미수행 | 위와 동일 |
| 5개 엔드포인트 try-it-out | ❌ 미수행 | 위와 동일 |
| PostgreSQL `schema.sql` 실행 결과 (`\d interface_config`) | ❌ 미수행 | 위와 동일 |
| Testcontainers 통합 테스트 | ⏳ Day 7 이월 | [backlog.md](backlog.md) |
| ArchUnit 규칙 | ⏳ Day 4 이월 | ADR-006 후속 조치 |

### 실 기동 절차 (권장)

```bash
# 1. 환경변수 준비
cd c:\project\erp
copy .env.example .env

# 2. PostgreSQL 기동
docker compose up -d

# 3. 백엔드 기동
cd backend
.\gradlew.bat bootRun

# 4. 접속 확인
#   - Swagger: http://localhost:8080/swagger-ui.html
#   - 목록:    GET http://localhost:8080/api/interfaces
#   - 등록:    POST http://localhost:8080/api/interfaces (body 참고: api-spec.md §4.3)
```

---

## 5. Day 3 착수 준비

Day 3 작업 목록(요약):

1. ADR-005 회의 — 재처리 체인·max_retry 정책
2. `ExecutionLog` Entity + Repository + schema.sql 제약·인덱스 확장
3. `ExecutionService` + `MockExecutorFactory` 및 5 프로토콜 구현체
4. `execute` 엔드포인트 stub(501) → 실 구현(201) 교체
5. `OrphanRunningWatchdog` + `@EnableScheduling` 재도입

상세 Day 3 이월 항목은 [backlog.md](backlog.md) 참조.
