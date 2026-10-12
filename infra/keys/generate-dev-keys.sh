#!/bin/sh
# Creates the local-development secrets. Never commit the outputs.
#   - jwt-private.pem: RSA 2048 PKCS#8 key that identity-service signs access tokens with.
#     Its public half is derived at startup and served at /.well-known/jwks.json.
#   - Prints an MFA_ENCRYPTION_KEY line for .env (AES-256 key for TOTP secrets at rest).
set -eu
cd "$(dirname "$0")"

if [ -f jwt-private.pem ]; then
  echo "jwt-private.pem already exists; delete it first to rotate." >&2
else
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem 2>/dev/null
  chmod 644 jwt-private.pem  # readable by the non-root user inside the container
  echo "Wrote infra/keys/jwt-private.pem"
fi

echo "Add this to .env if it isn't there yet:"
echo "MFA_ENCRYPTION_KEY=$(openssl rand -base64 32)"
