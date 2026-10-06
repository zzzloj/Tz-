// Error texts of the server's codes (tz.shared.Errors in engine/shared/Protocol.kt, copied 06.10).
using System.Collections.Generic;

namespace Amulet
{
    public static class Errors
    {
        static readonly Dictionary<string, string> Texts = new Dictionary<string, string>
        {
        ["no_connection"] = "Нет связи с сервером",
        ["unauthorized"] = "Нужно войти заново",
        ["invalid_login"] = "Логин: 3–20 символов, латинские буквы, цифры и _",
        ["weak_password"] = "Пароль должен быть не короче 8 символов",
        ["login_taken"] = "Такой логин уже занят",
        ["bad_credentials"] = "Неверный логин или пароль",
        ["too_many_attempts"] = "Слишком много попыток, подождите несколько минут",
        ["invalid_name"] = "Имя: 3–20 букв одного алфавита, можно пробел и дефис",
        ["name_taken"] = "Персонаж с таким именем уже есть",
        ["character_exists"] = "Персонаж уже создан",
        ["no_character"] = "Сначала создайте персонажа",
        ["not_an_exit"] = "Туда отсюда не пройти",
        ["bad_request"] = "Неверный запрос",
        ["no_such_item"] = "Здесь этого нет",
        ["cannot_take"] = "Это нельзя взять",
        ["not_in_inventory"] = "У вас этого нет",
        ["cannot_equip"] = "Это нельзя надеть",
        ["ghost"] = "Вы призрак: найдите камень воскрешения или лекаря",
        ["not_ghost"] = "Вы живы",
        ["resting"] = "Нужно отдохнуть",
        ["no_target"] = "Здесь нет такого противника",
        ["no_ammo"] = "Нет боеприпасов",
        ["no_resurrection_here"] = "Здесь нельзя воскреснуть — нужен камень воскрешения или лекарь",
        ["need_knife"] = "Нужен нож",
        ["no_such_corpse"] = "Здесь нет этого",
        ["peaceful"] = "На него нападать нельзя",
        ["cannot_talk"] = "С ним нельзя поговорить",
        ["not_a_trader"] = "Он этим не занимается",
        ["not_enough_money"] = "У вас недостаточно денег",
        ["out_of_stock"] = "Этого товара сейчас нет",
        ["not_wanted"] = "Это ему не нужно",
        ["bank_full"] = "В банке нет места",
        ["cannot_use"] = "Это нельзя использовать здесь",
        ["no_such_player"] = "Такого игрока здесь нет",
        ["not_in_contacts"] = "Писать можно только тем, у кого вы в контактах",
        ["no_exchange"] = "Обмена сейчас нет",
        ["cannot_trade"] = "Это нельзя передать",
        ["not_in_clan"] = "Вы не в клане",
        ["clan_rights"] = "Для этого нужно быть главой (или сенешалем) и иметь камень гильдии",
        ["said_already"] = "Вы это уже говорили! Измените текст",
        ["no_fight_here"] = "Здесь драться нельзя",
        ["topic_closed"] = "Разговор ушёл в сторону, начните заново",
        ["unknown_ability"] = "Вы этого не умеете: найдите учителя",
        ["cannot_drop"] = "Квестовую вещь бросить нельзя",
        ["too_many"] = "Больше таких носить нельзя",
        ["forbidden"] = "Нет прав",
        ["banned"] = "Вход в игру закрыт администрацией",
        ["muted"] = "Вам временно запрещено писать",
        ["wrong_code"] = "Неверный логин или код восстановления",
        ["not_found"] = "Не найдено",
        ["too_fast"] = "Не так быстро: подождите немного",
        ["topic_locked"] = "Тема закрыта",
        ["no_vault_here"] = "Клановое хранилище открывается у банкира или в хранилище своего замка",
        ["vault_rights"] = "Эту вещь оставили не для вашего ранга",
        ["too_weak"] = "Не хватает уровня или атрибутов, чтобы надеть это",
        ["in_combat"] = "Во время боя выйти нельзя: закончите сражение или отойдите в другую локацию",
        };

        public static string Text(string code) => code != null && Texts.TryGetValue(code, out var t) ? t : "Ошибка сервера (" + code + ")";
    }
}
