# Consumer reference image. Build the application-owned fat jar before this
# step; the module intentionally cannot invent a consumer Pipeline or main.
FROM --platform=linux/arm64 eclipse-temurin:24-jre

WORKDIR /opt/tpipe
ARG APPLICATION_JAR=TPipe-AgentCore-all.jar
COPY ${APPLICATION_JAR} /opt/tpipe/application.jar

ENV TPIPE_AGENTCORE_BIND_ADDRESS=0.0.0.0
ENV TPIPE_AGENTCORE_PORT=8080

EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "/opt/tpipe/application.jar"]
