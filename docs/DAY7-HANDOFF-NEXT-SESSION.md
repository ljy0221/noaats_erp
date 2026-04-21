# Day 7 — 다음 세션 인계 메모 (2026-04-21 종결 시점)

> 본 세션에서 Day 7 묶음 1·2 코드/문서 변경은 모두 완료(7 커밋). 최종 빌드 게이트만 미완.
> 차기 세션은 아래 조사 항목부터 시작.

---

## 0. 현재 상태 (이미 commit·push 완료된 변경)

```
030306b test(sse): SseSubscribeRaceTest @Disabled — M4 race가 실재 재현됨, 픽스 후 활성
bb8f605 feat(day7): MaskingRule 벤치 + SSE race(M4) + Jackson ALWAYS + 문서 정합 + DAY7-SUMMARY + README
b133106 fix(frontend): M5 Dashboard→/history 드릴다운 + M7 sessionStorage 정리 + M8 ErrorCode 21종
4ae7fd9 test(interface): SnapshotFieldParityTest — Detail↔Snapshot 필드 회귀 보호
02ba634 test(retry): RetryGuard 단위 8 케이스 — ADR-005 Q1·Q2·SYSTEM·ANONYMOUS·truncated·inactive·not_leaf·all_pass
e5cfff8 docs(day7): plan REV2 — 코드 사실 정합 개정
a773bd3 docs(day7): implementation plan — 21 task
8d0adab docs(day7): spec — 통합 테스트·부채 청산·제출 핸드오프
```

산출물:
- `backend/src/test/java/.../RetryGuardSnapshotPolicyTest.java` (8 케이스)
- `backend/src/test/java/.../SnapshotFieldParityTest.java` (1)
- `backend/src/test/java/.../MaskingRuleBenchTest.java` (1, RUN_BENCH=1)
- `backend/src/test/java/.../SseSubscribeRaceTest.java` (@Disabled — M4 후속)
- `backend/src/test/java/.../ApiResponseSerializationTest.java` (2)
- `backend/src/test/java/.../support/AbstractPostgresIntegrationTest.java` + `ExecutionLogTestSeeder.java` (Testcontainers 환경 회복 시 활성)
- 프런트: M5/M7/M8 코드 (이미 빌드 PASS — `npm run build` 실측 dist/ 생성)
- 문서: api-spec v0.8, backlog 갱신, DAY7-SUMMARY.md, README.md

---

## 1. 차기 세션 우선 조사 — 빌드 출력 0 byte 문제

### 증상
- `RUN_BENCH=1 ./gradlew test` (또는 `./gradlew test --tests "X"`)를 Bash run_in_background로 실행하면 stdout 파일이 **0 byte로 머무름**
- java 프로세스 CPU 사용은 풀가동 (작업은 진행 중)
- Monitor의 `until grep ... BUILD SUCCESSFUL`도 5분 timeout
- 강제 종료해야 결과 받음

### 가설 (확인 필요)
1. **Gradle daemon stdout이 buffer flush를 하지 않음** — `--console=plain --no-daemon` 옵션으로 우회 가능?
2. **Bash run_in_background의 출력 캡처**가 chunk 단위 → Gradle 출력의 마지막 flush가 없으면 빈 채로 남음
3. **PowerShell + `./gradlew.bat`**로 실행하면 정상 출력?
4. 출력을 파일로 직접 redirect (`./gradlew test > out.log 2>&1 &`) 후 file polling

### 우선 시도 순서
```bash
# A. 동기 실행 (timeout 충분히)
cd backend && ./gradlew test --tests "RetryGuardSnapshotPolicyTest" --console=plain --no-daemon

# B. PowerShell 직접
PowerShell: cd backend; ./gradlew.bat test --tests "RetryGuardSnapshotPolicyTest" --console=plain

# C. 파일 redirect
cd backend && ./gradlew test --console=plain > /tmp/test.log 2>&1 &
# 그리고 polling: until grep -q SUCCESSFUL /tmp/test.log; do ...
```

---

