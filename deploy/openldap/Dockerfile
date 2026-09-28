# Small OpenLDAP server for the docker compose demo. Built locally from Ubuntu's maintained
# slapd package, so it runs natively on amd64 and arm64 (e.g. Apple Silicon).
FROM ubuntu:24.04
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends slapd ldap-utils \
 && rm -rf /var/lib/apt/lists/* /etc/ldap/slapd.d /var/lib/ldap/*
COPY slapd.conf.template /etc/ldap/slapd.conf.template
COPY schema/acme.schema /etc/ldap/schema/acme.schema
COPY ldif/ /seed/
COPY entrypoint.sh /entrypoint.sh
# Tolerate checkouts with Windows line endings.
RUN sed -i 's/\r$//' /entrypoint.sh /seed/*.ldif /etc/ldap/schema/acme.schema /etc/ldap/slapd.conf.template \
 && chmod 0755 /entrypoint.sh
VOLUME /var/lib/ldap
EXPOSE 389
ENTRYPOINT ["/entrypoint.sh"]
