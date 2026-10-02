package tz.shared

/**
 * What the game screen shows, in the order both apps draw it (docs/design.md,
 * «Раскладка экрана»): the one list of the place, the journal lines with
 * their colours, the belt and the exits. Plain functions of [GameView].
 */
object GameScene {
    /** NPCs of the place: those fighting you first, then other fights, your own creatures, the rest. */
    fun npcs(game: GameView): List<NpcView> = game.location.npcs.sortedBy {
        when {
            it.fightingYou -> 0
            it.attacking != null -> 1
            it.mine -> 2
            it.canTalk -> 3
            else -> 4
        }
    }

    /**
     * The NPCs of the place in the sections of the combat list (board «Бой списком»):
     * those striking you, nearest blow first; fights with others; monsters that
     * have not noticed you yet; everyone else (yours first, then those who talk).
     */
    fun groups(game: GameView): NpcGroups {
        val all = npcs(game)
        val atYou = all.filter { it.fightingYou }.sortedBy { it.nextBlow ?: Int.MAX_VALUE }
        val atOthers = all.filter { !it.fightingYou && !it.mine && it.attacking != null }
        val unaware = all.filter { !it.fightingYou && it.attacking == null && it.hostile && !it.mine }
        val taken = (atYou + atOthers + unaware).map { it.id }.toSet()
        return NpcGroups(atYou, atOthers, unaware, all.filter { it.id !in taken })
    }

    /** A countdown from the server, [elapsed] seconds after the view came. */
    fun left(seconds: Int?, elapsed: Long): Int = ((seconds ?: 0) - elapsed).coerceAtLeast(0).toInt()

    /** The last [n] journal lines with their kinds (old servers send no kinds: sorted by words). */
    fun journal(game: GameView, n: Int, hereOnly: Boolean = false): List<Pair<String, String>> {
        val count = if (hereOnly && game.journalHere >= 0) minOf(n, game.journalHere) else n
        val lines = game.journal.takeLast(count)
        val kinds = game.journalKinds.takeLast(count).takeIf { it.size == lines.size } ?: lines.map(JournalKind::of)
        return lines.zip(kinds)
    }

    /** Belt cells: the chosen item id and what of it is carried (null — none left or empty cell). */
    fun belt(game: GameView): List<Pair<String, InventoryItemView?>> =
        game.belt.map { id -> id to game.inventory.firstOrNull { it.id == id && id.isNotEmpty() } }

    /** Combat buttons: the chosen spells and techniques that the character still knows (null — empty cell). */
    fun slots(game: GameView): List<AbilityView?> =
        game.slots.map { id -> game.abilities.firstOrNull { it.id == id && id.isNotEmpty() } }

    /** Equipment slots in the order of the bag screen: key of [Rules.equipSlot] and its title. */
    val SLOTS: List<Pair<String, String>> = listOf(
        "weapon" to "Оружие", "i.a.s." to "Щит", "i.a.h." to "Голова", "i.a.o." to "Глаза", "i.a.m." to "Шея",
        "i.a.d." to "Украшение", "i.a.e." to "Плащ", "i.a.b." to "Доспех", "i.a.r." to "Рубаха", "i.a.p." to "Руки",
        "i.a.k." to "Кольцо", "i.a.n." to "Браслет", "i.a.w." to "Штаны", "i.a.l." to "Ноги", "i.a.c." to "Обувь", "i.a.a." to "Наряд",
    )

    /** What is worn, by slot: every slot with the item in it or null. */
    fun equipment(game: GameView): List<Pair<String, InventoryItemView?>> =
        SLOTS.map { (key, title) -> title to game.inventory.firstOrNull { it.equipped && Rules.equipSlot(it.id) == key } }

    /** Skills by group for the character screen: group title and skill keys. */
    val SKILL_GROUPS: List<Pair<String, List<String>>> = listOf(
        "Атрибуты" to listOf("str", "dex", "int"),
        "Бой" to listOf("hand", "coldweapon", "ranged", "parring", "uklon"),
        "Магия" to listOf("magic", "magic_resist", "magic_uklon", "meditation", "spirit", "necro"),
        "Выживание" to listOf("regeneration", "hiding", "look", "healing"),
        "Воровство" to listOf("steal", "steallook"),
        "Звери" to listOf("animaltaming", "animallore", "currier"),
        "Ремёсла" to listOf("alchemy", "mine", "smith", "lumb", "bow", "stone", "fish", "food", "weaver"),
    )

    /** A skill's level (attributes from the character, the rest from its skills; 0 — not learnt). */
    fun skillLevel(c: CharacterView, key: String): Int = when (key) {
        "str" -> c.str
        "dex" -> c.dex
        "int" -> c.int
        else -> c.skills[key] ?: 0
    }

    /** Icon key (Design.ICONS) of an exit by its label. */
    fun exitIcon(label: String): String = when {
        "север" in label -> "north"
        "восток" in label -> "east"
        "юг" in label -> "south"
        "запад" in label -> "west"
        "вверх" in label || "наверх" in label || "подня" in label -> "up"
        "вниз" in label || "спуст" in label -> "down"
        else -> "enter"
    }

    /**
     * A short caption under an exit's arrow: the side for plain directions
     * («на юг по дороге» → «юг»), otherwise the label without its verb
     * («выйти на улицу» → «на улицу», «дом на севере» stays).
     */
    fun exitCaption(label: String): String {
        val l = label.trim()
        Regex("^на (север|юг|восток|запад)\\b").find(l)?.let { return it.groupValues[1] }
        val rest = l.replaceFirst(Regex("^(выйти|войти|подойти|спуститься|подняться|идти|пройти|зайти)\\s+"), "")
        return rest.ifEmpty { l }
    }

    /** Health is low: the belt lights its healing potion. */
    fun lowHealth(game: GameView): Boolean = game.character.hp * 100 < game.character.hpMax * 35

    /** Path of a picture on the server for an art key from a view ("npcs/npc-beginner"). */
    fun artPath(key: String): String = "/art/$key.webp"

    /** Path of an item's picture. */
    fun itemPath(id: String): String = "/art/item/$id"
}

/** See [GameScene.groups]. */
class NpcGroups(val atYou: List<NpcView>, val atOthers: List<NpcView>, val unaware: List<NpcView>, val rest: List<NpcView>)