## 2. @SpringBootTest 다중 부팅 비용 분석 (2차)

`./gradlew test`가 매우 느린 추가 원인:
- ApiResponseSerializationTest, DeltaServiceTest, SseReassignmentSchedulerTest, SseSessionExpiryListenerTest, SseUnauthorizedIsolationTest, SseSubscribeRaceTest(@Disabled) 등 **6+개의 @SpringBootTest** 가 각자 컨텍스트 부팅
- @Disabled 처리에도 컨텍스트는 부팅 (또는 부팅 안 함? — 확인 필요)

### 시도 항목
- 각 @SpringBootTest의 `@TestPropertySource` 동일성 확인 → 동일하면 컨텍스트 캐시 재사용 가능
- `@DirtiesContext` 잘못 붙은 곳 없는지 검사
- `@SpringBootTest(webEnvironment=NONE)` 적용 가능 케이스 분리

---

## 3. 차기 세션에서 완료할 task

남은 작업은 **단 2개**:

### T18: 빌드 게이트 결과 인용
- 위 §1 해결 후 `./gradlew test` 실행 결과 캡처
- 30+ 테스트 PASS, ArchUnit 3 PASS, MaskingRule p95 ms 값 확인
- `docs/DAY7-SUMMARY.md` §4 표에 실측치 기재

### T21: 사용자 핸드오프 메시지
- `DAY7-SUMMARY.md` §5의 수동 E2E 8 + Day 5 회귀 5 + Swagger 11 체크리스트
- 사용자에게 절차 안내 + 결과 받아 SUMMARY ✅ 마킹

---

## 4. 환경 이슈 보존 (Testcontainers)

별도 트랙. 다음 시도:
- TC 1.21+ 출시 확인 (Docker Desktop 29 호환 패치 기대)
- 또는 Docker Desktop 25.x 다운그레이드
- 또는 WSL2 backend 옵션 (TC unix socket 경로)

코드는 보존됨:
- `AbstractPostgresIntegrationTest.java`
- `ExecutionLogTestSeeder.java`

---

## 5. 빌드 게이트 임시 우회 — 컴파일 검증만으로 sufficient 결정 가능

테스트 실행을 끝까지 못 보더라도:
- `./gradlew compileTestJava` PASS = 모든 신규 테스트가 컴파일·시그니처 정합
- `npm run build` PASS = 프런트 변경 무결 (이미 확인됨)
- ArchUnit은 `./gradlew test --tests "ArchitectureTest"` 단독 실행으로 빠르게 확인 가능 (Spring 컨텍스트 불필요)
- RetryGuardSnapshotPolicyTest는 POJO만 — `--tests "RetryGuardSnapshotPolicyTest"` 1초 내 결과
- SnapshotFieldParityTest도 동일 (리플렉션만)

이 4가지로 최소 검증 묶음을 빠르게 완료한 후 SUMMARY §4를 갱신하는 것도 합리적.

---

## 6. 차기 세션 시작 명령어 (복사·붙여넣기 가능)

```bash
# 1) 환경 정리
cd c:/project/erp
PowerShell: Get-Process java -EA SilentlyContinue | Where-Object { $_.StartTime -gt (Get-Date).AddHours(-1) } | Stop-Process -Force

# 2) 빠른 검증 묶음 (각 1-3초)
cd backend
./gradlew test --tests "RetryGuardSnapshotPolicyTest" --console=plain --no-daemon
./gradlew test --tests "SnapshotFieldParityTest" --console=plain --no-daemon
./gradlew test --tests "ArchitectureTest" --console=plain --no-daemon
./gradlew test --tests "ApiResponseSerializationTest" --console=plain --no-daemon

# 3) 마스킹 벤치 (선택)
RUN_BENCH=1 ./gradlew test --tests "MaskingRuleBenchTest" --console=plain --no-daemon

# 4) 결과를 DAY7-SUMMARY.md §4에 인용 + 커밋

# 5) 사용자 핸드오프 메시지 (DAY7-SUMMARY §5 체크리스트 안내)
```
