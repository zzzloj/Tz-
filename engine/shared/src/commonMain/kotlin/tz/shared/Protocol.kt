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
    /** «Звуки»: someone (a character or an NPC) is there. */
    val occupied: Boolean = false,
    /** On horseback: the next location has an exit with the same label — gallop two locations at once (MoveRequest.gallop). */
    val gallop: Boolean = false,
)

@Serializable
data class NpcView(
    val id: String,
    val name: String,
    val hp: Int = 0,
    val hpMax: Int = 0,
    /** This NPC is fighting you. */
    val fightingYou: Boolean = false,
    /** Anyone can be attacked; striking townsfolk, traders or guards is a crime (see CharacterView.crime). */
    val attackable: Boolean = false,
    /** Has a dialog: POST /api/game/talk. */
    val canTalk: Boolean = false,
    /** Whom it is fighting: "вас" or a name; null — nobody. */
    val attacking: String? = null,
    /** Yours (a pet, a horse, a mercenary…): talk to it to give orders. */
    val mine: Boolean = false,
    /** Whose it is, if someone's. */
    val owner: String? = null,
)

@Serializable
data class CorpseView(
    val id: String,
    val name: String,
    val items: List<GroundItemView> = emptyList(),
    /** Meat or hides can be cut off with a knife. */
    val canButcher: Boolean = false,
    /** Taking from it is looting (мародёрство): the corpse of an innocent that is not yours or your clan's. */
    val looting: Boolean = false,
    /** A monster's or animal's: a necromancer can raise it (POST /api/game/skill "necro"). */
    val canRaise: Boolean = false,
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
    /** Used on something: "item" (a thing in the backpack), "player" (a character here) or, for scrolls, an AbilityView.target. */
    val target: String? = null,
)

/** [arg]: a choice some things ask for (where to sail the boat). */
@Serializable
data class ItemRequest(val item: String, val arg: String? = null, /** take or drop only this many; null — the whole stack */ val count: Int? = null)

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
    /** Criminal title (бандит, убийца, мародер…) and minutes left; null — innocent. */
    val crime: String? = null,
    val crimeMinutes: Long = 0,
    /** Poisoned: health goes down instead of coming back (never below 1). */
    val poisoned: Boolean = false,
    /** On horseback (dismount: POST /api/game/skill "dismount"). */
    val mounted: Boolean = false,
    /** Holds the leadership flag: +10 health, +20 to spells, +10 to techniques — and anyone may strike you. */
    val flag: Boolean = false,
    /** Husband or wife. */
    val spouse: String? = null,
    /** Skills above 0 by key (Rules.SKILLS), spells and techniques learnt. */
    val skills: Map<String, Int> = emptyMap(),
    val known: List<String> = emptyList(),
    /** Combat numbers for the character screen. */
    val hit: Int = 0,
    val dmgMin: Int = 0,
    val dmgMax: Int = 0,
    val armor: Int = 0,
    val dodge: Int = 0,
    val parry: Int = 0,
    val magicDodge: Int = 0,
    val magicParry: Int = 0,
    val magicResist: Int = 0,
    /** Rank by the sum of skills (Новичок … Лорд) and title by the best skill (воин, маг, вор…), f_lookuser.dat. */
    val rank: String = "",
    val title: String = "",
)

@Serializable
data class MeView(
    val login: String,
    val character: CharacterView? = null,
    /** player, moder (chat bans, teleport, forum) or admin (everything, AdminRequest). */
    val role: String = "player",
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
    /** Spells and techniques learnt, with what they are aimed at and when they are ready. */
    val abilities: List<AbilityView> = emptyList(),
    /** The defensive stance held, "реакция (1 мин)"; null — none. */
    val stance: String? = null,
    /** A question from a thing (the boat: where to?): answer by repeating the action with the option's value as arg. */
    val choice: ChoiceView? = null,
    /** What you saw in someone's backpack (POST /api/game/skill steal without item): steal from it for a minute. */
    val peek: PeekView? = null,
    /** Answer to POST /api/game/look: a character, an NPC, an item, a spell or a skill described. */
    val look: LookView? = null,
    /** Your spouse was wounded there: go to them for 5 minutes (POST /api/game/skill "stele"). */
    val stele: String? = null,
    /** Strangers in your clan's castle: go and defend it (POST /api/game/castle op "tele"). */
    val alarm: String? = null,
)

