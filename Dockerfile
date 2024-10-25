FROM clojure:temurin-17-lein-2.11.2-alpine AS builder

RUN mkdir /app
WORKDIR /app
COPY . /app/
RUN lein uberjar

FROM gcr.io/distroless/java17-debian12
COPY --from=builder /app/target/eduhub-rio-mapper.jar /eduhub-rio-mapper.jar
# Make sure there is an opentelemetry agent in the workdir in case docker-compose
# starts up a process with -javaagent in the JAVA_TOOL_OPTIONS
COPY --from=builder /app/vendor/opentelemetry-javaagent-2.9.0.jar /opentelemetry-javaagent.jar

WORKDIR /
ENTRYPOINT ["java", "-jar", "/eduhub-rio-mapper.jar"]
