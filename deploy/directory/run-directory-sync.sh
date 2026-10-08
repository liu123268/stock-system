#!/bin/sh
set -eu
deploy_root=/home/ubuntu/stock-system-deploy
test "$(id -un)" = ubuntu
test -d "$deploy_root/directory/scripts"
test -x "$deploy_root/directory/venv/bin/python"
cd "$deploy_root"
# Both the timer and a manual invocation use the same lock. MySQL separately
# guards imports from other hosts; source collection never holds a DB lock.
exec flock -n "$deploy_root/directory/state/sync.lock" \
  "$deploy_root/directory/venv/bin/python" -B directory/scripts/sync-stock-directory.py \
  --state-dir "$deploy_root/directory/state" \
  --import-command-json '["sudo","-n","docker","exec","-i","stock-system-backend-1","java","-Dloader.main=com.stock.system.stock.directory.StockDirectorySyncCommand","-cp","/app/app.jar","org.springframework.boot.loader.launch.PropertiesLauncher","prod","apply"]'
