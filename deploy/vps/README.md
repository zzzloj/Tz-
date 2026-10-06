# Сервер игры на своём VPS

Новый сервер (`tz-engine`) и его база Postgres живут на VPS (решение владельца 06.10). Старый PHP-стенд остаётся на Railway как эталон для тестов.

## Что где
- `/opt/tz/docker-compose.yml`: Postgres 17, сервер и Caddy. Caddy сам получает HTTPS от Let's Encrypt; WebSocket проходит через него.
- `/opt/tz/.env` — настройки:
  - `DB_PASSWORD`;
  - `TZ_HOST`: адрес сервера. Пока домена нет, это `<ip-через-дефисы>.sslip.io`;
  - `TZ_ADMINS`;
  - `TZ_IMAGE_TAG`: коммит, который сейчас запущен.
- Образ `tz-engine:<коммит>` собирает GitHub Actions (`.github/workflows/engine-deploy.yml`) после зелёного `engine` на `main` и загружает на VPS по SSH.

## Первый запуск
1. На VPS под root: `curl -fsSL https://raw.githubusercontent.com/zzzloj/Tz-/main/deploy/vps/setup.sh | sh`. Скрипт ставит Docker, заводит пользователя `tzdeploy` с ключом и пишет `/opt/tz/.env`.
2. В GitHub добавить секреты репозитория (Settings → Secrets and variables → Actions):
   - `VPS_HOST`;
   - `VPS_USER`;
   - `VPS_SSH_KEY`.

   Скрипт печатает все три.
3. Actions → engine-deploy → Run workflow. При переезде поставить галочку `migrate`: база с Railway (секрет `DATABASE_PUBLIC_URL`) заменит базу на VPS.
4. Проверить `https://<TZ_HOST>/api/version`. Затем перевести приложения на новый адрес: `SERVER_URL` в `engine/iosApp/project.yml`, `tz.serverUrl` в `engine/androidApp/build.gradle.kts`.

## Повседневное
- Логи: `cd /opt/tz && docker compose logs -f server`.
- Перезапуск после правки `.env`: `docker compose up -d`.
- Свой домен: направить A-запись на IP, вписать его в `TZ_HOST`, затем `docker compose up -d caddy`.
- Бэкап: каждую ночь `engine-backup.yml` снимает дамп с VPS (нужен ещё секрет `BACKUP_PASSPHRASE`).
