#!/usr/bin/env bash
# Genera un .env local con secretos aleatorios y una cédula FICTICIA válida para el superadministrador.
# Uso: ./scripts/generar-env.sh   (no sobrescribe un .env existente)
set -euo pipefail

cd "$(dirname "$0")/.."
if [[ -f .env ]]; then
  echo "Ya existe .env; no se sobrescribe." >&2
  exit 1
fi

rand_b64() { openssl rand -base64 "$1" | tr -d '\n'; }
rand_alnum() { openssl rand -hex "$1"; }

# Cédula ficticia: provincia 30 (ecuatorianos en el exterior) + 7 dígitos aleatorios + verificador módulo 10.
cedula() {
  local nine
  nine="30$(printf '%07d' $(( $(od -An -N4 -tu4 /dev/urandom | tr -d ' ') % 10000000 )))"
  local sum=0 i d p
  local coef=(2 1 2 1 2 1 2 1 2)
  for i in $(seq 0 8); do
    d=${nine:$i:1}
    p=$((d * coef[i]))
    ((p >= 10)) && p=$((p - 9))
    sum=$((sum + p))
  done
  echo "${nine}$(((10 - sum % 10) % 10))"
}

# Frase aleatoria que cumple la política: sin palabras bloqueadas (nombre de la institución, secuencias comunes).
password() {
  local p
  while true; do
    p="Cupula-$(openssl rand -base64 18 | tr -dc 'A-Za-z0-9' | head -c 14)-9x"
    if ! echo "$p" | grep -qiE 'password|contrasena|unisfera|universidad|qwerty|123456|abcdef'; then
      echo "$p"
      return
    fi
  done
}

cat > .env <<EOF
DB_PASSWORD=$(rand_alnum 16)
REDIS_PASSWORD=$(rand_alnum 16)
JWT_SECRET=$(rand_b64 48)
SENSITIVE_DATA_KEY=$(rand_b64 32)
ACT_SIGNING_KEY=$(rand_b64 48)
GATEWAY_WEBHOOK_SECRET=$(rand_b64 48)
SUPERADMIN_ID_NUMBER=$(cedula)
SUPERADMIN_INITIAL_PASSWORD=$(password)
SEED_DEMO_DATA=true
SEED_STUDENTS=1500
SEED_LOAD_TEST_STUDENTS=2000
DEMO_PASSWORD=$(password)
CORS_ALLOWED_ORIGINS=http://localhost:8081,http://localhost:4200
COOKIE_SECURE=false
EOF
chmod 600 .env
echo ".env generado. La contraseña demo está en DEMO_PASSWORD; los usuarios demo se listan en el registro del backend."
