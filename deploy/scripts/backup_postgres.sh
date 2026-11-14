#!/usr/bin/env bash
set -euo pipefail

BACKUP_DIR="${1:-./backups}"
TIMESTAMP="$(date -u +%Y%m%d_%H%M%S)"
mkdir -p "$BACKUP_DIR"

docker exec kopilot_postgres pg_dump -U "${POSTGRES_USER:-kopilot}" "${POSTGRES_DB:-kopilot}" \
  | gzip > "${BACKUP_DIR}/kopilot_${TIMESTAMP}.sql.gz"

echo "Backup written to ${BACKUP_DIR}/kopilot_${TIMESTAMP}.sql.gz"
