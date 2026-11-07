# ---- web build
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

# ---- server build
FROM maven:3.9-eclipse-temurin-21 AS server
WORKDIR /server
COPY server/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY server/src ./src
RUN mvn -q -B package -DskipTests

# ---- runtime: glibc-based JRE (ONNX Runtime and the tokenizer ship glibc native libraries), non-root
FROM eclipse-temurin:21-jre-noble
RUN groupadd --system app && useradd --system --gid app --home-dir /app app
WORKDIR /app
COPY --from=server /server/target/streaming-answer-ui.jar app.jar
COPY --from=web /web/dist ./web
COPY policies ./policies
RUN mkdir -p /app/.cache && chown -R app:app /app/.cache
ENV STATIC_DIR=/app/web POLICY_DIR=/app/policies MODEL_CACHE_DIR=/app/.cache PORT=8080
USER app
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=120s CMD wget -qO- http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]
