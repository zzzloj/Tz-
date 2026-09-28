#!/bin/sh
# Bring the game tree on the data volume in line with the built image.
# Shared by the Railway container (start.sh) and the tests (tests/env/).
# Usage: prepare-game.sh <image game dir> <data game dir> <sync-code.sh>
set -eu
src="$1"; dst="$2"; sync="$3"

mkdir -p "$dst"
sh "$sync" "$src" "$dst"
# Temporary files of atomic writes interrupted by a crash or redeploy.
find "$dst" -name '*.tmp.*' -type f -delete

# Archive has F_blank.dat, while g.php requires f_blank.dat on Linux.
for server in 1 2; do
    if [ -f "$dst/$server/F_blank.dat" ]; then
        cp "$dst/$server/F_blank.dat" "$dst/$server/f_blank.dat"
    fi
done

for config in "$dst/config.ssp" "$dst/1/config.ssp" "$dst/2/config.ssp"; do
    [ -f "$config" ] || continue
    cat > "$config" <<'PHP'
<?php
$server = getenv('MYSQLHOST') . ':' . (getenv('MYSQLPORT') ?: '3306');
$user = getenv('MYSQLUSER');
$dbpass = getenv('MYSQLPASSWORD');
$dbname = getenv('MYSQLDATABASE');
?>
PHP
done

# The shipped menu points to a defunct external domain; keep links local.
printf '/' > "$dst/1/srvmain.dat"
printf '/' > "$dst/srvmain.dat"
printf '/' > "$dst/2/srvmain.dat"
printf '/' > "$dst/1/serverurl.dat"
printf '/' > "$dst/2/serverurl.dat"
printf '1\n/1/\n' > "$dst/1/servers.dat"
printf '1\n/1/\n' > "$dst/2/servers.dat"
printf '1\n/1/\n' > "$dst/servers.dat"
