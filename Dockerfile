# Mule Runtime Community Edition for Railway. Community Edition has no deployment API (that is Anypoint
# Runtime Manager, Enterprise only) and Railway gives a container no shell, so this image adds a small
# admin front, started beside the runtime, that accepts a Mule application archive over authenticated
# HTTP and drops it into the hot-deploy directory, and forwards /gateway/* to the applications.
FROM eclipse-temurin:17-jdk AS admin
WORKDIR /build
COPY admin/Admin.java ./
RUN javac --release 17 -d classes Admin.java && jar cfe /railway-admin.jar Admin -C classes .

FROM eclipse-temurin:17-jre
ARG MULE_VERSION=4.12.0
ARG MULE_SHA1=a86cf412725cf5fb88649e015b67ccd549046e54
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL -o /tmp/mule.tgz "https://repository.mulesoft.org/nexus/content/repositories/releases/org/mule/distributions/mule-standalone/${MULE_VERSION}/mule-standalone-${MULE_VERSION}.tar.gz" \
 && echo "${MULE_SHA1}  /tmp/mule.tgz" | sha1sum -c - \
 && mkdir -p /opt && tar -xzf /tmp/mule.tgz -C /opt && mv /opt/mule-standalone-${MULE_VERSION} /opt/mule && rm /tmp/mule.tgz

COPY --from=admin /railway-admin.jar /opt/railway/admin.jar
COPY railway-entrypoint.sh /usr/local/bin/railway-entrypoint.sh
RUN chmod 0755 /usr/local/bin/railway-entrypoint.sh

ENV MULE_HOME=/opt/mule \
    MULE_BASE=/opt/mule \
    MULE_VERSION=4.12.0 \
    MULE_ADMIN_DATA=/data \
    MULE_HTTP_PORT=8081

EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/railway-entrypoint.sh"]
