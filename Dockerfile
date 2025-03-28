FROM clojure:temurin-17-lein-2.11.2-alpine as builder

RUN mkdir /app
WORKDIR /app
COPY . /app/
RUN lein uberjar

FROM gcr.io/distroless/java17-debian12
COPY --from=builder /app/target/eduhub-rio-mapper.jar /eduhub-rio-mapper.jar

ENTRYPOINT ["java", "-jar", "/eduhub-rio-mapper.jar"]
