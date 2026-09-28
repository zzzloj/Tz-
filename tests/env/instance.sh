#!/bin/sh
# Run an isolated copy of the game from a repository tree (the current one or
# a git worktree of another commit): own world copy, own database, PHP's
# built-in server with 4 workers plus a single worker for the internal login
# request.
#
#   instance.sh start <name> <repo-root> <http-port> <internal-port>
#   instance.sh stop  <name>
#
# Needs MYSQLHOST, MYSQLPORT, MYSQLUSER, MYSQLPASSWORD (a user that may create
# databases) and TEST_ROOT (scratch directory, default /tmp/tz-tests).
# TZ_CLOCK_FILE (optional): run PHP under libfaketime with a clock read from it.
set -eu
cmd="$1"; name="$2"
TEST_ROOT="${TEST_ROOT:-/tmp/tz-tests}"
dir="$TEST_ROOT/$name"
here="$(cd "$(dirname "$0")/../.." && pwd)"   # the checkout running the tests

if [ "$cmd" = stop ]; then
    for pid in $(cat "$dir"/*.pid 2>/dev/null); do kill "$pid" 2>/dev/null || true; done
    rm -f "$dir"/*.pid
    exit 0
fi

repo="$(cd "$3" && pwd)"; port="$4"; iport="$5"
db="tz_test_$name"

# A leftover server from an earlier run would answer on the same port with
# another world and database, so stop ours and refuse ports that stay busy.
sh "$here/tests/env/instance.sh" stop "$name"
for p in "$port" "$iport"; do
    for i in $(seq 1 25); do
        curl -s -o /dev/null "http://127.0.0.1:$p/" || break
        [ "$i" = 25 ] && { echo "port $p is already in use" >&2; exit 1; }
        sleep 0.2
    done
done
rm -rf "$dir"; mkdir -p "$dir/sessions"

# Image copy exactly as the Dockerfile builds it, from that commit's code.
python3 "$repo/railway-preview/fix-game.py" "$repo" "$dir/img" > "$dir/build.log"
sh "$here/railway-preview/prepare-game.sh" "$dir/img/game" "$dir/data/game" "$here/railway-preview/sync-code.sh"

php -r '
$l = mysqli_connect(getenv("MYSQLHOST"), getenv("MYSQLUSER"), getenv("MYSQLPASSWORD"), "", (int)getenv("MYSQLPORT"));
if (!$l) { fwrite(STDERR, mysqli_connect_error() . "\n"); exit(1); }
mysqli_query($l, "DROP DATABASE IF EXISTS `'"$db"'`");
mysqli_query($l, "CREATE DATABASE `'"$db"'`") or exit(1);'

cat > "$dir/env.sh" <<ENV
export MYSQLHOST='$MYSQLHOST' MYSQLPORT='$MYSQLPORT' MYSQLUSER='$MYSQLUSER' MYSQLPASSWORD='$MYSQLPASSWORD'
export MYSQLDATABASE='$db'
export PREVIEW_USER='tester' PREVIEW_PASSWORD='ci-preview-password-123'
export PORT='$port' LEGACY_INTERNAL_PORT='$iport'
export LEGACY_GAME_ROOT='$dir/data/game'
export LEGACY_HTML_PHP='$repo/railway-preview/html-output.php'
export LEGACY_WML_JS='$repo/railway-preview/wml.js'
export LEGACY_RANDOM_SEED='${LEGACY_RANDOM_SEED:-tz-ci}'
ENV
. "$dir/env.sh"
php -d auto_prepend_file= "$repo/railway-preview/init-db.php"

cat > "$dir/php.ini" <<INI
short_open_tag=On
display_errors=Off
log_errors=On
error_log=$dir/php-errors.log
error_reporting=E_ALL & ~E_NOTICE & ~E_WARNING & ~E_DEPRECATED
session.save_path=$dir/sessions
auto_prepend_file=$repo/railway-preview/bootstrap.php
date.timezone=Europe/Moscow
INI
: > "$dir/php-errors.log"

# Optional controllable clock (tests/ab_walk.py): every PHP process reads the
# frozen time from TZ_CLOCK_FILE, so two copies of the world age in lockstep.
if [ -n "${TZ_CLOCK_FILE:-}" ]; then
    fake="$(ls /usr/lib/*/faketime/libfaketime.so.1 /usr/lib/faketime/libfaketime.so.1 2>/dev/null | head -1)"
    [ -n "$fake" ] || { echo "libfaketime not found (apt install faketime)" >&2; exit 1; }
    export LD_PRELOAD="$fake" FAKETIME_TIMESTAMP_FILE="$TZ_CLOCK_FILE" FAKETIME_NO_CACHE=1 FAKETIME_DONT_FAKE_MONOTONIC=1
fi

cd "$dir/data/game"
PHP_CLI_SERVER_WORKERS=4 php -c "$dir/php.ini" -S "127.0.0.1:$port" -t . > "$dir/http.log" 2>&1 &
echo $! > "$dir/http.pid"
php -c "$dir/php.ini" -S "127.0.0.1:$iport" -t . > "$dir/internal.log" 2>&1 &
echo $! > "$dir/internal.pid"

for i in $(seq 1 50); do
    if ! kill -0 "$(cat "$dir/http.pid")" 2>/dev/null || ! kill -0 "$(cat "$dir/internal.pid")" 2>/dev/null; then
        break
    fi
    if curl -s -o /dev/null "http://127.0.0.1:$port/1/g.php" && curl -s -o /dev/null "http://127.0.0.1:$iport/1/g.php"; then
        echo "instance $name ready on :$port"; exit 0
    fi
    sleep 0.2
done
echo "instance $name did not start" >&2; cat "$dir/http.log" "$dir/internal.log" >&2
sh "$here/tests/env/instance.sh" stop "$name"; exit 1
