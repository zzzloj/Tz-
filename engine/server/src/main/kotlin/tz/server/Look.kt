package tz.server

import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import tz.shared.GameView
import tz.shared.LookView
import tz.shared.Rules

/*
 * «Осмотреть» (f_look.dat and friends): a character (f_lookuser.dat), an
 * NPC (f_looknpc.dat), an item (f_lookitem.dat), a spell (f_lookmagic.dat)
 * or technique, or a help page from content/raw/desc.
 */

suspend fun Game.look(account: Account, target: String): GameView = lock.withLock {
    val p = player(account)
    val now = clock()
    val t = target.trim()
    val look = when {
        t.startsWith("n.") -> world.npc(p.location, t)?.let { lookNpc(it) }
        t.startsWith("m.") || t.startsWith("p.") && t.length > 1 -> lookAbility(p, t)
        t == "i.s.book.news" -> noticeBoard()
        t == "i.s.book.story" -> LookView("Книжная полка", "На полке лежат несколько книг, которые можно почитать:\n" + content.pages.joinToString("\n") { "— " + it.title }, page = "pages")
        t.startsWith("i.") -> lookItem(t)
        HELP.matches(t) -> lookHelp(t)
        else -> players.values.firstOrNull { it.location == p.location && it.name.equals(t, ignoreCase = true) && now - it.lastSeen < Game.ACTIVE_SECONDS }
            ?.let { q -> lookPlayer(q).let { v -> about(q.id)?.let { v.copy(text = v.text + "\n\nО себе: " + it) } ?: v } }
    } ?: LookView("Осмотр", "Не на кого смотреть")
    viewLocked(p).copy(look = look)
}

private val HELP = Regex("^(skill|weapon|armor|magic|char)\\.[a-z_]+$|^p$")

private fun strip(html: String): String =
    html.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ").trim()

private fun Game.lookHelp(key: String): LookView {
    val f = content.dir.resolve("raw/desc").resolve(key)
    return LookView("Описание", if (f.isFile) strip(f.readText()) else "Описание недоступно")
}

private val SEX_VERBS = listOf(
    Stat.MONSTERS to ("Убил монстров" to "Убила монстров"), Stat.PLAYERS to ("Убил игроков" to "Убила игроков"),
    Stat.DEATHS to ("Умер раз" to "Умерла раз"), Stat.THEFTS to ("Своровал раз" to "Своровала раз"),
    Stat.FISH to ("Поймал рыбы" to "Поймала рыбы"), Stat.BRANCHES to ("Срубил веток" to "Срубила веток"),
    Stat.ORE to ("Выкопал руды" to "Выкопала руды"), Stat.GEMS_FOUND to ("Нашёл самоцветов" to "Нашла самоцветов"),
    Stat.GEMS_SET to ("Вставил камней" to "Вставила камней"), Stat.TAMED to ("Приручил животных" to "Приручила животных"),
)

private fun Game.equipmentLines(equipped: List<String>): List<String> {
    fun name(id: String) = content.itemName(id) + if (".." in id) " *" else ""
    val hands = equipped.filter { !it.startsWith("i.a.") }.map(::name)
    val worn = equipped.filter { it.startsWith("i.a.") }.map(::name)
    return listOfNotNull(
        hands.takeIf { it.isNotEmpty() }?.let { "Держит в руках: " + it.joinToString() },
        worn.takeIf { it.isNotEmpty() }?.let { "Одет: " + it.joinToString() },
    )
}

