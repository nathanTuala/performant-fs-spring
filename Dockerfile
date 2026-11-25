# --- 1. Build the web app --------------------------------------------------
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# --- 2. Build the server -----------------------------------------------------
FROM eclipse-temurin:25-jdk AS server
WORKDIR /server
# Dependencies first, so source edits don't re-download them.
COPY server/gradlew server/settings.gradle.kts server/build.gradle.kts ./
COPY server/gradle gradle
RUN ./gradlew dependencies --no-daemon -q > /dev/null
# The embedding model (90 MB, checksum-verified), in its own layer so code changes reuse it.
RUN ./gradlew downloadModel --no-daemon -q
COPY server/src src
RUN ./gradlew bootJar --no-daemon -q

# --- 3. Run ----------------------------------------------------------------
FROM eclipse-temurin:25-jre
RUN useradd --system --create-home app && mkdir /data && chown app /data
WORKDIR /app
COPY --from=server /server/build/libs/app.jar app.jar
COPY --from=web /web/dist web
COPY --from=server /server/models models
ENV PFS_WEB_DIR=/app/web \
    PFS_EMBEDDING_MODEL_DIR=/app/models/all-MiniLM-L6-v2 \
    PFS_DATA_DIR=/data \
    SERVER_PORT=8080
USER app
EXPOSE 8080
# ONNX Runtime (semantic search) loads a native library; allow it explicitly.
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
