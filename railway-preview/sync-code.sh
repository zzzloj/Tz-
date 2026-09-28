#!/bin/sh
# Refresh game code on the persistent volume without touching world state.
# Usage: sync-code.sh <image copy of game> <volume copy of game>
# Code and static world data (never written at runtime) are overwritten on
# every start, so new deploys reach the game. Anything else - game.dat,
# online/, l_i/ (live locations), clans, logs - is only seeded if missing.
set -eu
src="$1"; dst="$2"
mkdir -p "$dst"
cd "$src"
find . -type f \( -name '*.php' -o -name 'f_*.dat' -o -name 'F_*.dat' \
    -o -path '*/l_t/*' -o -path '*/l_f/*' -o -path '*/npc/*' -o -path '*/items/*' \
    -o -path '*/items1/*' -o -path '*/speak/*' -o -path '*/desc/*' -o -path '*/plugin/*' \) \
    -exec cp --parents -p {} "$dst"/ \;
cp -a -n . "$dst"/
