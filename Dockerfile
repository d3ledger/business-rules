FROM openjdk:8-jre
WORKDIR /opt/brvs
COPY ./brvs-core/build/libs/brvs-core-all.jar /opt/brvs/brvs-core.jar
ADD ./config/context/ /opt/brvs/config/context/
EXPOSE 8090

## THE LIFE SAVER
ADD https://github.com/ufoscout/docker-compose-wait/releases/download/2.5.0/wait /wait
RUN chmod +x /wait
## Launch the wait tool and then the application
ADD ./deploy/brvs_run.sh /brvs_run.sh
CMD /wait && /brvs_run.sh
