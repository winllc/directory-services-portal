#!/bin/sh
# Renders ${NGINX_ENVSUBST_TEMPLATE_DIR}/*.template into /opt/app-root/etc/dsp.d/*.conf, then runs
# the command (nginx). Only the variables below are substituted, so nginx's own $variables are
# left alone. Settings: API_URL, HTTPS_PORT_SUFFIX, NGINX_LOCAL_RESOLVERS and, for X.509 sign-in,
# X509_PROXY_SECRET_FILE / X509_PROXY_SECRET, X509_HEADER and X509_EDGE_TERMINATION.
set -eu

template_dir="${NGINX_ENVSUBST_TEMPLATE_DIR:-/etc/nginx/templates}"
output_dir=/opt/app-root/etc/dsp.d

# DNS server for runtime resolution of the API host: only the first nameserver, which is the
# container runtime's own resolver (Docker 127.0.0.11, Podman's aardvark-dns). nginx would
# rotate between all listed servers, and host/corporate DNS servers (which Podman also lists)
# don't know service names such as "api": every other lookup would fail with a 502.
# Set NGINX_LOCAL_RESOLVERS to override (IPv6 addresses in brackets).
if [ -z "${NGINX_LOCAL_RESOLVERS:-}" ]; then
    NGINX_LOCAL_RESOLVERS=$(awk '$1 == "nameserver" { if ($2 ~ ":") print "[" $2 "]"; else print $2; exit }' /etc/resolv.conf)
fi
export NGINX_LOCAL_RESOLVERS API_URL="${API_URL:-http://api:3001}" HTTPS_PORT_SUFFIX="${HTTPS_PORT_SUFFIX:-}"
if [ -z "$(echo "$NGINX_LOCAL_RESOLVERS" | tr -d " ")" ]; then
    echo "dsp-entrypoint: no nameserver in /etc/resolv.conf; set NGINX_LOCAL_RESOLVERS" >&2
    exit 1
fi

# The proxy secret, sent to the API in X-DSP-Proxy-Secret with every request in place of any
# value a client sent: from X509_PROXY_SECRET_FILE (preferred) or X509_PROXY_SECRET. The API
# accepts a forwarded client certificate only with it (X509_SOURCE=header). Empty: the header is
# removed.
if [ -n "${X509_PROXY_SECRET_FILE:-}" ]; then
    if [ ! -r "$X509_PROXY_SECRET_FILE" ]; then
        echo "dsp-entrypoint: cannot read X509_PROXY_SECRET_FILE ($X509_PROXY_SECRET_FILE)" >&2
        exit 1
    fi
    DSP_PROXY_SECRET=$(cat "$X509_PROXY_SECRET_FILE")
else
    DSP_PROXY_SECRET="${X509_PROXY_SECRET:-}"
fi
case "$DSP_PROXY_SECRET" in
    *[![:alnum:]._~+/=-]*)
        echo "dsp-entrypoint: the proxy secret may contain only letters, digits and . _ ~ + / = - (e.g. openssl rand -hex 32)" >&2
        exit 1 ;;
esac

# Client-certificate headers. X509_HEADER is the header the API reads the certificate from
# (default X-SSL-Client-Cert). templates-mtls sets it to the certificate nginx verified. The
# plain-HTTP config removes it from client requests, unless a proxy in front of this one
# terminates TLS, verifies client certificates and always sets that header itself (edge
# termination, X509_EDGE_TERMINATION=true): then it is passed on. X-SSL-Client-Cert is removed
# whenever it is not the header in use.
cert_header="${X509_HEADER:-X-SSL-Client-Cert}"
case "$cert_header" in
    ""|*[![:alnum:]-]*)
        echo "dsp-entrypoint: X509_HEADER must be a header name (letters, digits and -)" >&2
        exit 1 ;;
esac
nl='
        '
# shellcheck disable=SC2089,SC2090 # nginx directives: the quotes are for nginx
clear_default='proxy_set_header X-SSL-Client-Cert "";'
is_default=""
[ "$(printf '%s' "$cert_header" | tr '[:upper:]' '[:lower:]')" = x-ssl-client-cert ] && is_default=1
case "${X509_EDGE_TERMINATION:-false}" in
    true)
        if [ -n "$is_default" ]; then
            DSP_CERT_HEADERS_HTTP="# $cert_header is set by the edge proxy in front of this one: passed on"
        else
            DSP_CERT_HEADERS_HTTP="$clear_default"
        fi ;;
    false)
        DSP_CERT_HEADERS_HTTP="$clear_default"
        [ -n "$is_default" ] || DSP_CERT_HEADERS_HTTP="$DSP_CERT_HEADERS_HTTP${nl}proxy_set_header $cert_header \"\";" ;;
    *)
        echo "dsp-entrypoint: X509_EDGE_TERMINATION must be true or false" >&2
        exit 1 ;;
esac
DSP_CERT_HEADERS_HTTPS="proxy_set_header $cert_header \$dsp_client_cert;"
[ -n "$is_default" ] || DSP_CERT_HEADERS_HTTPS="$DSP_CERT_HEADERS_HTTPS${nl}$clear_default"
# shellcheck disable=SC2090 # see above
export DSP_PROXY_SECRET DSP_CERT_HEADERS_HTTP DSP_CERT_HEADERS_HTTPS

rm -f "$output_dir"/*.conf
found=0
for template in "$template_dir"/*.template; do
    [ -f "$template" ] || continue
    name=$(basename "$template" .template)
    # Owner-only: the rendered config holds the proxy secret.
    # shellcheck disable=SC2016 # the variables envsubst may replace
    (umask 077 && envsubst '${API_URL} ${HTTPS_PORT_SUFFIX} ${NGINX_LOCAL_RESOLVERS} ${DSP_PROXY_SECRET} ${DSP_CERT_HEADERS_HTTP} ${DSP_CERT_HEADERS_HTTPS}' \
        < "$template" > "$output_dir/$name")
    echo "dsp-entrypoint: rendered $template -> $output_dir/$name" >&2
    found=1
done
if [ "$found" -eq 0 ]; then
    echo "dsp-entrypoint: no *.template files in $template_dir" >&2
    exit 1
fi

exec "$@"
