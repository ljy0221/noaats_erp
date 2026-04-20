# Multi-Agent 기술 회의 프로토콜

## 목적
기술 결정이 필요한 시점에 4명의 에이전트가 다자간 회의를 통해
근거 있는 결정을 내리고 ADR로 기록한다.

---

## 에이전트 구성

| 에이전트 | 파일 | 핵심 관점 |
|---|---|---|
| @Architect | `.claude/agents/architect.md` | 전체 설계, 트레이드오프, 회의 사회 |
| @Security | `.claude/agents/security.md` | 금융 보안, 감사 로그, 취약점 |
| @DBA | `.claude/agents/dba.md` | ERD, 인덱스, 쿼리 성능, 정합성 |
| @DevilsAdvocate | `.claude/agents/devils-advocate.md` | 반론, 엣지 케이스, 현실성 검증 |

---

## 회의 소집 조건

아래 상황에서만 회의를 소집한다. 구현 중 세부 사항은 스킬 파일 참조로 충분하다.

```
✅ 회의 소집 대상
- 새로운 테이블/도메인 설계 결정
- API 설계 원칙 결정
- 보안 정책 결정 (인증 방식, 권한 제어)
- 비동기/트랜잭션 전략 결정
- 기술 스택 변경 결정
- "이게 맞나?" 싶을 때

❌ 회의 불필요 (스킬 파일로 해결)
- 코드 구현 세부 사항
- 변수명, 메서드명
- 단순 버그 수정
- UI 레이아웃 결정
```

---

## 회의 진행 순서

```
1. 안건 상정 (사용자 또는 Architect)
        ↓
2. @Security 보안 관점 검토
        ↓
3. @DBA 데이터 관점 검토
        ↓
4. @DevilsAdvocate 반론 제기
        ↓
5. @Architect 트레이드오프 정리 + 결정 선언
        ↓
6. ADR 작성 → docs/adr/ 저장
```

---

## 회의 소집 명령어

Claude Code에서 아래 형식으로 요청한다:

```
/meeting [안건 제목]

컨텍스트:
- 배경 설명
- 결정해야 할 내용
- 관련 코드/설계 (있으면 첨부)

참석: @Architect @Security @DBA @DevilsAdvocate
```

### 특정 에이전트만 호출

```
/consult @Security
검토 요청: JWT 토큰 만료 시간 설정
- Access Token: 현재 설정 없음
- Refresh Token: 미구현 상태
```

---

## 회의 출력 형식

```markdown
# 기술 회의: {안건 제목}
일시: {날짜}

## 안건
{내용}

---

## @Security 검토
{보안 검토 내용}

---

## @DBA 검토
{DB 설계 검토 내용}

---

## @DevilsAdvocate 반론
{반론 및 엣지 케이스}

---

## @Architect 결정

### 트레이드오프 정리
| 옵션 | 장점 | 단점 |
|---|---|---|
| A | ... | ... |
| B | ... | ... |

### 결정
**{최종 결정 내용}**

### 수용한 리스크
- {리스크}: {수용 근거}

---

## ADR
{ADR 내용 — architect.md 형식 따름}
```

---

## ADR 저장 위치

```
docs/
└── adr/
    ├── ADR-001-execution-log-transaction.md
    ├── ADR-002-sse-vs-websocket.md
    ├── ADR-003-payload-storage-jsonb.md
    └── ...
```

---

## 이 프로젝트 예상 회의 목록

| 번호 | 안건 | 소집 시점 |
|---|---|---|
| ADR-001 | ExecutionLog 트랜잭션 범위 | Day 2 백엔드 시작 전 |
| ADR-002 | SSE vs WebSocket 최종 확정 | Day 2 |
| ADR-003 | payload 저장 방식 (TEXT vs JSONB) | Day 2 ERD 설계 |
| ADR-004 | 동시 실행 중복 방지 전략 | Day 3 실행기 구현 전 |
| ADR-005 | 재처리 최대 횟수 정책 | Day 3 |
| ADR-006 | API 인증 방식 (프로토타입 수준) | Day 2 |

---

## 회의 효율화 규칙

1. **시간 제한**: 한 안건당 에이전트별 발언은 핵심만 (장황한 설명 금지)
2. **결론 우선**: Architect가 결론 먼저 선언 후 근거 보충 가능
3. **기록 의무**: 모든 결정은 ADR로 저장 (나중에 "왜 이렇게 했지?" 방지)
4. **번복 원칙**: ADR이 있는 결정을 번복할 때는 반드시 새 ADR 작성
5. **프로토타입 현실**: "완벽한 설계"보다 "1주일 안에 동작하는 설계" 우선
