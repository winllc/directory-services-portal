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
#                                  install an ENS license file into the ens-license volume (the
#                                  license screen does the same); --if-missing keeps an installed one
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
    mode=always
    if [ "${1:-}" = --if-missing ]; then mode=if-missing; shift; fi
    file="${1:-}"; [ -f "$file" ] || die "Usage: ./portal.sh license [--if-missing] <license.lic>"
    if ! grep -q -- '-----BEGIN ENS LICENSE-----' "$file"; then
      [ "$mode" = if-missing ] || die "$file is not an ENS license file (download it from the ENS licensing portal)"
      printf '\033[33mWarning:\033[0m %s is not an ENS license file (an old-format license?); not installed.\n' "$file" >&2
      exit 0
    fi
    # Written by the api image's own user into the volume, so the portal can replace it later.
    # shellcheck disable=SC2016 # expanded by the container's shell
    result="$(dc run --rm --no-deps -T --entrypoint sh api -c '
      f=/var/lib/ens/directory-portal/license.lic
      if [ "$1" = if-missing ] && [ -s "$f" ]; then echo kept; exit 0; fi
      cat > "$f.new" && mv -f "$f.new" "$f" && echo installed' sh "$mode" < "$file")"
    case "$result" in
      *installed) say "Installed $file into the portal's license volume (ens-license). Check it under Administration > License." ;;
      *kept) say "A license is already installed in the license volume; kept it (replace it with ./portal.sh license $file)" ;;
      *) die "Could not install $file" ;;
    esac
    ;;
  version) echo "$VERSION" ;;
  *) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//' ;;
esac
