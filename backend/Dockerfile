# syntax=docker/dockerfile:1
# 백엔드 운영 이미지 (TASK-17#1). scripts/build-backend.sh 가 linux/arm64(t4g)로 빌드해 ECR 에 push 한다.
# 빌드 스테이지에서 Gradle wrapper 로 bootJar 를 만들고, Spring Boot jarmode=tools 로 레이어를 풀어
# 의존성 레이어(자주 안 바뀜)와 앱 레이어를 나눈다 → 배포마다 push·pull 하는 양이 작다.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
# 의존성 해석을 소스보다 먼저 해 레이어 캐시를 쓴다(빌드 스크립트가 안 바뀌면 다시 받지 않는다)
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon --quiet dependencies >/dev/null
COPY src src
RUN ./gradlew --no-daemon --quiet bootJar -x test \
 && jar="$(find build/libs -name '*.jar' ! -name '*-plain.jar' | head -1)" \
 && java -Djarmode=tools -jar "$jar" extract --layers --launcher --destination /extracted

FROM eclipse-temurin:25-jre
# 컨테이너 메모리 한도(compose mem_limit)의 60% 를 힙으로 쓴다
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60"
RUN groupadd --system --gid 10001 app \
 && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /extracted/dependencies/ ./
COPY --from=build /extracted/spring-boot-loader/ ./
COPY --from=build /extracted/snapshot-dependencies/ ./
COPY --from=build /extracted/application/ ./
USER 10001:10001
EXPOSE 8080
# 헬스체크는 호스트(remote-deploy.sh)가 curl http://127.0.0.1:8080/api/actuator/health 로 한다(JRE 이미지에 curl 이 없다)
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
