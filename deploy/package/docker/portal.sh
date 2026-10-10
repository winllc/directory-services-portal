#!/usr/bin/env bash
# Manage the Directory Services Portal @VERSION@ deployment in this directory.
#
#   ./portal.sh up                 start or update (applies .env changes), wait until healthy
#   ./portal.sh down               stop and remove the containers (data is kept)
#   ./portal.sh restart            restart the services
#   ./portal.sh status             containers, health and where to open the portal
#   ./portal.sh logs [service]     follow the logs (api, web or openldap)
#   ./portal.sh backup [file]      archive the portal data (configuration, audit log, results)
#   ./portal.sh restore <file>     replace the portal data with a backup (stops the API meanwhile)
#   ./portal.sh version
set -euo pipefail
cd "$(dirname "$0")"
VERSION="@VERSION@"

say() { printf '\033[1m==>\033[0m %s\n' "$*"; }
die() { printf '\033[31mError:\033[0m %s\n' "$*" >&2; exit 1; }
[ -f .env ] || die "No .env here; run ./install.sh first."
dc() { docker compose "$@"; }
env_value() { sed -n "s/^$1=//p" .env | tail -n 1 | tr -d '"'; }

urls() {
  local files; files="$(env_value COMPOSE_FILE)"
  local host; host="$(hostname -f 2>/dev/null || hostname)"
  case "$files" in *mtls*) echo "    https://$host:$(env_value HTTPS_PORT)" ;; esac
  case "$files" in *publish*) echo "    http://$host:$(env_value WEB_PORT)" ;; esac
  case "$files" in *mtls*|*publish*) ;; *) echo "    (no port published: route your reverse proxy to the web service on port 8080)" ;; esac
}

wait_healthy() {
  local id status
  id="$(dc ps -q api)"
  [ -n "$id" ] || die "The API container did not start; see ./portal.sh logs api"
  for _ in $(seq 1 90); do
    status="$(docker inspect -f '{{.State.Health.Status}}' "$id" 2>/dev/null || echo unknown)"
    case "$status" in
      healthy) return 0 ;;
      unhealthy) die "The API is unhealthy; see ./portal.sh logs api" ;;
    esac
    sleep 2
  done
  die "The API did not become healthy in time; see ./portal.sh logs api"
}

cmd="${1:-help}"; shift || true
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
  version) echo "$VERSION" ;;
  *) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//' ;;
esac
