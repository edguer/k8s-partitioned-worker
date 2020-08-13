FROM adoptopenjdk:latest

COPY entrypoint.sh /usr/entrypoint.sh
COPY target/k8s-partitioned-worker-1.0-SNAPSHOT-jar-with-dependencies.jar /usr/worker.jar

WORKDIR /usr/

ENTRYPOINT ["./entrypoint.sh", "worker.jar"]
