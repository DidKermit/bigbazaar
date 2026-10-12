#!/bin/sh
# Rotates the access-token signing key without logging anyone out.
#
#   1. Saves the public half of the current key as jwt-previous-<timestamp>.pub.pem.
#   2. Writes a fresh jwt-private.pem.
#   3. Prints the JWT_PREVIOUS_PUBLIC_KEYS line for .env.
#
# Restart identity-service afterwards. It signs new tokens with the new key and keeps publishing and
# accepting the old public key, so tokens issued before the switch keep working. The gateway and other
# services pick up the new key from the JWKS automatically when they first see its key ID.
# After one access-token lifetime (15 minutes by default) remove the line from .env, delete the
# .pub.pem file and restart identity-service again: the old key is then fully retired.
set -eu
cd "$(dirname "$0")"

if [ ! -f jwt-private.pem ]; then
  echo "No jwt-private.pem to rotate; run generate-dev-keys.sh first." >&2
  exit 1
fi

previous="jwt-previous-$(date -u +%Y%m%dT%H%M%SZ).pub.pem"
openssl pkey -in jwt-private.pem -pubout -out "$previous"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem.new 2>/dev/null
chmod 644 jwt-private.pem.new "$previous"
mv jwt-private.pem.new jwt-private.pem

echo "Rotated. Retired public key saved as infra/keys/$previous"
echo "Set this in .env (path as seen inside the identity-service container), then restart identity-service:"
echo "JWT_PREVIOUS_PUBLIC_KEYS=file:/run/secrets/jwt/$previous"
