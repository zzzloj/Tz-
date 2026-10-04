package tz.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tz.shared.CraftOptionView
import tz.shared.CraftView
import tz.shared.Errors
import tz.shared.GameView
import tz.shared.Rules
import java.io.File

/**
 * content/crafting.json (format in content/crafting.md): eating and
 * drinking, table crafting, gathering at resource nodes, fishing and the
 * special kits. Keys ending with "." are prefixes (any axe "i.w.t.").
 */
class Crafting(private val root: JsonObject) {
    val recipes: JsonObject = root["recipes"] as? JsonObject ?: JsonObject(emptyMap())
    val gathering: JsonObject = root["gathering"] as? JsonObject ?: JsonObject(emptyMap())
    val nodes: JsonObject = root["nodes"] as? JsonObject ?: JsonObject(emptyMap())
    val consumables: JsonObject = root["consumables"] as? JsonObject ?: JsonObject(emptyMap())
    val special: JsonObject = root["special"] as? JsonObject ?: JsonObject(emptyMap())

    enum class Kind { FOOD, RECIPES, GATHER, SPECIAL }

    /** What using [itemId] does, and the key of its entry. */
    fun find(itemId: String): Pair<Kind, String>? {
        val base = itemId.substringBefore('_')
        fun match(section: JsonObject) = section.keys.firstOrNull { k -> k == itemId || k == base } ?:
            section.keys.filter { it.endsWith(".") && base.startsWith(it) }.maxByOrNull { it.length }
        match(consumables)?.let { return Kind.FOOD to it }
        match(recipes)?.let { return Kind.RECIPES to it }
        match(gathering)?.let { return Kind.GATHER to it }
        match(special)?.let { k ->
            // Knives butcher corpses through the corpse's own button; gems of i.i. only with a target.
            if (special[k]?.jsonObject?.str("handler") in SUPPORTED) return Kind.SPECIAL to k
        }
        return null
    }

