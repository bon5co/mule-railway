#!/bin/bash
# Boot script: keeps applications on the volume (/data/apps, symlinked into the runtime), starts the admin
# front on $PORT, then runs Mule. Exits when either process dies so Railway restarts the service.
set -eu

: "${MULE_ADMIN_TOKEN:?MULE_ADMIN_TOKEN must be set (the template generates it)}"
DATA="${MULE_ADMIN_DATA:-/data}"
export PORT="${PORT:-8080}"

mkdir -p "$DATA/apps" "$DATA/upload"
echo "[railway] data dir $DATA owner uid=$(stat -c %u "$DATA")"
touch "$DATA/.write-test" && rm -f "$DATA/.write-test" || { echo "[railway] FATAL: $DATA is not writable"; exit 1; }

rm -rf "$MULE_HOME/apps"
ln -s "$DATA/apps" "$MULE_HOME/apps"

# Optional bootstrap: comma-separated URLs of Mule application archives to fetch into the apps dir.
if [ -n "${MULE_APP_URLS:-}" ]; then
  echo "$MULE_APP_URLS" | tr ',' '\n' | while read -r url; do
    [ -z "$url" ] && continue
    name="$(basename "${url%%\?*}")"
    echo "[railway] fetching $url -> apps/$name"
    curl -fsSL -o "$DATA/upload/$name" "$url" && mv "$DATA/upload/$name" "$DATA/apps/$name" \
      || echo "[railway] WARNING: could not fetch $url"
  done
fi

echo "[railway] starting admin on port $PORT and Mule ${MULE_VERSION:-}"
java -Xmx96m -jar /opt/railway/admin.jar &
"$MULE_HOME/bin/mule" &
wait -n
echo "[railway] a child process exited; stopping"
exit 1
