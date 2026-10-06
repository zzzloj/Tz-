#!/bin/bash
# Full regression run: lint, player scenario, random crawl + world integrity,
# and (with BASE_REF) the differential walk against another commit.
#
# Env: MYSQLHOST MYSQLPORT MYSQLUSER MYSQLPASSWORD (may create databases),
#      TEST_ROOT (default /tmp/tz-tests), BASE_REF (optional commit to compare
#      with), AB_ACCEPT=1 (screen changes are intended), CRAWL_STEPS, AB_STEPS.
set -u
cd "$(dirname "$0")/.."
root="$(pwd)"
export TEST_ROOT="${TEST_ROOT:-/tmp/tz-tests}"
mkdir -p "$TEST_ROOT"
failed=()
step() { echo; echo "=== $1"; }
# A failed check also goes out as a GitHub annotation with the end of its output: the job logs are not
# always at hand, annotations are.
run() {
    local name="$1"; shift
    local out="$TEST_ROOT/check-$$.out"
    if "$@" > "$out" 2>&1; then cat "$out"; echo "--- $name: passed"
    else
        cat "$out"; echo "--- $name: FAILED"; failed+=("$name")
        if [ -n "${GITHUB_ACTIONS:-}" ]; then
            printf '::error title=%s failed::%s\n' "$name" "$(tail -n 40 "$out" | sed -e 's/%/%25/g' | awk 'BEGIN{ORS="%0A"} {print}')"
        fi
    fi
    rm -f "$out"
}

step "lint"
run lint sh tests/lint.sh
run content sh tests/content.sh

step "player scenario and crawl (current commit)"
unset TZ_CLOCK_FILE
run "start head" sh tests/env/instance.sh start head "$root" 8101 8102
run scenario python3 tests/scenario.py http://127.0.0.1:8101
run crawl python3 tests/crawl.py http://127.0.0.1:8101 "$TEST_ROOT/head/php-errors.log" 3 "${CRAWL_STEPS:-150}" "${GITHUB_RUN_NUMBER:-1}"
run integrity php tests/integrity.php "$TEST_ROOT/head/data/game" "$TEST_ROOT/head/img/game"
sh tests/env/instance.sh stop head

if [ -n "${BASE_REF:-}" ]; then
    step "differential walk: $BASE_REF -> HEAD"
    base="$TEST_ROOT/base-src"
    git worktree remove --force "$base" 2>/dev/null; rm -rf "$base"
    if git worktree add --detach "$base" "$BASE_REF" >/dev/null 2>&1 \
       && grep -q legacy_srand "$base/game/lib/legacy.php" 2>/dev/null; then
        export TZ_CLOCK_FILE="$TEST_ROOT/clock.txt"
        date -u -d @1780000000 '+%Y-%m-%d %H:%M:%S' > "$TZ_CLOCK_FILE"
        for seed in 1 2; do
            run "start ab_base" sh tests/env/instance.sh start ab_base "$base" 8201 8202
            run "start ab_head" sh tests/env/instance.sh start ab_head "$root" 8301 8302
            run "ab_walk seed $seed" python3 tests/ab_walk.py http://127.0.0.1:8201 http://127.0.0.1:8301 "${AB_STEPS:-400}" "$seed"
            sh tests/env/instance.sh stop ab_base; sh tests/env/instance.sh stop ab_head
        done
        unset TZ_CLOCK_FILE
    else
        echo "skipped: $BASE_REF has no deterministic test mode (legacy_srand) or cannot be checked out"
    fi
    git worktree remove --force "$base" 2>/dev/null
fi

echo
if [ ${#failed[@]} -gt 0 ]; then echo "FAILED: ${failed[*]}"; exit 1; fi
echo "all checks passed"
