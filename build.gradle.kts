plugins {
    java
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.graalvm.buildtools.native") version "0.10.6"
    // Statically enhances @Entity classes at compile time. With enhancement done at build,
    // Hibernate does NOT need to generate per-entity HibernateProxy classes via ByteBuddy at
    // runtime — which is forbidden in native image. Version must match the Hibernate runtime
    // pulled in by spring-boot-starter-data-jpa (7.2.12.Final).
    id("org.hibernate.orm") version "7.2.12.Final"
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
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.session:spring-session-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui")
    implementation("io.github.springwolf:springwolf-stomp:${property("springwolfVersion")}")
    implementation("io.github.springwolf:springwolf-ui:${property("springwolfVersion")}")
    implementation("org.apache.commons:commons-pool2")
    implementation("org.springframework.boot:spring-boot-starter-liquibase")
    implementation("org.springframework.ai:spring-ai-starter-model-google-genai")
    implementation("org.springframework.ai:spring-ai-starter-model-google-genai-embedding")
    implementation("org.springframework.ai:spring-ai-starter-vector-store-pgvector")
    implementation("org.springframework.ai:spring-ai-pdf-document-reader")
    implementation("io.github.resilience4j:resilience4j-ratelimiter")
    implementation("io.github.resilience4j:resilience4j-bulkhead")
    implementation("org.mapstruct:mapstruct:${property("mapstructVersion")}")
    annotationProcessor("org.mapstruct:mapstruct-processor:${property("mapstructVersion")}")
    runtimeOnly("org.postgresql:postgresql")
    // GraalVM substitution API (@TargetClass/@Substitute) for NativeRuntimeHints siblings.
    // compileOnly: present only at compile time, never on the runtime/JVM classpath. Version
    // tracks the GraalVM JDK used for native compilation (25.0.2).
    compileOnly("org.graalvm.nativeimage:svm:25.0.2")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(platform("org.testcontainers:testcontainers-bom:${property("testcontainersBomVersion")}"))
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // JUnit annotates its API with @API(status = ...) from apiguardian-api, but ships it with a
    // non-propagating scope. The Spring AOT test-source compile (compileAotTestJava) can't resolve
    // API$Status without it on the classpath, emitting "unknown enum constant Status.STABLE".
    testImplementation("org.apiguardian:apiguardian-api:1.1.2")
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
    // Match the native image: PDFBox pulls in AWT, and the image runs headless. Spring Boot also
    // forces this at startup, but set it explicitly so the agent records the headless AWT path.
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true")
    // Opt-in: run with GraalVM's native-image-agent attached to record reflection/proxy/jni/resource
    // usage. Activate with `./gradlew bootRun -PtracingAgent`. Requires GraalVM JDK.
    // IMPORTANT: the agent only records code paths that actually execute. The resume ingestion
    // (PDFBox -> AWT) is skipped when the DB already has the resume, so wipe ingestion_state first
    // (e.g. `docker compose down -v`) to force ingestion and capture the AWT JNI/reflection config.
    // config-merge-dir (not output) unions new entries into the existing metadata instead of
    // overwriting it, so a single run that doesn't hit every endpoint can't drop prior captures.
    if (project.hasProperty("tracingAgent")) {
        val agentOutDir = file("src/main/resources/META-INF/native-image").absolutePath
        jvmArgs(
            "-agentlib:native-image-agent=config-merge-dir=$agentOutDir,config-write-period-secs=30"
        )
    }
}

// Enable native access via JAR manifest (JEP 472, Java 22+). Removes the need for
// --enable-native-access on the command line for `java -jar app.jar` (Docker, prod).
tasks.bootJar {
    manifest {
        attributes("Enable-Native-Access" to "ALL-UNNAMED")
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("-Xlint:deprecation")
}

// Hibernate static bytecode enhancement.
// Rewrites compiled @Entity classes so they no longer require runtime proxy generation —
// the killer of native image compatibility. Each entity becomes self-sufficient with
// pre-injected lazy-loading, dirty-tracking and association management hooks.
hibernate {
    enhancement {
        enableLazyInitialization.set(true)
        enableDirtyTracking.set(true)
        // Association management is deprecated and not needed — we have no bidirectional
        // @OneToMany / @ManyToOne associations in this app.
        enableAssociationManagement.set(false)
    }
}

graalvmNative {
    binaries {
        named("main") {
            imageName.set("rag")
            buildArgs.addAll(
                "--no-fallback",
                "--enable-url-protocols=http,https",
                // native-image bakes only a minimal charset set (UTF-8, ISO-8859-1, US-ASCII,
                // UTF-16). PDFBox's BaseParser.<clinit> does Charset.forName("Windows-1252") for
                // PDF text; without this it falls back to ISO-8859-1 and mangles 0x80-0x9F glyphs
                // (bullets, smart quotes, en/em dashes) that then poison the resume embeddings.
                "-H:+AddAllCharsets",
                "-H:+UnlockExperimentalVMOptions",
                "-H:+ReportExceptionStackTraces",
                // PDFBox (resume ingestion) pulls in java.awt (ColorModel/Raster) on PDDocument
                // class init. Bake headless mode so the image loads libawt_headless instead of
                // libawt_xawt (no X11 needed). The libawt*.so files GraalVM emits next to the
                // binary are copied into the runtime image by the Dockerfile.
                "-Djava.awt.headless=true",
                // Force ALL ByteBuddy and Hibernate-ByteBuddy classes to initialize at build time.
                // Hibernate's BytecodeProviderInitiator builds the provider list via ServiceLoader,
                // which INSTANTIATES every registered provider (including ByteBuddy's) before our
                // HibernateBytecodeProviderSubstitution can pick the "none" one. The ByteBuddy
                // constructor chains into JavaDispatcher.<clinit>, which does defineClass at runtime
                // (forbidden in native image). Initializing these packages at build time runs that
                // defineClass during compilation instead. Verified load-bearing: removing these
                // flags builds fine but crashes at startup with "Classes cannot be defined at
                // runtime ... net.bytebuddy.utility.Invoker$Dispatcher".
                "--initialize-at-build-time=net.bytebuddy",
                "--initialize-at-build-time=org.hibernate.bytecode.internal.bytebuddy",
                // Native-image needs ~10 GB of heap to analyze the full Spring Boot 4 + Spring AI
                // graph with agent-generated reachability metadata (which expands reflection
                // tables by 25-40%). Docker builder must have at least 14 GB of memory.
                "-J-Xmx10g",
                // Lower parallelism — each parallel worker holds a copy of the call graph.
                "-H:NumberOfThreads=2"
            )
        }
    }
}
