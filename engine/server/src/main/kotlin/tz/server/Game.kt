package tz.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonObject
import tz.shared.CharacterView
import tz.shared.DialogOption
import tz.shared.DialogView
import tz.shared.PersonView
import tz.shared.Errors
import tz.shared.GameView
import tz.shared.InventoryItemView
import tz.shared.NpcView
import tz.shared.Protocol
import tz.shared.Rules
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Everything a character does in the world: moving, items, fighting, dying.
 * Characters and inventories are stored in PostgreSQL; while a character is
 * active its state (HP, pause, target, journal) is kept here and written
 * back after each change. NPCs and things on the ground live in [World].
 *
 * One lock ([lock]) serialises all actions and the world tick, like the old
 * engine's game.lock — simple and fast enough for one world.
 */
class Game(
    internal val content: Content,
    internal val db: Db,
    private val accounts: Accounts,
    val world: World,
    internal val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    random: Random = Random.Default,
) {
    internal val dice = Dice.of(random)
    internal val rnd = random
    internal val lock = Mutex()

    class Player(
        val id: Long,
        val accountId: Long,
        val name: String,
        val sex: String,
        var location: String,
        var hp: Int,
        var mana: Int,
        var ghost: Boolean,
        var str: Int,
        var dex: Int,
        var int: Int,
        var exp: Int,
        var points: Int,
    ) {
        var equipped: List<String> = emptyList()
        var stats: Stats = Formulas.player(Skills.of(str, dex, int, exp, points), emptyList(), { null })
        var busyUntil = 0L
        var regenFrom = 0L
        var lastSeen = 0L
        val journal = ArrayDeque<String>()

        /** Skills other than the attributes, by key of [Rules.SKILLS]. */
        val other = HashMap<String, Int>()
        /** Spells and techniques learnt. */
        val known = HashSet<String>()
        /** Open conversation: NPC and the (topic, arg) choices shown last, the only ones accepted next. */
        var talkingTo: String? = null
        var talkChoices: Set<Pair<String, String?>> = emptySet()
        /** Topic that accepts typed text as its arg (a clan name). */
        var talkInput: String? = null
        /** Clan: id, name, rank (head, seneschal, vassal, neophyte). */
        var clanId: Long? = null
        var clanName: String? = null
        var clanRank: String? = null
        /** Last thing said, to refuse repeats (f_say.dat:65). */
        var lastSaid: String? = null

        fun skill(key: String): Int = when (key) {
            "str" -> str; "dex" -> dex; "int" -> int; "exp" -> exp; "points" -> points
            else -> other[key] ?: 0
        }

        fun skills() = Skills.of(str, dex, int, exp, points).also { s ->
            for ((key, index, _) in Rules.SKILLS) if (index > 4) s[index] = other[key] ?: 0
        }
        val hpMax get() = (Rules.hpMax(str) + stats.hpBonus).coerceAtLeast(1)
        val manaMax get() = (Rules.manaMax(int) + stats.manaBonus).coerceAtLeast(0)

        fun log(line: String) {
            journal.addLast(line)
            while (journal.size > JOURNAL_SIZE) journal.removeFirst()
        }
    }

    internal val players = ConcurrentHashMap<Long, Player>()
    /** Open exchanges by character id (Social.kt). */
    internal val exchanges = HashMap<Long, ExchangeSide>()
    private val byAccount = ConcurrentHashMap<Long, Long>()
    private val events = ConcurrentHashMap<Long, MutableSharedFlow<Unit>>()

    // ---- public actions -----------------------------------------------------------

    suspend fun view(account: Account): GameView = lock.withLock { viewLocked(player(account)) }

    suspend fun move(account: Account, target: String): GameView = lock.withLock {
        val p = player(account)
        val here = content.locations[p.location]
        if (here == null || here.exits.none { it.target == target } || target !in content.locations)
            throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_AN_EXIT)
        for (npc in world.npcsIn(p.location)) npc.enemies.remove(p.id)
        p.location = target
        save(p)
        notifyLocation(target, except = p.id)
        viewLocked(p)
    }

    suspend fun take(account: Account, itemId: String): GameView = lock.withLock {
        val p = alive(player(account))
        if (itemId.startsWith("i.s.")) throw ApiException(HttpStatusCode.BadRequest, Errors.CANNOT_TAKE)
        val now = clock()
        val item = world.take(p.location, itemId, now) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_ITEM)
        try {
            db.tx { c -> addItem(c, p.id, item.id, item.count) }
        } catch (e: Exception) {
            world.restore(p.location, item)
            throw e
        }
        viewLocked(p)
    }

    suspend fun drop(account: Account, itemId: String): GameView = lock.withLock {
        val p = alive(player(account))
        val count = db.tx { c ->
            c.prepareStatement("DELETE FROM character_items WHERE character_id = ? AND item_id = ? RETURNING count").use { st ->
                st.setLong(1, p.id)
                st.setString(2, itemId)
                st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null }
            }
        } ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_INVENTORY)
        world.drop(p.location, itemId, count, clock())
        refreshStats(p)
        viewLocked(p)
    }

    /** Puts an item on; whatever occupied the same slot (or conflicts, shield vs bow) comes off. */
    suspend fun equip(account: Account, itemId: String): GameView = lock.withLock {
        val p = alive(player(account))
        val slot = Rules.equipSlot(itemId) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.CANNOT_EQUIP)
        db.tx { c ->
            val owned = inventoryRows(c, p.id)
            if (owned.none { it.first == itemId }) throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_INVENTORY)
            val off = owned.filter { (id, _, equipped) ->
                equipped && id != itemId && (Rules.equipSlot(id) == slot || Rules.conflicts(id, itemId))
            }.map { it.first }
            for (id in off) setEquipped(c, p.id, id, false)
            setEquipped(c, p.id, itemId, true)
        }
        refreshStats(p)
        viewLocked(p)
    }

    suspend fun unequip(account: Account, itemId: String): GameView = lock.withLock {
        val p = alive(player(account))
        db.tx { c ->
            if (!setEquipped(c, p.id, itemId, false)) throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_INVENTORY)
        }
        refreshStats(p)
        viewLocked(p)
    }

    /** Strikes an NPC here (f_attackf.dat): pause, blow, its free counter-blow, death. */
    suspend fun attack(account: Account, target: String): GameView = lock.withLock {
        val p = alive(player(account))
        val now = clock()
        val npc = world.npc(p.location, target) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_TARGET)
        if (!Rules.attackable(npc.key)) throw ApiException(HttpStatusCode.BadRequest, Errors.PEACEFUL)
        if (now < p.busyUntil) throw ApiException(HttpStatusCode.Conflict, Errors.RESTING)
        p.busyUntil = now + p.stats.delay
        if (p.stats.ammo.isNotEmpty() && !useAmmo(p)) {
            viewLocked(p)
            throw ApiException(HttpStatusCode.BadRequest, Errors.NO_AMMO)
        }
        playerHits(p, npc, now, answer = true)
        save(p)
        viewLocked(p)
    }

    suspend fun loot(account: Account, corpseId: String, itemId: String): GameView = lock.withLock {
        val p = alive(player(account))
        val count = world.lootCorpse(p.location, corpseId, itemId, clock())
            ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_CORPSE)
        db.tx { c -> addItem(c, p.id, itemId, count) }
        viewLocked(p)
    }

    /** Cuts meat and hides off a corpse; needs a knife (i.w.k.*) in the backpack (plugin/i.w.k.dat). */
    suspend fun butcher(account: Account, corpseId: String): GameView = lock.withLock {
        val p = alive(player(account))
        val hasKnife = db.tx { c -> inventoryRows(c, p.id) }.any { it.first.startsWith("i.w.k.") }
        if (!hasKnife) throw ApiException(HttpStatusCode.BadRequest, Errors.NEED_KNIFE)
        val loot = world.butcher(p.location, corpseId, clock())
            ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_CORPSE)
        db.tx { c -> for ((id, n) in loot) addItem(c, p.id, id, n) }
        if (loot.isNotEmpty()) p.log("Вы разделали: " + loot.entries.joinToString { (id, n) -> content.itemName(id) + if (n > 1) " ×$n" else "" })
        viewLocked(p)
    }

    /**
     * A ghost at a resurrection stone (i.s.res*) or a healer (n.h.*) comes
     * back to life with [Rules.RESURRECT_HP_PERCENT] of max HP — 0 as in the
     * old f_ressurect.dat; health comes back with regeneration.
     * f_ressurect.dat left 0 HP — any blow killed again).
     */
    suspend fun resurrect(account: Account): GameView = lock.withLock {
        val p = player(account)
        if (!p.ghost) throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_GHOST)
        if (!canResurrect(p)) throw ApiException(HttpStatusCode.BadRequest, Errors.NO_RESURRECTION_HERE)
        p.ghost = false
        p.hp = p.hpMax * Rules.RESURRECT_HP_PERCENT / 100
        p.regenFrom = clock()
        p.log("Вы воскресли.")
        save(p)
        viewLocked(p)
    }

    /** Puts a character somewhere without walking (tests; later teleports and quests). */
    internal suspend fun place(account: Account, location: String) = lock.withLock {
        val p = player(account)
        p.location = location
        save(p)
    }

    /** Kills a character outright (tests). */
    internal suspend fun kill(account: Account) = lock.withLock { killPlayer(player(account), "проверка", clock()) }

    /** Signals "your screen changed" for the WebSocket of this account's character. */
    suspend fun events(account: Account): SharedFlow<Unit> = lock.withLock { eventsOf(player(account).id) }

    // ---- dialogs ----------------------------------------------------------------------

    /**
     * Opens a topic of an NPC's dialog (f_speak.dat). Only "begin" or one of
     * the choices shown last may be asked for: no jumping to any topic by id,
     * as the old engine allowed with &id=.
     */
    suspend fun talk(account: Account, npcKey: String, topic: String, arg: String?): GameView = lock.withLock {
        val p = player(account)
        val now = clock()
        val npc = world.npc(p.location, npcKey) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_TARGET)
        val dialogId = if (npcKey.startsWith("n.g.")) "n.g.guard" else npcKey
        val d = content.logic
        if (!d.hasDialog(dialogId)) throw ApiException(HttpStatusCode.BadRequest, Errors.CANNOT_TALK)
        val typed = p.talkingTo == npcKey && topic == p.talkInput && !arg.isNullOrBlank()
        if (topic != "begin" && !typed && (p.talkingTo != npcKey || (topic to arg) !in p.talkChoices))
            throw ApiException(HttpStatusCode.Conflict, Errors.TOPIC_CLOSED)
        // Trade and bank choices open the trader's list or the bank cell instead of a line.
        if (topic in Content.ENGINE_TOPICS && !p.ghost && p.id !in npc.enemies) {
            p.talkingTo = npcKey
            p.talkChoices = emptySet()
            val base = viewLocked(p)
            return@withLock when (topic) {
                "tobank", "frombank" -> base.copy(bank = bankView(p, npc))
                else -> base.copy(shop = shopView(p, npc, dialogId, topic))
            }
        }
        val view = when {
            p.ghost && !npcKey.startsWith("n.h.") && !npcKey.startsWith("n.cap") ->
                DialogView(npcKey, npc.name, "Вы призрак и поэтому не можете ни с кем говорить, найдите лекаря или камень воскрешения.")
            p.id in npc.enemies ->
                DialogView(npcKey, npc.name, "Вы не можете разговаривать с ${npc.name}, т.к. он вас атакует.")
            else -> {
                val ctx = TalkCtx(p, npc, dialogId, arg, now).also { it.load() }
                arg?.let { ctx.vars["arg"] = it }
                val shown = showTopic(ctx, topic, 0)
                fun fill(t: String) = ctx.vars.entries.fold(Dialogs.plain(t).replace("<imja>", p.name)) { acc, (k, v) -> acc.replace("{$k}", v) }
                DialogView(npcKey, npc.name, fill(shown.first),
                    shown.second.map { it.copy(label = fill(it.label)) }.filter { it.label.isNotBlank() },
                    inputTopic = ctx.inputTopic)
            }
        }
        p.talkingTo = npcKey
        p.talkChoices = view.options.map { it.topic to it.arg }.toSet()
        p.talkInput = view.inputTopic
        save(p)
        viewLocked(p).copy(dialog = view)
    }

    /** What a conversation can see and change: the character, its things and quest state. */
    private inner class TalkCtx(val p: Player, val npc: World.Npc, val dialog: String, val arg: String?, val now: Long) {
        var topic = "begin"
        /** Values handlers put into texts and labels: {clan}, {clanStatus}… */
        val vars = HashMap<String, String>()
        /** Set by a handler that asks the player to type something. */
        var inputTopic: String? = null
        val inventory = HashMap<String, Int>()
        val equipped = ArrayList<String>()
        val flags = HashMap<String, String>()
        val playerTimers = HashMap<String, Long>()

        suspend fun load() {
            inventory.clear(); equipped.clear(); flags.clear(); playerTimers.clear()
            db.tx { c ->
                for ((id, n, on) in inventoryRows(c, p.id)) { inventory[id] = n; if (on) equipped += id }
                c.prepareStatement("SELECT key, value, until FROM character_state WHERE character_id = ? AND (until IS NULL OR until > ?)").use { st ->
                    st.setLong(1, p.id); st.setLong(2, now)
                    st.executeQuery().use { rs ->
                        while (rs.next()) {
                            val key = rs.getString(1)
                            if (key.startsWith(TIMER_PREFIX)) playerTimers[key.removePrefix(TIMER_PREFIX)] = rs.getLong(3)
                            else flags[key] = rs.getString(2)
                        }
                    }
                }
            }
        }

        fun count(id: String) = inventory[id] ?: 0

        /** A flag of the character, or of the whole world. */
        suspend fun flag(key: String, world: Boolean): String? =
            if (!world) flags[key] else db.tx { c ->
                c.prepareStatement("SELECT value FROM world_state WHERE key = ? AND (until IS NULL OR until > ?)").use { st ->
                    st.setString(1, key); st.setLong(2, now)
                    st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }

        /** Unix time the timer ends, or null if it is not running. */
        suspend fun timerEnd(key: String): Long? {
            val t = content.logic.timers[key] ?: return null
            val until = if (t.scope == "world") db.tx { c ->
                c.prepareStatement("SELECT until FROM world_state WHERE key = ?").use { st ->
                    st.setString(1, TIMER_PREFIX + key)
                    st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                }
            } else playerTimers[key]
            return until?.takeIf { it > now }
        }
    }

    /** Text and choices of a topic after its rules ran. */
    private suspend fun showTopic(ctx: TalkCtx, topic: String, depth: Int): Pair<String, List<DialogOption>> {
        ctx.topic = topic
        val d = content.logic
        val base = d.baseTopic(ctx.dialog, topic)
        val rules = d.logic[ctx.dialog]?.get(topic)
        var text: String
        var options: List<DialogOption> = base?.second ?: emptyList()
        when {
            rules != null -> {
                val rule = rules.firstOrNull { r -> r.conditions.all { cond(ctx, it) } && r.actions.all { guard(ctx, it) } }
                val baseText = base?.first?.takeUnless { it.startsWith("eval:") } ?: ""
                if (rule == null) text = baseText
                else {
                    val done = runActions(ctx, rule.actions)
                    if (done.failure != null) {
                        text = done.failure
                    } else {
                        text = listOfNotNull(done.prefix, rule.text ?: baseText).filter { it.isNotBlank() }.joinToString(" ")
                        text = listOfNotNull(text, done.extra).filter { it.isNotBlank() }.joinToString("\n")
                        if (rule.options != null) options = rule.options.filter { o -> o.conditions.all { cond(ctx, it) } }
                            .map { DialogOption(it.label, it.goto, it.arg) }
                        options = options.filter { it.topic !in rule.hide }
                        done.options?.let { options = it }
                        if (rule.goto != null && depth < 5) {
                            val next = showTopic(ctx, rule.goto, depth + 1)
                            text = listOf(text, next.first).filter { it.isNotBlank() }.joinToString("\n")
                            options = next.second
                        }
                    }
                }
            }
            base == null -> {
                if (topic == "end") return "" to emptyList()
                throw ApiException(HttpStatusCode.Conflict, Errors.TOPIC_CLOSED)
            }
            base.first.startsWith("eval:") -> text = UNTRANSLATED
            base.first.startsWith("skill|") -> {
                val parts = base.first.split('|')
                val t = teach(ctx, parts.getOrElse(1) { "" }, parts.getOrNull(2)?.toIntOrNull() ?: 0,
                    parts.getOrNull(3)?.toIntOrNull() ?: 0, parts.getOrNull(4)?.toIntOrNull() ?: 0)
                text = t.first
                options = t.second ?: emptyList()
            }
            else -> text = base.first
        }
        for (key in Regex("\\{left:([^}]+)\\}").findAll(text).map { it.groupValues[1] }.toSet()) {
            val end = ctx.timerEnd(key) ?: ctx.now
            text = text.replace("{left:$key}", ((end - ctx.now + 59) / 60).toString())
        }
        text = text.replace("{arg}", ctx.arg ?: "")
        // Option labels that are still PHP are not shown; nor Uin's accidental bank.
        return text to options.filter { it.label.isNotBlank() && !it.label.startsWith("eval:") &&
            !(ctx.dialog == "n.beginner" && it.topic in Content.ENGINE_TOPICS) }
    }

    private suspend fun cond(ctx: TalkCtx, c: JsonObject): Boolean {
        val p = ctx.p
        fun n(key: String, default: Int = 1) = c.int(key) ?: default
        return when {
            "has" in c -> ctx.count(c.str("has")!!) >= n("count")
            "lacks" in c -> ctx.count(c.str("lacks")!!) < n("count")
            "money" in c -> ctx.count(Rules.MONEY) >= n("money", 0)
            "equipped" in c -> ctx.equipped.any { it.startsWith(c.str("equipped")!!) }
            "skill" in c -> {
                val v = p.skill(c.str("skill")!!)
                (c.int("min")?.let { v >= it } ?: true) && (c.int("max")?.let { v <= it } ?: true)
            }
            "newbie" in c -> (p.skills().sumExceptExp() == 5) == Dialogs.truthy(c["newbie"])
            "ready" in c -> ctx.timerEnd(c.str("ready")!!) == null
            "waiting" in c -> ctx.timerEnd(c.str("waiting")!!) != null
            "flag" in c -> ctx.flag(c.str("flag")!!, Dialogs.truthy(c["world"]) && "world" in c)?.let { v -> c.str("value")?.let { it == v } ?: true } ?: false
            "noflag" in c -> ctx.flag(c.str("noflag")!!, Dialogs.truthy(c["world"]) && "world" in c) == null
            "known" in c -> c.str("known")!! in p.known
            "unknown" in c -> c.str("unknown")!! !in p.known
            "here" in c -> world.hasFixture(p.location, c.str("here")!!, exact = true)
            "notHere" in c -> !world.hasFixture(p.location, c.str("notHere")!!, exact = true)
            "npcAt" in c -> world.npc(c.str("location") ?: p.location, c.str("npcAt")!!) != null
            "noNpcAt" in c -> world.npc(c.str("location") ?: p.location, c.str("noNpcAt")!!) == null
            "arg" in c -> (c["arg"] as? kotlinx.serialization.json.JsonArray).orEmpty()
                .any { (it as? kotlinx.serialization.json.JsonPrimitive)?.content == ctx.arg }
            "chance" in c -> rnd.nextInt(100) < n("chance", 0)
            "sex" in c -> p.sex == c.str("sex")
            "ghost" in c -> p.ghost == Dialogs.truthy(c["ghost"])
            "any" in c -> (c["any"] as? kotlinx.serialization.json.JsonArray).orEmpty().any { cond(ctx, it.jsonObject) }
            "not" in c -> !cond(ctx, c["not"]!!.jsonObject)
            else -> false
        }
    }

    /**
     * The check part of a handler: a rule whose guard fails is skipped and
     * the next rule is tried, like a condition.
     */
    private suspend fun guard(ctx: TalkCtx, a: JsonObject): Boolean = when (a.str("handler")) {
        // Killing players comes with PvP; until then nobody has.
        "require-pk" -> false
        "npc-hand-over" -> world.npcHas(a.str("npc")!!, a.str("location") ?: ctx.p.location, a.str("item")!!)
        else -> true
    }

    /** Handlers implemented in code (Dialogs.HANDLERS); returns text to put before the NPC's line. */
    internal class HandlerFailed(val text: String) : Exception(text)

    private suspend fun handler(ctx: TalkCtx, a: JsonObject): String? {
        val p = ctx.p
        when (a.str("handler")) {
            "arena-count" -> {
                val n = players.values.count { it.location == "arena" && ctx.now - it.lastSeen < ACTIVE_SECONDS }
                return if (n == 0) "Сейчас на арене никого нет." else "Сейчас на арене $n человек."
            }
            "hide-item-random" -> {
                val places = content.locations.keys.filter { id ->
                    !id.startsWith("z.") && !id.startsWith("c.") && !id.startsWith("arena") && !id.startsWith("qv") && id != Protocol.START_LOCATION
                }
                if (places.isNotEmpty()) world.placePermanent(places[rnd.nextInt(places.size)], a.str("item")!!, 1)
            }
            "repair-boat" -> {
                world.removeItem(p.location, a.str("from")!!)
                world.placePermanent(p.location, a.str("to")!!, 1)
            }
            "lower-int" -> { p.int = (p.int - 1).coerceAtLeast(1); refreshStats(p) }
            "clan-status", "clan-leave", "clan-name-input", "clan-create", "clan-restore" -> return clanHandler(ctx.p, a, ctx.vars) { ctx.inputTopic = it }
            "npc-hand-over" -> {
                val item = a.str("item")!!
                if (world.takeFromNpc(a.str("npc")!!, a.str("location") ?: p.location, item)) {
                    changeItem(p, item, 1); ctx.inventory.merge(item, 1, Int::plus)
                }
            }
        }
        return null
    }

    private class Done(val failure: String? = null, val extra: String? = null, val options: List<DialogOption>? = null, val prefix: String? = null)

    /**
     * Runs a rule's actions. Everything a rule takes is checked first: if
     * something is missing nothing happens (the old code often gave the
     * reward anyway).
     */
    private suspend fun runActions(ctx: TalkCtx, actions: List<JsonObject>): Done {
        val p = ctx.p
        val need = HashMap<String, Int>()
        for (a in actions) a.str("take")?.let { need[it] = (need[it] ?: 0) + (a.int("count") ?: 1) }
        val missing = need.filter { (id, n) -> ctx.count(id) < n }.keys
        if (missing.isNotEmpty()) return Done(failure = "У вас нет: " + missing.joinToString { content.itemName(it) })
        if (actions.any { a -> a.str("handler")?.let { it !in Dialogs.HANDLERS } == true }) return Done(failure = UNTRANSLATED)

        val extra = ArrayList<String>()
        val prefix = ArrayList<String>()
        var options: List<DialogOption>? = null
        var statsChanged = false
        for (a in actions) {
            val count = a.int("count") ?: 1
            when {
                "take" in a -> { changeItem(p, a.str("take")!!, -count); ctx.inventory.merge(a.str("take")!!, -count, Int::plus); statsChanged = true }
                "give" in a -> { changeItem(p, a.str("give")!!, count); ctx.inventory.merge(a.str("give")!!, count, Int::plus) }
                "exp" in a -> addExp(p, a.int("exp") ?: 0)
                "start" in a -> {
                    val key = a.str("start")!!
                    val t = content.logic.timers.getValue(key)
                    val until = ctx.now + if (t.max > t.min) rnd.nextInt(t.min, t.max + 1) else t.min
                    if (t.scope == "world") setWorldState(TIMER_PREFIX + key, "", until)
                    else { setState(p.id, TIMER_PREFIX + key, "", until); ctx.playerTimers[key] = until }
                }
                "stop" in a -> {
                    val key = a.str("stop")!!
                    if (content.logic.timers[key]?.scope == "world") clearWorldState(TIMER_PREFIX + key)
                    else { clearState(p.id, TIMER_PREFIX + key); ctx.playerTimers.remove(key) }
                }
                "set" in a -> {
                    val value = a.str("value") ?: "1"
                    val until = a.int("for")?.let { ctx.now + it }
                    if ("world" in a && Dialogs.truthy(a["world"])) setWorldState(a.str("set")!!, value, until)
                    else { setState(p.id, a.str("set")!!, value, until); ctx.flags[a.str("set")!!] = value }
                }
                "clear" in a -> {
                    if ("world" in a && Dialogs.truthy(a["world"])) clearWorldState(a.str("clear")!!)
                    else { clearState(p.id, a.str("clear")!!); ctx.flags.remove(a.str("clear")!!) }
                }
                "giveNpc" in a -> world.giveNpc(a.str("npc")!!, a.str("location") ?: p.location, a.str("giveNpc")!!, count)
                "learn" in a -> learn(p, a.str("learn")!!)
                "teach" in a -> {
                    val t = teach(ctx, a.str("teach")!!, a.int("cost") ?: 0, a.int("min") ?: 0, a.int("max") ?: 0)
                    extra += t.first
                    options = t.second
                }
                "teleport" in a -> {
                    p.location = a.str("teleport")!!
                    save(p)
                    notifyLocation(p.location, except = p.id)
                }
                "spawn" in a -> {
                    val template = a.str("spawn")!!
                    world.spawn(template, a.str("key") ?: template, a.str("location") ?: p.location, ctx.now)
                }
                "remove" in a -> world.removeNpc(a.str("remove")!!, a.str("location") ?: p.location)
                "place" in a -> world.drop(a.str("location") ?: p.location, a.str("place")!!, count, ctx.now)
                "removeHere" in a -> world.removeItem(p.location, a.str("removeHere")!!)
                "resurrect" in a -> if (p.ghost) {
                    p.ghost = false
                    p.hp = p.hpMax * Rules.RESURRECT_HP_PERCENT / 100
                    p.regenFrom = ctx.now
                    p.log("Вы воскресли.")
                    save(p)
                }
                "heal" in a -> {
                    p.hp = if (a.str("heal") == "full") p.hpMax else (p.hp + (a.int("heal") ?: 0)).coerceAtMost(p.hpMax)
                    save(p)
                }
                "say" in a -> content.logic.jokes[a.str("say")!!]?.takeIf { it.isNotEmpty() }?.let { extra += it[rnd.nextInt(it.size)] }
                "journal" in a -> p.log(a.str("journal")!!)
                "handler" in a -> try {
                    handler(ctx, a)?.let { prefix += it }
                } catch (f: HandlerFailed) {
                    return Done(failure = f.text)
                }
            }
        }
        if (statsChanged) refreshStats(p)
        save(p)
        return Done(extra = extra.joinToString("\n").takeIf { it.isNotBlank() }, options = options,
            prefix = prefix.joinToString(" ").takeIf { it.isNotBlank() })
    }

    /**
     * A teacher (f_speakskillup.dat, docs/mechanics-progression.md §3.4):
     * attributes and skills for a skill point (free while the character has
     * only its starting points), spells and techniques for money.
     * [ctx.arg] is the attribute or skill chosen to lower when at the limit.
     */
    private suspend fun teach(ctx: TalkCtx, what: String, cost: Int, min: Int, max: Int): Pair<String, List<DialogOption>?> {
        val p = ctx.p
        if (what.startsWith("m.") || what.startsWith("p.")) {
            val spell = what.startsWith("m.")
            if (what in p.known) return (if (spell) "У вас уже есть это заклинание" else "Вы уже знаете этот прием") to null
            val magic = p.skill("magic")
            if (spell && min > 0 && magic < min) return "У вас недостаточный навык магии (надо минимум $min)" to null
            if (spell && max > 0 && magic > max) return "У вас слишком высокий навык магии (максимум $max)" to null
            if (cost > 0) {
                if (ctx.count(Rules.MONEY) < cost) return "У вас недостаточно денег (надо $cost монет)" to null
                changeItem(p, Rules.MONEY, -cost)
            }
            learn(p, what)
            return (if (spell) "Вы выучили новое заклинание!" else "Вы выучили новый прием!") to null
        }
        if (Rules.SKILLS.none { it.first == what }) return "Этому здесь не учат." to null
        if (p.points < 1) return "Недостаточно очков опыта" to null
        val current = p.skill(what)
        if (min > 0 && current < min) return "Вы должны иметь уровень навыка не ниже $min" to null
        if (max > 0 && current > max) return "Вы и так достаточно опытны, я учу только до уровня ${max + 1}" to null
        val attribute = what in Rules.ATTRIBUTES
        val down = ctx.arg?.takeIf { it.isNotEmpty() }
        if (attribute) {
            if (current >= Rules.ATTR_MAX) return "Невозможно повысить, т.к. аттрибут уже на максимальном уровне ${Rules.ATTR_MAX}" to null
            if (down != null && down !in Rules.ATTRIBUTES) return "Неверный аттрибут" to null
            if (down != null && p.skill(down) <= 1) return "Невозможно понизить, т.к. аттрибут уже на минимальном уровне 1, выберите другой" to null
            if (down == null && p.str + p.dex + p.int >= Rules.ATTR_SUM) {
                return "Превышен предел суммы очков (${Rules.ATTR_SUM}) для аттрибутов, выберите что уменьшить:" to
                    Rules.ATTRIBUTES.filter { it != what }.map { DialogOption("${Rules.skillTitle(it)}: ${p.skill(it)}", ctx.topic, it) }
            }
        } else {
            if (current >= Rules.SKILL_MAX) return "Невозможно повысить, т.к. навык уже на максимальном уровне ${Rules.SKILL_MAX}" to null
            if (down != null && (down in Rules.ATTRIBUTES || Rules.SKILLS.none { it.first == down })) return "Неверный навык" to null
            if (down != null && p.skill(down) <= 0) return "Невозможно понизить, т.к. навык уже на минимальном уровне 0, выберите другой" to null
            if (down == null && p.other.values.sum() >= Rules.SKILL_SUM) {
                return "Превышен предел суммы очков (${Rules.SKILL_SUM}) для навыков, выберите что уменьшить:" to
                    Rules.SKILLS.filter { it.first !in Rules.ATTRIBUTES && it.first != what && p.skill(it.first) > 0 }
                        .map { DialogOption("${it.third}: ${p.skill(it.first)}", ctx.topic, it.first) }
            }
        }
        var text = ""
        if (cost > 0) {
            if (p.skills().sumExceptExp() == 5) text = "Ладно, так уж и быть, раз ты новичок, то я это сделаю бесплатно.\n"
            else {
                if (ctx.count(Rules.MONEY) < cost) return "У вас недостаточно денег (надо $cost монет)" to null
                changeItem(p, Rules.MONEY, -cost)
                ctx.inventory.merge(Rules.MONEY, -cost, Int::plus)
            }
        }
        setSkill(p, what, current + 1)
        if (down != null) setSkill(p, down, p.skill(down) - 1)
        p.points -= 1
        refreshStats(p)
        save(p)
        text += Rules.skillTitle(what) + ": +1"
        if (down != null) text += "\n" + Rules.skillTitle(down) + ": -1"
        return text to null
    }

    private fun setSkill(p: Player, key: String, v: Int) {
        when (key) {
            "str" -> p.str = v
            "dex" -> p.dex = v
            "int" -> p.int = v
            else -> p.other[key] = v
        }
    }

    internal suspend fun learn(p: Player, id: String) {
        if (!p.known.add(id)) return
        db.tx { c ->
            c.prepareStatement("INSERT INTO character_known (character_id, id) VALUES (?, ?) ON CONFLICT DO NOTHING").use { st ->
                st.setLong(1, p.id); st.setString(2, id); st.executeUpdate()
            }
        }
    }

    /** Adds (count > 0) or removes items; a stack that reaches 0 is deleted. */
    internal suspend fun changeItem(p: Player, itemId: String, count: Int) = db.tx { c ->
        if (count > 0) addItem(c, p.id, itemId, count)
        else if (count < 0) {
            // The last ones: delete the row (count must stay > 0), otherwise decrease.
            val deleted = c.prepareStatement("DELETE FROM character_items WHERE character_id = ? AND item_id = ? AND count <= ?").use { st ->
                st.setLong(1, p.id); st.setString(2, itemId); st.setInt(3, -count); st.executeUpdate()
            }
            if (deleted == 0) c.prepareStatement("UPDATE character_items SET count = count + ? WHERE character_id = ? AND item_id = ?").use { st ->
                st.setInt(1, count); st.setLong(2, p.id); st.setString(3, itemId); st.executeUpdate()
            }
        }
    }

    internal suspend fun setState(characterId: Long, key: String, value: String, until: Long?) = db.tx { c ->
        c.prepareStatement(
            "INSERT INTO character_state (character_id, key, value, until) VALUES (?, ?, ?, ?) " +
                "ON CONFLICT (character_id, key) DO UPDATE SET value = EXCLUDED.value, until = EXCLUDED.until"
        ).use { st ->
            st.setLong(1, characterId); st.setString(2, key); st.setString(3, value)
            if (until == null) st.setNull(4, java.sql.Types.BIGINT) else st.setLong(4, until)
            st.executeUpdate()
        }
    }

    internal suspend fun clearState(characterId: Long, key: String) = db.tx { c ->
        c.prepareStatement("DELETE FROM character_state WHERE character_id = ? AND key = ?").use { st ->
            st.setLong(1, characterId); st.setString(2, key); st.executeUpdate()
        }
    }

    private suspend fun setWorldState(key: String, value: String, until: Long?) = db.tx { c ->
        c.prepareStatement(
            "INSERT INTO world_state (key, value, until) VALUES (?, ?, ?) ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, until = EXCLUDED.until"
        ).use { st ->
            st.setString(1, key); st.setString(2, value)
            if (until == null) st.setNull(3, java.sql.Types.BIGINT) else st.setLong(3, until)
            st.executeUpdate()
        }
    }

    private suspend fun clearWorldState(key: String) = db.tx { c ->
        c.prepareStatement("DELETE FROM world_state WHERE key = ?").use { st -> st.setString(1, key); st.executeUpdate() }
    }

    // ---- world clock ------------------------------------------------------------------

    /**
     * One second of the world (g.php doai()): timers and wandering, then in
     * every location with active players: regeneration, monsters choosing
     * and attacking targets, fleeing NPCs.
     */
    suspend fun tick(now: Long) = lock.withLock {
        world.tick(now)
        val active = players.values.filter { now - it.lastSeen < ACTIVE_SECONDS }
        for (p in active) if (!p.ghost) regen(p, now)
        for ((loc, here) in active.groupBy { it.location }) {
            val living = here.filter { !it.ghost }
            for (npc in world.npcsIn(loc)) {
                regenNpc(npc, now)
                // Enemies who left or died are forgotten (no chasing yet, f_goto.dat).
                npc.enemies.retainAll(living.filter { !it.ghost }.map { it.id }.toSet())
                if (npc.enemies.isEmpty() && npc.aggressive && living.isNotEmpty()) {
                    npc.enemies += living[rnd.nextInt(living.size)].id
                }
                // Blows go round all its enemies in turn.
                val victimId = npc.enemies.firstOrNull() ?: continue
                val victim = living.firstOrNull { it.id == victimId } ?: continue
                if (now < npc.busyUntil || npc.hp < 1) continue
                if (npc.hp < npc.proto.hpMax / 4 && dice.roll(0, 100) < 50 && world.flee(npc)) {
                    npc.enemies.clear()
                    for (q in here) { q.log("${npc.name} убегает."); notify(q.id) }
                    continue
                }
                npc.busyUntil = now + npc.stats.delay
                npc.enemies.remove(victimId); npc.enemies.add(victimId)
                npcHits(npc, victim, now, answer = true)
                save(victim)
            }
        }
    }

    // ---- fighting -------------------------------------------------------------------

    private fun describe(h: Formulas.Hit, verb: String): String = when (h.outcome) {
        Formulas.Outcome.MISS, Formulas.Outcome.FIZZLED -> "мимо"
        Formulas.Outcome.DODGED -> "мимо (уклон)"
        Formulas.Outcome.HIT -> buildString {
            if (h.crit) append("критически ")
            append(verb).append(' ').append(h.damage)
            if (h.shield > 0) append(" (щит ${h.shield})")
            if (h.resisted > 0) append(" (сопр. магии ${h.resisted})")
        }
    }

    private suspend fun playerHits(p: Player, npc: World.Npc, now: Long, answer: Boolean) {
        val h = Formulas.attack(p.stats, npc.stats, dice)
        if (h.outcome == Formulas.Outcome.FIZZLED) return
        val text = describe(h, p.stats.verb)
        p.log(if (answer) "Вы по ${npc.name} $text" else "  вы отвечаете: $text")
        tellOthers(p.location, p.id, if (answer) "${p.name} по ${npc.name} $text" else "${p.name} отвечает: $text")
        if (h.outcome == Formulas.Outcome.HIT) {
            npc.hp -= h.damage
            npc.regenFrom = now
        }
        // Everyone who strikes an NPC becomes its enemy; it answers them all.
        npc.enemies += p.id
        if (npc.hp < 1) {
            killNpc(p, npc, now)
            return
        }
        // Free counter-blow of a target that is not resting (f_attackf.dat:171).
        if (answer && now >= npc.busyUntil) npcHits(npc, p, now, answer = false)
    }

    private suspend fun npcHits(npc: World.Npc, p: Player, now: Long, answer: Boolean) {
        val h = Formulas.attack(npc.stats, p.stats, dice)
        if (h.outcome == Formulas.Outcome.FIZZLED) return
        val text = describe(h, npc.stats.verb)
        p.log(if (answer) "${npc.name} по вам $text" else "  ${npc.name} отвечает: $text")
        tellOthers(p.location, p.id, "${npc.name} по ${p.name} $text")
        notify(p.id)
        if (h.outcome == Formulas.Outcome.HIT) {
            p.hp -= h.damage
            p.regenFrom = now
        }
        // Only a blow that lands kills: just resurrected at 0 HP, a miss leaves the character alive.
        if (h.outcome == Formulas.Outcome.HIT && p.hp < 1) {
            killPlayer(p, "${npc.name}", now)
            return
        }
        // The player answers too, if not resting (the old engine did this for players as well).
        if (answer && now >= p.busyUntil && !p.ghost) playerHits(p, npc, now, answer = false)
    }

    private suspend fun killNpc(p: Player, npc: World.Npc, now: Long) {
        world.kill(npc, now)
        p.log("${npc.name} погибает.")
        tellOthers(p.location, p.id, "${npc.name} погибает.")
        addExp(p, npc.stats.expValue)
    }

    /** f_addexp.dat: over the threshold the experience turns into one skill point (the rest burns). */
    internal suspend fun addExp(p: Player, gained: Int) {
        if (gained <= 0) return
        p.exp += gained
        p.log("Опыт +$gained")
        if (p.exp > Formulas.expThreshold(p.skills())) {
            p.exp = 0
            p.points += 1
            p.log("Вы получили очко опыта! Потратить его можно у учителей.")
            refreshStats(p)
        }
    }

    /** Death (f_kill.dat): everything carried falls into a corpse, the character becomes a ghost. */
    private suspend fun killPlayer(p: Player, killer: String, now: Long) {
        p.hp = 0
        p.ghost = true
        val items = db.tx { c ->
            val rows = inventoryRows(c, p.id)
            c.prepareStatement("DELETE FROM character_items WHERE character_id = ?").use { it.setLong(1, p.id); it.executeUpdate() }
            rows.associate { it.first to it.second }
        }
        world.addCorpse(p.location, "труп: ${p.name}", items, now, p.id)
        p.equipped = emptyList()
        p.stats = Formulas.player(p.skills(), emptyList(), { content.items[it] })
        p.log("Вас убил $killer. Вы призрак; ваши вещи остались в трупе на 10 минут.")
        tellOthers(p.location, p.id, "${p.name} погибает.")
        for (npc in world.npcsIn(p.location)) npc.enemies.remove(p.id)
        save(p)
        notify(p.id)
    }

    private suspend fun useAmmo(p: Player): Boolean = db.tx { c ->
        val left = c.prepareStatement(
            "UPDATE character_items SET count = count - 1 WHERE character_id = ? AND item_id = ? AND count > 0 RETURNING count"
        ).use { st ->
            st.setLong(1, p.id)
            st.setString(2, p.stats.ammo)
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null }
        } ?: return@tx false
        if (left == 0) c.prepareStatement("DELETE FROM character_items WHERE character_id = ? AND item_id = ?").use { st ->
            st.setLong(1, p.id); st.setString(2, p.stats.ammo); st.executeUpdate()
        }
        true
    }

    private fun regen(p: Player, now: Long) {
        if (p.hp >= p.hpMax && p.mana >= p.manaMax) { p.regenFrom = now; return }
        val since = now - p.regenFrom
        val add = Formulas.regen(since)
        if (add <= 0) return
        p.hp = (p.hp + add).coerceAtMost(p.hpMax)
        p.mana = (p.mana + add).coerceAtMost(p.manaMax)
        p.regenFrom = now
        dirty += p.id
    }

    private fun regenNpc(npc: World.Npc, now: Long) {
        if (npc.hp >= npc.proto.hpMax) { npc.regenFrom = now; return }
        val add = Formulas.regen(now - npc.regenFrom)
        if (add <= 0) return
        npc.hp = (npc.hp + add).coerceAtMost(npc.proto.hpMax)
        npc.regenFrom = now
    }

    private val dirty = HashSet<Long>()

    /** Writes regeneration to the database now and then, not every second. */
    suspend fun flush() = lock.withLock {
        val ids = dirty.toList()
        dirty.clear()
        for (id in ids) players[id]?.let { save(it) }
    }

    private suspend fun canResurrect(p: Player): Boolean =
        p.ghost && (world.hasFixture(p.location, "i.s.res") || world.npcsIn(p.location).any { it.key.startsWith("n.h.") })

    // ---- players --------------------------------------------------------------------

    internal fun alive(p: Player): Player {
        if (p.ghost) throw ApiException(HttpStatusCode.Conflict, Errors.GHOST)
        return p
    }

    internal suspend fun player(account: Account): Player {
        val now = clock()
        val cached = byAccount[account.id]?.let { players[it] }
        val p = cached ?: load(account) ?: throw ApiException(HttpStatusCode.Conflict, Errors.NO_CHARACTER)
        p.lastSeen = now
        return p
    }

    private suspend fun load(account: Account): Player? {
        val p = db.tx { c ->
            c.prepareStatement(
                "SELECT id, name, sex, location, hp, mana, ghost, str, dex, intel, exp, skill_points, skills::text AS skills FROM characters " +
                    "WHERE account_id = ? AND world_id = 1"
            ).use { st ->
                st.setLong(1, account.id)
                st.executeQuery().use { rs ->
                    if (!rs.next()) null else Player(
                        rs.getLong("id"), account.id, rs.getString("name"), rs.getString("sex"), rs.getString("location"),
                        rs.getInt("hp"), rs.getInt("mana"), rs.getBoolean("ghost"),
                        rs.getInt("str"), rs.getInt("dex"), rs.getInt("intel"), rs.getInt("exp"), rs.getInt("skill_points"),
                    ).also { p ->
                        for ((k, v) in kotlinx.serialization.json.Json.parseToJsonElement(rs.getString("skills")).jsonObject) {
                            (v as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull?.let { p.other[k] = it }
                        }
                    }
                }
            }
        } ?: return null
        p.known += db.tx { c ->
            c.prepareStatement("SELECT id FROM character_known WHERE character_id = ?").use { st ->
                st.setLong(1, p.id)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }
        loadClan(p)
        p.regenFrom = clock()
        refreshStats(p)
        players[p.id] = p
        byAccount[account.id] = p.id
        return p
    }

    internal suspend fun refreshStats(p: Player) {
        p.equipped = db.tx { c -> inventoryRows(c, p.id) }.filter { it.third }.map { it.first }
        p.stats = Formulas.player(p.skills(), p.equipped, { content.items[it] })
        p.hp = p.hp.coerceAtMost(p.hpMax)
        p.mana = p.mana.coerceAtMost(p.manaMax)
    }

    internal suspend fun save(p: Player) = db.tx { c ->
        c.prepareStatement(
            "UPDATE characters SET location = ?, hp = ?, mana = ?, ghost = ?, exp = ?, skill_points = ?, " +
                "str = ?, dex = ?, intel = ?, skills = ?::jsonb WHERE id = ?"
        ).use { st ->
            st.setString(1, p.location)
            st.setInt(2, p.hp.coerceAtLeast(0))
            st.setInt(3, p.mana.coerceAtLeast(0))
            st.setBoolean(4, p.ghost)
            st.setInt(5, p.exp)
            st.setInt(6, p.points)
            st.setInt(7, p.str)
            st.setInt(8, p.dex)
            st.setInt(9, p.int)
            st.setString(10, kotlinx.serialization.json.JsonObject(p.other.filterValues { it != 0 }.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString())
            st.setLong(11, p.id)
            st.executeUpdate()
        }
    }

    private fun eventsOf(id: Long) = events.getOrPut(id) {
        MutableSharedFlow(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }

    internal fun notify(id: Long) {
        events[id]?.tryEmit(Unit)
    }

    internal fun notifyLocation(loc: String, except: Long) {
        val now = clock()
        for (q in players.values) if (q.id != except && q.location == loc && now - q.lastSeen < ACTIVE_SECONDS) notify(q.id)
    }

    internal fun tellOthers(loc: String, except: Long, line: String) {
        val now = clock()
        for (q in players.values) if (q.id != except && q.location == loc && now - q.lastSeen < ACTIVE_SECONDS) {
            q.log(line)
            notify(q.id)
        }
    }

    internal suspend fun viewLocked(p: Player): GameView {
        val now = clock()
        val loc = content.locations[p.location] ?: content.locations.getValue(Protocol.START_LOCATION)
        val here = players.values
            .filter { it.id != p.id && it.location == loc.id && now - it.lastSeen < ACTIVE_SECONDS }
            .sortedBy { it.name }
        val others = here.map { it.name + (it.clanName?.let { c -> " *$c*" } ?: "") + if (it.ghost) " (призрак)" else "" }
        val npcs = world.npcsIn(loc.id).map { NpcView(it.key, it.name, it.hp, it.proto.hpMax, p.id in it.enemies, Rules.attackable(it.key), content.logic.hasDialog(if (it.key.startsWith("n.g.")) "n.g.guard" else it.key)) }
        val location = loc.view().copy(
            npcs = npcs,
            items = world.itemsAt(loc.id, now),
            players = others,
            corpses = world.corpsesAt(loc.id, now),
        )
        val inventory = db.tx { c -> inventoryRows(c, p.id) }.map { (id, count, equipped) ->
            InventoryItemView(id, content.itemName(id), count, equipped, Rules.equipSlot(id) != null,
                content.crafting.find(id) != null, content.crafting.targetOf(id))
        }
        val s = p.stats
        val character = CharacterView(
            id = p.id, name = p.name, sex = p.sex, location = p.location,
            hp = p.hp.coerceAtLeast(0), hpMax = p.hpMax, mana = p.mana.coerceAtLeast(0), manaMax = p.manaMax,
            str = p.str, dex = p.dex, int = p.int, skillPoints = p.points,
            skills = p.other.filterValues { it > 0 }.toSortedMap(), known = p.known.sorted(),
            ghost = p.ghost, exp = p.exp, expNext = Formulas.expThreshold(p.skills()),
            hit = s.hit, dmgMin = s.dmgMin, dmgMax = s.dmgMax, armor = s.armor, dodge = s.dodge,
        )
        return GameView(
            character, location, inventory,
            journal = p.journal.toList(),
            restSeconds = (p.busyUntil - now).coerceAtLeast(0).toInt(),
            canResurrect = canResurrect(p),
            exchange = exchangeView(p),
            unread = unreadCount(p),
            clanInvites = clanInvites(p),
            people = here.map { PersonView(it.name, it.clanName, it.ghost) },
            clan = p.clanName,
        )
    }

    // ---- inventory rows ----------------------------------------------------------------

    internal fun addItem(c: Connection, characterId: Long, itemId: String, count: Int) {
        c.prepareStatement(
            "INSERT INTO character_items (character_id, item_id, count) VALUES (?, ?, ?) " +
                "ON CONFLICT (character_id, item_id) DO UPDATE SET count = character_items.count + EXCLUDED.count"
        ).use { st ->
            st.setLong(1, characterId)
            st.setString(2, itemId)
            st.setInt(3, count)
            st.executeUpdate()
        }
    }

    internal fun inventoryRows(c: Connection, characterId: Long): List<Triple<String, Int, Boolean>> =
        c.prepareStatement("SELECT item_id, count, equipped FROM character_items WHERE character_id = ? ORDER BY equipped DESC, item_id").use { st ->
            st.setLong(1, characterId)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(Triple(rs.getString(1), rs.getInt(2), rs.getBoolean(3))) }
            }
        }

    private fun setEquipped(c: Connection, characterId: Long, itemId: String, equipped: Boolean): Boolean =
        c.prepareStatement("UPDATE character_items SET equipped = ? WHERE character_id = ? AND item_id = ?").use { st ->
            st.setBoolean(1, equipped)
            st.setLong(2, characterId)
            st.setString(3, itemId)
            st.executeUpdate() == 1
        }

    companion object {
        /** Characters active in the last 10 minutes are shown and can be attacked by monsters. */
        const val ACTIVE_SECONDS = 600
        const val JOURNAL_SIZE = 30
        const val TIMER_PREFIX = "timer:"
        const val UNTRANSLATED = "Этот разговор пока не перенесён в новую версию игры."
    }
}
