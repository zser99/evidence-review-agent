# 증빙 검수 백엔드 (Spring Boot) — back pod
# 빌드 컨텍스트는 저장소 루트다:  docker build -f backend/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY backend/pom.xml backend/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -f backend/pom.xml dependency:go-offline
COPY backend/src backend/src
# 단위·통합 테스트는 CI(Jenkins 'test' 단계)에서 돌린다
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -f backend/pom.xml package -DskipTests

FROM eclipse-temurin:21-jre
RUN useradd --uid 10001 --create-home appuser
WORKDIR /app
COPY --from=build /src/backend/target/evidence-backend.jar app.jar
# 초기 규정(YAML·규정집), 대학 목록, 정답 증빙 세트(가상 데이터)
COPY seed /app/seed
ENV APP_SEED_DIR=/app/seed \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Seoul"
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
