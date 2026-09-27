#!/usr/bin/env bash
# Generates a throw-away PKI for trying X.509 sign-in locally:
#   ca.pem / ca.key           certificate authority that issues user certificates
#   server.pem / server.key   TLS certificate for https://localhost:8443
#   <user>.pem / <user>.key / <user>.p12   client certificates (p12 password: "password")
# Usage: scripts/generate-dev-certs.sh [out-dir] [users...]   (default: ./certs admin alice bob)
# Development only: keys are unencrypted and the CA is not protected.
set -euo pipefail
OUT=${1:-certs}
shift || true
if [ $# -gt 0 ]; then USERS=("$@"); else USERS=(admin alice bob); fi
mkdir -p "$OUT"
cd "$OUT"

if [ ! -f ca.pem ]; then
  openssl req -x509 -newkey rsa:3072 -nodes -keyout ca.key -out ca.pem -days 825 -sha256 \
    -subj "/O=Example Corp/CN=Example Users CA" \
    -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null
fi

openssl req -newkey rsa:2048 -nodes -keyout server.key -out server.csr -subj "/CN=localhost" 2>/dev/null
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out server.pem -days 365 -sha256 \
  -extfile <(printf "subjectAltName=DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth\nkeyUsage=critical,digitalSignature,keyEncipherment") 2>/dev/null

for u in "${USERS[@]}"; do
  openssl req -newkey rsa:2048 -nodes -keyout "$u.key" -out "$u.csr" -subj "/O=Example Corp/OU=People/CN=$u" 2>/dev/null
  openssl x509 -req -in "$u.csr" -CA ca.pem -CAkey ca.key -CAcreateserial -out "$u.pem" -days 365 -sha256 \
    -extfile <(printf "subjectAltName=email:$u@example.com\nextendedKeyUsage=clientAuth\nkeyUsage=critical,digitalSignature") 2>/dev/null
  openssl pkcs12 -export -in "$u.pem" -inkey "$u.key" -certfile ca.pem -name "$u" -out "$u.p12" -passout pass:password
  rm -f "$u.csr"
done
rm -f server.csr
echo "Certificates written to $(pwd)"
echo "Start the portal with mutual TLS:"
echo "  SPRING_PROFILES_ACTIVE=mtls TLS_CERT_FILE=$(pwd)/server.pem TLS_KEY_FILE=$(pwd)/server.key TLS_CLIENT_CA_FILE=$(pwd)/ca.pem \\"
echo "    java -jar backend/target/directory-services-portal.jar"
echo "Import <user>.p12 (password: password) into your browser and open https://localhost:8443"
