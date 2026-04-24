# ==========================================
# STAGE 1: Build (Using Gradle Wrapper)
# ==========================================
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app

# 1. Copy the Gradle Wrapper files FIRST
COPY gradlew .
COPY gradle/ gradle/

# 2. Fix permissions AND forcefully convert Windows line-endings to Linux line-endings
RUN chmod +x ./gradlew && sed -i 's/\r$//' gradlew

# 3. Copy your build scripts and source code
COPY build.gradle.kts settings.gradle.kts ./
COPY src/ src/

# 4. Build with strict memory limits (-Xmx256m) so DigitalOcean doesn't kill the process
RUN ./gradlew clean buildFatJar --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs="-Xmx256m" && ./gradlew --stop