/** GET /api/world: who is online, clans, castles, the leadership flag (the old site pages). */
@Serializable
data class WorldView(
    val online: List<OnlineView> = emptyList(),
    val clans: List<ClanSummary> = emptyList(),
    val castles: List<CastleSummary> = emptyList(),
    /** Who holds the leadership flag (null — it lies somewhere) and where. */
    val flagHolder: String? = null,
    val flagLocation: String? = null,
    val flagLocationId: String? = null,
)

@Serializable
data class OnlineView(val name: String, val level: Int, val clan: String? = null, val crime: String? = null)

@Serializable
data class ClanSummary(val name: String, val members: Int)

@Serializable
data class CastleSummary(val id: Int, val name: String, val owner: String? = null)

/** GET /api/map: every location with coordinates (x, y from its id "x<X>x<Y>") and zone, for drawing the map. */
@Serializable
data class MapView(val points: List<MapPoint> = emptyList())

@Serializable
data class MapPoint(val id: String, val mapX: Int, val mapY: Int, val zone: Int) {
    /** A guarded street. (Swift sees `zone` as NSObject's zone(), so the apps use this.) */
    val guarded: Boolean get() = zone == 1
}

@Serializable
data class PeekItem(val id: String, val name: String, val count: Int, val equipped: Boolean = false)

@Serializable
data class PeekView(val target: String, val targetName: String, val items: List<PeekItem> = emptyList())

@Serializable
data class LookView(
    val title: String,
    val text: String,
    /** A notice board or a bookshelf: "news" opens the forum's news, "pages" the site pages. */
    val page: String? = null,
)

/** skill: meditation, or steal ([target]; without [item] — peek into the backpack first). */
@Serializable
data class SkillRequest(val skill: String, val target: String? = null, val item: String? = null)

/** [target]: an NPC id, a character's name, an item id, a spell or technique id, or skill.<key> for help. */
@Serializable
data class LookRequest(val target: String)

/** A choice a thing asks for: [item] is taken again with arg = the chosen option's value. */
@Serializable
data class ChoiceView(val item: String, val title: String, val options: List<ChoiceOption> = emptyList())

@Serializable
data class ChoiceOption(val label: String, val value: String)

/** A spell (POST /api/game/cast) or a technique or stance (POST /api/game/technique). */
@Serializable
data class AbilityView(
    val id: String,
    val name: String,
    /** spell, technique (a special blow) or stance (a defensive stance). */
    val kind: String,
    val manaCost: Int = 0,
    /**
     * What it is aimed at: creature — an NPC or another character here;
     * creature_self — the same or yourself; player / player_self — characters
     * only; ghost — a ghost here; npc; rune — a teleport rune in the backpack
     * (its item id); null — nothing. Send an NPC's id, a character's name or
     * an item id as the target.
     */
    val target: String? = null,
    /** Seconds until it can be used again. */
    val readyIn: Long = 0,
    val description: String = "",
    /** Needs pets and horses, which come later: shown, but does nothing yet. */
    val later: Boolean = false,
)

@Serializable
data class CastRequest(val spell: String, val target: String? = null)

@Serializable
data class TechniqueRequest(val id: String, val target: String? = null)

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
data class PersonView(
    val name: String,
    val clan: String? = null,
    val ghost: Boolean = false,
    val crime: String? = null,
    /** Health in percent when below 100; null — unhurt. */
    val hpPercent: Int? = null,
    /** Whom this character is fighting: "вас" or a name. */
    val attacking: String? = null,
    /** On the Wolf island: тамплиер or пират. */
    val faction: String? = null,
    val rider: Boolean = false,
    val flag: Boolean = false,
)

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
data class TargetRequest(val target: String, /** target is a character name, not an NPC key */ val player: Boolean = false)

