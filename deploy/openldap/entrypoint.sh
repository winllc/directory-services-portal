#!/bin/sh
# Starts slapd, loading the demo LDIF into an empty database on first start.
set -eu

SUFFIX="${LDAP_SUFFIX:-dc=example,dc=com}"
ROOT_DN="${LDAP_ADMIN_DN:-cn=admin,$SUFFIX}"
DATA=/var/lib/ldap
CONF=/etc/ldap/slapd.conf

# slapd sizes internal tables by the open-file limit; very high container defaults make it
# hang or run out of memory, so keep it modest.
ulimit -n "${LDAP_NOFILE:-1024}" 2>/dev/null || true

ROOT_PW=$(slappasswd -s "${LDAP_ADMIN_PASSWORD:-admin}")
sed -e "s|@SUFFIX@|$SUFFIX|" -e "s|@ROOT_DN@|$ROOT_DN|" -e "s|@ROOT_PW@|$ROOT_PW|" \
  /etc/ldap/slapd.conf.template > "$CONF"

mkdir -p "$DATA" /run/slapd
if [ ! -f "$DATA/data.mdb" ]; then
  echo "Initialising directory $SUFFIX"
  for f in /seed/*.ldif; do
    [ -f "$f" ] || continue
    echo "  loading $(basename "$f")"
    slapadd -f "$CONF" -l "$f"
  done
fi
chown -R openldap:openldap "$DATA" /run/slapd

echo "Starting slapd for $SUFFIX"
exec slapd -f "$CONF" -h "ldap:///" -u openldap -g openldap -d "${LDAP_LOG_LEVEL:-0}"
