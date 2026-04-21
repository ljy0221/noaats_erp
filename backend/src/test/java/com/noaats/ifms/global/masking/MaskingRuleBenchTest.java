package com.noaats.ifms.global.masking;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * MaskingRule.mask 성능 벤치 (api-spec.md §3.4 SHOULD: p95 &lt; 50ms).
 *
 * <p>RUN_BENCH=1 환경변수가 설정될 때만 실행되어 일반 빌드 노이즈를 차단한다.
 * DefensiveMaskingFilter는 ResponseBodyAdvice라 단독 호출이 까다로워 1차 마스킹 코어인
 * MaskingRule을 직접 측정한다 (filter는 mask 호출 + 헤더 인젝션만 추가하므로 동일 의미).
 *
 * <p>실행:
 * <pre>
 *   # bash
 *   RUN_BENCH=1 ./gradlew test --tests "MaskingRuleBenchTest"
 *   # PowerShell
 *   $env:RUN_BENCH=1; ./gradlew test --tests "MaskingRuleBenchTest"
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "RUN_BENCH", matches = "1")
class MaskingRuleBenchTest {

    @Test
    void maskingP95UnderFiftyMillisOn64KBPayload() {
        var rule = new MaskingRule();

        // 64KB 시드 — Map 트리. 민감값(주민·전화·이메일·JWT 후보) 일부 포함
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("apiKey", "sk_live_abcdef1234567890");
        payload.put("rrn", "900101-1234567");
        payload.put("phone", "010-1234-5678");
        payload.put("email", "user@example.com");
        payload.put("jwt", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NSJ9.signature");
        // 60KB 큰 문자열로 채워 총 ~64KB
        payload.put("largeField", "x".repeat(60_000));

        // 워밍업 30회 (JIT 안정화)
        for (int i = 0; i < 30; i++) {
            rule.mask(payload);
        }

        long[] times = new long[200];
        for (int i = 0; i < times.length; i++) {
            long t0 = System.nanoTime();
            rule.mask(payload);
            times[i] = System.nanoTime() - t0;
        }
        Arrays.sort(times);
        long p95Nanos = times[(int) Math.floor(times.length * 0.95) - 1];
        long medianNanos = times[times.length / 2];
        long p95Millis = p95Nanos / 1_000_000;
        long medianMillis = medianNanos / 1_000_000;

        System.out.printf("MaskingRule median = %d ms, p95 = %d ms%n", medianMillis, p95Millis);
        assertThat(p95Millis).as("p95 should be < 50ms (api-spec §3.4 SHOULD)").isLessThan(50);
    }
}
