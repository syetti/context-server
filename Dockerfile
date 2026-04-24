# ==========================================
# STAGE 1: Build the app using Gradle
# ==========================================
FROM gradle:8.11.1-jdk17 AS build

# Copy your source code into the container
COPY --chown=gradle:gradle . /home/gradle/src
WORKDIR /home/gradle/src

# Run your Fat JAR build command
RUN ./gradlew clean buildFatJar --no-daemon -Dkotlin.compiler.execution.strategy=in-process

# ==========================================
# STAGE 2: Create a tiny runtime image
# ==========================================
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Copy ONLY the compiled JAR file from Stage 1 (leaves all the heavy source code behind)
COPY --from=build /home/gradle/src/build/libs/*-all.jar /app/server.jar

# Expose the port (DigitalOcean will automatically map this)
EXPOSE 8000

# Tell Docker how to start the server
CMD ["java", "-jar", "/app/server.jar"]