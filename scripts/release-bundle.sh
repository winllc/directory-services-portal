#!/usr/bin/env bash
# Builds the customer release bundle of the Directory Services Portal from images that are
# already built (in CI: the images that were just smoke-tested and published).
#
#   scripts/release-bundle.sh [--output DIR] [--chart FILE] [--no-native] <version> <image-ref>...
#
#   <version>     release version, e.g. 1.2.3 or 0.0.0-pr42
#   <image-ref>   the API and web images, named as customers pull them, e.g.
#                 ghcr.io/winllc/directory-services-portal-api:1.2.3
#                 ghcr.io/winllc/directory-services-portal-web:1.2.3
#                 (told apart by the repository name ending in -api / -web)
#   --output DIR  where to write the bundle (default: dist/release/<version>)
#   --chart FILE  also release this packaged Helm chart (helm package output, .tgz)
#   --no-native   skip the native (systemd) package
#
# Writes to DIR:
#   directory-services-portal-<version>.tar.gz   the docker deployment package (scripts/package.sh)
#                                                with the images, compose files, .env template,
#                                                install.sh, upgrade.sh and portal.sh
#   directory-services-portal-<version>-native.tar.gz
#                                                the native (systemd) package: the API jar and the
#                                                web client taken from the same images
#   directory-services-portal-<version>.tgz      the Helm chart (with --chart)
#   sbom-<image>.spdx.json                       SPDX SBOM per image (needs syft)
#   SHA256SUMS                                   checksums of all of the above
#
# Environment: SYFT (syft binary, default "syft"); REQUIRE_SBOM=1 fails instead of skipping the
# SBOMs when syft is missing (CI sets it). Signing and upload are done by CI (.github/workflows/ci.yml).
set -euo pipefail

PRODUCT=directory-services-portal
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

usage() { sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//'; }
die() { printf '\033[31mError:\033[0m %s\n' "$*" >&2; exit 1; }
say() { printf '\033[1m==>\033[0m %s\n' "$*"; }
image_name() { local n="${1%@*}"; n="${n##*/}"; echo "${n%%:*}"; } # registry/x/name:tag -> name

out=""; chart=""; native=1; args=()
while [ $# -gt 0 ]; do
  case "$1" in
    --output) [ -n "${2:-}" ] || die "--output needs a value"; out="$2"; shift ;;
    --chart) [ -n "${2:-}" ] || die "--chart needs a value"; chart="$2"; shift ;;
    --no-native) native="" ;;
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
if [ -n "$chart" ]; then
  [ -f "$chart" ] || die "Chart $chart not found"
  [[ "$(basename "$chart")" == "$PRODUCT-$version.tgz" ]] || die "The chart must be $PRODUCT-$version.tgz (helm package --version $version)"
  chart="$(cd "$(dirname "$chart")" && pwd)/$(basename "$chart")"
fi
command -v docker >/dev/null || die "Docker is required"
for ref in "$api_ref" "$web_ref"; do
  docker image inspect "$ref" >/dev/null 2>&1 || die "Image $ref not found locally (build/pull and tag it first)"
done

out="${out:-$ROOT/dist/release/$version}"
mkdir -p "$out"
out="$(cd "$out" && pwd)"
rm -f "$out/$PRODUCT"-*.tar.gz "$out/$PRODUCT"-*.tgz "$out"/sbom-*.spdx.json "$out/SHA256SUMS" "$out/SHA256SUMS.asc"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# --- The deployment package, from the given images ----------------------------------
bundle="$PRODUCT-$version.tar.gz"
"$ROOT/scripts/package.sh" --target docker --version "$version" \
  --api-image "$api_ref" --web-image "$web_ref" --output "$work/package"
mv "$work/package/$PRODUCT-$version-docker.tar.gz" "$out/$bundle"

# --- The native (systemd) package, with the jar and client from the same images -----
native_bundle="$PRODUCT-$version-native.tar.gz"
if [ -n "$native" ]; then
  "$ROOT/scripts/package.sh" --target native --version "$version" \
    --api-image "$api_ref" --web-image "$web_ref" --output "$work/package"
  mv "$work/package/$native_bundle" "$out/$native_bundle"
fi

# --- The Helm chart --------------------------------------------------------------
[ -z "$chart" ] || cp "$chart" "$out/"

# --- Nothing secret in it ----------------------------------------------------------
say "Checking the bundle for licenses, keys and settings files"
mkdir "$work/check"
tar -xzf "$out/$bundle" -C "$work/check"
[ -d "$work/check/$PRODUCT-$version" ] || die "The bundle does not unpack to $PRODUCT-$version/"
if [ -n "$native" ]; then
  tar -xzf "$out/$native_bundle" -C "$work/check"
  [ -d "$work/check/$PRODUCT-$version-native" ] || die "The native package does not unpack to $PRODUCT-$version-native/"
fi
if [ -n "$chart" ]; then
  mkdir "$work/check/chart" && tar -xzf "$chart" -C "$work/check/chart"
fi
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
(cd "$out" && find . -maxdepth 1 -type f \( -name '*.tar.gz' -o -name '*.tgz' -o -name 'sbom-*.spdx.json' \) -printf '%f\n' \
  | LC_ALL=C sort | xargs sha256sum > SHA256SUMS)

say "Release bundle $PRODUCT $version in $out:"
for f in "$out"/*; do printf '    %6s  %s\n' "$(du -h "$f" | cut -f1)" "$(basename "$f")"; done
