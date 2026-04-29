plugins {
    java
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.nechaev"
version = "0.0.1-SNAPSHOT"
description = "nechaev"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

extra["springAiVersion"] = "2.0.0-M4"
extra["resilience4jVersion"] = "2.3.0"
extra["springdocVersion"] = "3.0.3"
extra["springwolfVersion"] = "2.2.0"
extra["mapstructVersion"] = "1.6.3"
extra["testcontainersBomVersion"] = "1.20.4"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui")
    implementation("io.github.springwolf:springwolf-stomp:${property("springwolfVersion")}")
    implementation("io.github.springwolf:springwolf-ui:${property("springwolfVersion")}")
    implementation("org.apache.commons:commons-pool2")
    implementation("org.springframework.boot:spring-boot-starter-liquibase")
    implementation("org.springframework.ai:spring-ai-starter-model-google-genai")
    implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
    implementation("org.springframework.ai:spring-ai-pdf-document-reader")
    implementation("org.springframework.ai:spring-ai-transformers")
    implementation("io.github.resilience4j:resilience4j-ratelimiter")
    implementation("io.github.resilience4j:resilience4j-bulkhead")
    implementation("org.mapstruct:mapstruct:${property("mapstructVersion")}")
    annotationProcessor("org.mapstruct:mapstruct-processor:${property("mapstructVersion")}")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(platform("org.testcontainers:testcontainers-bom:${property("testcontainersBomVersion")}"))
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
        mavenBom("io.github.resilience4j:resilience4j-bom:${property("resilience4jVersion")}")
        mavenBom("org.springdoc:springdoc-openapi-bom:${property("springdocVersion")}")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}
