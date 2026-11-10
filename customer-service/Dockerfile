FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# O POM agregador precisa conhecer os dois módulos; apenas este serviço é compilado.
COPY pom.xml ./pom.xml
COPY customer-service/pom.xml ./customer-service/pom.xml
COPY customer-notification-service/pom.xml ./customer-notification-service/pom.xml
COPY customer-service/src ./customer-service/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl customer-service -am verify

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app

COPY --from=build --chown=app:app /workspace/customer-service/target/customer-service-*.jar ./app.jar

COPY --chown=app:app --chmod=755 infra/health/application.sh /app/healthcheck
ENV SERVER_PORT=8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=12 CMD ["/app/healthcheck"]

USER app:app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
