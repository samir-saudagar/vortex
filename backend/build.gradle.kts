plugins {
    java
    alias(libs.plugins.spring.boot)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.10.0")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

val kafkaSmokeEnabled = providers.environmentVariable("VORTEX_KAFKA_SMOKE_TEST")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)
val databaseIntegrationEnabled = providers.environmentVariable("VORTEX_DB_INTEGRATION_TEST")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(false)

tasks.test {
    inputs.property("vortexKafkaSmokeEnabled", kafkaSmokeEnabled)
    inputs.property("vortexDatabaseIntegrationEnabled", databaseIntegrationEnabled)
    inputs.property("vortexKafkaIntegrationEnabled", providers.environmentVariable("VORTEX_KAFKA_INTEGRATION_TEST").orElse("false"))
    inputs.property("vortexPhase3KafkaEnabled", providers.environmentVariable("VORTEX_PHASE3_KAFKA_TEST").orElse("false"))
    inputs.property("vortexPhase4KafkaEnabled", providers.environmentVariable("VORTEX_PHASE4_KAFKA_TEST").orElse("false"))
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
