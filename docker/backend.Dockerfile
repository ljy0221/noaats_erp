# ============================================================
# IFMS backend — multi-stage build
# ============================================================
# Stage 1: Gradle로 bootJar 생성
# Stage 2: JRE 17 Alpine 런타임 — 약 170MB
# ============================================================

# ---- build stage ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# Gradle wrapper + 설정만 먼저 복사 → 의존성 레이어 캐시
COPY backend/gradlew /workspace/gradlew
COPY backend/gradle /workspace/gradle
COPY backend/build.gradle /workspace/build.gradle
COPY backend/settings.gradle /workspace/settings.gradle
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

# 소스 복사 후 bootJar
COPY backend/src /workspace/src
RUN ./gradlew --no-daemon bootJar

# ---- runtime stage ----
FROM eclipse-temurin:17-jre-alpine AS runtime
RUN apk add --no-cache netcat-openbsd bash tzdata \
 && ln -sf /usr/share/zoneinfo/Asia/Seoul /etc/localtime \
 && echo "Asia/Seoul" > /etc/timezone
ENV TZ=Asia/Seoul

WORKDIR /app
COPY --from=build /workspace/build/libs/*-SNAPSHOT.jar /app/app.jar
COPY docker/backend-entrypoint.sh /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

EXPOSE 8080
ENTRYPOINT ["/app/entrypoint.sh"]
