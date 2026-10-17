#!/usr/bin/env bash
# Upgrades a Directory Services Portal deployment to @VERSION@ (this package): loads the new
# images, points .env at them and restarts. Settings (.env), license/, certs/, the data volume
# and the license volume (ens-license: the installed license and this installation's id) are kept.
#
#   ./upgrade.sh [PREVIOUS-DIR] [options]
#     PREVIOUS-DIR   the unpacked package of the running version: its .env, license/ and certs/
#                    are copied here first (nothing that already exists here is overwritten)
#     --no-backup    don't back up the portal data before upgrading
#     --no-start     prepare everything but don't restart the portal
#     --skip-verify  don't check SHA256SUMS
set -euo pipefail
cd "$(dirname "$0")"

VERSION="@VERSION@"
API_IMAGE="@API_IMAGE@"
WEB_IMAGE="@WEB_IMAGE@"
OPENLDAP_IMAGE="@OPENLDAP_IMAGE@"

say() { printf '\033[1m==>\033[0m %s\n' "$*"; }
die() { printf '\033[31mError:\033[0m %s\n' "$*" >&2; exit 1; }

prev=""; backup=1; start=1; install_args=()
while [ $# -gt 0 ]; do
  case "$1" in
    --no-backup) backup="" ;;
    --no-start) start="" ;;
    --skip-verify) install_args+=(--skip-verify) ;;
    -h|--help) sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    -*) die "Unknown option: $1 (see --help)" ;;
    *) [ -z "$prev" ] || die "Only one previous directory"; prev="$1" ;;
  esac
  shift
done

# --- Carry the settings over from the previous package ------------------------------
if [ -n "$prev" ]; then
  [ -f "$prev/.env" ] || die "$prev/.env not found (is $prev the directory of the running portal?)"
  [ "$(cd "$prev" && pwd)" != "$(pwd)" ] || die "$prev is this directory"
  if [ -f .env ]; then say ".env already exists here; keeping it"
  else cp -p "$prev/.env" .env; say "Copied .env from $prev"; fi
  for d in license certs; do
    if [ -d "$prev/$d" ]; then
      mkdir -p "$d"
      for f in "$prev/$d"/*; do
        [ -e "$f" ] || continue
        [ -e "$d/$(basename "$f")" ] || cp -pR "$f" "$d/"
      done
    fi
  done
fi
[ -f .env ] || die "No .env here. Run ./upgrade.sh <directory of the running version>, or ./install.sh for a first install."

# Verify the package and load its images (install.sh keeps the existing .env, adding the X.509
# proxy secret if it has none).
./install.sh --no-license ${install_args[@]+"${install_args[@]}"} >/dev/null

env_value() { sed -n "s/^$1=//p" .env | tail -n 1 | tr -d '"'; }
setenv() { # setenv KEY VALUE: replace (or add) the KEY= line in .env
  if grep -q "^$1=" .env; then sed -i.tmp "s|^$1=.*|$1=$2|" .env && rm -f .env.tmp
  else echo "$1=$2" >> .env; fi
}

# --- Back up the data with the running version ------------------------------------
project="$(env_value COMPOSE_PROJECT_NAME)"; project="${project:-directory-services-portal}"
if [ -n "$backup" ] && docker volume inspect "${project}_portal-data" >/dev/null 2>&1; then
  mkdir -p backups
  ./portal.sh backup "backups/pre-upgrade-$VERSION-$(date +%Y%m%d-%H%M%S).tar.gz"
fi

# --- Point .env at this version's images ------------------------------------------
cp -p .env ".env.bak-$(date +%Y%m%d%H%M%S)"
setenv API_IMAGE "$API_IMAGE"
setenv WEB_IMAGE "$WEB_IMAGE"
if docker image inspect "$OPENLDAP_IMAGE" >/dev/null 2>&1; then setenv OPENLDAP_IMAGE "$OPENLDAP_IMAGE"; fi
mkdir -p license
docker compose config -q || die "The compose configuration is invalid; check .env."
say "Settings now use $API_IMAGE and $WEB_IMAGE"

# The license volume is kept as is. A license.lic here is copied in only if it has none yet.
if [ -f license/license.lic ]; then
  ./portal.sh license --if-missing license/license.lic
fi

if [ -n "$start" ]; then
  ./portal.sh up
else
  echo "    Start the new version with: ./portal.sh up"
fi
