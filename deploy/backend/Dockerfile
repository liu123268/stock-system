# 发布构建上下文必须包含 Linux 版 bin/longbridge，Token 不进入镜像。
FROM eclipse-temurin:17-jre-noble

WORKDIR /app
COPY --chmod=0644 app.jar /app/app.jar
COPY --chmod=0755 bin/longbridge /usr/local/bin/longbridge
RUN mkdir -p /home/stock/.longbridge \
    && chown -R 1000:1001 /home/stock

# Compose 启动时必须核对并匹配主机 Secret 文件所属用户的 UID/GID。
USER 1000:1001
ENV HOME=/home/stock
ENV SPRING_PROFILES_ACTIVE=prod
ENV TZ=Asia/Shanghai
ENV JAVA_TOOL_OPTIONS="-Xms128m -Xmx768m -Duser.timezone=Asia/Shanghai"
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
