FROM openjdk:17-jdk-alpine
COPY target/plaza-order-no-provider.jar app.jar
ENV JAVA_OPTS="-server -Xms256m -Xmx256m -Xmn256m"    
ENTRYPOINT java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar /app.jar