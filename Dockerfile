FROM openjdk:17-slim
RUN mkdir /app
COPY build/libs/libs /app/libs
COPY build/libs/app.jar /app
EXPOSE 8080
CMD ["java", "-jar", "/app/app.jar"]