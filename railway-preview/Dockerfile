FROM python:3.12-slim AS archive
COPY game/ /tmp/src/game/
COPY content/ /tmp/src/content/
COPY tools/content/ /tmp/src/tools/content/
COPY railway-preview/fix-game.py /tmp/fix-game.py
RUN python /tmp/fix-game.py /tmp/src /opt/legacy

FROM php:8.4-apache
# mysqli backs lib/mysql_compat.php; GD draws the in-game map (m.php).
RUN apt-get update \
    && apt-get install -y --no-install-recommends libpng-dev libjpeg62-turbo-dev \
    && rm -rf /var/lib/apt/lists/* \
    && docker-php-ext-configure gd --with-jpeg \
    && docker-php-ext-install mysqli gd
COPY --from=archive /opt/legacy/game/ /opt/legacy-game/
COPY railway-preview/php.ini /usr/local/etc/php/conf.d/legacy-preview.ini
COPY railway-preview/apache.conf /etc/apache2/conf-enabled/legacy-preview.conf
COPY railway-preview/bootstrap.php /opt/legacy-bootstrap.php
COPY railway-preview/html-output.php /opt/legacy-html.php
COPY railway-preview/wml.js /opt/legacy-wml.js
COPY railway-preview/init-db.php /opt/init-db.php
COPY railway-preview/start.sh /usr/local/bin/legacy-start
COPY railway-preview/sync-code.sh /usr/local/bin/legacy-sync-code
COPY railway-preview/prepare-game.sh /usr/local/bin/legacy-prepare-game
RUN chmod +x /usr/local/bin/legacy-start /usr/local/bin/legacy-sync-code /usr/local/bin/legacy-prepare-game \
    && a2dismod mpm_event mpm_worker \
    && a2enmod mpm_prefork rewrite headers \
    && php -l /opt/legacy-html.php \
    && apache2ctl -t
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/legacy-start"]
