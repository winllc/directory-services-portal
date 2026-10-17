#!/usr/bin/env bash
# Builds a standalone deployment package of the Directory Services Portal: one archive with
# everything a host needs to run it, without the source tree, build tools or internet access.
#
#   scripts/package.sh [options]
#
#   --target docker|native|all  what to package (default: docker)
#       docker  container images (docker save) + compose files + installer + portal.sh
#       native  API jar + built web client + systemd unit + nginx site + installer
#   --version VERSION           package/image version (default: package.json version + git revision)
#   --output DIR                where to write the archives (default: dist/)
#   --with-openldap             also include the demo OpenLDAP image (docker)
#   --registry PREFIX           name images PREFIX/directory-services-portal-*:VERSION
#   --no-images                 don't embed the images: the package pulls them from --registry
#   --push                      push the images to --registry (after building)
#   --skip-build                package images / build output that already exist
#   --api-image REF             use this already built API image instead of building one (docker);
#   --web-image REF             likewise for the web image. The package refers to REF as given,
#                               e.g. ghcr.io/winllc/directory-services-portal-api:1.2.3 (used by
#                               scripts/release-bundle.sh to package the images CI tested). With
#                               --target native, the jar and the web client are taken from these
#                               images instead of being built (both are needed)
#   --platform PLATFORM         build images for another platform, e.g. linux/arm64 (needs buildx)
#   --keep                      keep the unpacked staging directory next to the archive
#
# Environment: DOCKER_BUILD_ARGS (extra "docker build" arguments, e.g. proxy build args),
# API_DOCKERFILE / WEB_DOCKERFILE (alternative Dockerfiles, e.g. adding a corporate CA).
#
# Output: dist/directory-services-portal-<version>-<target>.tar.gz and a .sha256 file.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TEMPLATES="$ROOT/deploy/package"
target=docker; version=""; out="$ROOT/dist"; with_ldap=""; registry=""; no_images=""; skip_build=""; platform=""; keep=""; push=""
api_ref=""; web_ref=""

usage() { sed -n '2,29p' "$0" | sed 's/^# \{0,1\}//'; }
die() { printf '\033[31mError:\033[0m %s\n' "$*" >&2; exit 1; }
say() { printf '\033[1m==>\033[0m %s\n' "$*"; }
need() { [ $# -ge 2 ] && [ -n "$2" ] || die "$1 needs a value"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --target) need "$@"; target="$2"; shift ;;
    --version) need "$@"; version="$2"; shift ;;
    --output) need "$@"; out="$2"; shift ;;
    --with-openldap) with_ldap=1 ;;
    --registry) need "$@"; registry="${2%/}"; shift ;;
    --no-images) no_images=1 ;;
    --skip-build) skip_build=1 ;;
    --platform) need "$@"; platform="$2"; shift ;;
    --keep) keep=1 ;;
    --api-image) need "$@"; api_ref="$2"; shift ;;
    --web-image) need "$@"; web_ref="$2"; shift ;;
    --push) push=1 ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown option: $1 (see --help)" ;;
  esac
  shift
done
case "$target" in docker|native|all) ;; *) die "--target must be docker, native or all" ;; esac
[ -z "$no_images" ] || [ -n "$registry" ] || die "--no-images needs --registry (where the host pulls the images from)"
[ -z "$push" ] || [ -n "$registry" ] || die "--push needs --registry"

# --- Version -------------------------------------------------------------------------
revision="unknown"
if git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
  revision="$(git -C "$ROOT" rev-parse --short=10 HEAD)"
  [ -z "$(git -C "$ROOT" status --porcelain --untracked-files=no)" ] || revision="$revision-dirty"
fi
if [ -z "$version" ]; then
  base="$(sed -n 's/^  "version": "\(.*\)",$/\1/p' "$ROOT/package.json" | head -n1)"
  version="${base:-0.0.0}"
  [ "$revision" = unknown ] || version="$version-${revision}"