private suspend fun Game.lookPlayer(q: Game.Player): LookView {
    val female = q.sex == "f"
    val days = db.tx { c ->
        c.prepareStatement("SELECT EXTRACT(EPOCH FROM now() - created_at)::BIGINT / 86400 FROM characters WHERE id = ?").use { st ->
            st.setLong(1, q.id); st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
    }
    val s = q.skills()
    val lines = mutableListOf<String>()
    lines += (if (female) "Женщ." else "Мужч.") + ", в игре $days дн."
    lines += Levels.rank(Levels.percent(s)) + " " + Levels.title(s)
    lines += Levels.build(q.str, q.dex, q.int)
    q.spouseName?.let { lines += if (female) "Замужем за $it" else "Женат на $it" }
    q.clanName?.let { clan -> lines += (Rules.CLAN_RANKS[q.clanRank] ?: "Состоит в") + " клана $clan" }
    if (q.hasFlag) lines += "Держит в руках флаг лидерства (нападение не будет преступлением)"
    if (q.ghost) lines += "Призрак"
    lines += equipmentLines(q.equipped)
    lines += "— Статистика —"
    for ((i, verbs) in SEX_VERBS) lines += "${if (female) verbs.second else verbs.first}: ${q.statistics[i]}"
    return LookView(q.name, lines.joinToString("\n"))
}

private fun Game.lookNpc(n: World.Npc): LookView {
    val k = n.key
    val type = when {
        k.startsWith("n.a.losh.") -> "лошадь, на ней можно ездить, если она принадлежит вам"
        k.startsWith("n.a.") -> "животное"
        k.startsWith("n.m.") -> "гном"
        k.startsWith("n.w.") -> "божественная личность"
        k.startsWith("n.c.") -> "монстр"
        k.startsWith("n.z.") -> "зомби, поднятый из мёртвых с помощью некромантии"
        k.startsWith("n.g.") -> "городская стража"
        k.startsWith("n.s.") -> "призван с помощью магии"
        k.startsWith("n.h.") -> "лекарь, может воскрешать мёртвых (для этого надо поговорить с ним)"
        else -> "человек"
    }
    val lines = mutableListOf("Тип: $type")
    if (content.logic.hasDialog(if (k.startsWith("n.g.")) "n.g.guard" else k)) lines += "Может с вами говорить"
    if (k.startsWith("n.a.")) {
        val char = content.npcs[n.proto.template]?.get("char") as? JsonObject
        (char?.get("tame_difficulty") as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it > 0 }?.let { lines += "Сложность приручения: $it" }
    }
    lines += "Здоровье: ${n.hp.coerceAtLeast(0)}/${n.proto.hpMax}"
    return LookView(n.name, lines.joinToString("\n"))
}

/** Special texts of f_lookitem.dat, by id. */
private val ITEM_TEXTS = mapOf(
    "i.ress" to "Пузырёк с живой водой: при попадании на кожу призрака может его воскресить. Шанс зависит от навыка «Лечение».",
    "i.q.pervor" to "Перчатки вора позволяют незаметно подглядеть в любой рюкзак (только подглядеть: шанс своровать увиденное зависит от ловкости и навыка «Кража», а также от осторожности цели), но после использования исчезают. Сделано в гильдии воров в Ансалоне.",
    "i.q.pjpt" to "Перо жар-птицы защищает того, кто его носит: в случае смерти все предметы остаются у призрака, а не на трупе; само перо при этом исчезает.",
    "i.kirka" to "Кирка. Используется для добычи руды.",
    "i.q.kirka" to "Кирка мастера. Используется для добычи руды; шанс добыть руду на 10 % выше, чем обычной.",
    "i.q.ssword" to "Наносит урон цели 0..90, после чего разлетается на осколки. Требует минимум силы 5 и ловкости 4.",
    "i.q.pdeath" to "Наносит урон магией всем в округе 0..90, после чего исчезает. Не имеет требований к параметрам персонажа.",
    "i.q.keykrep1" to "Ключ так начищен, что в нём отчётливо видны ваши очертания; на нём надпись на неизвестном вам языке.",
    "i.b.fire" to "Наносит урон всем в округе 1..24, не имеет требований к параметрам персонажа.",
    "i.b.jad" to "Наносит урон цели 1..28, не имеет требований к параметрам персонажа.",
    "i.b.holy" to "Наносит урон всем преступникам в округе 6..24, не имеет требований к параметрам персонажа.",
    "i.b.jad.c" to "Яд древесной змеи: отравляет цель на 5 минут — здоровье убывает, а не восстанавливается.",
    "i.guildstone" to "Камень гильдии позволяет управлять собственным кланом.",
    "i.s.tree" to "С дерева можно рубить ветки любым топором, если у вас развит навык «Лесоруб».",
    "i.s.rudnik" to "Здесь можно накопать руды, если у вас есть кирка и развит навык «Рудокоп».",
    "i.q.dublon" to "Квестовые деньги.",
    "i.s.res" to "Если призрак дотронется до этого камня, то тут же воскреснет. Также воскресить мёртвого может лекарь или сильный маг заклинанием «Воскрешение».",
    "i.s.fontan" to "В фонтане можно наполнить водой пустую бутылку.",
    "i.set.koptilka" to "Переносная коптилка для рыбы, для копчения требует ветки и рыбу.",
    "i.money" to "Золотые монеты, на них можно что-нибудь купить в магазинах.",
    "i.s.lodka" to "Лодка: на ней можно доплыть к причалу, в Ансалон или к морю.",
    "i.s.arena" to "Камень выхода с арены: покинуть арену можно призраком или оставшись единственным в живых.",
)

private fun req(o: JsonObject): List<Int> = when (val r = o["req"]) {
    is JsonArray -> r.map { (it as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0 }
    is JsonPrimitive -> r.contentOrNull?.split(':')?.map { it.toIntOrNull() ?: 0 } ?: emptyList()
    else -> emptyList()
}

/** The town notice board (i.s.book.news): the latest news from the forum. */
private suspend fun Game.noticeBoard(): LookView {
    val news = forum.news(5)
    val text = if (news.isEmpty()) "Новостей пока нет." else news.joinToString("\n\n") { (t, body) ->
        Moderation.date(t.updated).substringBefore(' ') + " — " + t.title + "\n" + body.take(300) + if (body.length > 300) "…" else ""
    }
    return LookView("Доска объявлений", text, page = "news")
}

private suspend fun Game.about(characterId: Long): String? = db.tx { c ->
    c.prepareStatement("SELECT about FROM characters WHERE id = ?").use { st ->
        st.setLong(1, characterId); st.executeQuery().use { rs -> if (rs.next()) rs.getString(1).takeIf { it.isNotBlank() } else null }
    }
}

private fun Game.lookItem(id: String): LookView {
    val base = Formulas.baseId(id).substringBefore("..")
    val o = content.items[id] ?: content.items[base]
    val lines = mutableListOf<String>()
    val known = ITEM_TEXTS[base]
    when {
        known != null -> lines += known
        base.startsWith(Spells.PORTAL) || base == "i.s.portal" -> lines += "Мерцающий овал магического портала: если дотронуться до него, окажетесь там, куда он ведёт."
        base.startsWith("i.rr.") -> lines += if (base == Spells.EMPTY_RUNE) "Руна для телепортации, не помечена ни в одно место (это можно сделать заклинанием «Пометить»)."
            else "Руна для телепортации в " + (content.locations[base.removePrefix("i.rr.")]?.name ?: "неизвестное место") + " (заклинанием «Возвращение» или сама руна)."
        base.startsWith("i.i.") -> lines += ((o?.get("description") as? JsonPrimitive)?.contentOrNull ?: "") + "\nСамоцвет: при инкрустировании добавляет магические свойства предметам."
        base.startsWith("i.bc.") -> lines += "Из цветов можно создавать букеты."
        base.startsWith("i.m.") -> lines += "Свиток с заклинанием, исчезает после прочтения.\n" + spellText(Spells.spellOfItem(base))
        base.startsWith("i.r.") -> lines += "Руна с заклинанием, после использования не исчезает.\n" + spellText(Spells.spellOfItem(base))
        base.startsWith("i.b.") -> lines += "Бутылка с зельем, которую можно бросить под ноги цели."
        base.startsWith("i.f.") && o != null -> {
            val food = content.crafting.consumables[base] as? JsonObject
            lines += "Пища восстанавливает здоровье и иногда ману"
            lines += "Здоровье +" + (food?.int("hp") ?: o.int("heal_hp") ?: 0)
            lines += "Мана +" + (food?.int("mana") ?: o.int("heal_mana") ?: 0)
        }
        base.startsWith("i.a.") && o != null -> {
            lines += "Броня: " + (o.int("armor") ?: 0)
            val r = req(o)
            listOf("Требует силы", "Требует ловкости", "Требует интеллекта").forEachIndexed { i, t -> r.getOrNull(i)?.takeIf { it > 0 }?.let { lines += "$t: $it" } }
        }
        base.startsWith("i.w.") && o != null -> {
            lines += (if (base.startsWith("i.w.r.")) "Стрелковое/метательное" else "Холодное") + " оружие" +
                (if (base.startsWith("i.w.k.")) ", подходит для разделки трупов" else "") +
                (if (base.startsWith("i.w.t.")) ", подходит для рубки деревьев" else "")
            lines += "Урон: ${o.int("dmg_min") ?: 0}-${o.int("dmg_max") ?: 0}"
            val r = req(o)
            r.getOrNull(3)?.takeIf { it > 0 }?.let { lines += "Требует жизни: $it" }
            listOf("Требует силы", "Требует ловкости", "Требует интеллекта").forEachIndexed { i, t -> r.getOrNull(i)?.takeIf { it > 0 }?.let { lines += "$t: $it" } }
            lines += "Скорость: " + (o.int("speed") ?: 0)
            o.str("ammo")?.takeIf { it.isNotBlank() }?.let { lines += "Использует: " + content.itemName(it) }
        }
        base.startsWith("i.note") || base.startsWith("i.s.note") || base.startsWith("i.book") || base.startsWith("i.s.book") ->
            lines += strip(o?.str("text") ?: "").replace("[page]", "\n\n")
        base.startsWith("i.s.") -> o?.str("description")?.let { lines += strip(it) }
        else -> o?.str("description")?.let { lines += strip(it) }
    }
    Regex("-(\\d+)-").find(id)?.let { lines += "Заточен: +" + it.groupValues[1] }
    if ('_' in id && !base.startsWith("i.s.")) id.substringAfter('_').substringBefore('_').takeIf { it.isNotBlank() && !it.startsWith("..") }?.let { lines += "Сделано мастером: $it" }
    val gems = Regex("\\.\\.([A-Za-z0-9]+)").findAll(id).map { it.groupValues[1] }.toList()
    if (gems.isNotEmpty()) {
        lines += "Инкрустированы самоцветы:"
        for (g in gems) lines += content.itemName("i.i.$g") + ((content.items["i.i.$g"]?.get("description") as? JsonPrimitive)?.contentOrNull?.let { ": $it" } ?: "")
    }
    if (lines.isEmpty()) lines += "Описание отсутствует"
    return LookView(content.itemName(id), lines.joinToString("\n"))
}

private fun Game.spellText(spell: String?): String = spell?.let { content.items[it] }?.str("description")?.let { strip(it) } ?: ""

/** f_lookmagic.dat and the technique page of f_look.dat, with the viewer's own numbers. */
private fun Game.lookAbility(p: Game.Player, id: String): LookView? {
    val o = content.items[id] ?: return null
    val name = o.str("name") ?: id
    val lines = mutableListOf<String>()
    o.str("description")?.let { lines += strip(it) }
    if (id.startsWith("p.")) {
        lines += "Период: " + (o.int("cooldown") ?: 0) + " сек"
        val penalty = (p.int - 1) * 10
        if (id != "p.d.c" && penalty > 0) lines += "Шанс успешного использования (с учётом вашего интеллекта): -$penalty%"
        return LookView(name, lines.joinToString("\n"))
    }
    val pmin = o.int("power_min") ?: 0
    val pmax = o.int("power_max") ?: 0
    val level = o.int("level") ?: 1
    val cast = o.int("cast_time") ?: 0
    val period = o.int("cooldown") ?: 0
    fun time(sec: Int) = "${sec / 60} мин ${sec % 60} сек"
    lines += "Слова: " + (o.str("words") ?: "")
    lines += "Уровень: $level"
    lines += "Мана: " + (o.int("mana_cost") ?: 0)
    if (pmin != 0 || pmax != 0) lines += (if (id.startsWith("m.heal")) "Лечение: " else "Урон: ") + "$pmin-$pmax"
    if ((o.int("needs_target") ?: 0) != 0) lines += "Требует цель"
    if ((o.int("criminals_only") ?: 0) != 0) lines += "Действует только на преступников"
    lines += "Скорость: $cast сек"
    lines += "Период: " + time(period)
    lines += "— С учётом ваших характеристик —"
    val magic = p.skill("magic")
    val chance = ((magic * 0.5 + p.int * 1.5) * 10 - level * 10 + 10 - (maxOf(p.str, 2) - 2) * 4).toInt()
    lines += "Шанс: " + (if (magic == 0) 0 else chance.coerceIn(0, 95)) + " %"
    if (pmin != 0 || pmax != 0) lines += (if (id.startsWith("m.heal")) "Лечение: " else "Урон: ") + "${(pmin - 10 + p.int * 2).coerceAtLeast(0)} - ${pmax + p.int * 2}"
    lines += "Скорость: " + (cast + 3 - p.dex + (maxOf(p.str, 2) - 2) * 4) + " сек"
    lines += "Период: " + time(period + if (id.startsWith("m.w.")) (maxOf(p.str, 2) - 2) * 1200 else 0)
    return LookView(name, lines.joinToString("\n"))
}
