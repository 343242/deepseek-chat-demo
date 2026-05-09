# Java 沙箱镜像
# 构建命令：docker build -f Dockerfile.java -t sandbox-java:bookworm .
# 需要先拉取基础镜像：docker pull eclipse-temurin:21-jre-bookworm
FROM eclipse-temurin:21-jre-bookworm

RUN useradd -m -s /bin/bash sandbox
USER sandbox
WORKDIR /tmp
