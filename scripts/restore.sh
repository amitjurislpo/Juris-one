#!/usr/bin/env sh
# Restore a dump made by scripts/backup.sh into DATABASE_URL. Destructive: existing objects are
# dropped first. Stop the app (and jobs) before running it.
#   ./scripts/restore.sh storage/backups/jurisone-20261007T021500Z.dump
set -eu

FILE=${1:?usage: scripts/restore.sh <dump file>}
# DATABASE_URL (postgresql://user:password@host:port/db) comes from the environment; for dev, run it through the helper:
#   node scripts/with-env.mjs dev ./scripts/restore.sh <dump file>
: "${DATABASE_URL:?DATABASE_URL is not set}"
URL=$(printf '%s' "$DATABASE_URL" | sed 's/[?&]schema=[^&]*//')

printf 'Restore %s into %s? This replaces its data. Type "restore" to continue: ' "$FILE" "$(printf '%s' "$URL" | sed -E 's#//[^@]*@#//***@#')"
read -r ok
[ "$ok" = "restore" ] || { echo "aborted"; exit 1; }

if [ -n "${PG_DOCKER:-}" ]; then
  IN_URL=$(printf '%s' "$URL" | sed -E 's#@[^/]+/#@localhost:5432/#')
  docker exec -i "$PG_DOCKER" pg_restore --clean --if-exists --no-owner --dbname="$IN_URL" < "$FILE"
else
  pg_restore --clean --if-exists --no-owner --dbname="$URL" "$FILE"
fi
echo "restored $FILE"
