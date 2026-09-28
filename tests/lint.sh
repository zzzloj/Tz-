#!/bin/sh
# Every PHP file of the game must parse on the current PHP (short tags on, as
# in production). forum/ is known broken since the original archive.
cd "$(dirname "$0")/../game"
grep -rlI '<?' . | grep -vE '^\./[12]/(l_i|online)/|\.(htm|html|txt|jad)$|^\./forum/' > /tmp/tz-lint-files.txt
n=$(wc -l < /tmp/tz-lint-files.txt)
errors=$(xargs -P8 -I{} sh -c 'php -d short_open_tag=On -l "{}" 2>&1 | grep -E "Parse error|Fatal error" || true' < /tmp/tz-lint-files.txt)
if [ -n "$errors" ]; then echo "$errors"; echo "lint: FAILED"; exit 1; fi
echo "lint: $n files parse"
