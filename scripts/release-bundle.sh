#!/usr/bin/env bash
# Builds the customer release bundle of the Directory Services Portal from images that are
# already built (in CI: the images that were just smoke-tested and published).
#
#   scripts/release-bundle.sh [--output DIR] <version> <image-ref>...
#
#   <version>     release version, e.g. 1.2.3 or 0.0.0-pr42
#   <image-ref>   the API and web images, named as customers pull them, e.g.
#                 ghcr.io/winllc/directory-services-portal-api:1.2.3
#                 ghcr.io/winllc/directory-services-portal-web:1.2.3
#                 (told apart by the repository name ending in -api / -web)
#   --output DIR  where to write the bundle (default: dist/release/<version>)
#
# Writes to DIR:
#   directory-services-portal-<version>.tar.gz   the docker deployment package (scripts/package.sh)
#                                                with the images, compose files, .env template,
#                                                install.sh, upgrade.sh and portal.sh
#   sbom-<image>.spdx.json                       SPDX SBOM per image (needs syft)
#   SHA256SUMS                                   checksums of all of the above
#
# Environment: SYFT (syft binary, default "syft"); REQUIRE_SBOM=1 fails instead of skipping the
# SBOMs when syft is missing (CI sets it). Signing and upload are done by CI (.github/workflows/ci.yml).
set -euo pipefail

PRODUCT=directory-services-portal
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

usage() { sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; }
die() { printf '\033[31mError:\033[0m %s\n' "$*" >&2; exit 1; }
say() { printf '\033[1m==>\033[0m %s\n' "$*"; }
image_name() { local n="${1%@*}"; n="${n##*/}"; echo "${n%%:*}"; } # registry/x/name:tag -> name

out=""; args=()
while [ $# -gt 0 ]; do
  case "$1" in
    --output) [ -n "${2:-}" ] || die "--output needs a value"; out="$2"; shift ;;
    -h|--help) usage; exit 0 ;;
    -*) die "Unknown option: $1 (see --help)" ;;
    *) args+=("$1") ;;
  esac
  shift
done
[ "${#args[@]}" -ge 3 ] || { usage >&2; exit 2; }
version="${args[0]}"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z][0-9A-Za-z.-]*)?$ ]] \
  || die "Invalid version \"$version\" (expected X.Y.Z or X.Y.Z-suffix)"

api_ref=""; web_ref=""
for ref in "${args[@]:1}"; do
  case "$(image_name "$ref")" in
    *-api) [ -z "$api_ref" ] || die "Two API images given"; api_ref="$ref" ;;
    *-web) [ -z "$web_ref" ] || die "Two web images given"; web_ref="$ref" ;;
    *) die "Don't know which image $ref is (expected a repository name ending in -api or -web)" ;;
  esac
  [[ "$ref" == *":$version" ]] || printf '\033[33mWarning:\033[0m %s is not tagged %s\n' "$ref" "$version" >&2
done
[ -n "$api_ref" ] && [ -n "$web_ref" ] || die "Both the API and the web image are needed"
command -v docker >/dev/null || die "Docker is required"
for ref in "$api_ref" "$web_ref"; do
  docker image inspect "$ref" >/dev/null 2>&1 || die "Image $ref not found locally (build/pull and tag it first)"
done

out="${out:-$ROOT/dist/release/$version}"
mkdir -p "$out"
out="$(cd "$out" && pwd)"
rm -f "$out/$PRODUCT"-*.tar.gz "$out"/sbom-*.spdx.json "$out/SHA256SUMS" "$out/SHA256SUMS.asc"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# --- The deployment package, from the given images ----------------------------------
bundle="$PRODUCT-$version.tar.gz"
"$ROOT/scripts/package.sh" --target docker --version "$version" \
  --api-image "$api_ref" --web-image "$web_ref" --output "$work/package"
mv "$work/package/$PRODUCT-$version-docker.tar.gz" "$out/$bundle"

# --- Nothing secret in it ----------------------------------------------------------
say "Checking the bundle for licenses, keys and settings files"
mkdir "$work/check"
tar -xzf "$out/$bundle" -C "$work/check"
[ -d "$work/check/$PRODUCT-$version" ] || die "The bundle does not unpack to $PRODUCT-$version/"
bad="$(cd "$work/check" && find . -type f \( -name '*.lic' -o -name '.env' -o -name '.env.*' -o -name '*.key' \
  -o -name '*.p12' -o -name '*.pfx' -o -name '*.jks' -o -name '*private*.pem' \) -print)"
bad="$bad$(cd "$work/check" && grep -rlE -e '-----BEGIN ([A-Z]+ )?PRIVATE KEY-----' --exclude-dir=images . || true)"
[ -z "$bad" ] || die "The bundle contains files that must not ship: $bad"

# --- SBOMs -------------------------------------------------------------------------
syft="${SYFT:-syft}"
if command -v "$syft" >/dev/null 2>&1; then
  for ref in "$api_ref" "$web_ref"; do
    short="$(image_name "$ref")"
    say "SBOM for $ref"
    "$syft" "docker:$ref" -q -o "spdx-json=$out/sbom-$short.spdx.json"
  done
elif [ "${REQUIRE_SBOM:-}" = 1 ]; then
  die "syft not found (set SYFT, or install it: https://github.com/anchore/syft)"
else
  printf '\033[33mWarning:\033[0m syft not found; no SBOMs written\n' >&2
fi

# --- Checksums ---------------------------------------------------------------------
(cd "$out" && find . -maxdepth 1 -type f \( -name '*.tar.gz' -o -name 'sbom-*.spdx.json' \) -printf '%f\n' \
  | LC_ALL=C sort | xargs sha256sum > SHA256SUMS)

say "Release bundle $PRODUCT $version in $out:"
for f in "$out"/*; do printf '    %6s  %s\n' "$(du -h "$f" | cut -f1)" "$(basename "$f")"; done
