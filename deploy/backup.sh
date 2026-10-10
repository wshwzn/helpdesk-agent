#!/bin/bash
set -euo pipefail
cd /opt/helpdesk-agent
umask 077
install -d -m 700 backups
backup_id=$(date +%Y%m%d-%H%M%S)
docker compose exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysqldump -u helpdesk --single-transaction --no-tablespaces --set-gtid-purged=OFF helpdesk_agent' | gzip > "backups/mysql-${backup_id}.sql.gz"
cp -p .env "backups/env-${backup_id}"
echo "Database and application keys backed up: ${backup_id}"
