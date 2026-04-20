# Skill: 테스트 작성 패턴

## 목적
1주일 개발 일정에서 효율적으로 테스트를 작성한다.
핵심 비즈니스 로직과 API 레이어에 집중, 커버리지보다 신뢰도 우선.

---

## 테스트 계층 선택 기준

| 테스트 유형 | 언제 쓰나 | 속도 |
|---|---|---|
| `@SpringBootTest` | 통합 테스트, 전체 컨텍스트 필요 시 | 느림 |
| `@WebMvcTest` | Controller 단독 테스트 | 빠름 |
| `@DataJpaTest` | Repository 쿼리 검증 | 중간 |
| 순수 단위 테스트 | Service 비즈니스 로직 | 매우 빠름 |

**1주일 일정에서의 우선순위**
1. Service 단위 테스트 (Mockito, 빠름)
2. Controller 슬라이스 테스트 (`@WebMvcTest`)
3. Repository 테스트 (H2, 핵심 쿼리만)
4. 통합 테스트 (시간 있을 때만)

---

## 테스트 환경 설정

### `application-test.yml`

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb;MODE=PostgreSQL
    driver-class-name: org.h2.Driver
  jpa:
    hibernate:
      ddl-auto: create-drop
    properties:
      hibernate:
        dialect: org.hibernate.dialect.H2Dialect
  h2:
    console:
      enabled: false
```

### `build.gradle` 테스트 의존성

```groovy
dependencies {
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.mockito:mockito-core'
}

test {
    useJUnitPlatform()
}
```

---

## Service 단위 테스트 패턴

```java
@ExtendWith(MockitoExtension.class)
class ExecutionServiceTest {

    @Mock
    private ExecutionLogRepository logRepository;

    @Mock
    private InterfaceConfigRepository configRepository;

    @Mock
    private MockExecutorFactory executorFactory;

    @Mock
    private SseEmitterService sseEmitterService;

    @InjectMocks
    private ExecutionService executionService;