    /**
     * Tools that work at a fixture (an anvil, a loom, a fire, a tree, an ore vein):
     * the tool's key (an id or an id prefix ending with «.»), what it does, its skill.
     */
    fun toolsAt(fixture: String): List<Triple<String, String, String?>> {
        fun here(x: String) = fixture == x || fixture.startsWith("$x.")
        val out = mutableListOf<Triple<String, String, String?>>()
        for (section in listOf(recipes, special, gathering)) for ((key, v) in section) {
            val o = v as? JsonObject ?: continue
            val needHere = (o["needHere"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            if (needHere.any(::here) || o.str("node")?.let(::here) == true) out += Triple(key, o.str("about") ?: key, o.str("skill"))
        }
        return out
    }

    /** Whether item [id] is the tool [key] (an exact id, or any id under a prefix ending with «.»). */
    fun isTool(id: String, key: String): Boolean = if (key.endsWith(".")) id.startsWith(key) else id == key || id.substringBefore('_') == key

    /** What the item is used on: "item", "player" or null (nothing). */
    fun targetOf(itemId: String): String? {
        val (kind, key) = find(itemId) ?: return null
        if (kind != Kind.SPECIAL) return null
        return when (special[key]?.jsonObject?.str("handler")) {
            "polishGem", "inlayGem", "extractGem", "cutCloth", "sharpen" -> "item"
            "resurrect" -> "player"
            "peekInventory" -> "creature"
            "bouquet" -> "item"
            else -> null
        }
    }

    companion object {
        /** Special handlers written so far; the rest (bouquets, peeking) come with their stages. */
        val SUPPORTED = setOf(
            "bandage", "resurrect", "cutCloth", "campfire", "fry", "fillBottle", "polishGem", "inlayGem",
            "extractGem", "unpack", "emote", "sharpen", "peekInventory", "bouquet",
        )

        fun load(file: File): Crafting =
            Crafting(if (file.isFile) Json.parseToJsonElement(file.readText()).jsonObject else JsonObject(emptyMap()))

        /** `{"die":[a,b],"perLevel":k,"plus":p,"minusDifficulty":bool,"op":"<"}` → roll rand(a..b) OP k·S+p−difficulty. */
        fun roll(c: JsonObject?, skill: Int, difficulty: Int, dice: Dice): Boolean {
            if (c == null) return true
            return test(c, dice.roll(dieFrom(c), dieTo(c)), value(c, skill, difficulty))
        }

        fun value(c: JsonObject, skill: Int, difficulty: Int): Int =
            (c.int("perLevel") ?: 0) * skill + (c.int("plus") ?: 0) - (if (Dialogs.truthy(c["minusDifficulty"]) && "minusDifficulty" in c) difficulty else 0)

        fun dieFrom(c: JsonObject) = (c["die"] as? JsonArray)?.getOrNull(0)?.jsonPrimitive?.intOrNull ?: 0
        fun dieTo(c: JsonObject) = (c["die"] as? JsonArray)?.getOrNull(1)?.jsonPrimitive?.intOrNull ?: 100

        fun test(c: JsonObject, rolled: Int, value: Int): Boolean = when (c.str("op") ?: "<") {
            "<=" -> rolled <= value
            ">=" -> rolled >= value
            ">" -> rolled > value
            else -> rolled < value
        }

        /** Percent chance the menu shows (rough: share of die values that pass). */
        fun percent(c: JsonObject?, skill: Int, difficulty: Int): Int {
            if (c == null) return 100
            val from = dieFrom(c); val to = dieTo(c); val v = value(c, skill, difficulty)
            val pass = (from..to).count { test(c, it, v) }
            return pass * 100 / (to - from + 1)
        }

        /** Experience of an `exp` object (content/crafting.md): fixed, randUpTo, randRange, chance. */
        fun exp(e: JsonElement?, dice: Dice): Int {
            val o = e as? JsonObject ?: return 0
            return when (o.str("kind")) {
                "fixed" -> o.int("value") ?: 0
                "randUpTo" -> dice.roll(0, o.int("max") ?: 0)
                "randRange" -> dice.roll(o.int("min") ?: 0, o.int("max") ?: 0)
                "chance" -> if (roll(o["chance"] as? JsonObject, 0, 0, dice)) exp(o["then"], dice) else 0
                else -> 0
            }
        }

        fun counts(e: JsonElement?): Map<String, Int> =
            (e as? JsonObject).orEmpty().mapValues { (_, v) -> (v as? JsonPrimitive)?.intOrNull ?: 1 }

        fun strings(e: JsonElement?): List<String> = (e as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }
}

// ---- using items ------------------------------------------------------------------------

private class Refusal(val text: String) : Exception(text)

private fun refuse(text: String): Nothing = throw Refusal(text)

/**
 * Uses an item from the backpack (f_useitem.dat): eat or drink it, craft
 * with a tool ([recipe] picks from its menu), gather, or apply a kit to
 * [target]. Outcomes go to the journal; a tool with a menu and no recipe
 * answers with the menu (GameView.craft).
 */
suspend fun Game.use(account: Account, itemId: String, recipe: Int?, target: String?): GameView = lock.withLock {
    val p = alive(player(account))
    val now = clock()
    val inventory = inventoryMap(p).toMutableMap()
    if ((inventory[itemId] ?: 0) < 1) throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_INVENTORY)
    val c = content.crafting
    // Scrolls, runes of spells and teleport runes (Magic.kt).
    if (c.find(itemId) == null && useMagicItem(p, itemId, target)) {
        save(p)
        return@withLock viewLocked(p)
    }
    val (kind, key) = c.find(itemId) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.CANNOT_USE)
    if (clockMs() < p.busyUntil) throw ApiException(HttpStatusCode.Conflict, Errors.RESTING)
    var menu: CraftView? = null
    try {
        when (kind) {
            Crafting.Kind.FOOD -> eat(p, itemId, c.consumables[key]!!.jsonObject, now)
            Crafting.Kind.RECIPES -> menu = craft(p, itemId, c.recipes[key]!!.jsonObject, recipe, inventory, now)
            Crafting.Kind.GATHER -> gather(p, itemId, c.gathering[key]!!.jsonObject, now)
            Crafting.Kind.SPECIAL -> special(p, itemId, c.special[key]!!.jsonObject, target, inventory, now)
        }
    } catch (r: Refusal) {
        p.log(r.text)
    }
    refreshStats(p)
    save(p)
    viewLocked(p).copy(craft = menu)
}

private fun msg(o: JsonObject, key: String, default: String): String =
    (o["messages"] as? JsonObject)?.str(key) ?: default

private suspend fun Game.eat(p: Game.Player, itemId: String, food: JsonObject, now: Long) {
    changeItem(p, itemId, -1)
    food.str("leaves")?.let { changeItem(p, it, 1) }
    // Food heals a share of the maximum (balance of 04.10.2026, content/balance/items.json); the old points otherwise.
    val nb = content.itemBalance(itemId)
    val hp = nb?.int("healPct")?.let { p.hpMax * it / 100 } ?: food.int("hp") ?: 0
    val mana = nb?.int("manaPct")?.let { p.manaMax * it / 100 } ?: food.int("mana") ?: 0
    p.hp = (p.hp + hp).coerceAtMost(p.hpMax)
    p.mana = (p.mana + mana).coerceAtMost(p.manaMax)
    p.busyUntil = clockMs() + 1000L * ((food.int("busy") ?: 0))
    // The antidote shortens poisoning (plugin/i.b.antidot.dat).
    food.int("curesPoisonSeconds")?.let { p.poisonUntil -= it }
    p.log(food.str("message") ?: buildString {
        append("Вы съели: ").append(content.itemName(itemId))
        if (hp > 0) append(", HP +").append(hp)
        if (mana > 0) append(", мана +").append(mana)
    })
}

/** Checks the skill and the place; sets the pause (the old code paused even on failure). */
private fun Game.prepare(p: Game.Player, o: JsonObject, now: Long): Int {
    val skillKey = o.str("skill")
    // The craft tables are tuned to the old 0–5 scale (balance of 04.10.2026: steps 0–10 count half).
    val skill = skillKey?.let { p.oldSkill(it) } ?: 0
    if (skillKey != null && (o["needSkill"] == null || Dialogs.truthy(o["needSkill"])) && skill <= 0)
        refuse(msg(o, "lowSkill", "Вы этого не умеете"))
    p.busyUntil = clockMs() + 1000L * ((o.int("busy") ?: 0))
    return skill
}

private suspend fun Game.requireHere(p: Game.Player, o: JsonObject) {
    for (f in Crafting.strings(o["needHere"])) if (!world.hasFixture(p.location, f, exact = true))
        refuse(msg(o, "noHere", msg(o, "noFire", "Здесь нет: ${content.itemName(f)}")))
}

/** Resolves "i.h.p.*" (any one stack with the prefix, enough of it) to a concrete id. */
private fun resolve(id: String, need: Int, inventory: Map<String, Int>): String? =
    if (id.endsWith(".*")) inventory.entries.firstOrNull { it.key.startsWith(id.removeSuffix("*")) && it.value >= need }?.key
    else id.takeIf { (inventory[it] ?: 0) >= need }

private suspend fun Game.craft(
    p: Game.Player, tool: String, o: JsonObject, choice: Int?, inventory: MutableMap<String, Int>, now: Long,
): CraftView? {
    val list = (o["recipes"] as? JsonArray).orEmpty().map { it.jsonObject }
    val menu = o["menu"]?.let { Dialogs.truthy(it) } ?: (list.size > 1)
    val skill = o.str("skill")?.let { p.oldSkill(it) } ?: 0
    if (choice == null && menu) {
        return CraftView(tool, o.str("about") ?: content.itemName(tool), list.map { r ->
            val needs = (Crafting.counts(r["takes"]) + Crafting.counts(r["takesOnSuccess"]))
                .entries.joinToString { (id, n) -> (if (id.endsWith(".*")) "перья" else content.itemName(id)) + " ×$n" }
            CraftOptionView(r.int("key") ?: 0, r.str("name") ?: content.itemName(r.str("make") ?: ""),
                Crafting.percent(r["chance"] as? JsonObject, skill, r.int("difficulty") ?: 0), needs)
        })
    }
    val r = (if (choice == null) list.firstOrNull() else list.firstOrNull { it.int("key") == choice })
        ?: throw ApiException(HttpStatusCode.BadRequest, Errors.CANNOT_USE)
    val difficulty = r.int("difficulty") ?: 0
    val chance = r["chance"] as? JsonObject
    // Refusals before any pause or loss.
    if (o.str("skill") != null && Dialogs.truthy(o["needSkill"] ?: JsonPrimitive(true)) && skill <= 0)
        refuse(msg(o, "lowSkill", "Вы этого не умеете"))
    requireHere(p, o)
    for (h in Crafting.strings(o["needHave"])) if (resolve(h, 1, inventory) == null)
        refuse(msg(o, "missingHave", "Нужно: " + (if (h.endsWith(".*")) "перья" else content.itemName(h))))
    if (Dialogs.truthy(o["refuseIfChanceNotPositive"]) && "refuseIfChanceNotPositive" in o && chance != null &&
        Crafting.value(chance, skill, difficulty) <= 0) refuse("Ваш навык слишком низок, чтобы сделать это")
    val takes = Crafting.counts(r["takes"]).map { (id, n) -> (resolve(id, n, inventory) ?: refuse("Не хватает: " + content.itemName(id.removeSuffix(".*")) + " ×$n")) to n }
    val onSuccess = Crafting.counts(r["takesOnSuccess"]).map { (id, n) -> (resolve(id, n, inventory) ?: refuse("Не хватает: " + content.itemName(id) + " ×$n")) to n }
    p.busyUntil = clockMs() + 1000L * ((o.int("busy") ?: 0))
    for ((id, n) in takes) changeItem(p, id, -n)
    val ok = Crafting.roll(chance, skill, difficulty, dice)
    val name = r.str("name") ?: content.itemName(r.str("make") ?: "")
    if (ok) {
        for ((id, n) in onSuccess) changeItem(p, id, -n)
        val make = r.str("make")!!
        val id = if (Dialogs.truthy(r["named"]) && "named" in r) "${make}_${p.name}_" else make
        changeItem(p, id, r.int("count") ?: 1)
        p.log(msg(o, "success", "Вы сделали: $name"))
        craftSuccess(p, o.str("skill"), Crafting.exp(r["exp"], dice))
    } else {
        for ((id, n) in Crafting.counts(r["failGives"])) changeItem(p, id, n)
        p.log(msg(o, "fail", "Не получилось: $name"))
    }
    when (o.str("toolConsumed")) {
        "always" -> changeItem(p, tool, -1)
        "success" -> if (ok) changeItem(p, tool, -1)
    }
    breakTool(p, tool, o.int("toolBreakPercent") ?: 0, msg(o, "broken", "Инструмент сломался"))
    return null
}

private suspend fun Game.breakTool(p: Game.Player, tool: String, percent: Int, text: String) {
    if (percent > 0 && dice.roll(1, 100) <= percent) {
        changeItem(p, tool, -1)
        p.log(text)
    }
}

private suspend fun Game.gather(p: Game.Player, tool: String, o: JsonObject, now: Long) {
    val skill = p.oldSkill(o.str("skill") ?: "")
    if (skill <= 0 && o.str("skill") != null) refuse(msg(o, "lowSkill", "Вы этого не умеете"))
    val node = o.str("node")
    val nodeInfo = node?.let { content.crafting.nodes[it] as? JsonObject }
    if (node != null && !world.hasFixture(p.location, node, exact = true)) refuse(msg(o, "noNode", "Здесь нечего добывать"))
    val places = Crafting.strings(o["locations"])
    if (places.isNotEmpty() && p.location !in places) refuse(msg(o, "noWater", msg(o, "noNode", "Здесь нечего добывать")))
    p.busyUntil = clockMs() + 1000L * ((o.int("busy") ?: 0))
    if (node != null && nodeInfo != null) {
        val here = (nodeInfo["locations"] as? JsonObject)?.get(p.location) as? JsonObject
        val stockMax = here?.int("stockMax") ?: 4
        if (!world.nodeHasStock(p.location, node, here?.int("stock") ?: stockMax, stockMax, now))
            refuse(msg(o, "empty", "Здесь пока пусто, ждите"))
    }
    val table = o["table"] as? JsonObject
    if (table != null) {
        // Fishing: one roll, the first row with roll < S·mult gives its catch.
        val rolled = dice.roll(Crafting.dieFrom(table), Crafting.dieTo(table))
        val row = (table["rows"] as? JsonArray).orEmpty().map { it.jsonObject }
            .firstOrNull { rolled < skill * ((it["mult"] as? JsonPrimitive)?.doubleOrNull ?: 0.0) }
        if (row != null) {
            changeItem(p, row.str("give")!!, row.int("count") ?: 1)
            p.log("Вы поймали " + (row.str("text") ?: content.itemName(row.str("give")!!)))
            o.int("stat")?.let { p.count(it) }
            craftSuccess(p, o.str("skill"), Crafting.exp(o["exp"], dice))
        } else p.log(msg(o, "fail", "Ничего не вышло"))
    } else if (Crafting.roll(o["chance"] as? JsonObject, skill, 0, dice)) {
        if (node != null && nodeInfo != null) world.spendNode(p.location, node, nodeInfo.int("regrowSeconds") ?: 300, now)
        for ((id, n) in Crafting.counts(o["gives"])) changeItem(p, id, n)
        p.log(msg(o, "success", "Удалось"))
        craftSuccess(p, o.str("skill"), Crafting.exp(o["exp"], dice))
        o.int("stat")?.let { p.count(it) }
        gatherExtras(p, o["extra"] as? JsonObject, now)
    } else p.log(msg(o, "fail", "Не получилось"))
    breakTool(p, tool, o.int("toolBreakPercent") ?: 0, msg(o, "broken", "Инструмент сломался"))
}

/** Gems in deep mines and the golem that wakes up (plugin/i.kirka.dat). */
private suspend fun Game.gatherExtras(p: Game.Player, extra: JsonObject?, now: Long) {
    val gems = extra?.get("gems") as? JsonObject
    if (gems != null) {
        val where = gems["where"] as? JsonObject
        val y = Regex("^x\\d+x(\\d+)$").find(p.location)?.groupValues?.get(1)?.toIntOrNull()
        val deep = (where?.int("minY")?.let { y != null && y >= it } ?: false) || p.location in Crafting.strings(where?.get("locations"))
        val pick = Crafting.strings(gems["pick"])
        if (deep && pick.isNotEmpty()) {
            val gem = pick[rnd.nextInt(pick.size)]
            if (Crafting.roll(gems["chance"] as? JsonObject, 0, 0, dice)) {
                changeItem(p, gem, gems.int("count") ?: 1)
                p.log("Вы нашли " + content.itemName(gem) + "!")
                p.count(gems.int("stat") ?: Stat.GEMS_FOUND)
            }
        }
    }
    val golem = extra?.get("golem") as? JsonObject
    if (golem != null && p.location in Crafting.strings(golem["locations"]) && Crafting.roll(golem["chance"] as? JsonObject, 0, 0, dice)) {
        val template = golem.str("npc") ?: return
        world.spawn(template, "$template.${rnd.nextInt(99, 10000)}", p.location, now)
        p.log("Голем: ${p.name}, как ты посмел потревожить меня?! За это ты умрешь!")
    }
}

private suspend fun Game.special(
    p: Game.Player, tool: String, o: JsonObject, target: String?, inventory: MutableMap<String, Int>, now: Long,
) {
    val fail = { key: String, d: String -> msg(o, key, d) }
    when (o.str("handler")) {
        "unpack" -> {
            for ((id, n) in Crafting.counts(o["takes"])) changeItem(p, id, -n)
            for ((id, n) in Crafting.counts(o["gives"])) changeItem(p, id, n)
            p.log("Вы распаковали: " + content.itemName(tool))
        }
        "fillBottle" -> {
            if (p.location !in Crafting.strings(o["locations"])) refuse(fail("noWater", "Здесь нет воды"))
            for ((id, n) in Crafting.counts(o["takes"])) changeItem(p, id, -n)
            for ((id, n) in Crafting.counts(o["gives"])) changeItem(p, id, n)
            p.log(fail("success", "Вы наполнили бутылку водой"))
        }
        "bandage" -> {
            val skill = prepare(p, o, now)
            changeItem(p, tool, -1)
            if (Crafting.roll(o["chance"] as? JsonObject, skill, 0, dice)) {
                val heal = o["heal"] as? JsonObject
                val add = dice.roll(heal?.int("min") ?: 5, (heal?.int("maxBase") ?: 5) + (heal?.int("maxPerLevel") ?: 2) * skill)
                p.hp = (p.hp + add).coerceAtMost(p.hpMax)
                p.log(fail("success", "Вы забинтовываете раны") + " (+$add HP)")
            } else p.log(fail("fail", "Вы не смогли забинтовать раны"))
        }
        "resurrect" -> {
            val other = players.values.firstOrNull { it.location == p.location && it.name == target && it.id != p.id }
                ?: refuse(fail("badTarget", "Воскресить можно только призрака игрока."))
            if (!other.ghost) refuse(fail("notGhost", "{target} - не призрак").replace("{target}", other.name))
            val skill = prepare(p, o, now)
            changeItem(p, tool, -1)
            if (Crafting.roll(o["chance"] as? JsonObject, skill, 0, dice)) {
                other.ghost = false
                other.hp = 0
                other.regenFrom = now
                other.log("${p.name} воскресил вас.")
                save(other); notify(other.id)
                p.log(fail("success", "Вы воскресили {target}").replace("{target}", other.name))
            } else p.log(fail("fail", "Вам не удалось воскресить"))
        }
        "cutCloth" -> {
            val t = target ?: refuse(fail("bad", "Выберите, что резать"))
            if ((inventory[t] ?: 0) < 1) throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_INVENTORY)
            val yields = (o["yields"] as? JsonArray).orEmpty().map { it.jsonObject }
            val y = yields.firstOrNull { it.str("id") == t } ?: yields.firstOrNull { it.str("prefix")?.let { pre -> t.startsWith(pre) } == true }
                ?: refuse(fail("bad", "Ножницами резать можно только ткань или одежду"))
            val n = y.int("count") ?: 1
            changeItem(p, t, -1)
            changeItem(p, o.str("gives") ?: "i.bint", n)
            p.log("Вы разрезали ${content.itemName(t)} на $n бинта")
        }
        "campfire" -> {
            val skill = prepare(p, o, now)
            val log = Crafting.counts(o["needOnGround"]).keys.firstOrNull() ?: "i.log"
            if (!world.takeFromGround(p.location, log, 1, now)) { p.busyUntil = clockMs(); refuse(fail("noLog", "Положите на землю ветку")) }
            changeItem(p, tool, -1)
            if (Crafting.roll(o["chance"] as? JsonObject, skill, 0, dice)) {
                val spawn = o["spawns"] as? JsonObject
                world.placeFor(p.location, spawn?.str("fixture") ?: "i.s.fire", (spawn?.int("lifetimePerLevelSeconds") ?: 120) * skill, now)
                p.log(fail("success", "Вы разожгли костер"))
                craftSuccess(p, o.str("skill"), Crafting.exp(o["exp"], dice))
            } else p.log(fail("fail", "Вам не удалось разжечь костер"))
        }
        "fry" -> {
            for (f in Crafting.strings(o["needHere"])) if (!world.hasFixture(p.location, f, exact = true)) refuse(fail("noFire", "Вначале найдите или разведите костер."))
            for ((id, n) in Crafting.counts(o["takes"])) if ((inventory[id] ?: 0) < n) refuse(fail("noMeat", "У вас нет сырого мяса"))
            val skill = prepare(p, o, now)
            for ((id, n) in Crafting.counts(o["takes"])) changeItem(p, id, -n)
            if (Crafting.roll(o["chance"] as? JsonObject, skill, 0, dice)) {
                for ((id, n) in Crafting.counts(o["gives"])) changeItem(p, id, n)
                p.log(fail("success", "Вы поджарили мясо на костре"))
                craftSuccess(p, o.str("skill"), Crafting.exp(o["exp"], dice))
            } else p.log(fail("fail", "Мясо подгорело"))
        }
        "polishGem" -> {
            val t = target ?: refuse(fail("badTarget", "Выберите камень"))
            if (!t.startsWith("i.c.") || t.endsWith(".good") || (inventory[t] ?: 0) < 1) refuse(fail("badTarget", "Шлифовать можно только неограненные драгоценные камни"))
            val skill = prepare(p, o, now)
            if (Crafting.roll(o["chance"] as? JsonObject, skill, 0, dice)) {
                changeItem(p, t, -1)
                changeItem(p, "$t.good", 1)
                p.log(fail("success", "Вы отшлифовали камень"))
                craftSuccess(p, o.str("skill"), Crafting.exp(o["exp"], dice))
            } else p.log(fail("fail", "Вам не удалось отшлифовать камень"))
            breakTool(p, tool, o.int("toolBreakPercent") ?: 5, fail("broken", "Вы сломали набор ювелира"))
        }
        "inlayGem" -> {
            val t = target ?: refuse(fail("notNamed", "Выберите предмет"))
            if ('_' !in t || (inventory[t] ?: 0) < 1) refuse(fail("notNamed", "Инкрустировать самоцветы можно только в именные предметы, созданные игроками"))
            if ((inventory["i.set.shlif"] ?: 0) < 1) refuse(fail("noKit", "В вашем рюкзаке должен быть набор ювелира"))
            val gem = tool.removePrefix("i.i.")
            val present = Regex("\\.\\.([A-Za-z0-9]+)").findAll(t).map { it.groupValues[1] }.toList()
            if (gem in present) refuse(fail("already", "Этот самоцвет уже инкрустирован в {name}").replace("{name}", content.itemName(t)))
            if (present.size >= 3) refuse(fail("tooMany", "В один предмет можно инкрустировать не более 3 самоцветов"))
            val skill = prepare(p, o, now)
            if (!Crafting.roll(o["firstRoll"] as? JsonObject, skill, 0, dice)) refuse(fail("retry", "Вам не удалось инкрустировать самоцвет, попробуйте еще раз"))
            changeItem(p, tool, -1)
            changeItem(p, t, -1)
            val second = o["secondRoll"] as? JsonObject
            val need = (second?.int("perLevel") ?: -10) * skill + (second?.int("plus") ?: 60) + (second?.int("perGemInItem") ?: 10) * present.size
            if (dice.roll(0, 100) >= need) {
                changeItem(p, "$t..$gem", 1)
                p.log(fail("success", "Вы инкрустировали самоцвет в {name}").replace("{name}", content.itemName(t)))
                craftSuccess(p, o.str("skill"), Crafting.exp(o["exp"], dice))
                p.count(o.int("stat") ?: Stat.GEMS_SET)
            } else p.log(fail("fail", "Вы испортили {name}").replace("{name}", content.itemName(t)))
            breakTool(p, "i.set.shlif", 5, fail("broken", "Вы сломали набор ювелира"))
        }
        "extractGem" -> {
            val t = target ?: refuse(fail("noGems", "Выберите предмет"))
            if ((inventory[t] ?: 0) < 1) throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_INVENTORY)
            val present = Regex("\\.\\.([A-Za-z0-9]+)").findAll(t).map { it.groupValues[1] }.toList()
            if (present.isEmpty()) refuse(fail("noGems", "в {name} нет вставленых камней").replace("{name}", content.itemName(t)))
            // The first gem set is taken out; the item is lost either way (as in the old game).
            val gem = present.first()
            val skill = prepare(p, o, now)
            val chance = o["chance"] as? JsonObject
            val bonus = (chance?.get("plusByGemCount") as? JsonObject)?.int(present.size.toString()) ?: 0
            changeItem(p, t, -1)
            if (dice.roll(0, 100) < (chance?.int("perLevel") ?: 10) * skill + bonus) {
                changeItem(p, "i.i.$gem", 1)
                p.log(fail("success", "Вы извлекли самоцвет"))
            } else p.log(fail("fail", "Вы не смогли извлечь самоцвет"))
            breakTool(p, tool, o.int("toolBreakPercent") ?: 5, fail("broken", "Вы сломали набор для извлечения камней"))
        }
        "sharpen" -> {
            val t = target ?: refuse(fail("bad", "Выберите оружие"))
            if (!t.contains("i.w.") || (inventory[t] ?: 0) < 1) refuse(fail("bad", "Точить можно только оружие"))
            val current = Regex("-(\\d+)-").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val level = current + 1
            p.busyUntil = clockMs() + 1000L * ((o.int("busy") ?: 10))
            changeItem(p, tool, -1)
            changeItem(p, t, -1)
            if (dice.roll(1, level * level + 22) < 12) {
                val base = t.substringBefore('-').substringBefore("..")
                val gems = Regex("\\.\\.[A-Za-z0-9]+").findAll(t).joinToString("") { it.value }
                changeItem(p, "$base-$level-$gems", 1)
                p.log("Вы заточили ${content.itemName(t)} до +$level")
            } else p.log("Вы не смогли заточить ${content.itemName(t)}, оружие испорчено")
        }
        "bouquet" -> bouquet(p, tool, target)
        "peekInventory" -> {
            // Thief's gloves: a peek without a roll, a rest or place limits; they wear out (plugin/i.q.pervor.dat).
            val aim = aim(p, target, now)?.takeIf { it !is Aim.Item && !(it is Aim.Pc && it.p.id == p.id) }
                ?: refuse(fail("noTarget", "Нет цели"))
            changeItem(p, tool, -1)
            showPeek(p, aim, now)
        }
        "emote" -> {
            val lines = Crafting.strings(o["lines"])
            val to = target ?: p.name
            changeItem(p, tool, -1)
            if (lines.isNotEmpty()) {
                val line = Dialogs.plain(lines[rnd.nextInt(lines.size)].replace("<from>", p.name).replace("<to>", to))
                p.log(line)
                tellOthers(p.location, p.id, line)
            }
        }
        else -> throw ApiException(HttpStatusCode.BadRequest, Errors.CANNOT_USE)
    }
}

