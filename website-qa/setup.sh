#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
docker compose up -d db wordpress mailpit proxy
ready=false
for attempt in $(seq 1 15); do
  if docker compose run --rm -T cli core version; then
    ready=true
    break
  fi
  sleep 2
done
if [ "$ready" != true ]; then
  docker compose logs --tail=80 wordpress db
  echo 'WordPress did not become ready' >&2
  exit 1
fi
if ! docker compose run --rm cli core is-installed >/dev/null 2>&1; then
  qa_password="$(openssl rand -hex 24)"
  docker compose run --rm cli core install --url=http://localhost:8090 \
    --title='NenoTV Website QA' --admin_user=qa_admin \
    --admin_password="$qa_password" --admin_email=admin@example.invalid --skip-email
fi
for package in woocommerce.11.1.2 mollie-payments-for-woocommerce.8.1.10; do
  docker compose run --rm -T downloader --fail --show-error --location \
    --proto '=https' --proto-redir '=https' --max-time 120 \
    "https://downloads.wordpress.org/plugin/$package.zip" --output "/packages/$package.zip"
done
docker compose run --rm -T cli plugin install /packages/woocommerce.11.1.2.zip --force --activate
docker compose run --rm -T cli plugin install /packages/mollie-payments-for-woocommerce.8.1.10.zip --force --activate
test "$(docker compose run --rm -T cli plugin get woocommerce --field=version)" = 11.1.2
test "$(docker compose run --rm -T cli plugin get mollie-payments-for-woocommerce --field=version)" = 8.1.10
docker compose run --rm cli eval-file /qa/bootstrap.php
docker compose run --rm cli plugin list --format=json
