FROM openjdk:8-jre
WORKDIR /opt/brvs
COPY ./brvs-core/build/libs/brvs-core-all.jar /opt/brvs/brvs-core.jar
ADD ./config/context/ /opt/brvs/config/context/
CMD ["java", "-cp", "/opt/brvs/*", "iroha.validation.Application", "config/context/spring-context.xml"]
