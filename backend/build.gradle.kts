plugins {
	java
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	// 코드 품질 (TASK-67#4): make sonar-scan 이 ./gradlew -Pcoverage test jacocoTestReport sonar 로 부른다
	jacoco
	id("org.sonarqube") version "7.5.0.8588"
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
	// 첨부 이미지 S3 presigned PUT·HeadObject·태그 (TASK-11#1). 위와 같이 url-connection 클라이언트만 쓴다.
	implementation("software.amazon.awssdk:s3") {
		exclude(group = "software.amazon.awssdk", module = "apache-client")
		exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
	}
	implementation("software.amazon.awssdk:url-connection-client")
	// 스케줄 작업 분산 락 (TASK-26#5, BE-37). 락은 같은 PostgreSQL 의 shedlock 테이블(V14)에 둔다.
	implementation("net.javacrumbs.shedlock:shedlock-spring:7.10.1")
	implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.10.1")
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
	// 첨부 업로드용 S3 호환 저장소 (TASK-11#3)
	testImplementation("org.testcontainers:testcontainers-minio")
	// OAuth 제공자(토큰·userinfo·JWKS) stub (TASK-4#5). standalone 은 Jetty·Jackson 을 shade 해 앱 의존성과 충돌하지 않는다.
	testImplementation("org.wiremock:wiremock-standalone:3.13.2")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

// TASK-67#4 커버리지(JaCoCo XML)는 분석 때만(-Pcoverage) — 평소 make test 에는 에이전트를 붙이지 않는다
jacoco {
	toolVersion = "0.8.16"
}

tasks.test {
	extensions.configure<JacocoTaskExtension> {
		isEnabled = providers.gradleProperty("coverage").isPresent
	}
}

tasks.jacocoTestReport {
	reports {
		xml.required = true
		html.required = false
	}
}

// 서버 주소·토큰은 환경변수(SONAR_HOST_URL·SONAR_TOKEN)로만 받는다 — 여기·gradle.properties 에 두지 않는다(B2)
sonar {
	properties {
		property("sonar.projectKey", "board-backend")
		property("sonar.projectName", "board backend")
	}
}
