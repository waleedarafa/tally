# Image for the Tally application. Built from the jar that `./mvnw verify` produced,
# so CI builds an image only for a commit whose checks passed.
#   docker build -t tally:<commit> tally/
FROM eclipse-temurin:25-jre AS layers
WORKDIR /build
COPY target/tally-*.jar app.jar
# Split the Spring Boot jar into layers, so dependencies are cached apart from application code.
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted
# The snapshot-dependencies layer is empty (no SNAPSHOT dependency), and it is not copied below: on Docker 28 with the
# classic image store, the legacy builder (the one Testcontainers uses) fails the first build when a COPY adds an
# empty layer ("failed to get layer ... layer does not exist"; Slice 2 step 0b). This line fails the build if a
# SNAPSHOT dependency ever arrives, so that it is copied instead of silently left out.
RUN [ -z "$(ls -A extracted/snapshot-dependencies)" ] \
    || { echo "extracted/snapshot-dependencies is not empty: copy it in the Dockerfile" >&2; exit 1; }

FROM eclipse-temurin:25-jre
ARG REVISION=unknown
# Overrides the labels inherited from the base image, which describe Ubuntu.
LABEL org.opencontainers.image.title="tally" \
      org.opencontainers.image.description="Tally subscription billing" \
      org.opencontainers.image.version="${REVISION}" \
      org.opencontainers.image.revision="${REVISION}"
RUN useradd --uid 10001 --no-create-home --shell /usr/sbin/nologin tally
WORKDIR /app
COPY --from=layers /build/extracted/dependencies/ ./
COPY --from=layers /build/extracted/spring-boot-loader/ ./
COPY --from=layers /build/extracted/application/ ./
USER tally
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
