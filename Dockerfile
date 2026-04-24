# ==========================================
# STAGE 1: Build (Using Gradle Wrapper & Java 21)
# ==========================================
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

COPY gradlew .
COPY gradle/ gradle/
RUN chmod +x ./gradlew && sed -i 's/\r$//' gradlew

COPY build.gradle.kts settings.gradle.kts ./
COPY src/ src/

RUN ./gradlew clean buildFatJar --no-daemon -Dkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs="-Xmx256m" && ./gradlew --stop

# ==========================================
# STAGE 2: Run (Using Java 21 JRE)
# ==========================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S ktor && adduser -S ktor -G ktor
USER ktor

COPY --from=build /app/build/libs/*-all.jar /app/server.jar

EXPOSE 8000

CMD ["java", "-jar", "/app/server.jar"]