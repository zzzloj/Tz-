package tz.shared

import kotlinx.serialization.Serializable

/**
 * What the server sends to the apps. The server is the only authority on
 * game rules; the apps render these views and send player actions.
 */
@Serializable
data class ExitView(
    /** Link text as in the original game, e.g. "на север". */
    val label: String,
    /** Location id the exit leads to. */
    val target: String,
)

@Serializable
data class NpcView(
    val id: String,
    val name: String,
)

@Serializable
data class LocationView(
    val id: String,
    val name: String,
    /** 0 ordinary, 1 guarded, 2 templar citadel, 3 pirate fort (docs/mechanics-world.md). */
    val zone: Int,
    val description: String? = null,
    val exits: List<ExitView> = emptyList(),
    val npcs: List<NpcView> = emptyList(),
    /** Items lying here; fixtures (trees, signs) cannot be taken. */
    val items: List<GroundItemView> = emptyList(),
    /** Other characters seen here in the last minutes. */
    val players: List<String> = emptyList(),
)

@Serializable
data class GroundItemView(
    val id: String,
    val name: String,
    val count: Int,
    val takeable: Boolean,
)

@Serializable
data class InventoryItemView(
    val id: String,
    val name: String,
    val count: Int,
    val equipped: Boolean,
    /** Weapon or armour: can be put on. */
    val equippable: Boolean,
)

@Serializable
data class ItemRequest(val item: String)

@Serializable
data class ContentReport(
    val locations: Int,
    val items: Int,
    val npcs: Int,
    val dialogs: Int,
    /** Problems found while loading content/ (dangling references etc.). */
    val problems: List<String> = emptyList(),
)

object Protocol {
    /** Where every new character starts (docs/mechanics-progression.md). */
    const val START_LOCATION = "_begin"
}

// ---- Accounts and characters (stage 1) --------------------------------------

@Serializable
data class Credentials(val login: String, val password: String)

@Serializable
data class AuthResponse(
    /** Opaque session token; send as "Authorization: Bearer <token>". */
    val token: String,
    val login: String,
)

@Serializable
data class NewCharacter(
    val name: String,
    /** "m" or "f". */
    val sex: String,
)

@Serializable
data class CharacterView(
    val id: Long,
    val name: String,
    val sex: String,
    val location: String,
    val hp: Int,
    val hpMax: Int,
    val mana: Int,
    val manaMax: Int,
    val str: Int,
    val dex: Int,
    val int: Int,
    val skillPoints: Int,
)

@Serializable
data class MeView(
    val login: String,
    val character: CharacterView? = null,
)

/** Everything the game screen needs after any action. */
@Serializable
data class GameView(
    val character: CharacterView,
    val location: LocationView,
    val inventory: List<InventoryItemView> = emptyList(),
)

@Serializable
data class MoveRequest(val target: String)

@Serializable
data class ErrorResponse(
    /** Stable machine code, see [Errors]. */
    val error: String,
    val message: String = "",
)

/** Error codes returned by the server, with the text the apps show. */
object Errors {
    const val UNAUTHORIZED = "unauthorized"
    const val INVALID_LOGIN = "invalid_login"
    const val WEAK_PASSWORD = "weak_password"
    const val LOGIN_TAKEN = "login_taken"
    const val BAD_CREDENTIALS = "bad_credentials"
    const val TOO_MANY_ATTEMPTS = "too_many_attempts"
    const val INVALID_NAME = "invalid_name"
    const val NAME_TAKEN = "name_taken"
    const val CHARACTER_EXISTS = "character_exists"
    const val NO_CHARACTER = "no_character"
    const val NOT_AN_EXIT = "not_an_exit"
    const val BAD_REQUEST = "bad_request"
    const val NO_SUCH_ITEM = "no_such_item"
    const val CANNOT_TAKE = "cannot_take"
    const val NOT_IN_INVENTORY = "not_in_inventory"
    const val CANNOT_EQUIP = "cannot_equip"

    fun text(code: String): String = when (code) {
        UNAUTHORIZED -> "Нужно войти заново"
        INVALID_LOGIN -> "Логин: 3–20 символов, латинские буквы, цифры и _"
        WEAK_PASSWORD -> "Пароль должен быть не короче 8 символов"
        LOGIN_TAKEN -> "Такой логин уже занят"
        BAD_CREDENTIALS -> "Неверный логин или пароль"
        TOO_MANY_ATTEMPTS -> "Слишком много попыток, подождите несколько минут"
        INVALID_NAME -> "Имя: 3–20 букв одного алфавита, можно пробел и дефис"
        NAME_TAKEN -> "Персонаж с таким именем уже есть"
        CHARACTER_EXISTS -> "Персонаж уже создан"
        NO_CHARACTER -> "Сначала создайте персонажа"
        NOT_AN_EXIT -> "Туда отсюда не пройти"
        BAD_REQUEST -> "Неверный запрос"
        NO_SUCH_ITEM -> "Здесь этого нет"
        CANNOT_TAKE -> "Это нельзя взять"
        NOT_IN_INVENTORY -> "У вас этого нет"
        CANNOT_EQUIP -> "Это нельзя надеть"
        else -> "Ошибка сервера"
    }
}

/** Rules shared by the server (enforced) and the apps (hints before sending). */
object Rules {
    val LOGIN = Regex("^[a-z0-9_]{3,20}$")
    const val PASSWORD_MIN = 8

    /**
     * Character names: 3–20 letters of ONE alphabet (Cyrillic or Latin) with
     * single spaces or hyphens inside, so look-alike mixes such as "Aнтoниo"
     * cannot be registered.
     */
    val NAME_CYRILLIC = Regex("^[А-ЯЁа-яё]+([ -][А-ЯЁа-яё]+)*$")
    val NAME_LATIN = Regex("^[A-Za-z]+([ -][A-Za-z]+)*$")

    fun validName(name: String): Boolean =
        name.length in 3..20 && (NAME_CYRILLIC.matches(name) || NAME_LATIN.matches(name))

    /** Logins the old game treated as administrators, and service names. */
    val RESERVED_LOGINS = setOf(
        "admin", "administrator", "moderator", "root", "system", "support", "server",
        "qv", "qw", "kv", "scream", "sn0k", "wildspb", "ps_one", "alatiel",
    )

    /** Starting inventory of a new character (f_site_reg2.dat): a knife, not yet equipped. */
    const val STARTING_KNIFE = "i.w.k.begin"

    /** Dropped items vanish after this many seconds (g_destroy in the old game). */
    const val DROPPED_ITEM_LIFETIME = 600

    /** Weapons (i.w.*) and armour (i.a.*) can be equipped; armour slot = first 6 chars (i.a.b., i.a.h., …). */
    fun equipSlot(itemId: String): String? = when {
        itemId.startsWith("i.w.") -> "weapon"
        itemId.startsWith("i.a.") && itemId.length > 6 -> itemId.substring(0, 6)
        else -> null
    }

    /** A shield cannot be used together with a ranged weapon (plugin/i.w.dat). */
    fun conflicts(a: String, b: String): Boolean =
        (a.startsWith("i.a.s.") && b.startsWith("i.w.r.")) || (b.startsWith("i.a.s.") && a.startsWith("i.w.r."))

    /** Old formulas: max HP 10 + str·10, max mana 10 + int·10 (docs/mechanics.md). */
    fun hpMax(str: Int) = 10 + str * 10
    fun manaMax(int: Int) = 10 + int * 10
}
