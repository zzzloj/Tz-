using System.Collections.Generic;
using MultiplayerARPG;
using UnityEngine;

/// <summary>
/// The framework's messages in Russian: its default texts (DefaultLocale) and every language it loaded are
/// overwritten with ours for the keys here — first the screens before the game (connection, account,
/// characters). Runs when the game starts and again from TzHomeSkin.
/// </summary>
public static class TzRussian
{
    static readonly Dictionary<string, string> Texts = new Dictionary<string, string>
    {
        ["UI_LABEL_DISCONNECTED"] = "Связь потеряна",
        ["UI_LABEL_SUCCESS"] = "Готово",
        ["UI_LABEL_ERROR"] = "Ошибка",
        ["UI_LABEL_NONE"] = "Нет",
        ["UI_ERROR_BAD_REQUEST"] = "Неверный запрос",
        ["UI_ERROR_SERVICE_NOT_AVAILABLE"] = "Сервер недоступен",
        ["UI_ERROR_REQUEST_TIMEOUT"] = "Сервер не ответил вовремя",
        ["UI_ERROR_KICKED_FROM_SERVER"] = "Вас отключили от сервера",
        ["UI_ERROR_CONNECTION_FAILED"] = "Не удалось подключиться к серверу",
        ["UI_ERROR_CONNECTION_REJECTED"] = "Сервер отклонил подключение",
        ["UI_ERROR_REMOTE_CONNECTION_CLOSE"] = "Сервер закрыл соединение",
        ["UI_ERROR_CONNECTION_TIMEOUT"] = "Сервер не отвечает",
        ["UI_ERROR_INTERNAL_SERVER_ERROR"] = "Ошибка на сервере",
        ["UI_ERROR_SERVER_NOT_FOUND"] = "Сервер не найден",
        ["UI_ERROR_USER_NOT_FOUND"] = "Аккаунт не найден",
        ["UI_ERROR_CHARACTER_NOT_FOUND"] = "Персонаж не найден",
        ["UI_ERROR_NOT_LOGGED_IN"] = "Вы не вошли в аккаунт",
        ["UI_ERROR_USERNAME_IS_EMPTY"] = "Введите логин",
        ["UI_ERROR_PASSWORD_IS_EMPTY"] = "Введите пароль",
        ["UI_ERROR_SERVER_CLOSE"] = "Сервер временно закрыт на обслуживание",
        ["UI_ERROR_INVALID_CHARACTER_DATA"] = "Неверные данные персонажа",
        ["UI_ERROR_INVALID_CHARACTER_ENTITY"] = "Неверный облик персонажа",
        ["UI_ERROR_INVALID_USERNAME_OR_PASSWORD"] = "Неверный логин или пароль",
        ["UI_ERROR_INVALID_USER_TOKEN"] = "Сессия устарела, войдите снова",
        ["UI_ERROR_ALREADY_LOGGED_IN"] = "Этот аккаунт уже в игре",
        ["UI_ERROR_ACCOUNT_LOGGED_IN_BY_OTHER"] = "В ваш аккаунт вошли с другого устройства",
        ["UI_ERROR_USER_BANNED"] = "Аккаунт заблокирован",
        ["UI_ERROR_EMAIL_NOT_VERIFIED"] = "Почта не подтверждена",
        ["UI_ERROR_INVALID_CONFIRM_PASSWORD"] = "Пароли не совпадают",
        ["UI_ERROR_USERNAME_TOO_SHORT"] = "Логин слишком короткий",
        ["UI_ERROR_USERNAME_TOO_LONG"] = "Логин слишком длинный",
        ["UI_ERROR_PASSWORD_TOO_SHORT"] = "Пароль слишком короткий",
        ["UI_ERROR_INVALID_EMAIL"] = "Неверный адрес почты",
        ["UI_ERROR_EMAIL_ALREADY_IN_USE"] = "Эта почта уже занята",
        ["UI_ERROR_USERNAME_EXISTED"] = "Такой логин уже занят",
        ["UI_ERROR_INVALID_USERNAME"] = "В логине можно только латинские буквы, цифры и _",
        ["UI_ERROR_ALREADY_CONNECTED_TO_LOBBY"] = "Уже подключено",
        ["UI_ERROR_ALREADY_CONNECTED_TO_GAME"] = "Уже в игре",
        ["UI_ERROR_NO_CHOSEN_CHARACTER_TO_START"] = "Выберите персонажа",
        ["UI_ERROR_NO_CHOSEN_CHARACTER_TO_DELETE"] = "Выберите персонажа для удаления",
        ["UI_ERROR_ALREADY_SELECT_CHARACTER"] = "Персонаж уже выбран",
        ["UI_ERROR_MAP_SERVER_NOT_READY"] = "Мир ещё загружается, попробуйте через минуту",
        ["UI_ERROR_CHARACTER_NAME_TOO_SHORT"] = "Имя слишком короткое",
        ["UI_ERROR_CHARACTER_NAME_TOO_LONG"] = "Имя слишком длинное",
        ["UI_ERROR_CHARACTER_NAME_EXISTED"] = "Такое имя уже занято",
        ["UI_ERROR_INVALID_CHARACTER_NAME"] = "В имени можно только буквы, цифры и _, без пробелов",
        ["UI_ERROR_UNABLE_TO_VALIDATE_USER"] = "Не удалось проверить аккаунт",
        ["UI_ERROR_UNABLE_TO_VALIDATE_CHARACTER"] = "Не удалось проверить персонажа",
        ["UI_ERROR_CHARACTER_NOT_PLAYABLE"] = "Этим персонажем нельзя играть",
        ["UI_ERROR_USER_IS_ALREADY_ONLINE"] = "Этот аккаунт уже в игре",
        ["UI_ERROR_CHARACTER_IS_ALREADY_ONLINE"] = "Этот персонаж уже в игре",
    };

    [RuntimeInitializeOnLoadMethod(RuntimeInitializeLoadType.AfterSceneLoad)]
    public static void Apply()
    {
        foreach (var kv in Texts)
        {
            DefaultLocale.Texts[kv.Key] = kv.Value;
            foreach (var lang in LanguageManager.Languages.Values)
                if (lang != null) lang[kv.Key] = kv.Value;
            if (LanguageManager.Texts != null) LanguageManager.Texts[kv.Key] = kv.Value;
        }
    }
}
