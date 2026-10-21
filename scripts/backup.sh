#!/usr/bin/env sh
# Daily Postgres backup (REQUIREMENTS §7). Schedule from cron, e.g.
#   15 2 * * *  DATABASE_URL=postgresql://user:password@host:5466/jurisone_prd /srv/jurisone/scripts/backup.sh >> /var/backups/jurisone/backup.log 2>&1
#
# Writes a compressed custom-format dump to $BACKUP_DIR (default $HOME/Documents/JurisOne/backups) and keeps
# $BACKUP_KEEP_DAYS days (default 14). Restore with scripts/restore.sh <file>.
# Needs pg_dump 16+ on PATH, or set PG_DOCKER=jurisone-db to run it inside the compose container.
# Copy the dumps, the files folder (jurisone.storage-dir: payslip PDFs, proofs, ...) and PII_ENCRYPTION_KEY off the server too.
set -eu

# DATABASE_URL (postgresql://user:password@host:port/db) comes from the environment, e.g. for dev:
#   DATABASE_URL=postgresql://jurisone:jurisone_dev_pw@localhost:5436/jurisone
: "${DATABASE_URL:?DATABASE_URL is not set}"
URL=$(printf '%s' "$DATABASE_URL" | sed 's/[?&]schema=[^&]*//')
DIR=${BACKUP_DIR:-$HOME/Documents/JurisOne/backups}
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
