plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-session-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	// 휴대폰 번호 E.164 정규화 (TASK-7#1)
	implementation("com.googlecode.libphonenumber:libphonenumber:9.0.41")
	// AWS End User Messaging SMS (pinpoint-sms-voice-v2) SendTextMessage (TASK-7#1). HTTP 클라이언트는 JDK 내장(url-connection)만 쓴다.
	implementation(platform("software.amazon.awssdk:bom:2.55.14"))
	implementation("software.amazon.awssdk:pinpointsmsvoicev2") {
		exclude(group = "software.amazon.awssdk", module = "apache-client")
		exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
	}
	implementation("software.amazon.awssdk:url-connection-client")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	// OAuth 제공자(토큰·userinfo·JWKS) stub (TASK-4#5). standalone 은 Jetty·Jackson 을 shade 해 앱 의존성과 충돌하지 않는다.
	testImplementation("org.wiremock:wiremock-standalone:3.13.2")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