@Serializable
data class LootRequest(val corpse: String, val item: String = "")

@Serializable
data class MoveRequest(val target: String, /** gallop on through the exit with the same label */ val gallop: Boolean = false)

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
    const val NO_FIGHT_HERE = "no_fight_here"
    const val TOPIC_CLOSED = "topic_closed"
    const val UNKNOWN_ABILITY = "unknown_ability"
    const val CANNOT_DROP = "cannot_drop"
    const val TOO_MANY = "too_many"
    const val FORBIDDEN = "forbidden"
    const val BANNED = "banned"
    const val MUTED = "muted"
    const val WRONG_CODE = "wrong_code"
    const val NOT_FOUND = "not_found"
    const val TOO_FAST = "too_fast"
    const val TOPIC_LOCKED = "topic_locked"

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
        NO_FIGHT_HERE -> "Здесь драться нельзя"
        TOPIC_CLOSED -> "Разговор ушёл в сторону, начните заново"
        UNKNOWN_ABILITY -> "Вы этого не умеете: найдите учителя"
        CANNOT_DROP -> "Квестовую вещь бросить нельзя"
        TOO_MANY -> "Больше таких носить нельзя"
        FORBIDDEN -> "Нет прав"
        BANNED -> "Вход в игру закрыт администрацией"
        MUTED -> "Вам временно запрещено писать"
        WRONG_CODE -> "Неверный логин или код восстановления"
        NOT_FOUND -> "Не найдено"
        TOO_FAST -> "Не так быстро: подождите немного"
        TOPIC_LOCKED -> "Тема закрыта"
        else -> "Ошибка сервера"
    }
}

/** Rules shared by the server (enforced) and the apps (hints before sending). */
object Rules {
    /** Monsters (n.c.*) and wild animals (n.a.*): fair game, no crime in attacking them. */
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
    /** f_docrim.dat: a crime lasts 30 minutes (g_crim), theft 20. */
    const val CRIME_SECONDS = 1800L
    /** The bank is the only place where nobody fights (docs/mechanics-combat.md §2.1). */
    const val BANK_LOCATION = "x1092x474"
    const val ARENA = "arena"
    const val ARENA_EXIT = "x1086x501"
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

    /**
     * Where a location is on the map (m.php calctc): coordinates from its id,
     * special places pinned (start, arena, castles 1–4), and the map it is on:
     * 0 — the main land, 1 — Ansalon (X > 1650), 2 — the Wolf island (Y > 1101).
     */
    fun mapPoint(loc: String): Triple<Int, Int, Int>? {
        val id = when {
            loc == "_begin" -> "x1158x523"
            loc == "arena" -> "x1086x501"
            loc.startsWith("c.1.") -> "x1429x168"
            loc.startsWith("c.2.") -> "x781x429"
            loc.startsWith("c.3.") -> "x1129x369"
            loc.startsWith("c.4.") -> "x2320x348"
            else -> loc
        }
        val m = Regex("^x(\\d+)x(\\d+)$").find(id) ?: return null
        val x = m.groupValues[1].toInt()
        val y = m.groupValues[2].toInt()
        return Triple(x, y, if (y > 1101) 2 else if (x > 1650) 1 else 0)
    }

    /** «О себе». */
    const val ABOUT_MAX = 300
    /** Forum: a topic title and a post. */
    const val TITLE_MAX = 80
    const val POST_MAX = 3000
    const val FORUM_PAGE = 20

    fun hpMax(str: Int) = 10 + str * 10
    fun manaMax(int: Int) = 10 + int * 10
}

// ---- account, site, forum, moderation (stage 13) ------------------------------------

/** GET /api/account and the answer to POST /api/account. */
@Serializable
data class AccountView(
    val login: String,
    val role: String = "player",
    val character: String? = null,
    /** «О себе», shown when others look at the character. */
    val about: String = "",
    /** A recovery code was made (the code itself is shown once, in [recoveryCode]). */
    val hasRecovery: Boolean = false,
    /** Muted (no chat, letters or forum) until this unix time; 0 — not. */
    val mutedUntil: Long = 0,
    /** A new recovery code, only in the answer that made it. */
    val recoveryCode: String? = null,
)

