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
    val hp: Int = 0,
    val hpMax: Int = 0,
    /** This NPC is fighting you. */
    val fightingYou: Boolean = false,
    /** Monsters and wild animals; townsfolk and traders cannot be attacked yet (no guards and crimes). */
    val attackable: Boolean = false,
    /** Has a dialog: POST /api/game/talk. */
    val canTalk: Boolean = false,
)

@Serializable
data class CorpseView(
    val id: String,
    val name: String,
    val items: List<GroundItemView> = emptyList(),
    /** Meat or hides can be cut off with a knife. */
    val canButcher: Boolean = false,
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
    val corpses: List<CorpseView> = emptyList(),
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
    /** Food, drink, a tool or a kit: POST /api/game/use. */
    val usable: Boolean = false,
    /** Used on something: "item" (a thing in the backpack) or "player" (a character here). */
    val target: String? = null,
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
    /** Dialog topics that still hold old PHP (eval:) with no declarative logic yet. */
    val untranslated: List<String> = emptyList(),
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
    /** Dead: a ghost walks, cannot fight or take things, and is revived at a resurrection stone. */
    val ghost: Boolean = false,
    val exp: Int = 0,
    /** Experience over this value gives a skill point. */
    val expNext: Int = 0,
    /** Skills above 0 by key (Rules.SKILLS), spells and techniques learnt. */
    val skills: Map<String, Int> = emptyMap(),
    val known: List<String> = emptyList(),
    /** Combat numbers for the character screen. */
    val hit: Int = 0,
    val dmgMin: Int = 0,
    val dmgMax: Int = 0,
    val armor: Int = 0,
    val dodge: Int = 0,
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
    /** Recent events, newest last: blows, deaths, experience. */
    val journal: List<String> = emptyList(),
    /** Seconds until the character may strike again. */
    val restSeconds: Int = 0,
    /** A ghost standing at a resurrection stone or healer. */
    val canResurrect: Boolean = false,
    /** Open conversation, answer to POST /api/game/talk; null after any other action. */
    val dialog: DialogView? = null,
    /** Trader's list (dialog choice «buy»/«sell»), answer to talk and POST /api/game/shop. */
    val shop: ShopView? = null,
    /** Bank cell, answer to talk («tobank»/«frombank») and POST /api/game/bank. */
    val bank: BankView? = null,
    /** Recipes of a crafting tool, answer to POST /api/game/use without a recipe. */
    val craft: CraftView? = null,
    /** Exchange with another player, while one is open. */
    val exchange: ExchangeView? = null,
    /** Unread private and clan messages (GET /api/messages). */
    val unread: Int = 0,
    /** Clans that invited this character (answer in GET/POST /api/clan). */
    val clanInvites: List<String> = emptyList(),
    /** Other characters here, for actions (location.players has the display strings). */
    val people: List<PersonView> = emptyList(),
    /** This character's clan, if any. */
    val clan: String? = null,
    /** At or in a clan castle: owner, gate, what can be done (POST /api/game/castle). */
    val castle: CastleView? = null,
)

@Serializable
data class CastleView(
    val id: Int,
    val name: String,
    /** Owning clan; null — nobody's, the first clan member to enter the gate takes it. */
    val owner: String? = null,
    val sign: String = "",
    /** Minutes the gate stays locked; 0 — open. */
    val lockedMinutes: Long = 0,
    val member: Boolean = false,
    val guest: Boolean = false,
    val canOpen: Boolean = false,
    val canKnock: Boolean = false,
)

/** op: knock, open, sign (text). */
@Serializable
data class CastleRequest(val op: String, val text: String? = null)

@Serializable
data class PersonView(val name: String, val clan: String? = null, val ghost: Boolean = false)

@Serializable
data class ExchangeView(
    val partner: String,
    /** The partner has not opened the exchange with you yet. */
    val waiting: Boolean,
    val mine: List<ShopItemView> = emptyList(),
    val theirs: List<ShopItemView> = emptyList(),
    val iAgree: Boolean = false,
    val theyAgree: Boolean = false,
)

/** op: start (with), add/remove (item, count), agree, cancel. */
@Serializable
data class ExchangeRequest(val op: String, val with: String? = null, val item: String? = null, val count: Int = 1)

/** channel: "here" (players in the location) or "clan". */
@Serializable
data class SayRequest(val text: String, val channel: String = "here")

@Serializable
data class ContactView(val name: String, val online: Boolean, val mutual: Boolean)

@Serializable
data class MessageView(val from: String, val text: String, val at: Long, val clan: Boolean, val read: Boolean)

@Serializable
data class MessagesView(val contacts: List<ContactView> = emptyList(), val messages: List<MessageView> = emptyList())

/** op: write (to, text), add/remove contact (to). */
@Serializable
data class MessageRequest(val op: String, val to: String, val text: String = "")

@Serializable
data class ClanMemberView(val name: String, val rank: String, val online: Boolean)

