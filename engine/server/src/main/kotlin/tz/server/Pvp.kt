package tz.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import tz.shared.Errors
import tz.shared.GameView
import tz.shared.Rules

/*
 * Fighting between players and the law (docs/mechanics-combat.md §2.3, §6,
 * docs/mechanics-world.md §3.4, §5): crimes, city guards, looting, the
 * arena, bounties, factions on the Wolf island, guards of castles.
 */

internal object Law {
    /** Crimes do not count inside castles (except at the gate outside) and on the arena (f_docrim.dat:5-6). */
    fun lawless(loc: String) = CastleRules.inside(loc) || loc == Rules.ARENA

    /** The Wolf island: Y >= 1099, where templars and pirates are at war (f_attackf.dat:57). */
    fun wolfIsland(loc: String): Boolean = Regex("^x\\d+x(\\d+)$").find(loc)?.groupValues?.get(1)?.toIntOrNull()?.let { it >= 1099 } == true

    /** NPCs anyone may strike without a crime. */
    fun outlawNpc(key: String) = key.startsWith("n.c.") || key == "n.w.Veelzevul" || key == "n.whitewolf" || key.startsWith("n.whitewolf.")

    val GUARD_NAMES = listOf("Ганс", "Бруно", "Отто", "Курт", "Фриц", "Карл", "Вальтер", "Густав", "Эрик", "Рольф")
    const val GUARD_LIFETIME = 600L
    val GUARD_STATS = Stats(100, 30, 45, 3, false, 100, 30, 0, 0, 80, 80, 70, "алебардой", 15, "")
}

internal fun Game.Player.criminal(now: Long) = crime != null && now < crimeUntil

/** Gives a crime title for 30 minutes (restarts the term, as the old docrim did). */
internal suspend fun Game.commitCrime(p: Game.Player, title: String, now: Long, seconds: Long = Rules.CRIME_SECONDS) {
    if (Law.lawless(p.location)) return
    p.crime = title
    p.crimeUntil = now + seconds
    setState(p.id, "crime", title, p.crimeUntil)
    p.log("Вы преступник ($title) на ${seconds / 60} минут: стража будет вас преследовать")
}

/** Is it no crime to strike this character? */
internal fun Game.guiltyPlayer(target: Game.Player, attacker: Game.Player, now: Long): Boolean =
    target.criminal(now) || target.fightingPlayer == attacker.id ||
        (target.clanId != null && target.clanId == attacker.clanId) ||
        (Law.wolfIsland(target.location) && (target.faction == "p" || (attacker.faction == "p" && target.faction == "t")))

internal fun guiltyNpc(npc: World.Npc, attacker: Game.Player, loc: String): Boolean =
    Law.outlawNpc(npc.key) || npc.key.startsWith("n.a.") && !npc.key.startsWith("n.o.") || attacker.id in npc.enemies ||
        (Law.wolfIsland(loc) && (npc.key.startsWith("n.p.") || (attacker.faction == "p" && npc.key.startsWith("n.t."))))

/** Crime for striking an NPC (f_attackf.dat:54-61): «живодер» for a hired guard, «бандит» for anyone innocent. */
internal suspend fun Game.npcAttackCrime(p: Game.Player, npc: World.Npc, now: Long) {
    if (p.criminal(now) || guiltyNpc(npc, p, p.location)) return
    // A castle's own guards serve its owners: no crime for them.
    if (npc.key.startsWith("n.o.") && castleGuards[npc.key]?.let { g -> castles()[g.castle]?.clanId == p.clanId } == true) return
    commitCrime(p, if (npc.key.startsWith("n.o.")) "живодер" else "бандит", now)
}

