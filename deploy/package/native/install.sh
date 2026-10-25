#!/usr/bin/env bash
# Installs the Directory Services Portal @VERSION@ on this host without containers:
#   /opt/directory-services-portal            the API jar and the web client
#   /etc/directory-services-portal/portal.env settings (kept if it exists)
#   /etc/directory-services-portal/proxy-secret  X.509 proxy secret (generated if missing), and
#     nginx-proxy-secret.conf, the copy nginx includes
#   /etc/directory-services-portal/ldap-bind-password  the LDAP service account's password (created
#     empty if missing; LDAP_BIND_PASSWORD_FILE in portal.env)
#   /var/lib/directory-services-portal        data (configuration, audit log, results)
#   /var/lib/ens/directory-portal             ENS license, installation id (ENS_LICENSE_* in portal.env)
#   systemd unit directory-services-portal    the API, on 127.0.0.1:3001
#   nginx site directory-services-portal.conf serves the client on :8080 and proxies /api
# Requires root, Java 21+ and systemd; nginx is configured if installed.
#
#   sudo ./install.sh [--start]
set -euo pipefail
cd "$(dirname "$0")"
VERSION="@VERSION@"
PREFIX=/opt/directory-services-portal
ETC=/etc/directory-services-portal
DATA=/var/lib/directory-services-portal
LICENSE_DIR=/var/lib/ens/directory-portal

say() { printf '\033[1m==>\033[0m %s\n' "$*"; }
die() { printf '\033[31mError:\033[0m %s\n' "$*" >&2; exit 1; }
start=""; [ "${1:-}" = "--start" ] && start=1

[ "$(id -u)" = 0 ] || die "Run as root (sudo ./install.sh)."
command -v systemctl >/dev/null || die "systemd is required."
JAVA="$(command -v java || true)"
[ -n "$JAVA" ] || die "Java 21 or later is required (e.g. dnf install java-21-openjdk-headless)."
# Keep the stable link (e.g. /usr/bin/java via alternatives) so Java updates don't break the unit.
major="$("$JAVA" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n1)"
[ "${major:-0}" -ge 21 ] || die "Java 21 or later is required (found $("$JAVA" -version 2>&1 | head -n1))."

if [ -f SHA256SUMS ]; then
  say "Verifying package checksums"
  sha256sum --quiet -c SHA256SUMS || die "Checksum mismatch: the package is damaged."
fi

id dsp >/dev/null 2>&1 || { say "Creating system user dsp"; useradd --system --home-dir "$DATA" --shell /sbin/nologin dsp; }

say "Installing to $PREFIX"
install -d -m 755 "$PREFIX/api" "$PREFIX/web"
install -m 644 api/directory-services-portal.jar "$PREFIX/api/"
rm -rf "$PREFIX/web.new" && cp -r web "$PREFIX/web.new" && rm -rf "$PREFIX/web" && mv "$PREFIX/web.new" "$PREFIX/web"
chmod -R a+rX "$PREFIX"
install -d -m 750 -o dsp -g dsp "$DATA"
# The license screen writes the license here; it also keeps the installation id. Keep it.
install -d -m 755 /var/lib/ens
install -d -m 750 -o dsp -g dsp "$LICENSE_DIR"
install -d -m 750 -o root -g dsp "$ETC"
if [ -f "$ETC/portal.env" ]; then
  say "Keeping $ETC/portal.env"
  # Earlier releases read a read-only license from /etc; the license now lives in $LICENSE_DIR.
  if grep -q "^ENS_LICENSE_FILE=$ETC/license.lic\$" "$ETC/portal.env"; then
    sed -i "s|^ENS_LICENSE_FILE=$ETC/license.lic\$|ENS_LICENSE_FILE=$LICENSE_DIR/license.lic|" "$ETC/portal.env"
    say "Set ENS_LICENSE_FILE=$LICENSE_DIR/license.lic in $ETC/portal.env"
  fi
else
  install -m 640 -o root -g dsp portal.env "$ETC/portal.env"
  say "Wrote $ETC/portal.env"
fi

