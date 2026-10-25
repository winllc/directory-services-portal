#!/usr/bin/env bash
# Installs the Directory Services Portal @VERSION@ from this package: verifies it, loads the
# container images into Docker or Podman and writes .env (never overwriting an existing one).
# By default the portal is published over plain HTTP on 0.0.0.0:8080 (every interface).
#
#   ./install.sh [options]
#     --http [PORT]      plain HTTP on PORT (default 8080; the default); with --https too it redirects to HTTPS
#     --https [PORT]     HTTPS on PORT (default 8443), X.509 sign-in available; needs ./certs.
#                        Without --http, only HTTPS is published
#     --bind ADDRESS     host address to publish on (default 0.0.0.0; 127.0.0.1: this host only)
#     --no-publish       publish nothing: route your own proxy on the compose network to web:8080
#     --engine ENGINE    docker or podman (default: CONTAINER_ENGINE, else Docker if it runs, else Podman)
#     --demo             built-in demo directory (no LDAP server), demo users and dashboards
#     --demo-openldap    demo with the bundled OpenLDAP container (if the package includes it)
#     --force-env        rewrite .env (the old one is kept as .env.bak-<time>)
#     --start            start the portal afterwards (./portal.sh up)
#     --skip-verify      don't check SHA256SUMS
set -euo pipefail
cd "$(dirname "$0")"

VERSION="@VERSION@"
API_IMAGE="@API_IMAGE@"
WEB_IMAGE="@WEB_IMAGE@"
OPENLDAP_IMAGE="@OPENLDAP_IMAGE@"

http_port=""; https_port=""; demo=""; force_env=""; start=""; verify=1; no_license=""
bind=""; no_publish=""; engine=""
while [ $# -gt 0 ]; do
  case "$1" in
    --http) http_port=8080; if [[ "${2:-}" =~ ^[0-9]+$ ]]; then http_port="$2"; shift; fi ;;
    --https) https_port=8443; if [[ "${2:-}" =~ ^[0-9]+$ ]]; then https_port="$2"; shift; fi ;;
    --bind) [ -n "${2:-}" ] || { echo "--bind needs an address" >&2; exit 2; }; bind="$2"; shift ;;
    --no-publish) no_publish=1 ;;
    --engine) [ -n "${2:-}" ] || { echo "--engine needs docker or podman" >&2; exit 2; }; engine="$2"; shift ;;
    --demo) demo=memory ;;
    --demo-openldap) demo=openldap ;;
    --force-env) force_env=1 ;;
    --start) start=1 ;;
    --skip-verify) verify="" ;;
    --no-license) no_license=1 ;; # used by upgrade.sh, which installs the license with the new images
    -h|--help) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $1 (see --help)" >&2; exit 2 ;;
  esac
  shift
done

say() { printf '\033[1m==>\033[0m %s\n' "$*"; }
die() { printf '\033[31mError:\033[0m %s\n' "$*" >&2; exit 1; }
warn() { printf '\033[33mWarning:\033[0m %s\n' "$*" >&2; }

[ -z "$no_publish" ] || [ -z "$http_port" ] || die "--no-publish and --http exclude each other"
[[ "$bind" =~ ^[0-9A-Za-z.:]*$ ]] || die "--bind takes an IP address, e.g. 127.0.0.1"
# shellcheck source=engine.sh
. ./engine.sh
engine_select "$engine"

if [ -n "$verify" ] && [ -f SHA256SUMS ]; then
  say "Verifying package checksums"
  if command -v sha256sum >/dev/null; then sha256sum --quiet -c SHA256SUMS || die "Checksum mismatch: the package is damaged."
  else shasum -a 256 -c SHA256SUMS >/dev/null || die "Checksum mismatch: the package is damaged."; fi
fi

