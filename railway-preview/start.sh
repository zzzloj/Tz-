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
/usr/local/bin/legacy-prepare-game /opt/legacy-game /data/game /usr/local/bin/legacy-sync-code
sed -i 's@DocumentRoot /var/www/html@DocumentRoot /data/game@' /etc/apache2/sites-available/000-default.conf

htpasswd -nbB "$PREVIEW_USER" "$PREVIEW_PASSWORD" > /run/legacy-preview.htpasswd
chown root:www-data /run/legacy-preview.htpasswd
chmod 640 /run/legacy-preview.htpasswd

chown -R www-data:www-data /data/game /data/sessions
php -d auto_prepend_file= /opt/init-db.php
rm -f /etc/apache2/mods-enabled/mpm_event.load /etc/apache2/mods-enabled/mpm_event.conf \
      /etc/apache2/mods-enabled/mpm_worker.load /etc/apache2/mods-enabled/mpm_worker.conf
apache2ctl -t
exec apache2ctl -D FOREGROUND
