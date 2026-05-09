# Java 沙箱镜像（需要 JDK 因为要编译）
# 构建：docker build -f Dockerfile.java -t sandbox-java:bookworm .
# 需要先拉取基础镜像：docker pull eclipse-temurin:21
FROM eclipse-temurin:21

RUN useradd -m -s /bin/bash sandbox
USER sandbox
WORKDIR /tmp
