FROM eclipse-temurin:8-jdk  AS jdk8
FROM eclipse-temurin:11-jdk AS jdk11
FROM eclipse-temurin:17-jdk AS jdk17
FROM eclipse-temurin:25-jdk AS jdk25
FROM eclipse-temurin:26-jdk AS jdk26

FROM maven:3.9-eclipse-temurin-21

RUN apt-get update \
  && apt-get install -y --no-install-recommends git curl \
  && rm -rf /var/lib/apt/lists/*


COPY --from=jdk8 /opt/java/openjdk /opt/jdk/8
COPY --from=jdk11 /opt/java/openjdk /opt/jdk/11
COPY --from=jdk17 /opt/java/openjdk /opt/jdk/17
COPY --from=jdk25 /opt/java/openjdk /opt/jdk/25
COPY --from=jdk26 /opt/java/openjdk /opt/jdk/26
RUN ln -s /opt/java/openjdk /opt/jdk/21

RUN git config --system --add safe.directory '*'

WORKDIR /work
