# Build stage — use the official Gradle image so we don't pay the
# gradle-wrapper download (often times out from services.gradle.org).
FROM gradle:8.12-jdk17-jammy AS builder

WORKDIR /build
COPY --chown=gradle:gradle . .
RUN gradle shadowJar --no-daemon

# Runtime stage
FROM eclipse-temurin:17-jre-jammy

RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg liblmdb0 && \
    rm -rf /var/lib/apt/lists/*

COPY --from=builder /build/build/libs/panako-*-all.jar /app/panako.jar

RUN mkdir -p /root/.panako/dbs

EXPOSE 8080

ENTRYPOINT ["java", "--add-opens=java.base/java.nio=ALL-UNNAMED", "-cp", "/app/panako.jar", "be.panako.http.PanakoHttpServer"]
CMD ["SERVER_PORT=8080"]

# Multi-platform (amd64 + arm64):
#   docker buildx build --platform linux/amd64,linux/arm64 -t innlabkz/ozen-panako:latest --push .
#   docker buildx build --platform linux/amd64 -t innlabkz/ozen-panako:latest --push .

# docker build -t innlabkz/ozen-panako:latest .
# docker push innlabkz/ozen-panako:latest
