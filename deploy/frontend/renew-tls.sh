#!/usr/bin/env bash
set -euo pipefail
cd /home/ubuntu/stock-system-deploy
stack=(-f compose.yaml -f compose.backend.yaml -f compose.frontend.yaml -f compose.public.yaml -f compose.https.yaml -f compose.certbot.yaml)
docker compose "${stack[@]}" run --rm --no-deps certbot renew \
  --cert-name stock-system-ip --non-interactive --no-random-sleep-on-renew \
  --deploy-hook 'touch /var/lib/letsencrypt/reload-needed'
if test -f tls/work/reload-needed; then
  docker compose "${stack[@]}" exec -T frontend nginx -t
  docker compose "${stack[@]}" exec -T frontend nginx -s reload
  rm -- tls/work/reload-needed
fi
