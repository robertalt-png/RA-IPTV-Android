#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
docker compose up -d db wordpress mailpit
ready=false
for attempt in $(seq 1 90); do
  if docker compose run --rm cli core version >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 2
done
if [ "$ready" != true ]; then echo 'WordPress did not become ready' >&2; exit 1; fi
if ! docker compose run --rm cli core is-installed >/dev/null 2>&1; then
  qa_password="$(openssl rand -hex 24)"
  docker compose run --rm cli core install --url=http://localhost:8090 \
    --title='NenoTV Website QA' --admin_user=qa_admin \
    --admin_password="$qa_password" --admin_email=admin@example.invalid --skip-email
fi
docker compose run --rm cli plugin install woocommerce --version=11.1.2 --activate
docker compose run --rm cli plugin install mollie-payments-for-woocommerce --version=8.1.10 --activate
docker compose run --rm cli eval-file /qa/bootstrap.php
docker compose run --rm cli plugin list --format=json