/** Strikes another character here. */
suspend fun Game.attackPlayer(account: Account, name: String): GameView = lock.withLock {
    val p = alive(player(account))
    val now = clock()
    val t = players.values.firstOrNull { it.id != p.id && it.name.equals(name.trim(), ignoreCase = true) && it.location == p.location && now - it.lastSeen < Game.ACTIVE_SECONDS }
        ?: throw ApiException(HttpStatusCode.BadRequest, Errors.NO_SUCH_PLAYER)
    if (t.ghost) throw ApiException(HttpStatusCode.Conflict, Errors.GHOST)
    if (p.location == Rules.BANK_LOCATION) throw ApiException(HttpStatusCode.BadRequest, Errors.NO_FIGHT_HERE)
    if (now < p.busyUntil) throw ApiException(HttpStatusCode.Conflict, Errors.RESTING)
    p.busyUntil = now + p.stats.delay
    if (p.stats.ammo.isNotEmpty() && !useAmmo(p)) throw ApiException(HttpStatusCode.BadRequest, Errors.NO_AMMO)
    // One swing already makes a criminal, before the roll (f_attackf.dat:50-63).
    val wasCriminal = p.criminal(now)
    if (!wasCriminal && !guiltyPlayer(t, p, now)) commitCrime(p, "бандит", now)
    playerHitsPlayer(p, t, now, answer = true, attackerWasCriminal = wasCriminal)
    save(p); save(t)
    viewLocked(p)
}

/** Damage bonus of rand(0,10) in a castle the attacker's clan owns (f_attackf.dat:83-87). */
internal suspend fun Game.castleBonus(p: Game.Player, h: Formulas.Hit): Formulas.Hit {
    if (h.outcome != Formulas.Outcome.HIT) return h
    val n = CastleRules.castleOf(p.location)?.takeIf { CastleRules.inside(p.location) } ?: return h
    return if (p.clanId != null && castles()[n]?.clanId == p.clanId) h.copy(damage = h.damage + dice.roll(0, 10)) else h
}

internal suspend fun Game.playerHitsPlayer(a: Game.Player, b: Game.Player, now: Long, answer: Boolean, attackerWasCriminal: Boolean) {
    val h = castleBonus(a, Formulas.attack(a.stats, b.stats, dice))
    if (h.outcome == Formulas.Outcome.FIZZLED) return
    val text = describe(h, a.stats.verb)
    a.fightingPlayer = b.id
    a.log(if (answer) "Вы по ${b.name} $text" else "  вы отвечаете: $text")
    b.log(if (answer) "${a.name} по вам $text" else "  ${a.name} отвечает: $text")
    for (q in players.values) if (q.id != a.id && q.id != b.id && q.location == a.location && now - q.lastSeen < Game.ACTIVE_SECONDS) {
        q.log("${a.name} по ${b.name} $text"); notify(q.id)
    }
    notify(b.id)
    if (h.outcome == Formulas.Outcome.HIT) {
        b.hp -= h.damage
        b.regenFrom = now
        if (b.hp < 1) {
            killPlayer(b, a.name, now, a, attackerWasCriminal)
            return
        }
    }
    // The victim answers at once if not resting — self-defence, no crime.
    if (answer && now >= b.busyUntil && !b.ghost) playerHitsPlayer(b, a, now, answer = false, attackerWasCriminal = b.criminal(now))
}

/**
 * What a player's death means for the killer (f_kill.dat:28-104):
 * «убийца» for killing an innocent; the bounty flags for the officer.
 */
internal suspend fun Game.murder(victim: Game.Player, killer: Game.Player, now: Long, killerWasCriminal: Boolean, victimWasGuilty: Boolean) {
    setState(killer.id, "pk", victim.name, null)
    if (!victimWasGuilty) {
        commitCrime(killer, "убийца", now)
        if (killerWasCriminal) setState(victim.id, "bounty.killer", killer.name, null)
    } else if (victim.criminal(now) && !killer.criminal(now)) {
        setState(killer.id, "bounty.killed", victim.name, null)
    }
}

/** Looting (f_additem.dat:86): taking from an innocent's corpse that is not yours or your clan's. */
internal suspend fun Game.lootCrime(p: Game.Player, corpse: World.Corpse, itemId: String, now: Long) {
    if (corpse.playerId != null && corpse.playerId != p.id && itemId.startsWith("i.q."))
        throw ApiException(HttpStatusCode.BadRequest, Errors.CANNOT_TRADE)
    val mine = corpse.playerId == p.id || (corpse.clanId != null && corpse.clanId == p.clanId)
    if (!corpse.free && !mine && !p.criminal(now)) commitCrime(p, "мародер", now)
}

