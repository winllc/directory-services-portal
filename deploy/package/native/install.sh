#!/usr/bin/env bash
# Installs the Directory Services Portal @VERSION@ on this host without containers:
#   /opt/directory-services-portal            the API jar and the web client
#   /etc/directory-services-portal/portal.env settings (kept if it exists)
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

install -d /etc/systemd/system
sed "s|@JAVA@|$JAVA|" directory-services-portal.service > /etc/systemd/system/directory-services-portal.service
systemctl daemon-reload
systemctl enable directory-services-portal >/dev/null

if command -v nginx >/dev/null; then
  conf=/etc/nginx/conf.d/directory-services-portal.conf
  [ -d /etc/nginx/conf.d ] || conf=/etc/nginx/sites-enabled/directory-services-portal.conf
  install -m 644 nginx/directory-services-portal.conf "$conf"
  nginx -t >/dev/null 2>&1 && systemctl reload nginx 2>/dev/null || say "Check the nginx configuration ($conf): nginx -t"
  say "nginx serves the portal on port 8080 ($conf)"
  if command -v getenforce >/dev/null && [ "$(getenforce)" = Enforcing ]; then
    echo "    SELinux: allow nginx to reach the API with: setsebool -P httpd_can_network_connect 1"
  fi
else
  say "nginx is not installed: serve $PREFIX/web with your web server and proxy /api to 127.0.0.1:3001 (example: nginx/directory-services-portal.conf)"
fi

echo
say "Directory Services Portal $VERSION is installed."
echo "    Edit $ETC/portal.env (LDAP connection, administrators), then:"
if [ ! -f "$LICENSE_DIR/license.lic" ]; then
  echo "    License: sign in as an administrator and upload your ENS license under Administration > License"
  echo "             (/admin/license), or: install -m 640 -o dsp -g dsp <your.lic> $LICENSE_DIR/license.lic"
  echo "             Until then only sign-in and the license screen work."
fi
[ -f "$ETC/license.lic" ] && echo "    Note: $ETC/license.lic (the old license format) is no longer used; ENS portal licenses replace it."
echo "    systemctl restart directory-services-portal && journalctl -u directory-services-portal -f"
if [ -n "$start" ]; then systemctl restart directory-services-portal; fi
