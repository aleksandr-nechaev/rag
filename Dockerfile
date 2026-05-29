FROM ghcr.io/graalvm/native-image-community:25-ol9 AS builder
WORKDIR /app
# Keep Gradle's own JVM small so most of the builder memory stays free for native-image.
ENV GRADLE_OPTS="-Dorg.gradle.jvmargs=-Xmx1g"
COPY gradlew .
COPY gradle gradle
COPY settings.gradle.kts .
COPY build.gradle.kts .
RUN ./gradlew dependencies --no-daemon -q
COPY src src
RUN ./gradlew nativeCompile --no-daemon -x test

FROM debian:stable-slim
WORKDIR /app
# Native-image dynamically links to libz (jar/gzip), libnss (DNS), libssl (HTTPS), tzdata (time zones).
# ca-certificates is needed for HTTPS calls to Google Gemini API.
# libfreetype6 + fontconfig back the libfontmanager.so that AWT/PDFBox load during PDF ingestion.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
        ca-certificates \
        libz1 \
        libnss3 \
        tzdata \
        libfreetype6 \
        fontconfig \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*
RUN groupadd --gid 10001 app \
 && useradd  --uid 10001 --gid 10001 --no-create-home app
COPY --from=builder --chown=app:app /app/build/native/nativeCompile/rag /app/rag
# AWT shared libraries GraalVM emits next to the binary (libawt.so, libawt_headless.so,
# libfontmanager.so, libjavajpeg.so, liblcms.so). The native image loads them relative to the
# executable, so they must sit in the same directory.
COPY --from=builder --chown=app:app /app/build/native/nativeCompile/*.so /app/
USER app
EXPOSE 8080
ENTRYPOINT ["/app/rag"]