@Serializable
data class ClanView(
    /** Null: not in a clan. */
    val name: String? = null,
    val rank: String? = null,
    val info: String = "",
    val members: List<ClanMemberView> = emptyList(),
    val invites: List<String> = emptyList(),
    /** Head or seneschal with a guild stone in the backpack. */
    val canManage: Boolean = false,
    val message: String? = null,
)

/** op: invite, kick, rank (name, rank), head (pass leadership), accept (clan), decline (clan), leave, info (text). */
@Serializable
data class ClanRequest(val op: String, val name: String? = null, val rank: String? = null, val clan: String? = null, val text: String? = null)

@Serializable
data class ShopItemView(val id: String, val name: String, val price: Int, val count: Int)

@Serializable
data class ShopView(
    val npc: String,
    val npcName: String,
    /** buy — the trader sells, sell — the trader buys from you, buy2 — sells for doubloons. */
    val mode: String,
    /** "монет" or "дублонов". */
    val currency: String,
    /** buy/buy2: the trader's goods with stock; sell: your things he takes, with his price per piece. */
    val items: List<ShopItemView> = emptyList(),
    val message: String? = null,
)

@Serializable
data class BankView(
    val npc: String,
    val npcName: String,
    val items: List<InventoryItemView> = emptyList(),
    /** Coins taken for each deposit (faction banks). */
    val fee: Int = 0,
    val message: String? = null,
)

@Serializable
data class CraftOptionView(val key: Int, val name: String, val chance: Int, val needs: String)

@Serializable
data class CraftView(val tool: String, val title: String, val options: List<CraftOptionView> = emptyList())

@Serializable
data class ShopRequest(val npc: String, val mode: String, val item: String, val count: Int = 1)

/** op: put (to the bank) or take (from it). */
@Serializable
data class BankRequest(val npc: String, val op: String, val item: String, val count: Int = 1)

/** Uses an item: eat, drink, gather, craft ([recipe] from CraftView), or apply it to [target] (an item or a character). */
@Serializable
data class UseRequest(val item: String, val recipe: Int? = null, val target: String? = null)

/** One answer the player can pick; [arg] carries a choice inside the topic (which attribute to lower, a stake). */
@Serializable
data class DialogOption(val label: String, val topic: String, val arg: String? = null)

@Serializable
data class DialogView(
    val npc: String,
    val npcName: String,
    val text: String,
    /** The NPC waits for typed text (a clan name): send it as `arg` of this topic. */
    val inputTopic: String? = null,
    /** Empty: the conversation is over, the app shows «Конец диалога». */
    val options: List<DialogOption> = emptyList(),
)

@Serializable
data class TalkRequest(val npc: String, val topic: String = "begin", val arg: String? = null)

@Serializable
data class TargetRequest(val target: String)

@Serializable
data class LootRequest(val corpse: String, val item: String = "")

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
    const val GHOST = "ghost"
    const val NOT_GHOST = "not_ghost"
    const val RESTING = "resting"
    const val NO_TARGET = "no_target"
    const val NO_AMMO = "no_ammo"
    const val NO_RESURRECTION_HERE = "no_resurrection_here"
    const val NEED_KNIFE = "need_knife"
    const val NO_SUCH_CORPSE = "no_such_corpse"
    const val PEACEFUL = "peaceful"
    const val CANNOT_TALK = "cannot_talk"
    const val NOT_A_TRADER = "not_a_trader"
    const val NOT_ENOUGH_MONEY = "not_enough_money"
    const val OUT_OF_STOCK = "out_of_stock"
    const val NOT_WANTED = "not_wanted"
    const val BANK_FULL = "bank_full"
    const val CANNOT_USE = "cannot_use"
    const val NO_SUCH_PLAYER = "no_such_player"
    const val NOT_IN_CONTACTS = "not_in_contacts"
    const val NO_EXCHANGE = "no_exchange"
    const val CANNOT_TRADE = "cannot_trade"
    const val NOT_IN_CLAN = "not_in_clan"
    const val CLAN_RIGHTS = "clan_rights"
    const val SAID_ALREADY = "said_already"
    const val TOPIC_CLOSED = "topic_closed"

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
        GHOST -> "Вы призрак: найдите камень воскрешения или лекаря"
        NOT_GHOST -> "Вы живы"
        RESTING -> "Нужно отдохнуть"
        NO_TARGET -> "Здесь нет такого противника"
        NO_AMMO -> "Нет боеприпасов"
        NO_RESURRECTION_HERE -> "Здесь нельзя воскреснуть — нужен камень воскрешения или лекарь"
        NEED_KNIFE -> "Нужен нож"
        NO_SUCH_CORPSE -> "Здесь нет этого"
        PEACEFUL -> "На него нападать нельзя"
        CANNOT_TALK -> "С ним нельзя поговорить"
        NOT_A_TRADER -> "Он этим не занимается"
        NOT_ENOUGH_MONEY -> "У вас недостаточно денег"
        OUT_OF_STOCK -> "Этого товара сейчас нет"
        NOT_WANTED -> "Это ему не нужно"
        BANK_FULL -> "В банке нет места"
        CANNOT_USE -> "Это нельзя использовать здесь"
        NO_SUCH_PLAYER -> "Такого игрока здесь нет"
        NOT_IN_CONTACTS -> "Писать можно только тем, у кого вы в контактах"
        NO_EXCHANGE -> "Обмена сейчас нет"
        CANNOT_TRADE -> "Это нельзя передать"
        NOT_IN_CLAN -> "Вы не в клане"
        CLAN_RIGHTS -> "Для этого нужно быть главой (или сенешалем) и иметь камень гильдии"
        SAID_ALREADY -> "Вы это уже говорили! Измените текст"
        TOPIC_CLOSED -> "Разговор ушёл в сторону, начните заново"
        else -> "Ошибка сервера"
    }
}

