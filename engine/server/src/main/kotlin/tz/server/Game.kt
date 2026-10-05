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
import tz.shared.CraftSkillView
import tz.shared.TeachOfferView
import tz.shared.DialogOption
import tz.shared.DialogView
import tz.shared.PersonView
import tz.shared.Errors
import tz.shared.GameView
import tz.shared.InventoryItemView
import tz.shared.JournalKind
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
/** The real clock in seconds; [Game.clockMs] then reads real milliseconds. */
val SYSTEM_SECONDS: () -> Long = { System.currentTimeMillis() / 1000 }

class Game(
    internal val content: Content,
    internal val db: Db,
    internal val accounts: Accounts,
    val world: World,
    internal val clock: () -> Long = SYSTEM_SECONDS,
    random: Random = Random.Default,
    /** Milliseconds for combat pauses (1–1.5 s); tests that drive [clock] get its seconds × 1000. */
    internal val clockMs: () -> Long = { if (clock === SYSTEM_SECONDS) System.currentTimeMillis() else clock() * 1000 },
) {
    /** Balance constants and formulas (content/logic/balance.json). */
    internal val balance get() = content.balance
    internal val dice = Dice.of(random)
    internal val rnd = random
    internal val lock = Mutex()

    class Player(
        val id: Long,
        val accountId: Long,
        val name: String,
        val sex: String,
        location: String,
        var hp: Int,
        var mana: Int,
        var ghost: Boolean,
        var str: Int,
        var dex: Int,
        var int: Int,
        /** All experience ever gained; the level is counted from it (it keeps growing past the top level). */
        var exp: Long,
        /** Training points not spent yet. */
        var points: Int,
    ) {
        var equipped: List<String> = emptyList()
        var level = 1
        var stats: Stats = Stats(1, 1, 3, 1000, false, 0, 0, 0, 0, 0, "кулаками", 0, "", hpMax = 25, manaMax = 20)
        /** Free to act again from this moment, milliseconds. */
        var busyUntil = 0L
        /** Ignite and poison burning on this character; chilled (slower blows) till then, ms. */
        val dots = ArrayList<Dot>()
        var chilledUntil = 0L
        /** Parts of a point of health and mana come back between ticks. */
        var regenHp = 0.0
        var regenMana = 0.0
        var regenLast = 0L
        var regenFrom = 0L
        var lastSeen = 0L
        /** Lines logged in all, and how many were logged before arriving here: the place shows only its own. */
        var logged = 0L
        var hereFrom = 0L
        var location: String = location
            set(v) { if (v != field) hereFrom = logged; field = v }
        val journal = ArrayDeque<String>()
        val journalKinds = ArrayDeque<String>()

        /** Combat buttons and belt chosen by the player (character_state "ui.slots", "ui.belt"); null — the default. */
        var slots: List<String>? = null
        var belt: List<String>? = null

        /** Skills other than the attributes, by key of [Rules.SKILLS]. */
        val other = HashMap<String, Int>()
        /** Practice gathered toward the next step of each craft (character_craft). */
        val practice = HashMap<String, Int>()
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
        /** Criminal title and when it ends (character_state "crime"). */
        var crime: String? = null
        var crimeUntil = 0L
        /** Faction on the Wolf island: "t" templars, "p" pirates (character_state "faction"). */
        var faction: String? = null
        /** The character last struck by this one: striking back is self-defence. */
        var fightingPlayer: Long? = null
        /** The last blow between this character and another one, either way: when and with whom (no leaving mid-fight). */
        var pvpAt = 0L
        var pvpWith: Long? = null
        /** Last thing said, to refuse repeats (f_say.dat:65). */
        var lastSaid: String? = null
        /** Statistics, the old `st` string (Skills.kt, Stat). */
        val statistics = IntArray(Stat.SIZE)
        /** Whose backpack was peeked into, till when stealing from it is allowed, and the list to show once. */
        var peekTarget: String? = null
        var peekUntil = 0L
        var peekView: tz.shared.PeekView? = null
        /** Whom this character strikes: an NPC key or "u:<character id>" (char[7], the «атакует» mark). */
        var attackTarget: String? = null
        /** Poisoned till then (i.b.jad.c): health goes down instead of regenerating. */
        var poisonUntil = 0L
        /** Poison counted up to then: it drips from the moment it is taken, no pause after blows. */
        var poisonLast = 0L
        /** The horse under this character (character_state "mount"). */
        var mount: Mount? = null
        /** Holds the leadership flag (Society.kt). */
        var hasFlag = false
        /** Husband or wife (character_state "spouse" = "<id>|<name>"). */
        var spouseId: Long? = null
        var spouseName: String? = null
        /** Where the wounded spouse is, till when one may go there, and when this one may call again. */
        var steleTo: String? = null
        var steleUntil = 0L
        var steleNext = 0L
        /** Defensive stance (p.d.*), one at a time (Magic.kt). */
        var stance: Stance? = null
        /** When each spell or technique may be used again (unix time). */
        val cooldowns = HashMap<String, Long>()
        /** Spell buffs (m.armor, m.str, m.man): kept until equipment changes or death, like the old calcparam reset. */
        var armorBuff = 0
        var hpBuff = 0
        var manaBuff = 0

        /**
         * A skill or attribute on the old 0–5 (1–5) scale, for the old formulas
         * that are still tuned to it (stealing, hiding, taming, crafts): 2 → 1, 10 → 5.
         */
        fun oldSkill(key: String): Int = (skill(key) + 1) / 2

        fun skill(key: String): Int = when (key) {
            "str" -> str; "dex" -> dex; "int" -> int; "exp" -> exp.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(); "points" -> points
            else -> other[key] ?: 0
        }

        fun skills() = Skills.of(str, dex, int, 0, points).also { s ->
            for ((key, index, _) in Rules.SKILLS) if (index > 4) s[index] = other[key] ?: 0
        }
        val hpMax get() = (stats.hpMax + hpBuff + (if (hasFlag) stats.hpMax / 10 else 0)).coerceAtLeast(1)
        val manaMax get() = (stats.manaMax + manaBuff).coerceAtLeast(0)

        fun clearBuffs() { armorBuff = 0; hpBuff = 0; manaBuff = 0 }

        fun log(line: String, kind: String? = null) {
            journal.addLast(line)
            logged++
            journalKinds.addLast(kind ?: JournalKind.of(line))
            while (journal.size > JOURNAL_SIZE) { journal.removeFirst(); journalKinds.removeFirst() }
        }
    }

    internal val players = ConcurrentHashMap<Long, Player>()
    /** The forum (Forum.kt): the notice boards show its news. */
    val forum by lazy { Forum(db) }
    /** The leadership flag: who holds it, or where it lies (Society.kt). */
    internal var flagHolder: Long? = null
    internal var flagLoc: String? = null
    internal var flagLoaded = false
    /** Lajma's marriage proposals: whom → who proposed. */
    internal val proposals = HashMap<Long, Long>()
    /** Castles and their hired guards (Castles.kt), loaded on first use. */
    internal var castleCache: HashMap<Int, CastleState>? = null
    internal val castleGuards = HashMap<String, CastleGuard>()
    private var lastGuardCheck = 0L

    /** Open exchanges by character id (Social.kt). */
    internal val exchanges = HashMap<Long, ExchangeSide>()
    internal val byAccount = ConcurrentHashMap<Long, Long>()
    internal val events = ConcurrentHashMap<Long, MutableSharedFlow<Unit>>()

    // ---- public actions -----------------------------------------------------------

    suspend fun view(account: Account): GameView = lock.withLock { viewLocked(player(account)) }

    suspend fun move(account: Account, target: String, gallop: Boolean = false): GameView = lock.withLock {
        val p = player(account)
        val here = content.locations[p.location]
        val exit = here?.exits?.firstOrNull { it.target == target }
        if (exit == null || target !in content.locations)
            throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_AN_EXIT)
        val carried = db.tx { c -> inventoryRows(c, p.id) }.map { it.first }
        Travel.locked(p.location, target) { part -> carried.any { part in it } }
            ?.let { p.log(it); return@withLock viewLocked(p) }
        castleEntry(p, p.location, target)?.let { p.log(it); return@withLock viewLocked(p) }
        // Walking away is always allowed, even mid-fight (g.php go=); the enemies here may follow (Travel.chase).
        val from = p.location
        val hidden = dice.roll(1, 100) <= p.oldSkill("hiding") * 8
        p.location = target
        p.attackTarget = null
        walkLines(p, from, target, exit.label, hidden)
        // Gallop (g.php:264): on horseback, on through the exit with the same label.
        if (gallop && p.mount != null) content.locations[target]?.exits?.firstOrNull { it.label == exit.label && it.target != from && it.target in content.locations }?.let { next ->
            if (Travel.locked(target, next.target) { false } == null && castleEntry(p, target, next.target) == null) {
                p.location = next.target
                walkLines(p, target, next.target, next.label, hidden)
                p.log("Вы проскакали галопом ${exit.label}")
            }
        }
        save(p)
        notifyLocation(target, except = p.id)
        viewLocked(p)
    }

    suspend fun take(account: Account, itemId: String, arg: String? = null, count: Int? = null): GameView = lock.withLock {
        if (itemId == "i.s.arena") {
            val q = player(account)
            q.log(leaveArena(q))
            return@withLock viewLocked(q)
        }
        val p = alive(player(account))
        val now = clock()
        if (itemId.startsWith(Spells.PORTAL)) { usePortal(p, itemId, now); return@withLock viewLocked(p) }
        if (itemId == Travel.BOAT) { val choice = sail(p, arg, now); return@withLock viewLocked(p).copy(choice = choice) }
        if (itemId == Society.FLAG) { takeFlag(p, now); return@withLock viewLocked(p) }
        if (itemId.startsWith("i.s.")) throw ApiException(HttpStatusCode.BadRequest, Errors.CANNOT_TAKE)
        if (p.stance?.id == "p.d.o" && now < p.stance!!.until) { p.log("В глухой обороне брать предметы нельзя"); return@withLock viewLocked(p) }
        val lying = world.itemsAt(p.location, now).firstOrNull { it.id == itemId } ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_ITEM)
        checkLimit(p, itemId, count ?: lying.count)
        if (count != null && count in 1 until lying.count) {
            if (!world.takeFromGround(p.location, itemId, count, now)) throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_ITEM)
            db.tx { c -> addItem(c, p.id, itemId, count) }
            return@withLock viewLocked(p)
        }
        val item = world.take(p.location, itemId, now) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_ITEM)
        try {
            db.tx { c -> addItem(c, p.id, item.id, item.count) }
        } catch (e: Exception) {
            world.restore(p.location, item)
            throw e
        }
        viewLocked(p)
    }

    suspend fun drop(account: Account, itemId: String, count: Int? = null): GameView = lock.withLock {
        val p = alive(player(account))
        if (itemId == Society.FLAG) {
            if (!p.hasFlag) throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_INVENTORY)
            dropFlag(p, "${p.name} бросил флаг!")
            p.log("Вы бросили флаг лидерства")
            return@withLock viewLocked(p)
        }
        // Quest things stay with you (f_drop.dat:9): otherwise the trade ban would mean nothing.
        if (!Rules.tradeable(itemId)) throw ApiException(HttpStatusCode.BadRequest, Errors.CANNOT_DROP)
        val have = db.tx { c -> inventoryRows(c, p.id) }.firstOrNull { it.first == itemId }?.second
            ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_INVENTORY)
        val n = (count ?: have).coerceIn(1, have)
        if (n == have) db.tx { c ->
            c.prepareStatement("DELETE FROM character_items WHERE character_id = ? AND item_id = ?").use { st ->
                st.setLong(1, p.id); st.setString(2, itemId); st.executeUpdate()
            }
        } else changeItem(p, itemId, -n)
        world.drop(p.location, itemId, n, clock())
        refreshStats(p)
        viewLocked(p)
    }

    /** Puts an item on; whatever occupied the same slot (or conflicts, shield vs bow) comes off. */
    suspend fun equip(account: Account, itemId: String): GameView = lock.withLock {
        val p = alive(player(account))
        val slot = Rules.equipSlot(itemId) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.CANNOT_EQUIP)
        // Requirements of the new balance: level and attributes (balance.md §7).
        val r = Formulas.requirement(content.itemBalance(Formulas.baseId(itemId)))
        val missing = listOfNotNull(
            "уровень ${r[0]}".takeIf { p.level < r[0] }, "сила ${r[1]}".takeIf { p.str < r[1] },
            "ловкость ${r[2]}".takeIf { p.dex < r[2] }, "интеллект ${r[3]}".takeIf { p.int < r[3] },
        )
        if (missing.isNotEmpty()) {
            p.log("Чтобы надеть ${content.itemName(itemId)}, нужно: ${missing.joinToString()}")
            throw ApiException(HttpStatusCode.BadRequest, Errors.TOO_WEAK)
        }
        db.tx { c ->
            val owned = inventoryRows(c, p.id)
            if (owned.none { it.first == itemId }) throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_INVENTORY)
            val off = owned.filter { (id, _, equipped) ->
                equipped && id != itemId && (Rules.equipSlot(id) == slot || Rules.conflicts(id, itemId))
            }.map { it.first }
            for (id in off) setEquipped(c, p.id, id, false)
            setEquipped(c, p.id, itemId, true)
        }
        p.clearBuffs()
        refreshStats(p)
        viewLocked(p)
    }

    suspend fun unequip(account: Account, itemId: String): GameView = lock.withLock {
        val p = alive(player(account))
        db.tx { c ->
            if (!setEquipped(c, p.id, itemId, false)) throw ApiException(HttpStatusCode.BadRequest, Errors.NOT_IN_INVENTORY)
        }
        p.clearBuffs()
        refreshStats(p)
        viewLocked(p)
    }

    /** Strikes an NPC here (f_attackf.dat): pause, blow, its free counter-blow, death. */
    suspend fun attack(account: Account, target: String): GameView = lock.withLock {
        val p = alive(player(account))
        val now = clock()
        val npc = world.npc(p.location, target) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_TARGET)
        if (!Law.mayFight(p, null, now)) throw ApiException(HttpStatusCode.BadRequest, Errors.NO_FIGHT_HERE)
        if (clockMs() < p.busyUntil) throw ApiException(HttpStatusCode.Conflict, Errors.RESTING)
        npcAttackCrime(p, npc, now)
        p.busyUntil = clockMs() + pauseOf(p)
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
        val corpse = world.corpse(p.location, corpseId, clock()) ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_CORPSE)
        lootCrime(p, corpse, itemId, clock())
        checkLimit(p, itemId, corpse.items[itemId] ?: 1)
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
        if (loot.isNotEmpty() && dice.roll(0, 100) < 4) addExp(p, 1)   // plugin/i.w.k.dat:9
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
        // Someone's subordinate: orders from its owner (f_speak.dat:14-16), nobody else talks to it.
        npc.owner?.let { o ->
            val view = if (o.ownerId == p.id) petDialog(p, npc, topic.takeIf { it == "begin" || (it to arg) in p.talkChoices || (it == p.talkInput && p.talkingTo == npcKey) } ?: "begin", arg)
                else DialogView(npcKey, npc.name, "${npc.name} принадлежит другому персонажу")
            p.talkingTo = npcKey
            p.talkChoices = view.options.map { it.topic to it.arg }.toSet()
            p.talkInput = view.inputTopic
            return@withLock viewLocked(p).copy(dialog = view)
        }
        castles()
        castleGuards[npcKey]?.let { g ->
            val view = guardDialog(p, npc, g, topic.takeIf { it == "begin" || (topic to arg) in p.talkChoices } ?: "begin", arg)
            p.talkingTo = npcKey
            p.talkChoices = view.options.map { it.topic to it.arg }.toSet()
            p.talkInput = null
            return@withLock viewLocked(p).copy(dialog = view)
        }
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
                val ctx = TalkCtx(p, npc, dialogId, arg, now).also { it.load(); it.engineFlags() }
                arg?.let { ctx.vars["arg"] = it }
                val shown = showTopic(ctx, topic, 0)
                fun fill(t: String) = ctx.vars.entries.fold(Dialogs.plain(t).replace("<imja>", p.name)) { acc, (k, v) -> acc.replace("{$k}", v) }
                val options = shown.second.map { it.copy(label = fill(it.label)) }.filter { it.label.isNotBlank() }
                val offers = options.mapNotNull { o -> teachSpec(dialogId, o.topic)?.let { o.topic to it } }
                    .distinctBy { it.second.what }.map { (t, spec) -> spec to teachOffer(p, t, spec) }
                // The old lines name the old prices: today's go in their place.
                var text = fill(shown.first)
                for ((spec, offer) in offers) if (spec.cost > 0)
                    text = text.replace(Regex("(?<!\\d)${spec.cost}(\\s+монет)"), "${balance.teacherPrice(offer.level + 1)}$1")
                DialogView(npcKey, npc.name, text, options = options, inputTopic = ctx.inputTopic, teach = offers.map { it.second })
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
        /** Choices a handler built (castle keeper: the player's runes). */
        var handlerOptions: List<DialogOption>? = null
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

        /** Flags the engine knows without storing them: the clan rank (castle keepers ask for clan.head). */
        fun engineFlags() {
            if (p.clanRank == "head") flags["clan.head"] = "1"
            p.clanName?.let { flags["clan"] = it }
        }

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
            "newbie" in c -> newbie(p) == Dialogs.truthy(c["newbie"])
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
        "require-pk" -> stateOf(ctx.p.id, "pk") != null
        "npc-hand-over" -> world.npcHas(a.str("npc")!!, a.str("location") ?: ctx.p.location, a.str("item")!!)
        "wedding" -> a.str("step") != "pending" || ctx.p.id in proposals
        else -> petGuard(ctx.p, a)
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
                val places = content.hidingPlaces()
                if (places.isNotEmpty()) world.placePermanent(places[rnd.nextInt(places.size)], a.str("item")!!, 1)
            }
            "repair-boat" -> {
                world.removeItem(p.location, a.str("from")!!)
                world.placePermanent(p.location, a.str("to")!!, 1)
            }
            "lower-int" -> { p.int = (p.int - 1).coerceAtLeast(1); refreshStats(p) }
            "clan-status", "clan-leave", "clan-name-input", "clan-create", "clan-restore" -> return clanHandler(ctx.p, a, ctx.vars) { ctx.inputTopic = it }
            "castle-keeper-access", "castle-rune-list", "castle-contract", "castle-teleport" ->
                return keeperHandler(ctx.p, ctx.npc.key, a, ctx.arg) { ctx.handlerOptions = it }
            "hire-mercenary", "buy-pet", "pet-owned-here", "pet-free", "sell-pet", "pet-return", "marten-unicorn", "sacrifice-pet",
            "hire-fairy", "kasten-squad", "escort" -> return petHandler(ctx.p, a, ctx.vars)
            "wedding", "tomrak-armor", "tomrak-life", "gred-bouquet-give", "gred-bouquet-take", "thieves-contract", "smsCode", "claim-dublons" ->
                return societyHandler(ctx.p, a, ctx.arg, ctx.vars) { ctx.handlerOptions = it }
            "arena-enter", "bounty-list", "bounty-form", "bounty-place", "bounty-claim" ->
                return pvpHandler(ctx.p, a, ctx.arg) { ctx.inputTopic = it }
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
        if (actions.any { !Dialogs.supported(it) }) return Done(failure = UNTRANSLATED)

        val extra = ArrayList<String>()
        val prefix = ArrayList<String>()
        var options: List<DialogOption>? = null
        var statsChanged = false
        for (a in actions) {
            val count = a.int("count") ?: 1
            when {
                // A handler's own parameters may be named like actions ("give"), so it goes first.
                "handler" in a -> try {
                    handler(ctx, a)?.let { prefix += it }
                } catch (f: HandlerFailed) {
                    return Done(failure = f.text)
                }
                "take" in a -> { changeItem(p, a.str("take")!!, -count); ctx.inventory.merge(a.str("take")!!, -count, Int::plus); statsChanged = true }
                "give" in a -> { changeItem(p, a.str("give")!!, count); ctx.inventory.merge(a.str("give")!!, count, Int::plus) }
                "exp" in a -> addExp(p, (a.int("exp") ?: 0).toLong())
                "kills" in a -> addExp(p, questExp(p, a.int("kills") ?: 0, a.int("level") ?: 1))
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
                    else {
                        setState(p.id, a.str("set")!!, value, until); ctx.flags[a.str("set")!!] = value
                        if (a.str("set") == "faction") p.faction = value
                    }
                }
                "clear" in a -> {
                    if ("world" in a && Dialogs.truthy(a["world"])) clearWorldState(a.str("clear")!!)
                    else {
                        clearState(p.id, a.str("clear")!!); ctx.flags.remove(a.str("clear")!!)
                        if (a.str("clear") == "faction") p.faction = null
                    }
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

            }
        }
        if (statsChanged) refreshStats(p)
        save(p)
        if (ctx.handlerOptions != null) options = ctx.handlerOptions
        return Done(extra = extra.joinToString("\n").takeIf { it.isNotBlank() }, options = options,
            prefix = prefix.joinToString(" ").takeIf { it.isNotBlank() })
    }

    /**
     * A teacher (f_speakskillup.dat, docs/mechanics-progression.md §3.4) on the
     * balance of 04.10.2026 (balance.md §4): attributes and the 19 skills for a
     * training point and 15·n² coins for the n-th step (the first 4 points
     * free); crafts only their first steps, for money — further only by
     * practice; spells and techniques for money. The dialog's old limits are
     * on the 0–5 scale and count double. [ctx.arg] is the attribute or skill
     * chosen to lower when at the limit.
     */
    private suspend fun teach(ctx: TalkCtx, what: String, cost: Int, min: Int, max: Int): Pair<String, List<DialogOption>?> {
        val p = ctx.p
        val b = balance
        if (what.startsWith("m.") || what.startsWith("p.")) {
            val spell = what.startsWith("m.")
            if (what in p.known) return (if (spell) "У вас уже есть это заклинание" else "Вы уже знаете этот прием") to null
            val magic = p.skill("magic")
            if (spell && min > 0 && magic < min * 2) return "У вас недостаточный навык магии (надо минимум ${min * 2})" to null
            if (spell && max > 0 && magic > max * 2 + 1) return "У вас слишком высокий навык магии (максимум ${max * 2 + 1})" to null
            if (cost > 0) {
                if (ctx.count(Rules.MONEY) < cost) return "У вас недостаточно денег (надо $cost монет)" to null
                changeItem(p, Rules.MONEY, -cost)
            }
            learn(p, what)
            return (if (spell) "Вы выучили новое заклинание!" else "Вы выучили новый прием!") to null
        }
        if (Rules.SKILLS.none { it.first == what }) return "Этому здесь не учат." to null
        val current = p.skill(what)
        if (min > 0 && current < min * 2) return "Вы должны иметь уровень навыка не ниже ${min * 2}" to null
        val craft = what in b.crafts
        val price = b.teacherPrice(current + 1)
        if (craft) {
            // Crafts: the teacher gives the first steps for money, the rest comes with practice.
            if (current >= b.craftTeacherMax) return "Дальше я тебя не научу — только практика. Работай, и ремесло вырастет само." to null
            if (b.crafts.sumOf { p.skill(it) } >= b.craftSumMax) return "Вы и так владеете ремёслами на пределе (${b.craftSumMax}): сначала забудьте другое." to null
            if (ctx.count(Rules.MONEY) < price) return "У вас недостаточно денег (надо $price монет)" to null
            changeItem(p, Rules.MONEY, -price)
            ctx.inventory.merge(Rules.MONEY, -price, Int::plus)
            setSkill(p, what, current + 1)
            refreshStats(p)
            save(p)
            return "${Rules.skillTitle(what)}: +1 (плата $price монет)" to null
        }
        if (p.points < 1) return "Недостаточно очков обучения: они приходят с новыми уровнями" to null
        if (max > 0 && current > max * 2 + 1) return "Вы и так достаточно опытны, я учу только до уровня ${max * 2 + 2}" to null
        val attribute = what in Rules.ATTRIBUTES
        val down = ctx.arg?.takeIf { it.isNotEmpty() }
        if (attribute) {
            if (current >= b.attrMax) return "Невозможно повысить, т.к. атрибут уже на максимальном уровне ${b.attrMax}" to null
            if (down != null && down !in Rules.ATTRIBUTES) return "Неверный атрибут" to null
            if (down != null && p.skill(down) <= 1) return "Невозможно понизить, т.к. атрибут уже на минимальном уровне 1, выберите другой" to null
            if (down == null && p.str + p.dex + p.int >= b.attrSumMax) {
                return "Превышен предел суммы атрибутов (${b.attrSumMax}), выберите что уменьшить:" to
                    Rules.ATTRIBUTES.filter { it != what }.map { DialogOption("${Rules.skillTitle(it)}: ${p.skill(it)}", ctx.topic, it) }
            }
        } else {
            if (current >= b.skillMax) return "Невозможно повысить, т.к. навык уже на максимальном уровне ${b.skillMax}" to null
            if (down != null && (down in Rules.ATTRIBUTES || down in b.crafts || Rules.SKILLS.none { it.first == down })) return "Неверный навык" to null
            if (down != null && p.skill(down) <= 0) return "Невозможно понизить, т.к. навык уже на минимальном уровне 0, выберите другой" to null
        }
        var text = ""
        // The first points of a new character are free, as in the old game for a newcomer.
        if (newbie(p)) text = "Ладно, так уж и быть, раз ты новичок, то я это сделаю бесплатно.\n"
        else {
            if (ctx.count(Rules.MONEY) < price) return "У вас недостаточно денег (надо $price монет)" to null
            changeItem(p, Rules.MONEY, -price)
            ctx.inventory.merge(Rules.MONEY, -price, Int::plus)
        }
        setSkill(p, what, current + 1)
        if (down != null) setSkill(p, down, p.skill(down) - 1)
        // Lowering one thing to raise another costs no point: it moves the point.
        if (down == null) p.points -= 1
        refreshStats(p)
        save(p)
        text += Rules.skillTitle(what) + ": +1"
        if (down != null) text += "\n" + Rules.skillTitle(down) + ": -1"
        return text to null
    }

    /** What a topic teaches: «skill|what|cost|min|max» in the dialog, or a "teach" action of its logic. */
    private class TeachSpec(val what: String, val cost: Int, val min: Int, val max: Int)

    private fun teachSpec(dialog: String, topic: String): TeachSpec? {
        val base = content.logic.baseTopic(dialog, topic)?.first
        val spec = if (base != null && base.startsWith("skill|")) base.split('|').let { s ->
            TeachSpec(s.getOrElse(1) { "" }, s.getOrNull(2)?.toIntOrNull() ?: 0, s.getOrNull(3)?.toIntOrNull() ?: 0, s.getOrNull(4)?.toIntOrNull() ?: 0)
        } else content.logic.logic[dialog]?.get(topic)?.flatMap { it.actions }?.lastOrNull { "teach" in it }?.let { a ->
            TeachSpec(a.str("teach")!!, a.int("cost") ?: 0, a.int("min") ?: 0, a.int("max") ?: 0)
        }
        return spec?.takeIf { s -> Rules.SKILLS.any { it.first == s.what } }
    }

    /** The teacher's offer at today's prices, and why it is out of reach, the same checks as [teach]. */
    private fun teachOffer(p: Player, topic: String, s: TeachSpec): TeachOfferView {
        val b = balance
        val current = p.skill(s.what)
        val craft = s.what in b.crafts
        val attribute = s.what in Rules.ATTRIBUTES
        val price = b.teacherPrice(current + 1)
        val title = Rules.skillTitle(s.what)
        if (craft) {
            val note = when {
                current >= b.craftTeacherMax -> "дальше только практикой"
                b.crafts.sumOf { p.skill(it) } >= b.craftSumMax -> "ремёсла на пределе (${b.craftSumMax})"
                else -> null
            }
            return TeachOfferView(topic, s.what, title, current, b.craftTeacherMax, price, 0, note = note)
        }
        val max = if (attribute) b.attrMax else b.skillMax
        val free = newbie(p)
        val note = when {
            s.min > 0 && current < s.min * 2 -> "нужно не ниже ${s.min * 2}"
            current >= max -> "предел"
            s.max > 0 && current > s.max * 2 + 1 -> "учит только до ${s.max * 2 + 2}"
            p.points < 1 -> "нет очков обучения"
            attribute && p.str + p.dex + p.int >= b.attrSumMax -> "сумма атрибутов ${b.attrSumMax}: придётся понизить другой"
            else -> null
        }
        return TeachOfferView(topic, s.what, title, current, max, if (free) 0 else price, 1, free, note)
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

    internal suspend fun setWorldState(key: String, value: String, until: Long?) = db.tx { c ->
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
        npcMoveLines(now)
        ensureFlag(now)
        // Leaving the game drops the flag where one stood (f_logout.dat:18-24).
        flagHolder?.let { id -> players[id]?.takeIf { now - it.lastSeen >= ACTIVE_SECONDS || it.ghost }?.let { dropFlag(it, "${it.name} потерял флаг!") } }
        if (now - lastGuardCheck >= 60) { lastGuardCheck = now; castles(); expireGuards(now) }
        val active = players.values.filter { now - it.lastSeen < ACTIVE_SECONDS }
        for (p in active) if (!p.ghost) regen(p, now)
        for (p in active) if (p.crime != null && now >= p.crimeUntil) { p.crime = null; p.log("Срок вашего преступления истёк") }
        // The players' locations and their neighbours, like the old doai() (g.php:257-259): NPCs next door may follow.
        val byLoc = active.groupBy { it.location }
        val locs = LinkedHashSet<String>(byLoc.keys)
        for (l in byLoc.keys) content.locations[l]?.exits?.forEach { locs += it.target }
        for (loc in locs) {
            val here = byLoc[loc] ?: emptyList()
            val living = here.filter { !it.ghost }
            lawTick(loc, living, now)
            for (npc in world.npcsIn(loc)) {
                regenNpc(npc, now)
                if (npc.owner != null && ownerTick(npc, loc, now)) continue
                // Fighting another NPC (a guard and a monster).
                npc.npcTarget?.let { key ->
                    val other = world.npc(loc, key)
                    if (other == null || other.hp < 1) npc.npcTarget = null
                    else if (clockMs() >= npc.busyUntil && npc.hp > 0) { npcHitsNpc(npc, other, now, here); continue }
                }
                // The firebird never lets anyone near (g.php:719).
                if (npc.key == Travel.FIREBIRD && living.isNotEmpty() && firebirdFlees(npc, loc)) continue
                // Its target walked off: follow (f_goto.dat); the others who left or died are forgotten.
                val livingIds = living.map { it.id }.toSet()
                val first = npc.enemies.firstOrNull()
                if (first != null && first !in livingIds && npc.owner?.follow == null && chase(npc, first, loc, now)) continue
                npc.enemies.retainAll(livingIds)
                // A monster picks its victim among those it notices: hiding·6 % of the time you are unseen (g.php:680).
                if (npc.enemies.isEmpty() && npc.npcTarget == null && (npc.aggressive || npc.criminal) && npc.owner?.guard == null) {
                    val seen = living.filter { dice.roll(0, 100) > it.oldSkill("hiding") * 6 }
                    if (seen.isNotEmpty()) npc.enemies += seen[rnd.nextInt(seen.size)].id
                }
            }
        }
        combatLocked()
    }

    private var lastCombat = 0L

    /**
     * Blows of monsters and burning ailments, several times a second (pauses are
     * 1–2 s since 04.10.2026). [tick] runs it too, so a test driving the clock sees it.
     */
    suspend fun combat() = lock.withLock { combatLocked() }

    private suspend fun combatLocked() {
        val now = clock()
        val ms = clockMs()
        val dt = (ms - lastCombat).coerceIn(0, 2000)
        lastCombat = ms
        val active = players.values.filter { now - it.lastSeen < ACTIVE_SECONDS }
        for (p in active) if (!p.ghost && p.dots.isNotEmpty()) {
            val lost = burn(p.dots, dt)
            if (lost > 0) {
                p.hp -= lost; p.regenFrom = now
                p.log("Вы теряете $lost здоровья", JournalKind.HURT); notify(p.id)
                if (p.hp < 1) killPlayer(p, "яд и огонь", now)
            }
        }
        for ((loc, here) in active.groupBy { it.location }) {
            val living = here.filter { !it.ghost }
            for (npc in world.npcsIn(loc)) {
                if (npc.dots.isNotEmpty() && npc.hp > 0) {
                    val lost = burn(npc.dots, dt)
                    if (lost > 0) {
                        npc.hp -= lost; npc.regenFrom = now
                        if (npc.hp < 1) {
                            val killer = living.firstOrNull { it.id in npc.enemies }
                            if (killer != null) killNpc(killer, npc, now) else world.kill(npc, now)
                            continue
                        }
                    }
                }
                npcBlow(npc, here, living, now)
            }
        }
    }

    /** A monster's blow when its pause is over; blows go round all its enemies in turn. */
    private suspend fun npcBlow(npc: World.Npc, here: List<Player>, living: List<Player>, now: Long) {
        if (npc.npcTarget != null) return
        val victimId = npc.enemies.firstOrNull() ?: return
        val victim = living.firstOrNull { it.id == victimId } ?: return
        if (clockMs() < npc.busyUntil || npc.hp < 1) return
        if (npc.hp < npc.proto.hpMax / 4 && dice.roll(0, 100) < 50 && world.flee(npc)) {
            npc.enemies.clear()
            for (q in here) { q.log("${npc.name} убегает."); notify(q.id) }
            return
        }
        npc.busyUntil = clockMs() + pauseOf(npc)
        npc.enemies.remove(victimId); npc.enemies.add(victimId)
        npcHits(npc, victim, now, answer = true)
        save(victim)
    }

    // ---- fighting -------------------------------------------------------------------

    /** The pause after a blow, longer while chilled (balance.md §13). */
    internal fun pauseOf(p: Player): Long = chilled(p.stats.pauseMs, p.chilledUntil)
    internal fun pauseOf(n: World.Npc): Long = chilled(n.stats.pauseMs, n.chilledUntil)
    private fun chilled(ms: Long, until: Long) = if (clockMs() < until) (ms * (1 + balance.ailment("chill").slow)).toLong() else ms

    /**
     * A gem's ailment after a blow that landed: ignite and poison burn a share of
     * the blow over a few seconds, chill makes the target's blows slower; a
     * necklace with the same gem halves it on its wearer.
     */
    internal fun ailmentAfter(a: Stats, dealt: Int, guard: Set<String>, dots: MutableList<Dot>, chill: (Long) -> Unit) {
        val kind = a.ailment ?: return
        if (dealt <= 0) return
        val ail = balance.ailment(kind)
        if (dice.roll(0, 9999) >= ail.chance * 100) return
        val seconds = ail.seconds * (if (kind in guard) balance.amuletCut else 1.0)
        val until = clockMs() + (seconds * 1000).toLong()
        when (kind) {
            "chill" -> chill(until)
            "ignite" -> { dots.removeAll { it.kind == "ignite" }; dots += Dot(kind, until, dealt * ail.share / ail.seconds) }
            "poison" -> {
                dots.removeAll { it.kind == "poison" && it.untilMs <= clockMs() }
                if (dots.count { it.kind == "poison" } >= ail.stacks) dots.remove(dots.first { it.kind == "poison" })
                dots += Dot(kind, until, dealt * ail.share / ail.seconds)
            }
        }
    }

    /** Burns [dots] for [ms] milliseconds; returns whole health points lost. */
    internal fun burn(dots: MutableList<Dot>, ms: Long): Int {
        val now = clockMs()
        var lost = 0
        for (d in dots) {
            val left = (d.untilMs - (now - ms)).coerceIn(0, ms)
            d.carry += d.perSecond * left / 1000.0
            val whole = d.carry.toInt(); d.carry -= whole; lost += whole
        }
        dots.removeAll { it.untilMs <= now }
        return lost
    }

    internal fun describe(h: Formulas.Hit, verb: String): String = when (h.outcome) {
        Formulas.Outcome.MISS, Formulas.Outcome.FIZZLED -> "мимо"
        Formulas.Outcome.DODGED -> "мимо (уклон)"
        Formulas.Outcome.HIT -> buildString {
            if (h.crit) append("критически ")
            append(verb).append(' ').append(h.damage)
            if (h.shield > 0) append(" (щит ${h.shield})")
            if (h.resisted > 0) append(" (сопр. магии ${h.resisted})")
        }
    }

    internal suspend fun playerHits(p: Player, npc: World.Npc, now: Long, answer: Boolean, blow: Blow? = null) {
        // The amulet of power doubles damage against monsters (f_attackf.dat:74).
        val stats = (blow?.stats ?: p.stats).let {
            if (npc.key.startsWith("n.c.") && p.equipped.any { e -> e.startsWith("i.a.m.vlast") }) it.copy(dmgMin = it.dmgMin * 2, dmgMax = it.dmgMax * 2) else it
        }
        val r = strike(stats, p, npc.stats, null, blow, now)
        if (r.hit.outcome == Formulas.Outcome.FIZZLED) return
        val h = castleBonus(p, r.hit)
        val text = describe(h, stats.verb) + r.note
        val t = blow?.title?.let { " ($it)" } ?: ""
        p.log(if (answer) "Вы$t по ${npc.name} $text" else "  вы отвечаете: $text", JournalKind.FIGHT)
        tellOthers(p.location, p.id, if (answer) "${p.name}$t по ${npc.name} $text" else "${p.name} отвечает: $text", JournalKind.FIGHT)
        if (h.outcome == Formulas.Outcome.HIT) {
            npc.hp -= h.damage
            npc.regenFrom = now
            npc.damageBy.merge(p.id, h.damage, Int::plus)
            ailmentAfter(stats, h.damage, emptySet(), npc.dots) { npc.chilledUntil = it }
        }
        // Everyone who strikes an NPC becomes its enemy; it answers them all.
        npc.enemies += p.id
        if (answer) p.attackTarget = npc.key
        if (npc.hp < 1) {
            killNpc(p, npc, now)
            return
        }
        // Free counter-blow of a target that is not resting (f_attackf.dat:171); spells get none.
        if (answer && blow?.rmagic != true && clockMs() >= npc.busyUntil) npcHits(npc, p, now, answer = false)
    }

    internal suspend fun npcHits(npc: World.Npc, p: Player, now: Long, answer: Boolean) {
        val r = strike(npc.stats, null, p.stats, p, null, now)
        val h = r.hit
        if (h.outcome == Formulas.Outcome.FIZZLED) return
        val text = describe(h, npc.stats.verb) + r.note
        p.log(if (answer) "${npc.name} по вам $text" else "  ${npc.name} отвечает: $text", JournalKind.HURT)
        tellOthers(p.location, p.id, "${npc.name} по ${p.name} $text", JournalKind.FIGHT)
        notify(p.id)
        if (h.outcome == Formulas.Outcome.HIT) {
            p.hp -= h.damage
            p.regenFrom = now
            woundedSpouse(p, now)
        }
        // Only a blow that lands kills: just resurrected at 0 HP, a miss leaves the character alive.
        if (h.outcome == Formulas.Outcome.HIT && p.hp < 1) {
            killPlayer(p, "${npc.name}", now)
            return
        }
        // The player answers too, if not resting (the old engine did this for players as well).
        if (answer && clockMs() >= p.busyUntil && !p.ghost) playerHits(p, npc, now, answer = false)
    }

    internal suspend fun killNpc(p: Player, npc: World.Npc, now: Long) {
        world.kill(npc, now)
        p.count(Stat.MONSTERS)
        if (npc.key == Society.DEMON) chronicle("${p.name} убил Демона")
        killOrder(p, npc)
        p.log("${npc.name} погибает.")
        tellOthers(p.location, p.id, "${npc.name} погибает.")
        // Experience by the share of damage among those still here, +10 % per extra fighter,
        // less for a monster far below one's level, more for one above (balance.md §3).
        val fighters = npc.damageBy.filterKeys { id -> id == p.id || players[id]?.let { it.location == p.location && !it.ghost } == true }
            .ifEmpty { mapOf(p.id to 1) }
        val total = fighters.values.sum().coerceAtLeast(1)
        val group = 1 + balance.groupBonusPerMember * (fighters.size - 1)
        for ((id, dmg) in fighters) {
            val q = players[id] ?: continue
            val gained = npc.stats.expValue * dmg / total.toDouble() * group * balance.expByGap(q.level, npc.stats.level)
            addExp(q, Math.round(gained))
            if (q.id != p.id) notify(q.id)
        }
        npc.damageBy.clear()
    }

    internal suspend fun addExp(p: Player, gained: Int) = addExp(p, gained.toLong())

    /**
     * A quest's reward (balance.md §3): [kills] monsters of the quest's [level]. A hero far
     * below it is paid as for monsters 4 levels above him, one above it less, as for monsters.
     */
    internal fun questExp(p: Player, kills: Int, level: Int): Long {
        val l = minOf(level, p.level + 4).coerceAtLeast(1)
        return Math.round(kills * balance.monsterExp(l) * balance.expByGap(p.level, l))
    }

    /**
     * Experience counts on past the top level (owner 04.10.2026); every level
     * reached gives its training points, spent at the teachers.
     */
    internal suspend fun addExp(p: Player, gained: Long) {
        if (gained <= 0) return
        p.exp += gained
        p.log("Опыт +$gained")
        val level = balance.level(p.exp)
        if (level > p.level) {
            val points = (p.level + 1..level).sumOf { balance.pointsForLevel(it) }
            p.points += points
            p.log("Новый уровень: $level! Очков обучения +$points — потратить их можно у учителей.")
            refreshStats(p)
            p.hp = p.hpMax; p.mana = p.manaMax
        }
    }

    /** Combat parameters of [p] wearing [equipped] (balance.md). */
    internal fun statsOf(p: Player, equipped: List<String>): Stats =
        Formulas.player(balance, p.skills(), p.level, equipped, { content.items[it] }, { content.itemBalance(it) }, content.sets, mounted = p.mount != null)

    /** Death (f_kill.dat): everything carried falls into a corpse, the character becomes a ghost. */
    internal suspend fun killPlayer(p: Player, killer: String, now: Long, by: Player? = null, killerWasCriminal: Boolean = false) {
        val guilty = by == null || guiltyPlayer(p, by, now)
        p.hp = 0
        p.ghost = true
        p.fightingPlayer = null
        p.stance = null
        p.clearBuffs()
        p.attackTarget = null
        p.poisonUntil = 0
        if (p.mount != null) leaveHorse(p, now, hour = false)
        if (p.hasFlag) dropFlag(p, "${p.name} потерял флаг!")
        p.count(Stat.DEATHS)
        by?.count(Stat.PLAYERS)
        if (p.location == Rules.ARENA) {
            // On the arena things stay with the fallen (f_kill.dat:19).
            p.log("Вас победил $killer. Вы призрак: покинуть арену можно через камень выхода.")
        } else if (db.tx { c -> inventoryRows(c, p.id) }.any { it.first == FEATHER }) {
            // The firebird feather burns and the things stay with the ghost, taken off (f_kill.dat:19-23,40).
            changeItem(p, FEATHER, -1)
            db.tx { c -> c.prepareStatement("UPDATE character_items SET equipped = FALSE WHERE character_id = ?").use { it.setLong(1, p.id); it.executeUpdate() } }
            p.equipped = emptyList()
            p.stats = statsOf(p, emptyList())
            p.log("Вас убил $killer. Вы спасли свои вещи пером жар-птицы!")
            tellOthers(p.location, p.id, "${p.name} спасает свои вещи пером жар-птицы!")
        } else {
            val items = db.tx { c ->
                val rows = inventoryRows(c, p.id)
                c.prepareStatement("DELETE FROM character_items WHERE character_id = ?").use { it.setLong(1, p.id); it.executeUpdate() }
                rows.associate { it.first to it.second }
            }
            val free = p.criminal(now) || CastleRules.inside(p.location)
            world.addCorpse(p.location, "труп: ${p.name}", items, now, p.id, free, p.clanId)
            p.equipped = emptyList()
            p.stats = statsOf(p, emptyList())
            p.log("Вас убил $killer. Вы призрак; ваши вещи остались в трупе на 10 минут.")
        }
        if (by != null && !Law.lawless(p.location)) murder(p, by, now, killerWasCriminal, guilty)
        else if (by != null) setState(by.id, "pk", p.name, null)
        tellOthers(p.location, p.id, "${p.name} погибает.")
        for (npc in world.npcsIn(p.location)) npc.enemies.remove(p.id)
        save(p)
        notify(p.id)
    }

    internal suspend fun useAmmo(p: Player): Boolean = db.tx { c ->
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

    /**
     * Rest (balance.md §5): 10 s after the last blow health comes back by
     * (1.5 % + 0.3 % per regeneration step) of the maximum every 5 s, mana the
     * same by meditation; three times faster in the bank and the tavern. Poison
     * (i.b.jad.c) takes a point every 10 s instead.
     */
    private fun regen(p: Player, now: Long) {
        val poisoned = p.poisonLast < p.poisonUntil
        if (poisoned) {
            // Poison drips 0.1 a second from the moment it was taken, blows or no blows.
            val till = minOf(now, p.poisonUntil)
            if (till > p.poisonLast) { p.regenHp -= 0.1 * (till - p.poisonLast); p.poisonLast = till }
        }
        if (!poisoned && p.hp >= p.hpMax && p.mana >= p.manaMax) return
        // Seconds of rest since the last blow (+10 s) or the last time this ran, whichever is later.
        val seconds = maxOf(0L, now - maxOf(p.regenFrom + balance.regenAfter, p.regenLast))
        if (seconds > 0) p.regenLast = now
        val safe = p.location == Rules.BANK_LOCATION || p.location in Spells.TAVERN || p.location == Society.TAVERN_HALL
        val perSecond = seconds.toDouble() / balance.regenEvery
        if (!(now < p.poisonUntil)) p.regenHp += balance.regenPerTick(p.hpMax, p.skill("regeneration"), safe) * perSecond
        p.regenMana += balance.regenPerTick(p.manaMax, p.skill("meditation"), safe) * perSecond
        val dh = p.regenHp.toInt(); p.regenHp -= dh
        val dm = p.regenMana.toInt(); p.regenMana -= dm
        if (dh == 0 && dm == 0) return
        p.hp = (p.hp + dh).coerceAtMost(p.hpMax).let { if (poisoned) it.coerceAtLeast(1) else it }
        p.mana = (p.mana + dm).coerceAtMost(p.manaMax)
        dirty += p.id
    }

    internal fun regenNpc(npc: World.Npc, now: Long) {
        val poisoned = npc.poisonLast < npc.poisonUntil
        if (poisoned) {
            val till = minOf(now, npc.poisonUntil)
            if (till > npc.poisonLast) { npc.regenCarry -= 0.1 * (till - npc.poisonLast); npc.poisonLast = till }
        }
        if (!poisoned && npc.hp >= npc.proto.hpMax) return
        val seconds = maxOf(0L, now - maxOf(npc.regenFrom + balance.regenAfter, npc.regenLast))
        if (seconds > 0) npc.regenLast = now
        if (!(now < npc.poisonUntil)) npc.regenCarry += balance.regenPerTick(npc.proto.hpMax, 0, false) * seconds / balance.regenEvery
        val d = npc.regenCarry.toInt(); npc.regenCarry -= d
        npc.hp = (npc.hp + d).coerceAtMost(npc.proto.hpMax).let { if (poisoned) it.coerceAtLeast(1) else it }
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
        if (cached != null && p.lastSeen != 0L && now - p.lastSeen >= ACTIVE_SECONDS) afterBreak(p, now - p.lastSeen)
        p.lastSeen = now
        return p
    }

    private suspend fun load(account: Account): Player? {
        val p = db.tx { c ->
            c.prepareStatement(
                "SELECT id, name, sex, location, hp, mana, ghost, str, dex, intel, exp, skill_points, skills::text AS skills, stats::text AS stats FROM characters " +
                    "WHERE account_id = ? AND world_id = 1"
            ).use { st ->
                st.setLong(1, account.id)
                st.executeQuery().use { rs ->
                    if (!rs.next()) null else Player(
                        rs.getLong("id"), account.id, rs.getString("name"), rs.getString("sex"), rs.getString("location"),
                        rs.getInt("hp"), rs.getInt("mana"), rs.getBoolean("ghost"),
                        rs.getInt("str"), rs.getInt("dex"), rs.getInt("intel"), rs.getLong("exp"), rs.getInt("skill_points"),
                    ).also { p ->
                        for ((k, v) in kotlinx.serialization.json.Json.parseToJsonElement(rs.getString("skills")).jsonObject) {
                            (v as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull?.let { p.other[k] = it }
                        }
                        (kotlinx.serialization.json.Json.parseToJsonElement(rs.getString("stats")) as? kotlinx.serialization.json.JsonArray)
                            ?.forEachIndexed { i, v -> if (i < Stat.SIZE) (v as? kotlinx.serialization.json.JsonPrimitive)?.intOrNull?.let { p.statistics[i] = it } }
                    }
                }
            }
        } ?: return null
        db.tx { c ->
            c.prepareStatement("SELECT craft, progress FROM character_craft WHERE character_id = ?").use { st ->
                st.setLong(1, p.id)
                st.executeQuery().use { rs -> while (rs.next()) p.practice[rs.getString(1)] = rs.getInt(2) }
            }
        }
        p.known += db.tx { c ->
            c.prepareStatement("SELECT id FROM character_known WHERE character_id = ?").use { st ->
                st.setLong(1, p.id)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }
        db.tx { c ->
            c.prepareStatement("SELECT key, value, until FROM character_state WHERE character_id = ? AND key IN ('crime', 'faction', 'mount', 'spouse', 'ui.slots', 'ui.belt')").use { st ->
                st.setLong(1, p.id)
                st.executeQuery().use { rs ->
                    while (rs.next()) when (rs.getString(1)) {
                        "crime" -> { p.crime = rs.getString(2); p.crimeUntil = rs.getLong(3) }
                        "faction" -> p.faction = rs.getString(2).takeIf { it.isNotEmpty() }
                        "mount" -> rs.getString(2).split('|').let { m -> p.mount = Mount(m[0].toIntOrNull() ?: 1, m.getOrNull(1)?.takeIf { it.isNotEmpty() }) }
                        "spouse" -> rs.getString(2).split('|', limit = 2).let { m -> p.spouseId = m[0].toLongOrNull(); p.spouseName = m.getOrNull(1) }
                        "ui.slots" -> p.slots = rs.getString(2).split(',')
                        "ui.belt" -> p.belt = rs.getString(2).split(',')
                    }
                }
            }
        }
        loadClan(p)
        checkMarriage(p)
        afterBreak(p, 0)
        p.regenFrom = clock()
        refreshStats(p)
        players[p.id] = p
        byAccount[account.id] = p.id
        return p
    }

    internal suspend fun refreshStats(p: Player) {
        p.equipped = db.tx { c -> inventoryRows(c, p.id) }.filter { it.third }.map { it.first }
        p.level = balance.level(p.exp)
        p.stats = statsOf(p, p.equipped).let { if (p.armorBuff != 0) it.copy(armor = it.armor + p.armorBuff) else it }
        p.hp = p.hp.coerceAtMost(p.hpMax)
        p.mana = p.mana.coerceAtMost(p.manaMax)
    }

    internal suspend fun save(p: Player) = db.tx { c ->
        c.prepareStatement(
            "UPDATE characters SET location = ?, hp = ?, mana = ?, ghost = ?, exp = ?, skill_points = ?, " +
                "str = ?, dex = ?, intel = ?, skills = ?::jsonb, stats = ?::jsonb WHERE id = ?"
        ).use { st ->
            st.setString(1, p.location)
            st.setInt(2, p.hp.coerceAtLeast(0))
            st.setInt(3, p.mana.coerceAtLeast(0))
            st.setBoolean(4, p.ghost)
            st.setLong(5, p.exp)
            st.setInt(6, p.points)
            st.setInt(7, p.str)
            st.setInt(8, p.dex)
            st.setInt(9, p.int)
            st.setString(10, kotlinx.serialization.json.JsonObject(p.other.filterValues { it != 0 }.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString())
            st.setString(11, p.statistics.joinToString(",", "[", "]"))
            st.setLong(12, p.id)
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

    internal fun tellOthers(loc: String, except: Long, line: String, kind: String? = null) {
        val now = clock()
        for (q in players.values) if (q.id != except && q.location == loc && now - q.lastSeen < ACTIVE_SECONDS) {
            q.log(line, kind)
            notify(q.id)
        }
    }

    internal suspend fun viewLocked(p: Player): GameView {
        val now = clock()
        val loc = content.locations[p.location] ?: content.locations.getValue(Protocol.START_LOCATION)
        val here = players.values
            .filter { it.id != p.id && it.location == loc.id && now - it.lastSeen < ACTIVE_SECONDS }
            .sortedBy { it.name }
        val others = here.map { it.name + (it.clanName?.let { c -> " *$c*" } ?: "") + (if (it.criminal(now)) " [${it.crime}]" else "") + if (it.ghost) " (призрак)" else "" }
        val npcsHere = world.npcsIn(loc.id)
        fun whom(id: Long?): String? = when (id) {
            null -> null
            p.id -> "вас"
            else -> here.firstOrNull { it.id == id && !it.ghost }?.name
        }
        fun attackingOf(target: String?): String? = when {
            target == null -> null
            target.startsWith("u:") -> whom(target.removePrefix("u:").toLongOrNull())
            else -> npcsHere.firstOrNull { it.key == target }?.name
        }
        val npcs = npcsHere.map {
            val pic = content.art.creature(it.name)
            val blowMs = it.enemies.indexOf(p.id).takeIf { i -> i >= 0 }?.let { i -> (it.busyUntil - clockMs()).coerceAtLeast(0) + i.toLong() * it.stats.pauseMs }
            NpcView(it.key, it.name, it.hp, it.proto.hpMax, p.id in it.enemies, true,
                content.logic.hasDialog(if (it.key.startsWith("n.g.")) "n.g.guard" else it.key) || it.owner?.ownerId == p.id,
                attacking = whom(it.enemies.firstOrNull()) ?: it.npcTarget?.let { k -> npcsHere.firstOrNull { n -> n.key == k }?.name },
                mine = it.owner?.ownerId == p.id,
                owner = it.owner?.let { o -> if (o.ownerId == p.id) "вы" else players[o.ownerId]?.name },
                art = pic?.first, undead = pic?.second == true,
                nextBlow = blowMs?.let { ms -> ((ms + 999) / 1000).toInt() }, nextBlowMs = blowMs,
                level = if (it.owner == null) it.stats.level else 0,
                hostile = (it.aggressive || it.criminal) && it.owner == null)
        }
        val occupied = loc.exits.map { it.target }.distinct().filter { t ->
            t != loc.id && (world.npcsIn(t).isNotEmpty() || players.values.any { it.location == t && now - it.lastSeen < ACTIVE_SECONDS })
        }.toSet()
        val location = loc.view().copy(
            exits = loc.exits.map { e ->
                e.copy(occupied = e.target in occupied,
                    gallop = p.mount != null && content.locations[e.target]?.exits?.any { it.label == e.label && it.target != loc.id } == true)
            },
            npcs = npcs,
            items = world.itemsAt(loc.id, now),
            players = others,
            corpses = world.corpsesAt(loc.id, now, p.id, p.clanId),
            art = content.art.location(loc.name),
        )
        val inventory = db.tx { c -> inventoryRows(c, p.id) }.map { (id, count, equipped) ->
            InventoryItemView(id, content.itemName(id), count, equipped, Rules.equipSlot(id) != null,
                content.crafting.find(id) != null || Spells.usable(content, id), content.crafting.targetOf(id) ?: Spells.itemTarget(content, id))
        }
        val abilityList = abilities(p, now)
        val s = p.stats
        val character = CharacterView(
            id = p.id, name = p.name, sex = p.sex, location = p.location,
            hp = p.hp.coerceAtLeast(0), hpMax = p.hpMax, mana = p.mana.coerceAtLeast(0), manaMax = p.manaMax,
            str = p.str, dex = p.dex, int = p.int, skillPoints = p.points,
            skills = p.other.filterValues { it > 0 }.toSortedMap(), known = p.known.sorted(),
            crafts = Rules.CRAFTS.map { k ->
                val step = p.skill(k)
                CraftSkillView(k, Rules.skillTitle(k), step, balance.craftMax, p.practice[k] ?: 0,
                    if (step >= balance.craftMax) 0 else balance.craftExpToNext(step))
            },
            craftSum = Rules.CRAFTS.sumOf { p.skill(it) }, craftSumMax = balance.craftSumMax,
            crime = p.crime.takeIf { p.criminal(now) }, crimeMinutes = if (p.criminal(now)) (p.crimeUntil - now + 59) / 60 else 0,
            poisoned = now < p.poisonUntil,
            mounted = p.mount != null,
            flag = p.hasFlag,
            spouse = p.spouseName,
            ghost = p.ghost,
            exp = (p.exp - balance.expForLevel(p.level)).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            expNext = if (p.level >= balance.maxLevel) 0 else balance.expToNext(p.level).toInt(),
            level = p.level, expTotal = p.exp,
            hit = s.hit, dmgMin = s.dmgMin, dmgMax = s.dmgMax, armor = s.armor, dodge = s.dodge,
            parry = s.parry, magicDodge = s.magicDodge, magicParry = 0, magicResist = s.magicResist,
            rank = Levels.rank(Levels.percent(p.skills())), title = Levels.title(p.skills()),
        )
        // Workplaces show the tool from the backpack that works there.
        val located = location.copy(items = location.items.map { g ->
            if (!g.id.startsWith("i.s.")) g else content.crafting.toolsAt(g.id).firstNotNullOfOrNull { (key, _, _) ->
                inventory.firstOrNull { content.crafting.isTool(it.id, key) }?.id
            }?.let { g.copy(useWith = it) } ?: g
        })
        return GameView(
            character, located, inventory,
            journal = p.journal.toList(),
            journalKinds = p.journalKinds.toList(),
            journalHere = (p.logged - p.hereFrom).coerceAtMost(p.journal.size.toLong()).toInt(),
            forumReplies = forum.replies(p.accountId),
            corpseAt = if (p.ghost) world.corpseOf(p.id, now)?.let { content.locations[it]?.name ?: it } else null,
            restSeconds = ((p.busyUntil - clockMs()).coerceAtLeast(0) + 999).div(1000).toInt(),
            restMs = (p.busyUntil - clockMs()).coerceAtLeast(0),
            canResurrect = canResurrect(p),
            exchange = exchangeView(p),
            castle = castleView(p),
            unread = unreadCount(p),
            clanInvites = clanInvites(p),
            people = here.map { q ->
                PersonView(q.name, q.clanName, q.ghost, q.crime.takeIf { q.criminal(now) },
                    hpPercent = if (!q.ghost && q.hp < q.hpMax) q.hp.coerceAtLeast(0) * 100 / q.hpMax else null,
                    attacking = if (q.ghost) null else attackingOf(q.attackTarget),
                    rider = q.mount != null,
                    flag = q.hasFlag,
                    faction = if (Law.wolfIsland(loc.id) && (Regex("x(\\d+)$").find(loc.id)?.groupValues?.get(1)?.toIntOrNull() ?: 0) <= 1370)
                        when (q.faction) { "t" -> "тамплиер"; "p" -> "пират"; else -> null } else null)
            },
            clan = p.clanName,
            abilities = abilityList,
            slots = slotsOf(p, abilityList),
            belt = beltOf(p, inventory),
            stance = p.stance?.takeIf { now < it.until }?.let { "${it.name} (${(it.until - now + 59) / 60} мин)" },
            peek = p.peekView.also { p.peekView = null },
            stele = p.steleTo?.takeIf { now < p.steleUntil }?.let { content.locations[it]?.name ?: it },
            alarm = castleAlarm(p, now)?.let { (n, _) -> content.locations["c.$n.gate"]?.name ?: "Замок $n" },
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

    /** At most one firebird feather and one death powder, two glass swords (f_additem.dat:23-24). */
    internal suspend fun checkLimit(p: Player, itemId: String, adding: Int) {
        val max = LIMITS[itemId] ?: return
        val have = db.tx { c -> inventoryRows(c, p.id) }.firstOrNull { it.first == itemId }?.second ?: 0
        if (have + adding > max) throw ApiException(HttpStatusCode.BadRequest, Errors.TOO_MANY)
    }

    companion object {
        const val FEATHER = "i.q.pjpt"
        val LIMITS = mapOf("i.q.pjpt" to 1, "i.q.pdeath" to 1, "i.q.ssword" to 2)
        /** Characters active in the last 10 minutes are shown and can be attacked by monsters. */
        const val ACTIVE_SECONDS = 600
        const val JOURNAL_SIZE = 30
        const val TIMER_PREFIX = "timer:"
        const val UNTRANSLATED = "Этот разговор пока не перенесён в новую версию игры."
    }
}

/** A newcomer: first level, the starting points not all spent yet (the old «сумма навыков = 5»). Teachers are free for him. */
internal fun Game.newbie(p: Game.Player): Boolean {
    val b = balance
    val spent = (p.str + p.dex + p.int - 3 * b.attrStart) + p.other.filterKeys { it !in b.crafts }.values.sum()
    return p.level == 1 && spent < b.creationPoints
}
