#!/bin/sh
set -eu
# Run as ubuntu from the checksummed, isolated directory deployment package.
deploy_root=/home/ubuntu/stock-system-deploy
test "$(id -un)" = ubuntu
test "$(hostname)" = VM-0-8-ubuntu
test -d "$deploy_root/backend"
test -f SHA256SUMS
sha256sum -c SHA256SUMS
test ! -e "$deploy_root/directory/scripts"
test ! -e "$deploy_root/directory/run-directory-sync.sh"
test ! -e /etc/systemd/system/stock-system-directory-sync.service
test ! -e /etc/systemd/system/stock-system-directory-sync.timer
# Reject an old application package before enabling a job that could never run.
receipt=$(sudo -n docker exec -i stock-system-backend-1 java \
  -Dloader.main=com.stock.system.stock.directory.StockDirectorySyncCommand \
  -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher prod apply </dev/null || true)
test "$receipt" = '{"status":"FAILED","errorType":"MismatchedInputException"}'
python3 -m venv "$deploy_root/directory/venv"
"$deploy_root/directory/venv/bin/python" -m pip install -r scripts/directory-requirements.txt
mkdir -p "$deploy_root/directory/state"
chmod 750 "$deploy_root/directory" "$deploy_root/directory/state"
cp -R scripts "$deploy_root/directory/scripts"
install -m 750 run-directory-sync.sh "$deploy_root/directory/run-directory-sync.sh"
sudo -n systemd-analyze verify stock-system-directory-sync.service stock-system-directory-sync.timer
sudo -n install -m 644 stock-system-directory-sync.service stock-system-directory-sync.timer /etc/systemd/system/
sudo -n systemctl daemon-reload
# First run must succeed before automatic runs are enabled. A failure remains
# visible in journal and last-run.json; the timer is left disabled.
sudo -n systemctl start stock-system-directory-sync.service
sudo -n systemctl enable --now stock-system-directory-sync.timer
sudo -n systemctl list-timers stock-system-directory-sync.timer --no-pager
