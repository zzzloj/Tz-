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
/usr/local/bin/legacy-sync-code /opt/legacy-game /data/game
sed -i 's@DocumentRoot /var/www/html@DocumentRoot /data/game@' /etc/apache2/sites-available/000-default.conf

# Archive has F_blank.dat, while g.php requires f_blank.dat on Linux.
for server in 1 2; do
    if [ -f "/data/game/$server/F_blank.dat" ]; then
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
chown root:www-data /run/legacy-preview.htpasswd
chmod 640 /run/legacy-preview.htpasswd

# The shipped menu points to a defunct external domain. Do not send test
# credentials to it. Local single-server links are repaired after boot tests.
printf '/' > /data/game/1/srvmain.dat
printf '/' > /data/game/srvmain.dat
printf '/' > /data/game/2/srvmain.dat
printf '/' > /data/game/1/serverurl.dat
printf '/' > /data/game/2/serverurl.dat
printf '1\n/1/\n' > /data/game/1/servers.dat
printf '1\n/1/\n' > /data/game/2/servers.dat
printf '1\n/1/\n' > /data/game/servers.dat

chown -R www-data:www-data /data/game /data/sessions
php -d auto_prepend_file= /opt/init-db.php
rm -f /etc/apache2/mods-enabled/mpm_event.load /etc/apache2/mods-enabled/mpm_event.conf \
      /etc/apache2/mods-enabled/mpm_worker.load /etc/apache2/mods-enabled/mpm_worker.conf
apache2ctl -t
exec apache2ctl -D FOREGROUND
