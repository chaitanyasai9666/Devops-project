# Runtime-only image: the Maven stage in Jenkins already built and tested the jar
# (building by hand? run `mvn package` first).
FROM eclipse-temurin:21-jre
COPY target/nlp-api.jar /app/app.jar
USER 1000
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
