FROM python:3.12-slim AS archive
COPY game/ /tmp/src/game/
COPY railway-preview/fix-game.py /tmp/fix-game.py
RUN python /tmp/fix-game.py /tmp/src /opt/legacy

FROM php:5.6-apache
# Debian stretch is archived; GD draws the in-game map (m.php).
RUN sed -i -e 's|deb.debian.org|archive.debian.org|g' -e 's|security.debian.org|archive.debian.org|g' -e '/stretch-updates/d' /etc/apt/sources.list \
    && apt-get -o Acquire::Check-Valid-Until=false update \
    && apt-get install -y --no-install-recommends --allow-unauthenticated libpng-dev libjpeg-dev \
    && rm -rf /var/lib/apt/lists/* \
    && docker-php-ext-configure gd --with-png-dir=/usr --with-jpeg-dir=/usr \
    && docker-php-ext-install mysql gd
COPY --from=archive /opt/legacy/game/ /opt/legacy-game/
COPY railway-preview/php.ini /usr/local/etc/php/conf.d/legacy-preview.ini
COPY railway-preview/apache.conf /etc/apache2/conf-enabled/legacy-preview.conf
COPY railway-preview/bootstrap.php /opt/legacy-bootstrap.php
COPY railway-preview/html-output.php /opt/legacy-html.php
COPY railway-preview/wml.js /opt/legacy-wml.js
COPY railway-preview/init-db.php /opt/init-db.php
COPY railway-preview/start.sh /usr/local/bin/legacy-start
COPY railway-preview/sync-code.sh /usr/local/bin/legacy-sync-code
RUN chmod +x /usr/local/bin/legacy-start /usr/local/bin/legacy-sync-code \
    && a2dismod mpm_event mpm_worker \
    && a2enmod mpm_prefork rewrite headers \
    && php -l /opt/legacy-html.php \
    && apache2ctl -t
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/legacy-start"]
