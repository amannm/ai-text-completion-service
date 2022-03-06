FROM openjdk:17-slim
RUN mkdir /app
COPY build/libs/libs /app/libs
COPY build/libs/app.jar /app
EXPOSE 8080
CMD ["java", \
     "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED", \
     "--add-opens", "java.base/java.nio=ALL-UNNAMED", \
     "-Dio.netty.tryReflectionSetAccessible=true", \
     "-jar", "/app/app.jar"]