/** Rules shared by the server (enforced) and the apps (hints before sending). */
object Rules {
    /** Stage 3 has no guards and crimes, so only monsters (n.c.*) and wild animals (n.a.*) can be attacked. */
    fun attackable(npcId: String): Boolean = npcId.startsWith("n.c.") || npcId.startsWith("n.a.")

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

    /** A revived ghost gets back this share of max HP (the old game revived with 0 HP — a bug). */
    const val RESURRECT_HP_PERCENT = 0

    /** Old formulas: max HP 10 + str·10, max mana 10 + int·10 (docs/mechanics.md). */
    /**
     * Skills a teacher can raise (f_speakskillup.dat): key, index in the old
     * `skills` string, title. The first three are attributes.
     */
    val SKILLS: List<Triple<String, Int, String>> = listOf(
        Triple("str", 0, "Сила"), Triple("dex", 1, "Ловкость"), Triple("int", 2, "Интеллект"),
        Triple("meditation", 5, "Медитация"), Triple("steal", 6, "Кража"), Triple("animaltaming", 7, "Прир.животных"),
        Triple("hand", 8, "Рукопашная"), Triple("coldweapon", 9, "Холодн.оружие"), Triple("ranged", 10, "Стрельба"),
        Triple("parring", 11, "Парирование"), Triple("uklon", 12, "Уклон"), Triple("magic", 13, "Магия"),
        Triple("magic_resist", 14, "Сопр.магии"), Triple("magic_uklon", 15, "Уклон от магии"),
        Triple("regeneration", 16, "Регенерация"), Triple("hiding", 17, "Скрытность"), Triple("look", 18, "Осторожность"),
        Triple("steallook", 19, "Подглядывание"), Triple("animallore", 20, "Изуч.животных"), Triple("spirit", 21, "Спиритизм"),
        Triple("healing", 22, "Лечение"), Triple("alchemy", 23, "Алхимия"), Triple("mine", 24, "Рудокоп"),
        Triple("smith", 25, "Кузнец"), Triple("lumb", 26, "Лесоруб"), Triple("bow", 27, "Плотник"),
        Triple("stone", 28, "Ювелир"), Triple("fish", 29, "Рыболов"), Triple("food", 30, "Повар"),
        Triple("necro", 31, "Некромант"), Triple("currier", 32, "Друид"), Triple("weaver", 33, "Ткач"),
    )
    val ATTRIBUTES = setOf("str", "dex", "int")
    fun skillTitle(key: String): String = SKILLS.firstOrNull { it.first == key }?.third ?: key

    /** Limits (g.php:44-47): attributes 1..5 with sum ≤ 12, skills 0..5 with sum ≤ 50. */
    const val ATTR_MAX = 5
    const val ATTR_SUM = 12
    const val SKILL_MAX = 5
    const val SKILL_SUM = 50
    const val MONEY = "i.money"
    const val SAY_MAX = 250
    const val MESSAGE_MAX = 500
    val CLAN_NAME = Regex("^[A-Za-zА-Яа-яЁё0-9_]{3,20}$")
    const val CLAN_COST = 50000
    const val GUILD_STONE = "i.guildstone"
    /** Quest items cannot change hands, except doubloons and firebird feathers (f_trade.dat:40). */
    fun tradeable(id: String) = !id.startsWith("i.q.") || id == DUBLON || id == "i.q.pjpt"
    val CLAN_RANKS = mapOf("head" to "Сеньор", "seneschal" to "Сенешаль", "vassal" to "Вассал", "neophyte" to "Неофит")
    const val DUBLON = "i.q.dublon"
    /** f_speaktobankto.dat: at most 70 000 coins in a bank cell. */
    const val BANK_MONEY_MAX = 70000
    /** The old cell held an 800-character string; here: different stacks. */
    const val BANK_STACKS_MAX = 40

    fun hpMax(str: Int) = 10 + str * 10
    fun manaMax(int: Int) = 10 + int * 10
}
