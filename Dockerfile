# Build stage: compile and package the app with Gradle.
FROM gradle:8.11.1-jdk21 AS build
WORKDIR /src
COPY settings.gradle.kts build.gradle.kts ./
COPY src ./src
RUN gradle installDist --no-daemon

# Runtime stage: only the JRE and the packaged app, running as a non-root user.
FROM eclipse-temurin:21-jre
RUN useradd --system --create-home stara
COPY --from=build /src/build/install/stara /app
USER stara
EXPOSE 8080
ENTRYPOINT ["/app/bin/stara"]
