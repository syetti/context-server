# ==========================================
# STAGE 1: Build (Using Gradle Wrapper)
# ==========================================
# We use a lightweight JDK image instead of a heavy Gradle image
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app

# 1. Copy the Gradle Wrapper files FIRST
COPY gradlew .
COPY gradle/ gradle/

# Ensure the wrapper script has execute permissions (fixes Windows-to-Linux deployment bugs)
RUN chmod +x ./gradlew

# 2. Copy your build scripts
COPY build.gradle.kts settings.gradle.kts ./
# If you have a gradle.properties file, copy it too by uncommenting the next line:
# COPY gradle.properties ./

# 3. Copy your actual Kotlin source code
COPY src/ src/

# 4. Build the Fat JAR safely using the Wrapper
# --no-daemon and in-process prevent background tasks
# --stop guarantees the environment is clean for DigitalOcean's snapshot
RUN ./gradlew clean buildFatJar --no-daemon -Dkotlin.compiler.execution.strategy=in-process && ./gradlew --stop

# ==========================================
# STAGE 2: Run (Using tiny JRE)
# ==========================================
# We switch to an even smaller JRE (Java Runtime Environment) since we don't need the compiler anymore
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Best Practice: Create a non-root user for security
RUN addgroup -S ktor && adduser -S ktor -G ktor
USER ktor

# Copy ONLY the compiled Fat JAR from the build stage
COPY --from=build /app/build/libs/*-all.jar /app/server.jar

# Expose the port (DigitalOcean automatically maps this to your public URL)
EXPOSE 8000

# Start the server
CMD ["java", "-jar", "/app/server.jar"]