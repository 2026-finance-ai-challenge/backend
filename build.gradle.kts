plugins {
	java
	jacoco
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.kmarket.navigator"
version = "0.1.0-SNAPSHOT"
description = "K-Market-Navigator Backend"

extra["jackson-bom.version"] = "3.1.5"
extra["log4j2.version"] = "2.25.5"
extra["postgresql.version"] = "42.7.12"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencyLocking {
	lockAllConfigurations()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-restclient")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.1.0")
	implementation("net.javacrumbs.shedlock:shedlock-spring:7.1.0")
	implementation("org.jsoup:jsoup:1.23.1")
	implementation("com.github.luben:zstd-jni:1.5.7-12")
	implementation("org.bouncycastle:bcprov-jdk18on:1.84")
	implementation("org.springframework.security:spring-security-oauth2-jose")
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
	runtimeOnly("org.postgresql:postgresql")

	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
	options.encoding = "UTF-8"
	options.compilerArgs.add("-Xlint:all")
}

tasks.withType<Test>().configureEach {
	useJUnitPlatform()
	jvmArgs("--enable-native-access=ALL-UNNAMED")
	finalizedBy(tasks.jacocoTestReport)
}

tasks.withType<org.springframework.boot.gradle.tasks.run.BootRun>().configureEach {
	jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.jacocoTestReport {
	dependsOn(tasks.test)
	reports {
		xml.required = true
		html.required = true
	}
}
