# Game server image. Build context: repository root (the server needs content/).
#   docker build -f engine/Dockerfile .
FROM gradle:8.14.3-jdk21 AS build
WORKDIR /src
COPY engine/ engine/
COPY content/ content/
RUN cd engine && gradle --no-daemon -Ptz.serverOnly=true :server:installDist

FROM eclipse-temurin:21-jre
COPY --from=build /src/engine/server/build/install/server /app
COPY content/ /app/content
ENV TZ_CONTENT=/app/content
EXPOSE 8080
CMD ["/app/bin/server"]
