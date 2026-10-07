#!/bin/sh
# Housekeeping before the restart (run by mmo-vps.yml): removes leftover one-off containers
# (earlier diagnostics left one spinning) and shows what is running.
cd "$(dirname "$0")"
docker ps -aq --filter label=com.docker.compose.project=tz-mmo --filter label=com.docker.compose.oneoff=True | xargs -r docker rm -f
ls -la data data/Config 2>/dev/null