// ---- city guards and the fighting NPCs of the law ---------------------------------------------

/**
 * One tick of the law in a location: city guards appear in guarded streets
 * (zone 1) where a criminal or a monster is (f_addguard.dat), and every
 * NPC of the law — city guards n.g.*, templars n.t.*, pirates n.p.*,
 * castle guards n.o.* — picks its enemy.
 */
internal suspend fun Game.lawTick(loc: String, living: List<Game.Player>, now: Long) {
    val zone = content.locations[loc]?.zone ?: 0
    val npcs = world.npcsIn(loc)
    val monsters = npcs.filter { it.key.startsWith("n.c.") }
    val criminals = living.filter { it.criminal(now) }
    if (zone == 1 && (criminals.isNotEmpty() || monsters.isNotEmpty()) && npcs.none { it.key.startsWith("n.g.") }) {
        val name = Law.GUARD_NAMES[rnd.nextInt(Law.GUARD_NAMES.size)] + " [стража]"
        val proto = World.Proto("n.g.guard", name, 200, Law.GUARD_STATS, emptyMap(), emptyList(), emptyMap(), null, null)
        world.spawnProto("n.g.${rnd.nextInt(5, 10000)}", proto, loc, now, Law.GUARD_LIFETIME)
        for (q in living) { q.log("Появляется $name"); notify(q.id) }
    }
    for (npc in world.npcsIn(loc)) {
        val k = npc.key
        if (!(k.startsWith("n.g.") || k.startsWith("n.t.") || k.startsWith("n.p.") || k.startsWith("n.o."))) continue
        if (npc.enemies.isNotEmpty() || npc.npcTarget != null) continue
        val foes: List<Game.Player> = when {
            k.startsWith("n.o.") -> {
                val c = CastleRules.castleOf(loc)?.takeIf { CastleRules.inside(loc) }?.let { castles()[it] } ?: continue
                if (c.clanId == null) continue
                living.filter { (it.clanId == null || it.clanId != c.clanId) && it.id !in c.guests }
            }
            k.startsWith("n.t.") && zone == 2 -> living.filter { it.faction == "p" || it.criminal(now) }
            k.startsWith("n.p.") && zone == 3 -> living.filter { it.faction == "t" }
            else -> criminals
        }
        val targets = foes.size + if (k.startsWith("n.o.")) 0 else monsters.size
        if (targets == 0) continue
        val pick = rnd.nextInt(targets)
        if (pick < foes.size) npc.enemies += foes[pick].id else npc.npcTarget = monsters[pick - foes.size].key
    }
}

/** An NPC striking another NPC (a guard and a monster that wandered into town). */
internal suspend fun Game.npcHitsNpc(a: World.Npc, b: World.Npc, now: Long, living: List<Game.Player>) {
    a.busyUntil = now + a.stats.delay
    val h = Formulas.attack(a.stats, b.stats, dice)
    if (h.outcome == Formulas.Outcome.FIZZLED) return
    val line = "${a.name} по ${b.name} ${describe(h, a.stats.verb)}"
    for (q in living) { q.log(line); notify(q.id) }
    if (b.npcTarget == null && b.enemies.isEmpty()) b.npcTarget = a.key
    if (h.outcome == Formulas.Outcome.HIT) {
        b.hp -= h.damage
        b.regenFrom = now
        if (b.hp < 1) {
            world.kill(b, now)
            a.npcTarget = null
            for (q in living) q.log("${b.name} погибает.")
        }
    }
}

// ---- arena ------------------------------------------------------------------------------

