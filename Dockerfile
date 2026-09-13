FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Dependencies resolve in their own layer so a source-only change does not re-download the world.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests \
 && java -Djarmode=layertools -jar target/wallet-service-1.0.0.jar extract --destination layers

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl \
 && addgroup -S wallet && adduser -S -G wallet wallet
WORKDIR /app
COPY --from=build --chown=wallet:wallet /build/layers/dependencies/ ./
COPY --from=build --chown=wallet:wallet /build/layers/spring-boot-loader/ ./
COPY --from=build --chown=wallet:wallet /build/layers/snapshot-dependencies/ ./
COPY --from=build --chown=wallet:wallet /build/layers/application/ ./
USER wallet
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=45s --retries=3 \
  CMD curl -fsS http://localhost:8080/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "org.springframework.boot.loader.launch.JarLauncher"]
