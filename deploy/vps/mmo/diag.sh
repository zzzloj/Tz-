#!/bin/sh
# Diagnostics for the MMO server (run by mmo-vps.yml before the restart): what makes it crash.
cd "$(dirname "$0")"
run() {
  echo "== $*"
  timeout 40 docker compose run --rm --no-deps -T mmo /srv/server/tz-mmo-server.x86_64 -logFile /dev/stdout "$@" 2>&1 \
    | grep -vE "memorysetup|^\s*$|Shader|referenced script|DataManager|UserLoginManager" | tail -8
}
run -batchmode -nographics
run -batchmode -nographics --burst-disable-compilation -startDatabaseServer
run -batchmode -startDatabaseServer
ls server | head -30
ls server/tz-mmo-server_Data/Plugins 2>/dev/null | head -30
