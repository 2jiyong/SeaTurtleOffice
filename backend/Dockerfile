# 배포용 백엔드 이미지. 서버에서 직접 빌드한다(compose.prod.yaml).
# 테스트는 Testcontainers가 도커를 써야 해서 여기서 돌리지 않는다. 커밋 전에 로컬에서 ./gradlew test.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
# 의존성을 먼저 받아 두면 소스만 바뀐 배포에서 이 층을 다시 쓴다.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# 2GB 서버에서 빌드한다. Gradle 메모리를 묶어 실행 중인 서비스가 스왑으로 밀리지 않게.
ENV GRADLE_OPTS="-Xmx64m -Dorg.gradle.jvmargs=-Xmx512m"
RUN ./gradlew dependencies --no-daemon -q > /dev/null
COPY src src
RUN ./gradlew bootJar -x test --no-daemon -q && cp build/libs/*-SNAPSHOT.jar /app.jar

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 app
COPY --from=build /app.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
