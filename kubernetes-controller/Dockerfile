# works directly but large
#FROM ubuntu:18.04
# requires --static option for GraalVM but smaller
FROM alpine:latest
COPY target/graalvm-native-image/kubernetes-controller /opt/docker/out
RUN chmod +x /opt/docker/out
ENTRYPOINT ["/opt/docker/out"]