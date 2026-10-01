#!/bin/sh
# Renders ${NGINX_ENVSUBST_TEMPLATE_DIR}/*.template into /opt/app-root/etc/dsp.d/*.conf, then runs
# the command (nginx). Only the variables below are substituted, so nginx's own $variables are
# left alone.
set -eu

template_dir="${NGINX_ENVSUBST_TEMPLATE_DIR:-/etc/nginx/templates}"
output_dir=/opt/app-root/etc/dsp.d

# DNS servers for runtime resolution of the API host (IPv6 addresses need brackets).
if [ -z "${NGINX_LOCAL_RESOLVERS:-}" ]; then
    NGINX_LOCAL_RESOLVERS=$(awk 'BEGIN { ORS = " " } $1 == "nameserver" { if ($2 ~ ":") print "[" $2 "]"; else print $2 }' /etc/resolv.conf)
fi
export NGINX_LOCAL_RESOLVERS API_URL="${API_URL:-http://api:3001}" HTTPS_PORT_SUFFIX="${HTTPS_PORT_SUFFIX:-}"
if [ -z "$(echo "$NGINX_LOCAL_RESOLVERS" | tr -d " ")" ]; then
    echo "dsp-entrypoint: no nameserver in /etc/resolv.conf; set NGINX_LOCAL_RESOLVERS" >&2
    exit 1
fi

rm -f "$output_dir"/*.conf
found=0
for template in "$template_dir"/*.template; do
    [ -f "$template" ] || continue
    name=$(basename "$template" .template)
    envsubst '${API_URL} ${HTTPS_PORT_SUFFIX} ${NGINX_LOCAL_RESOLVERS}' < "$template" > "$output_dir/$name"
    echo "dsp-entrypoint: rendered $template -> $output_dir/$name" >&2
    found=1
done
if [ "$found" -eq 0 ]; then
    echo "dsp-entrypoint: no *.template files in $template_dir" >&2
    exit 1
fi

exec "$@"
