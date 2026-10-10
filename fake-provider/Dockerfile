# Image for the fake payment provider (FR-020). Built from the jar that `./mvnw verify` produced.
# Tally's integration tests build and start it with Testcontainers, so calls cross a real network.
#   docker build -t fake-provider fake-provider/
FROM eclipse-temurin:25-jre AS layers
WORKDIR /build
COPY target/fake-provider-*.jar app.jar
# Split the Spring Boot jar into layers, so dependencies are cached apart from application code.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25-jre
ARG REVISION=unknown
# Overrides the labels inherited from the base image, which describe Ubuntu.
LABEL org.opencontainers.image.title="fake-provider" \
      org.opencontainers.image.description="Fake payment provider for Tally tests" \
      org.opencontainers.image.version="${REVISION}" \
      org.opencontainers.image.revision="${REVISION}"
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin provider
WORKDIR /app
COPY --from=layers /build/extracted/dependencies/ ./
COPY --from=layers /build/extracted/spring-boot-loader/ ./
COPY --from=layers /build/extracted/snapshot-dependencies/ ./
COPY --from=layers /build/extracted/application/ ./
USER provider
EXPOSE 8081
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
