FROM python:3.12-slim AS archive
COPY territory-evil-repository.zip /tmp/game.zip
COPY railway-preview/fix-archive.py /tmp/fix-archive.py
RUN python /tmp/fix-archive.py /tmp/game.zip /opt/legacy

FROM php:5.6-apache
RUN docker-php-ext-install mysql
COPY --from=archive /opt/legacy/game/ /opt/legacy-game/
COPY railway-preview/php.ini /usr/local/etc/php/conf.d/legacy-preview.ini
COPY railway-preview/apache.conf /etc/apache2/conf-enabled/legacy-preview.conf
COPY railway-preview/bootstrap.php /opt/legacy-bootstrap.php
COPY railway-preview/init-db.php /opt/init-db.php
COPY railway-preview/start.sh /usr/local/bin/legacy-start
RUN chmod +x /usr/local/bin/legacy-start \
    && a2dismod mpm_event mpm_worker \
    && a2enmod mpm_prefork rewrite headers \
    && apache2ctl -t
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/legacy-start"]
