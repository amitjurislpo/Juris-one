#!/usr/bin/env sh
# Daily Postgres backup (REQUIREMENTS §7). Schedule from cron, e.g.
#   15 2 * * *  cd /srv/jurisone && ./scripts/backup.sh >> storage/backups/backup.log 2>&1
#
# Writes a compressed custom-format dump to $BACKUP_DIR (default ./storage/backups) and keeps
# $BACKUP_KEEP_DAYS days (default 14). Restore with scripts/restore.sh <file>.
# Needs pg_dump 16+ on PATH, or set PG_DOCKER=jurisone-db to run it inside the compose container.
# Copy the dumps, ./storage (payslip PDFs, proofs) and PII_ENCRYPTION_KEY off the server too.
set -eu

[ -f .env ] && set -a && . ./.env && set +a
: "${DATABASE_URL:?DATABASE_URL is not set}"
URL=$(printf '%s' "$DATABASE_URL" | sed 's/[?&]schema=[^&]*//')
DIR=${BACKUP_DIR:-./storage/backups}
KEEP=${BACKUP_KEEP_DAYS:-14}
mkdir -p "$DIR"
FILE="$DIR/jurisone-$(date -u +%Y%m%dT%H%M%SZ).dump"

if [ -n "${PG_DOCKER:-}" ]; then
  # inside the container the database listens on 5432
  IN_URL=$(printf '%s' "$URL" | sed -E 's#@[^/]+/#@localhost:5432/#')
  docker exec "$PG_DOCKER" pg_dump --format=custom --no-owner "$IN_URL" > "$FILE"
else
  pg_dump --format=custom --no-owner --file="$FILE" "$URL"
fi

[ -s "$FILE" ] || { echo "backup failed: $FILE is empty" >&2; rm -f "$FILE"; exit 1; }
echo "$(date -u +%FT%TZ) wrote $FILE ($(wc -c < "$FILE") bytes)"
find "$DIR" -name 'jurisone-*.dump' -type f -mtime +"$KEEP" -print -delete
