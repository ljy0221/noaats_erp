# Skill: Spring Domain 생성

## 목적
Spring Boot 도메인 레이어를 일관된 패턴으로 생성한다.
Entity → Repository → Service → Controller → DTO 세트를 한 번에 만든다.

---

## 패키지 구조 규칙

```
com.noaats.ifms.domain/{도메인명}/
├── controller/   {도메인}Controller.java
├── service/      {도메인}Service.java
├── repository/   {도메인}Repository.java
├── domain/       {도메인}.java          ← JPA Entity
└── dto/
    ├── {도메인}Request.java
    └── {도메인}Response.java
```

---

## Entity 패턴

```java
@Entity
@Table(name = "{테이블명}")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class {도메인} extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // 필드...

    @Builder
    public {도메인}(/* 필드 */) {
        // 필드 초기화
    }

    // 비즈니스 메서드만 (setter 금지)
    public void update(/* 변경 필드 */) { ... }
    public void deactivate() { ... }
}
```

**규칙**
- `@Setter` 절대 사용 금지
- 생성자는 `@Builder` 또는 정적 팩토리 메서드
- `AccessLevel.PROTECTED` 기본 생성자 필수
- `BaseTimeEntity` 상속 (createdAt, updatedAt 자동 관리)

### BaseTimeEntity
```java
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter
public abstract class BaseTimeEntity {
    @CreatedDate
    @Column(updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
```

---

## Repository 패턴

```java
public interface {도메인}Repository extends JpaRepository<{도메인}, Long> {
    // 복잡한 쿼리는 @Query 또는 QueryDSL
    // N+1 주의: fetch join 또는 @EntityGraph 사용
    
    @Query("SELECT i FROM {도메인} i WHERE i.status = :status")
    List<{도메인}> findAllByStatus(@Param("status") StatusEnum status);
    
    Page<{도메인}> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
```

---

## Service 패턴

```java
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)   // ← 클래스 레벨: 기본 readOnly
public class {도메인}Service {

    private final {도메인}Repository {도메인소문자}Repository;

    public Page<{도메인}Response> getAll(Pageable pageable) {
        return {도메인소문자}Repository.findAllByOrderByCreatedAtDesc(pageable)
                .map({도메인}Response::from);
    }

    public {도메인}Response getById(Long id) {
        return {도메인}Response.from(findById(id));
    }

    @Transactional   // ← 쓰기 작업만 별도 선언
    public {도메인}Response create({도메인}Request request) {
        {도메인} entity = {도메인}.builder()
                // 필드 매핑
                .build();
        return {도메인}Response.from({도메인소문자}Repository.save(entity));
    }

    @Transactional
    public {도메인}Response update(Long id, {도메인}Request request) {
        {도메인} entity = findById(id);
        entity.update(/* 변경 필드 */);
        return {도메인}Response.from(entity);
    }

    // private 헬퍼: 404 처리 중앙화
    private {도메인} findById(Long id) {
        return {도메인소문자}Repository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("{도메인} not found: " + id));
    }
}
```

**규칙**
- `@Transactional`은 Service에서만 선언 (Controller 금지)
- 클래스 레벨 `readOnly = true`, 쓰기 메서드만 `@Transactional` 오버라이드
- `findById` 같은 내부 헬퍼로 404 처리 중앙화

---

## Controller 패턴

```java
@RestController
@RequestMapping("/api/{도메인복수}")
@RequiredArgsConstructor
@Tag(name = "{도메인}", description = "{도메인} 관리 API")
public class {도메인}Controller {

    private final {도메인}Service {도메인소문자}Service;

    @GetMapping
    @Operation(summary = "목록 조회")
    public ApiResponse<Page<{도메인}Response>> getAll(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return ApiResponse.success({도메인소문자}Service.getAll(pageable));
    }

    @GetMapping("/{id}")
    @Operation(summary = "단건 조회")
    public ApiResponse<{도메인}Response> getById(@PathVariable Long id) {
        return ApiResponse.success({도메인소문자}Service.getById(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "등록")
    public ApiResponse<{도메인}Response> create(@RequestBody @Valid {도메인}Request request) {
        return ApiResponse.success({도메인소문자}Service.create(request));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "수정")
    public ApiResponse<{도메인}Response> update(
            @PathVariable Long id,
            @RequestBody @Valid {도메인}Request request) {
        return ApiResponse.success({도메인소문자}Service.update(id, request));
    }
}
```

---

## DTO 패턴

```java
// Request
@Getter
@NoArgsConstructor
public class {도메인}Request {
    @NotBlank(message = "이름은 필수입니다")
    private String name;
    // 필드...
}

// Response (정적 팩토리 메서드 패턴)
@Getter
@Builder
public class {도메인}Response {
    private Long id;
    private String name;
    // 필드...
    private LocalDateTime createdAt;

    public static {도메인}Response from({도메인} entity) {
        return {도메인}Response.builder()
                .id(entity.getId())
                .name(entity.getName())
                // 필드 매핑
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
```

---

## 공통 ApiResponse

```java
@Getter
@Builder
public class ApiResponse<T> {
    private final boolean success;
    private final T data;
    private final String message;
    private final LocalDateTime timestamp;

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .success(true)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .success(false)
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
```

---

## GlobalExceptionHandler

```java
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(EntityNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNotFound(EntityNotFoundException e) {
        log.warn("Not found: {}", e.getMessage());
        return ApiResponse.error(e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return ApiResponse.error(message);
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleGeneral(Exception e) {
        log.error("Unexpected error", e);
        return ApiResponse.error("서버 내부 오류가 발생했습니다");
    }
}
```

---

## 체크리스트

도메인 생성 시 아래를 모두 확인한다:

- [ ] Entity에 `@Setter` 없음
- [ ] `@Transactional`이 Service에만 있음
- [ ] 클래스 레벨 `readOnly = true` 선언
- [ ] `findById` 헬퍼로 404 처리 통일
- [ ] Response DTO에 `from()` 정적 팩토리 메서드
- [ ] Controller에 Swagger `@Tag`, `@Operation` 선언
- [ ] `ApiResponse<T>` 래핑 확인
