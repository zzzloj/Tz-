#!/bin/sh
# Every PHP file of the game must parse on the current PHP (short tags on, as
# in production). Checks the tree as the image builds it (code plus the data
# built from content/). forum/ is known broken since the original archive.
set -e
root="$(cd "$(dirname "$0")/.." && pwd)"
tree="${TEST_ROOT:-/tmp/tz-tests}/lint"
rm -rf "$tree"
python3 "$root/railway-preview/fix-game.py" "$root" "$tree" > /dev/null
cd "$tree/game"
grep -rlI '<?' . | grep -vE '^\./[12]/(l_i|online)/|\.(htm|html|txt|jad)$|^\./forum/' > "$tree/files.txt"
n=$(wc -l < "$tree/files.txt")
errors=$(xargs -P8 -I{} sh -c 'php -d short_open_tag=On -l "{}" 2>&1 | grep -E "Parse error|Fatal error" || true' < "$tree/files.txt")
if [ -n "$errors" ]; then echo "$errors"; echo "lint: FAILED"; exit 1; fi
echo "lint: $n files parse"