# The X.509 proxy secret: nginx sends it to the API with every request (X-DSP-Proxy-Secret), and
# the API accepts a forwarded client certificate only with it (X509_SOURCE=header). The API reads
# $ETC/proxy-secret (X509_PROXY_SECRET_FILE, set in the unit; root and dsp only). nginx, whose
# master process reads its configuration as root, includes $ETC/nginx-proxy-secret.conf (root
# only), rewritten from it on every run. Generated once; to change it, write a new value to
# $ETC/proxy-secret and run this again.
if [ ! -s "$ETC/proxy-secret" ]; then
  (umask 077 && head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$ETC/proxy-secret")
  say "Generated the X.509 proxy secret ($ETC/proxy-secret)"
fi
chown root:dsp "$ETC/proxy-secret" && chmod 640 "$ETC/proxy-secret"
proxy_secret="$(sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//' "$ETC/proxy-secret")"
secret_re='^[[:alnum:]._~+/=-]{32,}$'
[[ "$proxy_secret" =~ $secret_re ]] \
  || die "$ETC/proxy-secret must hold one value of at least 32 letters, digits or . _ ~ + / = - (e.g. openssl rand -hex 32)."
(umask 077 && printf '# Written by install.sh from %s: the X.509 proxy secret, sent to the API.\nproxy_set_header X-DSP-Proxy-Secret "%s";\n' \
  "$ETC/proxy-secret" "$proxy_secret" > "$ETC/nginx-proxy-secret.conf")
chown root:root "$ETC/nginx-proxy-secret.conf" && chmod 600 "$ETC/nginx-proxy-secret.conf"

# The LDAP service account's password (LDAP_BIND_PASSWORD_FILE): root and dsp only, never
# overwritten. An upgraded portal.env that still sets LDAP_BIND_PASSWORD keeps working.
if [ ! -e "$ETC/ldap-bind-password" ]; then
  (umask 077 && : > "$ETC/ldap-bind-password")
  say "Created $ETC/ldap-bind-password (empty): write the LDAP service account's password into it"
fi
chown root:dsp "$ETC/ldap-bind-password" && chmod 640 "$ETC/ldap-bind-password"

install -d /etc/systemd/system
sed "s|@JAVA@|$JAVA|" directory-services-portal.service > /etc/systemd/system/directory-services-portal.service
systemctl daemon-reload
systemctl enable directory-services-portal >/dev/null

if command -v nginx >/dev/null; then
  conf=/etc/nginx/conf.d/directory-services-portal.conf
  [ -d /etc/nginx/conf.d ] || conf=/etc/nginx/sites-enabled/directory-services-portal.conf
  install -m 644 nginx/directory-services-portal.conf "$conf"
  # PUBLIC_HOST in portal.env: the host[:port] users open, sent to the API as X-Forwarded-Host
  # (redirect URIs and the SAML entity id) instead of the client's Host header.
  public_host=$(sed -n 's/^PUBLIC_HOST=//p' "$ETC/portal.env" | tail -n 1 | tr -d '"'"'"' ')
  case "$public_host" in
    "") ;;
    *[!A-Za-z0-9.:-]*) say "PUBLIC_HOST in $ETC/portal.env must be a host name with an optional :port; ignored" ;;
    *) sed -i "s|proxy_set_header X-Forwarded-Host \"\$http_host\";|proxy_set_header X-Forwarded-Host \"$public_host\";|" "$conf"
       say "nginx sends X-Forwarded-Host $public_host (PUBLIC_HOST)" ;;
  esac
  nginx -t >/dev/null 2>&1 && systemctl reload nginx 2>/dev/null || say "Check the nginx configuration ($conf): nginx -t"
  say "nginx serves the portal on port 8080 ($conf)"
  if command -v getenforce >/dev/null && [ "$(getenforce)" = Enforcing ]; then
    echo "    SELinux: allow nginx to reach the API with: setsebool -P httpd_can_network_connect 1"
  fi
else
  say "nginx is not installed: serve $PREFIX/web with your web server and proxy /api to 127.0.0.1:3001 (example: nginx/directory-services-portal.conf)"
  echo "    For X.509 sign-in it must also send X-DSP-Proxy-Secret with the value in $ETC/proxy-secret."
fi
if grep -q '^X509_SOURCE=header' "$ETC/portal.env"; then
  echo "    X.509 sign-in through nginx: the location that proxies /api in your HTTPS server must"
  echo "    include $ETC/nginx-proxy-secret.conf, and portal.env must set X509_TRUSTED_CA_FILE;"
  echo "    the API refuses forwarded certificates without the one and does not start without the other."
fi

echo
say "Directory Services Portal $VERSION is installed."
echo "    Edit $ETC/portal.env (LDAP connection, administrators) and put the service account's password in"
echo "    $ETC/ldap-bind-password (printf '%s' 'the-password' > $ETC/ldap-bind-password), then:"
if [ ! -f "$LICENSE_DIR/license.lic" ]; then
  echo "    License: sign in as an administrator and upload your ENS license under Administration > License"
  echo "             (/admin/license), or: install -m 640 -o dsp -g dsp <your.lic> $LICENSE_DIR/license.lic"
  echo "             (with DSP_DATABASE_URL, before the first start only; then set ENS_LICENSE in portal.env)"
  echo "             Until then only sign-in and the license screen work."
fi
[ -f "$ETC/license.lic" ] && echo "    Note: $ETC/license.lic (the old license format) is no longer used; ENS portal licenses replace it."
echo "    systemctl restart directory-services-portal && journalctl -u directory-services-portal -f"
if [ -n "$start" ]; then systemctl restart directory-services-portal; fi
