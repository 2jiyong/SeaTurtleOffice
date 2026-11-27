#!/bin/bash
# 새 서버(Ubuntu 24.04)에서 한 번 돌린다: sudo bash bootstrap.sh
# 도커 설치, 스왑 2GB, 시간대, 저장소 받기, 백업 cron. 다시 돌려도 안전하다.
set -euo pipefail
[[ $EUID -eq 0 ]] || { echo "sudo로 실행하세요"; exit 1; }
APP_USER=${APP_USER:-ubuntu}
REPO_DIR=/opt/seaturtle
BACKUP_DIR=/opt/seaturtle-backups

# 도커(공식 저장소)
if ! command -v docker > /dev/null; then
	apt-get update
	apt-get install -y ca-certificates curl git
	install -m 0755 -d /etc/apt/keyrings
	curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
	chmod a+r /etc/apt/keyrings/docker.asc
	. /etc/os-release
	echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $VERSION_CODENAME stable" \
		> /etc/apt/sources.list.d/docker.list
	apt-get update
	apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
usermod -aG docker "$APP_USER"

# 스왑 2GB: 서버에서 빌드할 때 메모리(2GB)가 모자라지 않게
if [[ ! -f /swapfile ]]; then
	fallocate -l 2G /swapfile
	chmod 600 /swapfile
	mkswap /swapfile
	swapon /swapfile
	echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

timedatectl set-timezone Asia/Seoul
# cron은 시작할 때의 시간대를 쓴다. 다시 띄워야 04:00이 한국 시간이 된다.
systemctl restart cron

# 저장소
if [[ ! -d $REPO_DIR/.git ]]; then
	git clone https://github.com/2jiyong/SeaTurtleOffice "$REPO_DIR"
fi
chown -R "$APP_USER:$APP_USER" "$REPO_DIR"
install -d -o "$APP_USER" -g "$APP_USER" "$BACKUP_DIR"
touch /var/log/seaturtle-backup.log
chown "$APP_USER:$APP_USER" /var/log/seaturtle-backup.log

# 매일 04:00 백업
cron_line="0 4 * * * $REPO_DIR/deploy/backup.sh >> /var/log/seaturtle-backup.log 2>&1"
( crontab -u "$APP_USER" -l 2> /dev/null | grep -vF "$REPO_DIR/deploy/backup.sh" || true; echo "$cron_line" ) | crontab -u "$APP_USER" -

echo "끝. 다시 로그인한 뒤 $REPO_DIR/.env를 채우고 $REPO_DIR/deploy/deploy.sh를 돌리세요."
