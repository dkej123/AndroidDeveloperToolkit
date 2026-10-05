# Self-hosted GitHub Actions runner image for AndroidDeveloperToolkit.
# JDK 21 on top of the generic runner base; Gradle itself comes from the repo's wrapper.
FROM myoung34/github-runner:latest

USER root

# The IDE started by :intellij:buildSearchableOptions and the platform/visual tests runs on its
# bundled JBR, which links freetype/fontconfig and the X11 client libraries even when headless
# (libfontmanager.so -> libfreetype.so.6). Keep this list in sync with
# .github/scripts/ensure-ide-runtime-libs.sh, which installs them at job time if the image lacks them.
RUN apt-get update && apt-get install -y --no-install-recommends \
      openjdk-21-jdk-headless git ca-certificates \
      libfreetype6 libfontconfig1 fontconfig fonts-dejavu-core \
      libx11-6 libxext6 libxrender1 libxtst6 libxi6 libxrandr2 \
    && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