/** Leaves the arena by its stone: as a ghost, or as the last one standing (f_take.dat:22). */
internal suspend fun Game.leaveArena(p: Game.Player): String {
    val now = clock()
    val others = players.values.any { it.id != p.id && it.location == Rules.ARENA && !it.ghost && now - it.lastSeen < Game.ACTIVE_SECONDS }
    if (!p.ghost && others) return "Покинуть арену можно либо призраком, либо оставшись единственным в живых."
    p.location = Rules.ARENA_EXIT
    save(p)
    notifyLocation(p.location, except = p.id)
    return "Вы покинули арену"
}

// ---- dialog handlers: arena, bounties, «has killed a player» ------------------------------------

internal suspend fun Game.pvpHandler(p: Game.Player, a: JsonObject, arg: String?, askInput: (String) -> Unit): String? {
    when (a.str("handler")) {
        "arena-enter" -> {
            // The entry fee lies on the arena floor: the last one standing picks it up.
            world.placePermanent(Rules.ARENA, Rules.MONEY, 1000)
            p.location = Rules.ARENA
            save(p)
            notifyLocation(Rules.ARENA, except = p.id)
        }
        "require-pk" -> if (stateOf(p.id, "pk") == null) throw Game.HandlerFailed("")
        "bounty-list" -> {
            val rows = db.tx { c ->
                c.prepareStatement("SELECT ch.name, b.amount FROM bounties b JOIN characters ch ON ch.id = b.character_id ORDER BY b.amount DESC LIMIT 20").use { st ->
                    st.executeQuery().use { rs -> buildList { while (rs.next()) add("${rs.getString(1)} — ${rs.getInt(2)} монет") } }
                }
            }
            return if (rows.isEmpty()) "Сейчас никого не разыскивают." else "Разыскиваются:\n" + rows.joinToString("\n")
        }
        "bounty-form" -> askInput("killedby2")
        "bounty-place" -> {
            val killer = stateOf(p.id, "bounty.killer") ?: throw Game.HandlerFailed("Вам некому назначать награду.")
            val amount = arg?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: throw Game.HandlerFailed("Укажите сумму числом.")
            if ((inventoryMap(p)[Rules.MONEY] ?: 0) < amount) throw Game.HandlerFailed("У вас нет столько денег.")
            val id = db.tx { c ->
                c.prepareStatement("SELECT id FROM characters WHERE world_id = 1 AND name = ?").use { st ->
                    st.setString(1, killer); st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                }
            } ?: throw Game.HandlerFailed("Такого человека больше нет.")
            changeItem(p, Rules.MONEY, -amount)
            db.tx { c ->
                c.prepareStatement(
                    "INSERT INTO bounties (character_id, amount) VALUES (?, ?) ON CONFLICT (character_id) DO UPDATE SET amount = bounties.amount + EXCLUDED.amount, updated_at = now()"
                ).use { st -> st.setLong(1, id); st.setInt(2, amount); st.executeUpdate() }
            }
            clearState(p.id, "bounty.killer")
            return "Награда за голову $killer увеличена на $amount монет."
        }
        "bounty-claim" -> {
            val victim = stateOf(p.id, "bounty.killed") ?: throw Game.HandlerFailed("Вы никого из разыскиваемых не убивали.")
            clearState(p.id, "bounty.killed")
            val amount = db.tx { c ->
                c.prepareStatement("DELETE FROM bounties b USING characters ch WHERE ch.id = b.character_id AND ch.name = ? RETURNING b.amount").use { st ->
                    st.setString(1, victim); st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
                }
            }
            if (amount <= 0) throw Game.HandlerFailed("За голову $victim награды не назначено, но спасибо за службу.")
            changeItem(p, Rules.MONEY, amount)
            return "Вот твоя награда за $victim: $amount монет."
        }
    }
    return null
}

internal suspend fun Game.stateOf(characterId: Long, key: String): String? = db.tx { c ->
    c.prepareStatement("SELECT value FROM character_state WHERE character_id = ? AND key = ? AND (until IS NULL OR until > ?)").use { st ->
        st.setLong(1, characterId); st.setString(2, key); st.setLong(3, clock())
        st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
    }
}