/**
 * op: password ([password] → [newPassword], other sessions end), recovery
 * (a new recovery code, needs [password]), about ([text]), delete (the
 * account and character for good, needs [password]).
 */
@Serializable
data class AccountRequest(val op: String, val password: String = "", val newPassword: String = "", val text: String = "")

/** POST /api/auth/recover: a new password by the recovery code; answers like login. */
@Serializable
data class RecoverRequest(val login: String, val code: String, val newPassword: String)

/**
 * POST /api/admin. Moderators: mute/unmute ([target] name, [minutes]),
 * teleport ([target] name or empty for oneself → location [text]), summon
 * ([target] to you), broadcast ([text]), kick. Administrators also: ban/unban
 * ([minutes] 0 — for good, [text] reason), give ([item] × [count] to
 * [target]), role ([target] name, [text] player/moder).
 */
@Serializable
data class AdminRequest(
    val op: String,
    val target: String = "",
    val text: String = "",
    val item: String = "",
    val count: Int = 1,
    val minutes: Int = 0,
)

@Serializable
data class AdminView(
    val role: String,
    /** What the last action did. */
    val message: String? = null,
    /** Latest moderator actions, newest first: "30.09 12:00 admin mute Вася (60 мин)". */
    val log: List<String> = emptyList(),
    /** Places to teleport to quickly (the old f_admin.dat list): name → location id. */
    val places: List<ChoiceOption> = emptyList(),
    /** Gift kits Edward hands out (content/logic/gifts.json): name → key. Only for an administrator. */
    val gifts: List<ChoiceOption> = emptyList(),
    /** Key of the kit every new character gets, or null. */
    val newGift: String? = null,
)

@Serializable
data class ForumSection(
    val id: Int,
    val title: String,
    val info: String = "",
    val topics: Int = 0,
    val posts: Int = 0,
    /** Only moderators start topics here (news). */
    val staffOnly: Boolean = false,
)

@Serializable
data class ForumTopic(
    val id: Long,
    val section: Int,
    val title: String,
    val author: String,
    /** Last post, unix time. */
    val updated: Long,
    val posts: Int = 0,
    val pinned: Boolean = false,
    val closed: Boolean = false,
    val lastAuthor: String? = null,
)

@Serializable
data class ForumPost(
    val id: Long,
    val author: String,
    val text: String,
    val created: Long,
    val editedBy: String? = null,
    /** Written by the one asking: he may edit it. */
    val mine: Boolean = false,
)

/**
 * GET /api/forum (sections), /api/forum/section/{id}?page= (topics),
 * /api/forum/topic/{id}?page= (posts), and the answer to POST /api/forum.
 */
@Serializable
data class ForumView(
    val sections: List<ForumSection> = emptyList(),
    val section: ForumSection? = null,
    val topics: List<ForumTopic> = emptyList(),
    val topic: ForumTopic? = null,
    val posts: List<ForumPost> = emptyList(),
    val page: Int = 0,
    val pages: Int = 1,
    /** The one asking may moderate (delete, close, pin, rename). */
    val moderator: Boolean = false,
    /** The one asking may write (signed in, not muted). */
    val canWrite: Boolean = false,
)

/**
 * POST /api/forum. op: topic ([section], [title], [text]), post ([topic],
 * [text]), edit ([post], [text]: one's own, or any for moderators);
 * moderators: delete ([post] or [topic]), close, open, pin, unpin, rename
 * ([topic], [title]).
 */
@Serializable
data class ForumRequest(
    val op: String,
    val section: Int? = null,
    val topic: Long? = null,
    val post: Long? = null,
    val title: String = "",
    val text: String = "",
)

/** GET /api/pages: rules, help, stories (content/pages, one Markdown file each). */
@Serializable
data class PageSummary(val id: String, val title: String)

@Serializable
data class PageView(val id: String, val title: String, val text: String)