fi
[[ "$version" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,100}$ ]] || die "Invalid version \"$version\" (letters, digits, . _ -)"
build_date="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
name="directory-services-portal-$version"
prefix="${registry:+$registry/}"
API_IMAGE="${prefix}directory-services-portal-api:$version"
WEB_IMAGE="${prefix}directory-services-portal-web:$version"
[ -z "$api_ref" ] || API_IMAGE="$api_ref"
[ -z "$web_ref" ] || WEB_IMAGE="$web_ref"
OPENLDAP_IMAGE="${prefix}directory-services-portal-openldap:$version"

mkdir -p "$out"
out="$(cd "$out" && pwd)"
staging_root="$(mktemp -d "$out/.staging.XXXXXX")"
trap 'rm -rf "$staging_root"' EXIT

# Fill in @VERSION@, image names and build details in a template.
render() { # render SRC DEST
  sed -e "s|@VERSION@|$version|g" -e "s|@API_IMAGE@|$API_IMAGE|g" -e "s|@WEB_IMAGE@|$WEB_IMAGE|g" \
      -e "s|@OPENLDAP_IMAGE@|$OPENLDAP_IMAGE|g" -e "s|@BUILD_DATE@|$build_date|g" -e "s|@GIT_REVISION@|$revision|g" "$1" > "$2"
  if [ -x "$1" ]; then chmod 755 "$2"; else chmod 644 "$2"; fi
}

# Checksums of every file, then the archive and its own checksum.
finish() { # finish DIR ARCHIVE-NAME
  local dir="$1" archive="$out/$2.tar.gz"
  echo "$version" > "$dir/VERSION"
  (cd "$dir" && find . -type f ! -name SHA256SUMS -print0 | LC_ALL=C sort -z | xargs -0 sha256sum > SHA256SUMS)
  tar -C "$(dirname "$dir")" --owner=0 --group=0 -czf "$archive" "$(basename "$dir")"
  (cd "$out" && sha256sum "$(basename "$archive")" > "$(basename "$archive").sha256")
  if [ -n "$keep" ]; then rm -rf "${archive%.tar.gz}"; cp -r "$dir" "${archive%.tar.gz}"; fi
  say "Wrote $archive ($(du -h "$archive" | cut -f1))"
}

# --- Docker package --------------------------------------------------------------------
package_docker() {
  local dir="$staging_root/$name"
  if [ -z "$skip_build" ] || [ -z "$no_images" ] || [ -n "$push" ]; then
    command -v docker >/dev/null || die "Docker is required to build or save the images"
    docker info >/dev/null 2>&1 || die "Cannot talk to the Docker daemon"
  fi
  if [ -z "$skip_build" ]; then
    local args=(); [ -n "$platform" ] && args+=(--platform "$platform")
    # shellcheck disable=SC2206
    [ -n "${DOCKER_BUILD_ARGS:-}" ] && args+=($DOCKER_BUILD_ARGS)
    if [ -z "$api_ref" ]; then
      say "Building $API_IMAGE"
      docker build "${args[@]}" --build-arg VERSION="$version" --build-arg REVISION="$revision" --build-arg BUILD_DATE="$build_date" \
        -t "$API_IMAGE" -f "${API_DOCKERFILE:-$ROOT/backend/Dockerfile}" "$ROOT/backend"
    fi
    if [ -z "$web_ref" ]; then
      say "Building $WEB_IMAGE"
      docker build "${args[@]}" -t "$WEB_IMAGE" -f "${WEB_DOCKERFILE:-$ROOT/web/Dockerfile}" "$ROOT"
    fi
    if [ -n "$with_ldap" ]; then
      say "Building $OPENLDAP_IMAGE"
      docker build "${args[@]}" -t "$OPENLDAP_IMAGE" "$ROOT/deploy/openldap"
    fi
  fi
  local images=("$API_IMAGE" "$WEB_IMAGE"); [ -n "$with_ldap" ] && images+=("$OPENLDAP_IMAGE")
  if [ -z "$no_images" ] || [ -n "$push" ]; then
    for img in "${images[@]}"; do docker image inspect "$img" >/dev/null 2>&1 || die "Image $img not found (build it, or drop --skip-build)"; done
  fi
  if [ -n "$push" ]; then
    for img in "${images[@]}"; do say "Pushing $img"; docker push "$img"; done
  fi

  say "Assembling $name (docker)"
  mkdir -p "$dir/scripts" "$dir/license"
  for f in docker-compose.yml docker-compose.publish.yml docker-compose.mtls.yml env.template install.sh upgrade.sh portal.sh README.md license/README.txt; do
    render "$TEMPLATES/docker/$f" "$dir/$f"
  done
  [ -n "$with_ldap" ] && render "$TEMPLATES/docker/docker-compose.openldap.yml" "$dir/docker-compose.openldap.yml"
  install -m 755 "$ROOT/scripts/generate-dev-certs.sh" "$dir/scripts/"

  if [ -z "$no_images" ]; then
    mkdir -p "$dir/images"
    for img in "${images[@]}"; do
      local file; file="$(basename "${img%%:*}").tar.gz"
      say "Saving $img"
      docker save "$img" | gzip -c > "$dir/images/$file"
    done
  fi
  finish "$dir" "$name-docker"
}

