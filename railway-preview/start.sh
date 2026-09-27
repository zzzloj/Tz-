#!/bin/sh
set -eu

: "${PREVIEW_USER:?A preview username is required}"
: "${PREVIEW_PASSWORD:?A preview password is required}"
: "${MYSQLHOST:?A MySQL service is required}"
: "${MYSQLUSER:?A MySQL user is required}"
: "${MYSQLPASSWORD:?A MySQL password is required}"
: "${MYSQLDATABASE:?A MySQL database name is required}"

PORT="${PORT:-8080}"
sed -i "s/^Listen 80$/Listen ${PORT}/" /etc/apache2/ports.conf
sed -i "s/<VirtualHost \*:80>/<VirtualHost *:${PORT}>/" /etc/apache2/sites-available/000-default.conf

mkdir -p /data/game /data/sessions
if [ ! -e /data/game/.preview-installed ]; then
    cp -a /opt/legacy-game/. /data/game/
    touch /data/game/.preview-installed
fi
sed -i 's@DocumentRoot /var/www/html@DocumentRoot /data/game@' /etc/apache2/sites-available/000-default.conf

# Archive has F_blank.dat, while g.php requires f_blank.dat on Linux.
for server in 1 2; do
    if [ -f "/data/game/$server/F_blank.dat" ] && [ ! -e "/data/game/$server/f_blank.dat" ]; then
        cp "/data/game/$server/F_blank.dat" "/data/game/$server/f_blank.dat"
    fi
done

for config in /data/game/config.ssp /data/game/1/config.ssp /data/game/2/config.ssp; do
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

htpasswd -nbB "$PREVIEW_USER" "$PREVIEW_PASSWORD" > /run/legacy-preview.htpasswd
chmod 600 /run/legacy-preview.htpasswd

# The shipped menu points to a defunct external domain. Do not send test
# credentials to it. Local single-server links are repaired after boot tests.
printf '/\n' > /data/game/1/srvmain.dat
printf '/\n' > /data/game/srvmain.dat
printf '/\n' > /data/game/2/srvmain.dat
printf '/\n' > /data/game/1/serverurl.dat
printf '/\n' > /data/game/2/serverurl.dat
printf '1\n/1/\n' > /data/game/1/servers.dat
printf '1\n/1/\n' > /data/game/2/servers.dat

php -d auto_prepend_file= /opt/init-db.php
exec apache2ctl -D FOREGROUND