    @Test
    @DisplayName("수동 실행 트리거 - ExecutionLog RUNNING 상태로 생성")
    void trigger_createsRunningLog() {
        // given
        Long interfaceId = 1L;
        InterfaceConfig config = InterfaceConfig.builder()
                .id(interfaceId)
                .name("테스트 인터페이스")
                .protocol(ProtocolType.REST)
                .endpoint("http://example.com/api")
                .build();

        ExecutionLog savedLog = ExecutionLog.builder()
                .id(1L)
                .interfaceConfig(config)
                .status(ExecutionStatus.RUNNING)
                .triggeredBy(TriggerType.MANUAL)
                .startedAt(LocalDateTime.now())
                .build();

        given(configRepository.findById(interfaceId)).willReturn(Optional.of(config));
        given(logRepository.save(any(ExecutionLog.class))).willReturn(savedLog);

        // when
        ExecutionLogResponse response = executionService.trigger(interfaceId, TriggerType.MANUAL);

        // then
        assertThat(response.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        then(logRepository).should().save(any(ExecutionLog.class));
    }

    @Test
    @DisplayName("존재하지 않는 인터페이스 실행 - EntityNotFoundException 발생")
    void trigger_throwsWhenInterfaceNotFound() {
        // given
        given(configRepository.findById(999L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> executionService.trigger(999L, TriggerType.MANUAL))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("999");
    }

    @Test
    @DisplayName("FAILED 상태가 아닌 로그 재처리 시도 - IllegalStateException 발생")
    void retry_throwsWhenNotFailed() {
        // given
        ExecutionLog successLog = ExecutionLog.builder()
                .id(1L)
                .status(ExecutionStatus.SUCCESS)
                .build();

        given(logRepository.findById(1L)).willReturn(Optional.of(successLog));

        // when & then
        assertThatThrownBy(() -> executionService.retry(1L))
                .isInstanceOf(IllegalStateException.class);
    }
}
```

---

## Controller 슬라이스 테스트 패턴 (`@WebMvcTest`)

```java
@WebMvcTest(InterfaceController.class)
class InterfaceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private InterfaceConfigService interfaceService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("인터페이스 목록 조회 - 200 OK")
    void getAll_returns200() throws Exception {
        // given
        InterfaceConfigResponse response = InterfaceConfigResponse.builder()
                .id(1L)
                .name("테스트")
                .protocol(ProtocolType.REST)
                .status(InterfaceStatus.ACTIVE)
                .build();

        Page<InterfaceConfigResponse> page = new PageImpl<>(List.of(response));
        given(interfaceService.getAll(any(Pageable.class))).willReturn(page);

        // when & then
        mockMvc.perform(get("/api/interfaces")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].name").value("테스트"))
                .andExpect(jsonPath("$.data.content[0].protocol").value("REST"));
    }

    @Test
    @DisplayName("인터페이스 등록 - 필수값 누락 시 400 Bad Request")
    void create_returns400WhenNameBlank() throws Exception {
        // given
        InterfaceConfigRequest request = new InterfaceConfigRequest();
        // name 미설정 (blank)

        // when & then
        mockMvc.perform(post("/api/interfaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("인터페이스 수동 실행 - 201 Created + logId 반환")
    void execute_returns201() throws Exception {
        // given
        ExecutionLogResponse logResponse = ExecutionLogResponse.builder()
                .id(1L)
                .status(ExecutionStatus.RUNNING)
                .build();

        given(interfaceService.trigger(eq(1L), eq(TriggerType.MANUAL))).willReturn(logResponse);

        // when & then
        mockMvc.perform(post("/api/interfaces/1/execute"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("RUNNING"));
    }
}
```

---

## Repository 테스트 패턴 (`@DataJpaTest`)

```java
@DataJpaTest
@ActiveProfiles("test")
class ExecutionLogRepositoryTest {

    @Autowired
    private ExecutionLogRepository logRepository;

    @Autowired
    private InterfaceConfigRepository configRepository;

    @Test
    @DisplayName("최근 실패 로그 조회 - FAILED 상태만 반환")
    void findRecentFailed_returnsOnlyFailed() {
        // given
        InterfaceConfig config = configRepository.save(InterfaceConfig.builder()
                .name("테스트")
                .protocol(ProtocolType.REST)
                .endpoint("http://test.com")
                .status(InterfaceStatus.ACTIVE)
                .build());

        logRepository.save(ExecutionLog.builder()
                .interfaceConfig(config)
                .status(ExecutionStatus.SUCCESS)
                .startedAt(LocalDateTime.now())
                .triggeredBy(TriggerType.MANUAL)
                .build());

        logRepository.save(ExecutionLog.builder()
                .interfaceConfig(config)
                .status(ExecutionStatus.FAILED)
                .startedAt(LocalDateTime.now())
                .triggeredBy(TriggerType.MANUAL)
                .errorMessage("Connection timeout")
                .build());

        // when
        List<ExecutionLog> failed = logRepository.findByStatusOrderByStartedAtDesc(
                ExecutionStatus.FAILED, PageRequest.of(0, 10));

        // then
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).getStatus()).isEqualTo(ExecutionStatus.FAILED);
    }
}
```

---

## Mock 실행기 테스트

```java
@ExtendWith(MockitoExtension.class)
class RestMockExecutorTest {

    private RestMockExecutor executor = new RestMockExecutor();

    @Test
    @DisplayName("REST Mock 실행 - 결과 반환 (성공 또는 실패)")
    void execute_returnsResult() {
        // given
        InterfaceConfig config = InterfaceConfig.builder()
                .protocol(ProtocolType.REST)
                .endpoint("http://example.com/api")
                .build();

        // when
        ExecutionResult result = executor.execute(config);

        // then - 성공 또는 실패 모두 유효한 결과
        assertThat(result).isNotNull();
        assertThat(result.getDurationMs()).isGreaterThan(0);
        if (!result.isSuccess()) {
            assertThat(result.getErrorMessage()).isNotBlank();
        } else {
            assertThat(result.getResponsePayload()).isNotBlank();
        }
    }

    @Test
    @DisplayName("REST Mock 실행 - 응답시간 200ms 이상")
    void execute_takesAtLeast200ms() {
        // given
        InterfaceConfig config = InterfaceConfig.builder()
                .protocol(ProtocolType.REST)
                .endpoint("http://example.com/api")
                .build();

        // when
        long start = System.currentTimeMillis();
        executor.execute(config);
        long elapsed = System.currentTimeMillis() - start;

        // then
        assertThat(elapsed).isGreaterThanOrEqualTo(200);
    }
}
```

---

## 테스트 네이밍 규칙

```
메서드명_시나리오_기대결과

예시:
trigger_createsRunningLog               → 트리거 호출 시 RUNNING 로그 생성
trigger_throwsWhenInterfaceNotFound     → 없는 ID면 예외
retry_throwsWhenNotFailed               → FAILED 아닌 로그 재처리 시 예외
getAll_returns200                       → 목록 조회 200 OK
create_returns400WhenNameBlank          → 이름 빈값이면 400
```

---

## 체크리스트

- [ ] `@DisplayName` 한글로 시나리오 설명
- [ ] given / when / then 주석으로 구분
- [ ] `assertThat` (AssertJ) 사용 (JUnit assertEquals 금지)
- [ ] `@MockBean` vs `@Mock` 구분 (Spring 컨텍스트 유무)
- [ ] 테스트용 `application-test.yml` H2 설정 확인
- [ ] 각 테스트는 독립적으로 실행 가능해야 함 (공유 상태 금지)