if compgen -G "images/*.tar.gz" >/dev/null; then
  for f in images/*.tar.gz; do
    say "Loading $(basename "$f")"
    gzip -dc "$f" | "$ENGINE" load -q
  done
else
  say "No images in this package; they are pulled from the registry ($API_IMAGE, $WEB_IMAGE)"
fi

if [ "$demo" = openldap ] && ! "$ENGINE" image inspect "$OPENLDAP_IMAGE" >/dev/null 2>&1; then
  die "This package has no OpenLDAP image ($OPENLDAP_IMAGE). Use --demo, or build the package with --with-openldap."
fi

# --- .env ------------------------------------------------------------------------
setenv() { # setenv KEY VALUE: replace the KEY= line in .env (values never contain "|")
  local v="${2//&/\\&}"
  sed -i.tmp "s|^$1=.*|$1=$v|" .env && rm -f .env.tmp
}
if [ -f .env ] && [ -z "$force_env" ]; then
  say ".env already exists; keeping it (use --force-env to recreate)"
  # An .env from a package before Podman support (no CONTAINER_ENGINE line): there,
  # docker-compose.yml published nothing and docker-compose.publish.yml published WEB_PORT. Now
  # docker-compose.yml publishes it, so use docker-compose.no-publish.yml instead where
  # publish.yml was missing, and drop publish.yml: the same ports, on the same addresses.
  if ! grep -q '^CONTAINER_ENGINE=' .env; then
    old_files="$(sed -n 's/^COMPOSE_FILE=//p' .env | tail -n 1 | tr -d '"')"
    IFS=: read -ra parts <<<"${old_files:-docker-compose.yml}"
    published=""; files=""
    for f in "${parts[@]}"; do [ "$f" = docker-compose.publish.yml ] && published=1; done
    for f in "${parts[@]}"; do
      case "$f" in ""|docker-compose.publish.yml) continue ;; esac
      if [ "$f" = docker-compose.yml ] && [ -z "$published" ]; then f=docker-compose.no-publish.yml; fi
      files="${files:+$files:}$f"
    done
    case ":$files:" in
      *:docker-compose.yml:*|*:docker-compose.no-publish.yml:*) ;;
      *) warn "COMPOSE_FILE in .env does not list docker-compose.yml; check which ports it publishes." ;;
    esac
    cp -p .env ".env.bak-$(date +%Y%m%d%H%M%S)"
    if grep -q '^COMPOSE_FILE=' .env; then setenv COMPOSE_FILE "$files"
    else printf 'COMPOSE_FILE=%s\n' "$files" >> .env; fi
    printf '\n# Added by install.sh %s: the container engine (docker or podman; see env.template).\nCONTAINER_ENGINE=%s\n' "$VERSION" "$ENGINE" >> .env
    say "Updated .env for this package: COMPOSE_FILE=$files (published ports unchanged), CONTAINER_ENGINE=$ENGINE" >&2
  fi
else
  if [ -f .env ]; then cp .env ".env.bak-$(date +%Y%m%d%H%M%S)"; fi
  files="docker-compose.yml"
  # docker-compose.yml publishes HTTP; HTTPS alone (--https without --http) publishes only HTTPS.
  if [ -n "$no_publish" ] || { [ -n "$https_port" ] && [ -z "$http_port" ]; }; then files=docker-compose.no-publish.yml; fi
  [ -n "$https_port" ] && files="$files:docker-compose.mtls.yml"
  [ "$demo" = openldap ] && files="$files:docker-compose.openldap.yml"
  hp="${https_port:-8443}"
  suffix=":$hp"; [ "$hp" = 443 ] && suffix=""
  secure=false; [ -n "$https_port" ] && secure=true
  sed -e "s|@COMPOSE_FILE@|$files|" -e "s|@WEB_PORT@|${http_port:-8080}|" -e "s|@HTTPS_PORT@|$hp|" \
      -e "s|@HTTPS_PORT_SUFFIX@|$suffix|" -e "s|@COOKIE_SECURE@|$secure|" \
      -e "s|@CONTAINER_ENGINE@|$ENGINE|" env.template > .env
  chmod 600 .env
  [ -z "$bind" ] || setenv WEB_BIND "$bind"
  case "$demo" in
    memory)
      setenv DIRECTORY_MODE memory
      setenv DEMO_MODE true
      setenv SEED_DEMO_CONFIG true
      setenv ADMIN_USERS admin
      setenv LOGIN_HINT '"Demo: sign in as admin (administrator), alice, bob, erin (HR), dave (helpdesk) or frank (partner). Password: password"'
      ;;
    openldap)
      setenv LDAP_URL ldap://openldap:389
      setenv LDAP_BIND_DN cn=admin,dc=example,dc=com
      setenv LDAP_BIND_PASSWORD admin
      setenv LDAP_USER_SEARCH_BASE dc=example,dc=com
      setenv SEED_DEMO_CONFIG true
      setenv ADMIN_USERS admin
      setenv LOGIN_HINT '"Demo: sign in as admin (administrator), alice, bob, erin (HR), dave (helpdesk) or frank (partner). Password: password"'
      ;;
  esac
  [ -n "$https_port" ] && setenv X509_ENABLED true
  say "Wrote .env"
fi

# The built-in demo directory (DIRECTORY_MODE=memory) now starts only with DEMO_MODE=true.
# An .env from an earlier --demo install has no DEMO_MODE line: add it, so that demo keeps starting.
if grep -qi '^DIRECTORY_MODE=["]\{0,1\}memory' .env && ! grep -q '^DEMO_MODE=' .env; then
  printf '\n# Added by install.sh %s: this is a demo (the built-in directory needs it; see env.template).\nDEMO_MODE=true\n' "$VERSION" >> .env
  say "Added DEMO_MODE=true to .env (DIRECTORY_MODE=memory: the built-in demo directory)"
fi

# The X.509 proxy secret: the web proxy sends it to the API, which accepts a forwarded client
# certificate only with it. Generated once (also into the .env of an earlier release); kept after.
if ! grep -q '^X509_PROXY_SECRET=.' .env; then
  secret="$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')"
  [ "${#secret}" -eq 64 ] || die "Could not generate the X.509 proxy secret."
  if grep -q '^X509_PROXY_SECRET=' .env; then setenv X509_PROXY_SECRET "$secret"
  else printf '\n# Generated: the secret the web proxy sends to the API (X.509 sign-in; see env.template).\nX509_PROXY_SECRET=%s\n' "$secret" >> .env; fi
  say "Generated X509_PROXY_SECRET in .env"
fi

if [ -n "$https_port" ] || grep -q '^COMPOSE_FILE=.*mtls' .env; then
  mkdir -p certs
  if [ ! -f certs/server.pem ] || [ ! -f certs/server.key ] || [ ! -f certs/ca.pem ]; then
    echo
    echo "HTTPS needs certs/server.pem, certs/server.key and certs/ca.pem."
    echo "For a test setup: scripts/generate-dev-certs.sh certs"
  fi
fi

mkdir -p license

compose config -q || die "The compose configuration is invalid; check .env."

# --- ENS license -----------------------------------------------------------------
# A license.lic placed next to this script goes into .env as ENS_LICENSE, which the API installs as it
# starts unless a different usable license, or a newer revision of it, is installed already.
if [ -f license/license.lic ] && [ -z "$no_license" ]; then
  ./portal.sh license --if-missing license/license.lic
fi

echo
say "Directory Services Portal $VERSION is installed."
if [ -z "$demo" ]; then
  echo "    Edit .env: at least LDAP_URL, LDAP_BIND_DN, LDAP_BIND_PASSWORD, the search bases and ADMIN_USERS/ADMIN_GROUPS."
fi
[ -f license/license.lic ] || echo "    License: after starting, sign in as an administrator and upload your ENS license under Administration > License (/admin/license). Until then only sign-in and that screen work."
# Client-certificate revocation: checked live by default (ocsp-then-crl, soft-fail). An .env from
# an earlier package sets it off explicitly, which is kept; say so rather than change it.
if grep -q '^X509_ENABLED=true' .env; then
  revocation="$(sed -n 's/^ENS_X509_REVOCATION=//p' .env | tail -n 1 | tr -d '"' | tr '[:upper:]' '[:lower:]')"
  revocation_fail="$(sed -n 's/^ENS_X509_REVOCATION_FAIL=//p' .env | tail -n 1 | tr -d '"' | tr '[:upper:]' '[:lower:]')"
  if [ "$revocation" = off ]; then
    echo "    Revocation: OFF (ENS_X509_REVOCATION=off in .env). DISA STIG requires it: empty ENS_X509_REVOCATION and"
    echo "                ENS_X509_REVOCATION_FAIL for the default live check (OCSP, then the CRL; soft-fail)."
  elif [ -z "$revocation" ] && [ -z "$revocation_fail" ]; then
    echo "    Revocation: client certificates are checked at sign-in (OCSP, then the CRL), soft-fail: one nobody"
    echo "                can answer for signs in with a warning. ENS_X509_REVOCATION_FAIL=closed refuses those instead."
  fi
fi
# What is published, and to whom.
files="$(sed -n 's/^COMPOSE_FILE=//p' .env | tail -n 1 | tr -d '"')"
web_bind="$(sed -n 's/^WEB_BIND=//p' .env | tail -n 1 | tr -d '"')"; web_bind="${web_bind:-0.0.0.0}"
ports=()
case ":$files:" in *:docker-compose.no-publish.yml:*) ;; *) ports+=("$(sed -n 's/^WEB_PORT=//p' .env | tail -n 1 | tr -d '"')") ;; esac
case ":$files:" in *:docker-compose.mtls.yml:*) ports+=("$(sed -n 's/^HTTPS_PORT=//p' .env | tail -n 1 | tr -d '"')") ;; esac
case ":$files:" in
  *:docker-compose.mtls.yml:*) ;;
  *:docker-compose.no-publish.yml:*) echo "    Nothing is published on the host: route your reverse proxy, on the compose network, to web:8080." ;;
  *)
    case "$web_bind" in
      127.*|::1|localhost) ;;
      *) warn "Plain HTTP on $web_bind:${ports[0]:-8080} is reachable from the network. Put a TLS proxy in front, use --https, or set WEB_BIND=127.0.0.1 in .env to keep it to this host." ;;
    esac ;;
esac
# Rootless Podman can't bind ports below net.ipv4.ip_unprivileged_port_start (normally 1024).
if [ "$ENGINE_ROOTLESS" = true ]; then
  lowest="$(cat /proc/sys/net/ipv4/ip_unprivileged_port_start 2>/dev/null || echo 1024)"
  for p in ${ports[@]+"${ports[@]}"}; do
    if [[ "$p" =~ ^[0-9]+$ ]] && [ "$p" -lt "$lowest" ]; then
      warn "Rootless Podman can't publish port $p (below $lowest). Use a port >= $lowest, or: sudo sysctl net.ipv4.ip_unprivileged_port_start=$p"
    fi
  done
fi
echo "    Start:   ./portal.sh up"
echo "    Status:  ./portal.sh status    Logs: ./portal.sh logs"
if [ -n "$start" ]; then ./portal.sh up; fi
