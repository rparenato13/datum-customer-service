FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# O POM agregador precisa conhecer os dois módulos; apenas este serviço é compilado.
COPY pom.xml ./pom.xml
COPY customer-service/pom.xml ./customer-service/pom.xml
COPY customer-notification-service/pom.xml ./customer-notification-service/pom.xml
COPY customer-notification-service/src ./customer-notification-service/src

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl customer-notification-service -am verify

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app

COPY --from=build --chown=app:app /workspace/customer-notification-service/target/customer-notification-service-*.jar ./app.jar

USER app:app
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
