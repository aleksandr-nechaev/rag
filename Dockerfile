FROM eclipse-temurin:25-jdk AS builder
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY settings.gradle.kts .
COPY build.gradle.kts .
RUN ./gradlew dependencies --no-daemon -q
COPY src src
RUN ./gradlew bootJar -x test --no-daemon \
 && JAR=$(find build/libs -name '*.jar' ! -name '*-plain.jar' | head -n1) \
 && mkdir -p extracted \
 && java -Djarmode=tools -jar "$JAR" extract --destination extracted \
 && mv extracted/*.jar extracted/app.jar

FROM eclipse-temurin:25-jre
WORKDIR /app
RUN groupadd --gid 10001 app \
 && useradd  --uid 10001 --gid 10001 --no-create-home app
COPY --from=builder --chown=app:app /app/extracted/lib ./lib
COPY --from=builder --chown=app:app /app/extracted/app.jar ./app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