// ---- crafts grow by practice (owner 04.10.2026, balance.md §4) ----------------------------------

/**
 * A craft or a gathering that worked: experience (an old point is a fifth of
 * a monster of one's level, so a craft gives ~60 % of a kill) and a step of
 * practice in the craft.
 */
internal suspend fun Game.craftSuccess(p: Game.Player, craft: String?, oldExp: Int) {
    if (oldExp > 0) addExp(p, Math.round(oldExp * balance.monsterExp(p.level) * 0.2))
    if (craft != null && craft in balance.crafts) practice(p, craft)
}

/** One more piece of practice; enough of it raises the craft a step, up to 10, all crafts together up to 30. */
internal suspend fun Game.practice(p: Game.Player, craft: String) {
    val step = p.skill(craft)
    if (step >= balance.craftMax) return
    if (balance.crafts.sumOf { p.skill(it) } >= balance.craftSumMax) return
    val progress = db.tx { c ->
        c.prepareStatement(
            "INSERT INTO character_craft (character_id, craft, progress) VALUES (?, ?, 1) " +
                "ON CONFLICT (character_id, craft) DO UPDATE SET progress = character_craft.progress + 1 RETURNING progress"
        ).use { st -> st.setLong(1, p.id); st.setString(2, craft); st.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
    }
    if (progress < balance.craftExpToNext(step)) return
    db.tx { c ->
        c.prepareStatement("UPDATE character_craft SET progress = 0 WHERE character_id = ? AND craft = ?").use { st ->
            st.setLong(1, p.id); st.setString(2, craft); st.executeUpdate()
        }
    }
    p.other[craft] = step + 1
    p.log("Ремесло «${Rules.skillTitle(craft)}» выросло: ${step + 1}")
    refreshStats(p)
    save(p)
}

