# Территория Зла (WAP RPG)

Исходники старой WAP-игры «Территория Зла» (PHP, ~2007 г., движок на базе «Амулета Дракона»).

Источник: архив «Территория Зла» с masteram.us (раздел «Скрипты → Онлайн игры», файл №96,
`KODERAM.INFO_1721_Zlo_2.zip`), залит на сайт пользователем Mayk 30.04.2014. Содержимое архива сохранено как есть.

## Структура
- `game/` — скрипты игры (точка входа `game/index.php`), данные мира в `*.dat`
- `sql/table.sql` — схема таблицы из архива (`teritoria_zla_table.zip`)
- `Readme_Bydem.ru.txt` — readme из исходного архива
- `railway-preview/` — сборка для Railway (PHP 5.6 + Apache, адаптер WML→HTML), подробности в `railway-preview/README.md`

## Предупреждения
- Файлы в кодировке windows-1251.
- Код рассчитан на PHP 4/5: используются `mysql_*` и модификатор `/e` в `preg_replace`, на PHP 7+/8 без доработки не запустится.
- Пароль БД в `config.ssp` (×3) и `forum/config.inc.php`, а также пароль админа форума в `forum/system.php` заменены на `CHANGE_ME`; остальное совпадает с оригинальным архивом. На Railway `config.ssp` перезаписывается из переменных окружения при старте.

## Запуск
Сервис `territory-evil-php` в Railway-проекте `territory-evil-legacy-preview` собирается из этой ветки по `railway-preview/Dockerfile` (build context — корень репозитория). Нужны MariaDB 10.11 и том `/data`, см. `railway-preview/README.md`.
