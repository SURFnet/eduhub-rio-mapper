FROM clojure:temurin-17-lein-jammy as builder

RUN mkdir /app
WORKDIR /app
COPY . /app/
RUN lein uberjar
RUN make opentelemetry-javaagent.jar

FROM gcr.io/distroless/java:11

COPY --from=builder /app/target/eduhub-rio-mapper.jar /eduhub-rio-mapper.jar
COPY --from=builder /app/opentelemetry-javaagent.jar /opentelemetry-javaagent.jar

WORKDIR /
ENTRYPOINT ["java", "-jar", "/eduhub-rio-mapper.jar"]
