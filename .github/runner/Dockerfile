# Self-hosted GitHub Actions runner image for AndroidDeveloperToolkit.
# JDK 21 on top of the generic runner base; Gradle itself comes from the repo's wrapper.
FROM myoung34/github-runner:latest

USER root

RUN apt-get update && apt-get install -y --no-install-recommends \
      openjdk-21-jdk-headless git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
