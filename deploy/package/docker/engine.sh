# shellcheck shell=bash
# Container engine selection for the Directory Services Portal @VERSION@ package: sourced by
# install.sh, upgrade.sh and portal.sh (not run on its own). Needs say() and die().
#
# The engine, first match wins:
#   1. --engine docker|podman (install.sh, upgrade.sh)
#   2. the CONTAINER_ENGINE environment variable
#   3. CONTAINER_ENGINE in .env (install.sh writes the engine it used)
#   4. docker, for an .env written by a package from before Podman support
#   5. detected: Docker if its daemon answers, else Podman
# The compose command: Docker Compose v2 ("docker compose"), else docker-compose v1 (1.29); for
# Podman, podman-compose (1.0.6 or later), else "podman compose".
#
# Sets ENGINE (docker or podman), ENGINE_ROOTLESS (true or false) and COMPOSE (the compose
# command, an array), and says which (unless ENGINE_QUIET is set); compose() runs the compose
# command with this deployment's files and project name.

engine_warn() { printf '\033[33mWarning:\033[0m %s\n' "$*" >&2; }

# The last KEY= value in .env, unquoted (empty without .env).
engine_env_value() { if [ -f .env ]; then sed -n "s/^$1=//p" .env | tail -n 1 | tr -d "\"'"; fi; }

# Docker proper (not Podman's "docker" command) is installed and its daemon answers.
engine_docker_ok() {
  command -v docker >/dev/null 2>&1 || return 1
  ! docker --version 2>/dev/null | grep -qi podman || return 1
  docker info >/dev/null 2>&1
}

# A version string's major, minor and patch as one number (4.9.3 -> 4009003, 1.0.6 -> 1000006).
engine_version_number() {
  local major minor patch
  IFS=. read -r major minor patch _ <<<"${1#v}"
  patch="${patch%%[!0-9]*}"
  [[ "$major" =~ ^[0-9]+$ ]] || major=0
  [[ "$minor" =~ ^[0-9]+$ ]] || minor=0
  [[ "$patch" =~ ^[0-9]+$ ]] || patch=0
  echo $((major * 1000000 + minor * 1000 + patch))
}

