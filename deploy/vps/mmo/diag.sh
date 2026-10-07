#!/bin/sh
# Diagnostics for the MMO server (run by mmo-vps.yml before the restart): which role crashes.
cd "$(dirname "$0")"
echo "== config"; cat data/Config/serverConfig.json 2>/dev/null | head -60
for role in -startDatabaseServer -startLoginServer -startCentralServer -startMapSpawnServer; do
  echo "== only $role"
  timeout 40 docker compose run --rm --no-deps -T mmo /srv/server/tz-mmo-server.x86_64 -batchmode -nographics -logFile /dev/stdout $role \
    -spawnExePath /srv/server/tz-mmo-server.x86_64 -publicAddress 217.177.74.66 2>&1 \
    | grep -vE "memorysetup|^\s*$|Shader|referenced script" | tail -12
done
