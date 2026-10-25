#!/usr/bin/env bash
# Manage the Directory Services Portal @VERSION@ deployment in this directory, with Docker or
# Podman (CONTAINER_ENGINE in .env, or in the environment; see engine.sh).
#
#   ./portal.sh up                 start or update (applies .env changes), wait until healthy
#   ./portal.sh down               stop and remove the containers (data is kept)
#   ./portal.sh restart            restart the services
#   ./portal.sh status             containers, health and where to open the portal
#   ./portal.sh logs [service]     follow the logs (api, web or openldap)
#   ./portal.sh backup [file]      archive the portal data (configuration, audit log, results)
#   ./portal.sh restore <file>     replace the portal data with a backup (stops the API meanwhile)
#   ./portal.sh license [--if-missing] <file>
#                                  install an ENS license file: sets ENS_LICENSE in .env, which the
#                                  API installs as it starts (and, without the shared database,
#                                  writes it into the ens-license volume at once); --if-missing only
#                                  sets .env, for install.sh and upgrade.sh
#   ./portal.sh version
set -euo pipefail
cd "$(dirname "$0")"
VERSION="@VERSION@"

say() { printf '\033[1m==>\033[0m %s\n' "$*"; }
die() { printf '\033[31mError:\033[0m %s\n' "$*" >&2; exit 1; }
[ -f .env ] || die "No .env here; run ./install.sh first."
env_value() { sed -n "s/^$1=//p" .env | tail -n 1 | tr -d '"'; }
cmd="${1:-help}"; shift || true
case "$cmd" in
  up|down|restart|status|logs|backup|restore|license)
    # shellcheck source=engine.sh
    . ./engine.sh
    case "$cmd" in up|status) ;; *) ENGINE_QUIET=1 ;; esac
    engine_select ""  # no --engine here: CONTAINER_ENGINE or .env
    ;;
esac
dc() { compose "$@"; }

urls() {
  local files; files=":$(env_value COMPOSE_FILE):"
  local host; host="$(env_value WEB_BIND)"
  case "$host" in ""|0.0.0.0|::) host="$(hostname -f 2>/dev/null || hostname)" ;; *:*) host="[$host]" ;; esac
  case "$files" in *:docker-compose.mtls.yml:*) echo "    https://$host:$(env_value HTTPS_PORT)" ;; esac
  case "$files" in *:docker-compose.no-publish.yml:*) ;; *) echo "    http://$host:$(env_value WEB_PORT)" ;; esac
  case "$files" in
    *:docker-compose.mtls.yml:*) ;;
    *:docker-compose.no-publish.yml:*) echo "    (no port published: route your reverse proxy to the web service on port 8080)" ;;
  esac
}

wait_healthy() {
  local id status i
  id="$(engine_service_container api)"
  [ -n "$id" ] || die "The API container did not start; see ./portal.sh logs api"
  for i in $(seq 0 89); do
    if [ $((i % 8)) -eq 0 ]; then status="$(engine_health "$id" run)"; else status="$(engine_health "$id")"; fi
    case "$status" in
      healthy) return 0 ;;
      unhealthy) die "The API is not ready: it can't reach the directory, write its audit log or its data disk is full; see ./portal.sh logs api" ;;
    esac
    sleep 2
  done
  die "The API did not become ready in time (directory, audit log, data disk); see ./portal.sh logs api"
}

case "$cmd" in
  up)
    dc up -d --remove-orphans
    say "Waiting for the API"
    wait_healthy
    say "Directory Services Portal $VERSION is running:"
    urls
    ;;
  down) dc down ;;
  restart) dc restart; wait_healthy; say "Restarted" ;;
  status) dc ps; echo; urls ;;
  logs) dc logs -f --tail=200 "$@" ;;
  backup)
    file="${1:-portal-backup-$(date +%Y%m%d-%H%M%S).tar.gz}"
    say "Backing up the portal data to $file"
    dc run --rm --no-deps -T --entrypoint tar api -cf - -C /data . | gzip > "$file"
    say "Done ($(du -h "$file" | cut -f1))"
    ;;
  restore)
    file="${1:-}"; [ -f "$file" ] || die "Usage: ./portal.sh restore <backup.tar.gz>"
    read -r -p "Replace ALL portal data with $file? [y/N] " answer
    [ "$answer" = y ] || [ "$answer" = Y ] || exit 1
    dc stop api
    gzip -dc "$file" | dc run --rm --no-deps -T --entrypoint sh api -c 'find /data -mindepth 1 -delete && tar -xf - -C /data'
    dc up -d api
    wait_healthy
    say "Restored $file"
    ;;
  license)
    # The license goes into .env as ENS_LICENSE (the file, base64), which the API installs on its
    # first license check after it starts: when no usable license is installed yet, or when it is a
    # newer revision of the installed one. That works with or without the shared database. With the
    # database (DSP_DATABASE_URL) the license is kept there and the ens-license volume is read only
    # on the first start, so a file copied into the volume afterwards would never be read.
    mode=always
    if [ "${1:-}" = --if-missing ]; then mode=if-missing; shift; fi
    file="${1:-}"; [ -f "$file" ] || die "Usage: ./portal.sh license [--if-missing] <license.lic>"
    if ! grep -q -- '-----BEGIN ENS LICENSE-----' "$file"; then
      [ "$mode" = if-missing ] || die "$file is not an ENS license file (download it from the ENS licensing portal)"
      printf '\033[33mWarning:\033[0m %s is not an ENS license file (an old-format license?); not installed.\n' "$file" >&2
      exit 0
    fi
    value="$(base64 < "$file" | tr -d '\n')"
    if [ "$(env_value ENS_LICENSE)" = "$value" ]; then
      say "$file is already ENS_LICENSE in .env"
    else
      # rewritten in place (cat >), so .env keeps its owner and mode; awk -v is safe for base64
      tmp="$(mktemp .env.XXXXXX)"
      awk -v v="$value" '
        /^ENS_LICENSE=/ { if (!done) print "ENS_LICENSE=" v; done = 1; next }
        { print }
        END { if (!done) { print ""; print "# The ENS license (base64), set by ./portal.sh license."; print "ENS_LICENSE=" v } }
      ' .env > "$tmp"
      cat "$tmp" > .env
      rm -f "$tmp"
      say "Set ENS_LICENSE in .env from $file"
    fi
    # install.sh and upgrade.sh (--if-missing) start the API themselves, or say how
    [ "$mode" = if-missing ] && exit 0
    if [ -z "$(env_value DSP_DATABASE_URL)" ]; then
      # Without the database the license is a file in the volume, read again when it changes: also
      # write it there, so it takes effect now, and replaces a different license already installed.
      # Written by the api image's own user, so the portal can replace it later.
      # shellcheck disable=SC2016 # expanded by the container's shell
      dc run --rm --no-deps -T --entrypoint sh api -c '
        f=/var/lib/ens/directory-portal/license.lic
        cat > "$f.new" && mv -f "$f.new" "$f"' < "$file" || die "Could not install $file"
      say "Installed $file. Check it under Administration > License."
    elif [ -n "$(engine_service_container api)" ]; then
      say "Restarting the API with it"
      dc up -d api
      wait_healthy
      say "The API installs it unless a different usable license, or a newer revision of it, is installed"
      say "already. Check it under Administration > License; upload a different license there."
    else
      say "The API installs it when it starts (./portal.sh up), unless a different usable license, or a newer"
      say "revision of it, is installed already."
    fi
    ;;
  version) echo "$VERSION" ;;
  *) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//' ;;
esac