# --- Native package --------------------------------------------------------------------
package_native() {
  local dir="$staging_root/$name-native"
  local jar="$ROOT/backend/target/directory-services-portal.jar" web_dist="$ROOT/web/dist"
  if [ -n "$api_ref" ] || [ -n "$web_ref" ]; then
    # The jar and the client from built images (CI: the images it just tested and published).
    [ -n "$api_ref" ] && [ -n "$web_ref" ] || die "--target native takes both --api-image and --web-image, or neither"
    command -v docker >/dev/null || die "Docker is required to read the images"
    local from="$staging_root/from-images" cid
    mkdir -p "$from"
    for img in "$api_ref" "$web_ref"; do
      docker image inspect "$img" >/dev/null 2>&1 || die "Image $img not found (build or pull it first)"
    done
    say "Taking the API jar from $api_ref"
    cid="$(docker create "$api_ref")"
    docker cp "$cid:/app/app.jar" "$from/directory-services-portal.jar" >/dev/null; docker rm "$cid" >/dev/null
    say "Taking the web client from $web_ref"
    cid="$(docker create "$web_ref")"
    # The client was copied over the nginx base image's sample pages; leave those out. Streamed
    # as a tar because the samples include a symlink out of the directory (icons/poweredby.png),
    # which newer Docker refuses to extract with a plain `docker cp`.
    mkdir -p "$from/web"
    docker cp "$cid:/usr/share/nginx/html/." - \
      | tar -x -C "$from/web" --exclude='./icons' --exclude='./404.html' --exclude='./50x.html' \
          --exclude='./nginx-logo.png' --exclude='./poweredby.png' --exclude='./system_noindex_logo.png'
    docker rm "$cid" >/dev/null
    jar="$from/directory-services-portal.jar"; web_dist="$from/web"
  elif [ -z "$skip_build" ]; then
    command -v npm >/dev/null || die "Node.js/npm is required to build the web client"
    say "Building the web client and the API"
    [ -d "$ROOT/node_modules" ] || (cd "$ROOT" && npm ci)
    (cd "$ROOT" && npm run build)
  fi
  [ -f "$jar" ] || die "$jar not found (build it, or drop --skip-build)"
  [ -f "$web_dist/index.html" ] || die "$web_dist/index.html not found (build it, or drop --skip-build)"

  say "Assembling $name-native"
  mkdir -p "$dir/api" "$dir/nginx"
  install -m 644 "$jar" "$dir/api/directory-services-portal.jar"
  cp -r "$web_dist" "$dir/web"
  chmod -R u+rwX,go+rX,go-w "$dir/web"
  render "$TEMPLATES/native/install.sh" "$dir/install.sh"
  render "$TEMPLATES/native/directory-services-portal.service" "$dir/directory-services-portal.service"
  render "$TEMPLATES/native/README.md" "$dir/README.md"
  # The container's nginx site, pointed at the local API and the installed client.
  sed -e 's|\${API_URL}|http://127.0.0.1:3001|' -e '/resolver \${NGINX_LOCAL_RESOLVERS}/d' \
      -e '/Resolve the API host at request time/,/follows it if its address changes/d' \
      -e 's|root /usr/share/nginx/html;|root /opt/directory-services-portal/web;|' \
      -e 's|^# Rendered at container start.*|# Directory Services Portal '"$version"': serves the web client and proxies /api to the local API.|' \
      -e '/^# \${NGINX_LOCAL_RESOLVERS}/d' \
      -e 's|proxy_set_header X-DSP-Proxy-Secret "\${DSP_PROXY_SECRET}";|include /etc/directory-services-portal/nginx-proxy-secret.conf;  # written by install.sh|' \
      -e 's|\${DSP_CERT_HEADERS_HTTP}|proxy_set_header X-SSL-Client-Cert "";|' \
      -e 's|\${DSP_CSP_HEADER}|Content-Security-Policy|' \
      -e 's|CSP is only reported with ENS_CSP_REPORT_ONLY=true (see entrypoint.sh)|CSP is only reported with the header name Content-Security-Policy-Report-Only|' \
      -e '/X509_EDGE_TERMINATION, see entrypoint.sh/d' \
      "$ROOT/web/nginx/templates/default.conf.template" | cat -s > "$dir/nginx/directory-services-portal.conf"
  if grep -q '\${' "$dir/nginx/directory-services-portal.conf"; then die "Unreplaced template variable in the nginx site"; fi
  # Settings: the container template without its compose section, plus the server settings.
  {
    echo "# Directory Services Portal $version settings (systemd EnvironmentFile)."
    echo "# Apply changes with: systemctl restart directory-services-portal"
    echo
    echo "# --- Server -------------------------------------------------------------------"
    echo "SERVER_ADDRESS=127.0.0.1"
    echo "PORT=3001"
    echo "DATA_DIR=/var/lib/directory-services-portal"
    echo "# nginx on this host overwrites X-Forwarded-*."
    echo "SERVER_FORWARD_HEADERS_STRATEGY=framework"
    echo "# ENS Solutions license: without one only sign-in and the license screen work. Upload it"
    echo "# under Administration > License; it is written to ENS_LICENSE_FILE. ENS_LICENSE_STATE_DIR"
    echo "# keeps this installation's id (shown on the license screen) and must stay writable by dsp."
    echo "ENS_LICENSE_STATE_DIR=/var/lib/ens/directory-portal"
    echo "ENS_LICENSE_FILE=/var/lib/ens/directory-portal/license.lic"
    echo "# The license itself, as an alternative to an upload on the license"
    echo "# screen: the token between the license file's BEGIN and END lines, or the whole file"
    echo "# base64-encoded (base64 -w0 license.lic). It is installed at start if no license is installed"
    echo "# yet, or if it is a newer revision of the installed one; otherwise the installed license is"
    echo "# kept. Leave it empty to supply the license another way."
    echo "ENS_LICENSE="
    echo
    sed -n '/^# --- Directory connection/,$p' "$TEMPLATES/docker/env.template" \
      | sed -e 's|@COOKIE_SECURE@|false|' -e 's|^# Empty: /data/audit inside the portal-data volume.|# Empty: $DATA_DIR/audit.|' \
            -e 's|(with docker-compose.mtls.yml)|(behind nginx: X509_SOURCE=header)|' \
            -e 's|=\"\(#[0-9a-fA-F]*\)\"$|=\1|' \
            -e '/^# The secret the web proxy sends to the API/,/^X509_PROXY_SECRET=$/c\
# The secret nginx sends to the API with every request (X-DSP-Proxy-Secret): the API accepts a\
# forwarded client certificate only with it. install.sh generates it into the file below (root\
# and dsp only) and the copy nginx includes into nginx-proxy-secret.conf next to it (root only).\
# To change it, write a new value to the file (at least 32 characters, e.g. openssl rand -hex 32)\
# and run install.sh again.\
X509_PROXY_SECRET_FILE=/etc/directory-services-portal/proxy-secret'
  } > "$dir/portal.env"
  if grep -q '^X509_PROXY_SECRET=' "$dir/portal.env"; then die "portal.env: the X.509 proxy secret block was not replaced"; fi
  chmod 644 "$dir/portal.env"
  finish "$dir" "$name-native"
}

say "Packaging Directory Services Portal $version"
case "$target" in
  docker) package_docker ;;
  native) package_native ;;
  all) package_docker; package_native ;;
esac
