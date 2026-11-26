plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com"
version = "0.0.1-SNAPSHOT"
description = "payment"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

// Spring Boot가 버전을 관리하지 않는 QueryDSL (Hibernate 7을 지원하는 OpenFeign 포크)
val querydslVersion = "7.7"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-mysql")

    // Redis 분산 락 (phase-02 2-7). 스타터 대신 코어만 쓰고 RedisLockExecutor가 클라이언트를 직접 관리한다.
    implementation("org.redisson:redisson:4.8.0")

    // Redis 스핀락 (phase-02 2-7+). 스타터 없이 클라이언트만 쓰고 RedisSpinLockExecutor가 직접 관리한다. 버전은 Boot BOM이 관리.
    implementation("io.lettuce:lettuce-core")
    runtimeOnly("com.mysql:mysql-connector-j")

    // QueryDSL
    implementation("io.github.openfeign.querydsl:querydsl-jpa:$querydslVersion")
    annotationProcessor("io.github.openfeign.querydsl:querydsl-apt:$querydslVersion:jpa")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")

    developmentOnly("org.springframework.boot:spring-boot-devtools")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mysql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// 성능·실행 계획 측정(@Tag("measurement"))은 일반 테스트에서 빼고, 측정할 때만 measurementTest로 돌린다.
tasks.test {
    useJUnitPlatform {
        excludeTags("measurement")
    }
}

val measurementTest by tasks.registering(Test::class) {
    description = "@Tag(\"measurement\") 측정 테스트만 실행한다."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("measurement")
    }
}

// build.gradle.kts
tasks.bootRun {
    val envFile = file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
            .forEach { line ->
                val (k, v) = line.split("=", limit = 2)
                environment(k.trim(), v.trim().removeSurrounding("\""))
            }
    }
}