engine_select() { # engine_select [ENGINE]: ENGINE from --engine, if one was given
  local want="${1:-}" source="--engine" saved version compose_version
  saved="$(engine_env_value CONTAINER_ENGINE)"
  if [ -z "$want" ] && [ -n "${CONTAINER_ENGINE:-}" ]; then want="$CONTAINER_ENGINE"; source="CONTAINER_ENGINE"; fi
  if [ -z "$want" ] && [ -n "$saved" ]; then want="$saved"; source=".env"; fi
  if [ -z "$want" ] && [ -f .env ] && ! grep -q '^CONTAINER_ENGINE=' .env; then
    want=docker; source=".env from an earlier package"
  fi
  if [ -z "$want" ]; then
    source="detected"
    if engine_docker_ok; then want=docker
    elif command -v podman >/dev/null 2>&1; then want=podman
    elif command -v docker >/dev/null 2>&1; then want=docker
    else die "Neither Docker (https://docs.docker.com/engine/install/) nor Podman (https://podman.io/docs/installation) is installed."
    fi
  fi
  case "$want" in
    docker|podman) ;;
    *) die "CONTAINER_ENGINE must be docker or podman, not \"$want\"." ;;
  esac
  if [ -n "$saved" ] && [ "$saved" != "$want" ]; then
    engine_warn "Using $want, but this deployment was installed with $saved (CONTAINER_ENGINE in .env): its containers and volumes are in $saved."
  fi
  ENGINE="$want"; ENGINE_ROOTLESS=false

  if [ "$ENGINE" = docker ]; then
    command -v docker >/dev/null 2>&1 || die "Docker is not installed (https://docs.docker.com/engine/install/)."
    docker info >/dev/null 2>&1 || die "Cannot talk to the Docker daemon. Is it running, and may this user use it?"
    version="$(docker version --format '{{.Server.Version}}' 2>/dev/null || true)"
    if docker compose version >/dev/null 2>&1; then
      COMPOSE=(docker compose)
      compose_version="$(docker compose version --short 2>/dev/null || true)"
    elif command -v docker-compose >/dev/null 2>&1 && docker-compose version >/dev/null 2>&1; then
      COMPOSE=(docker-compose)
      compose_version="$(docker-compose version --short 2>/dev/null || true)"
    else
      die "Docker Compose v2 ('docker compose') is required."
    fi
  else
    command -v podman >/dev/null 2>&1 || die "Podman is not installed (https://podman.io/docs/installation)."
    podman info >/dev/null 2>&1 || die "Podman does not work for this user ('podman info' fails)."
    version="$(podman version --format '{{.Client.Version}}' 2>/dev/null || true)"
    [ "$(engine_version_number "$version")" -ge 4000000 ] || die "Podman 4.0 or later is required (found ${version:-an unknown version})."
    [ "$(podman info --format '{{.Host.Security.Rootless}}' 2>/dev/null)" = true ] && ENGINE_ROOTLESS=true
    if command -v podman-compose >/dev/null 2>&1; then
      # "podman-compose version 1.6.0", "podman-compose version: 1.0.6", "podman-composer version  1.0.3"
      compose_version="$(podman-compose version 2>/dev/null | sed -nE 's/^podman-composer? version:? *//p' | head -n 1)"
      # 1.0.6 is what EPEL ships for RHEL 8 and 9; CI checks the package with it and the latest.
      [ "$(engine_version_number "$compose_version")" -ge 1000006 ] \
        || die "podman-compose 1.0.6 or later is required (found ${compose_version:-an unknown version}): pip install --user --upgrade podman-compose"
      COMPOSE=(podman-compose)
    elif podman compose version >/dev/null 2>&1; then
      COMPOSE=(podman compose)
      compose_version=""
    else
      die "podman-compose is required (pip install --user podman-compose, or your distribution's package)."
    fi
  fi
  local mode=""; [ "$ENGINE" = podman ] && { mode=" rootful"; [ "$ENGINE_ROOTLESS" = true ] && mode=" rootless"; }
  [ -n "${ENGINE_QUIET:-}" ] || say "Container engine: $ENGINE ${version:-}$mode ($source); compose: ${COMPOSE[*]} ${compose_version:-}"
}

# The compose project name (volumes are <project>_portal-data, ...).
engine_project() { local p; p="$(engine_env_value COMPOSE_PROJECT_NAME)"; echo "${p:-directory-services-portal}"; }

# The compose command with this deployment's files (COMPOSE_FILE in .env) and project name, given
# explicitly: not every compose implementation reads COMPOSE_FILE from .env.
compose() {
  local files list f args=()
  files="$(engine_env_value COMPOSE_FILE)"
  IFS=: read -ra list <<<"${files:-docker-compose.yml}"
  for f in "${list[@]}"; do [ -z "$f" ] || args+=(-f "$f"); done
  "${COMPOSE[@]}" "${args[@]}" -p "$(engine_project)" "$@"
}

# The running container of a compose service (empty if none).
engine_service_container() {
  "$ENGINE" ps -q --filter "label=com.docker.compose.project=$(engine_project)" \
    --filter "label=com.docker.compose.service=$1" | head -n 1
}

# A container's health: starting, healthy, unhealthy or unknown. Podman runs healthchecks from
# systemd timers, which a host without systemd (or a rootless user without a session) lacks, so
# with Podman a check is also run here (at most every 15 seconds, the services' interval).
engine_health() { # engine_health CONTAINER [RUN-CHECK]
  if [ "$ENGINE" = podman ] && [ -n "${2:-}" ]; then podman healthcheck run "$1" >/dev/null 2>&1 || true; fi
  "$ENGINE" inspect -f '{{.State.Health.Status}}' "$1" 2>/dev/null || echo unknown
}
