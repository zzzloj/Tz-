package tz.server

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import tz.shared.DialogOption
import tz.shared.DialogView
import tz.shared.Errors

/*
 * Subordinates (docs/mechanics-progression.md §7, f_owner.dat,
 * f_speakowner.dat): pets bought or tamed, horses and riding, mercenaries,
 * the fairy, summoned creatures, zombies, charmed animals, escorts.
 */

internal object Pets {
    /** A subordinate that has not walked for an hour goes away (f_owner.dat:11). */
    const val IDLE = 3600L
    /** At most this many subordinates of one character follow into one location (f_owner.dat:47-50). */
    const val MAX_HERE = 3
    const val HORSE = "n.a.losh"
    /** Escorts: Jofe counts as rescued once out of the dungeon, below Y 840 (docs/quests.md §29). */
    const val DUNGEON_Y = 840

    /** Summoned creatures of plugin/m.s.dat: name, HP, war numbers. */
    private fun s(hit: Int, min: Int, max: Int, delay: Int, armor: Int, dodge: Int, md: Int, mp: Int, mr: Int, verb: String) =
        Stats(hit, min, max, delay, false, armor, dodge, 0, 0, md, mp, mr, verb, 0, "")
    val SUMMONS: Map<String, Triple<String, Int, Stats>> = mapOf(
        "m.s.wolf" to Triple("призванный волк", 20, s(70, 4, 11, 5, 0, 5, 0, 0, 0, "зубами")),
        "m.s.tree" to Triple("призванное гигантское дерево", 80, s(90, 15, 30, 10, 0, 10, 20, 80, 50, "ветвями")),
        "m.s.volna" to Triple("призванная волна", 80, s(90, 15, 30, 10, 0, 10, 20, 80, 50, "струей воды")),
        "m.s.skeleton" to Triple("призванный скелет", 30, s(65, 5, 16, 6, 0, 10, 0, 0, 0, "бьёт")),
        "m.s.golem" to Triple("призванный голем", 50, s(90, 8, 18, 7, 5, 10, 30, 50, 4, "бьёт")),
        "m.s.fantom" to Triple("призванный фантом", 50, s(100, 4, 12, 4, 0, 10, 10, 70, 40, "магией")),
        "m.s.tucha" to Triple("призванная грозовая туча", 50, s(200, 0, 25, 4, 0, 10, 10, 70, 40, "молнией")),
        "m.s.demon" to Triple("призванный демон", 80, s(95, 10, 25, 7, 3, 10, 20, 70, 8, "бьёт")),
    )

    /** f_rndname.dat: a name from 4–6 letters of syllables, «Garsl». */
    private val SYLLABLES = ("cor ur ae li sim na vax vin lib er mac cam is ta gor i ca um mu og os tru ver vay an as prin su oc dor a mor ia gon ar " +
        "nor ang dai mar grace van dir am va ber em je tar she eru ilu rat gil do ge lad le go bins las gim fro tor tol mab den dag tir nogt es ka bur " +
        "du ran dal ken vap dlo negn mur kok mel rul sa ru gan uuk map blo son eva nul eng zah vat obi no rip bi car ma lan be ril log raf hill nart " +
        "bosk ir gard en ged gob cri man shna god re vur tur el eri ker shed gae bol der desh nol nek dur vek nang zug cup ida lum si jai kon nel jer " +
        "lorn fax got vald lance feld kay had ja gun tal nai ven det nog aro kle vam dam sic erg unk ils dol dul gu arc jin shel chri chra gec apr anu al e u ol it uv ai zu").split(' ')

    fun randomName(dice: Dice): String {
        val sb = StringBuilder()
        val want = dice.roll(4, 6)
        while (sb.length < want) sb.append(SYLLABLES[dice.roll(0, SYLLABLES.size - 1)])
        return sb.toString().replaceFirstChar { it.uppercase() }
    }

    fun y(loc: String): Int? = Regex("^x\\d+x(\\d+)$").find(loc)?.groupValues?.get(1)?.toIntOrNull()
}

/** A horse under the rider: its HP stays as it was (char[12] = "1:<hp>"). */
class Mount(val hp: Int, val name: String?)

private fun Game.Player.isActive(now: Long) = now - lastSeen < Game.ACTIVE_SECONDS

/** Makes [npc] [p]'s: follows and/or guards, leaves at [until] (0 — never), vanishes then or not. */
internal fun Game.adopt(npc: World.Npc, p: Game.Player, follow: Boolean, guard: Boolean, until: Long, vanish: Boolean, now: Long,
                        home: String? = null, flag: String? = null, flagBelowY: Int = 0) {
    npc.owner = World.Owner(p.id, if (follow) p.id else null, if (guard) p.id else null, until, vanish, now + Pets.IDLE, home, flag, flagBelowY)
    npc.enemies.clear()
    npc.npcTarget = null
}

/** Subordinates of [p] standing in [loc]. */
internal suspend fun Game.subordinates(p: Game.Player, loc: String = p.location): List<World.Npc> =
    world.npcsIn(loc).filter { it.owner?.ownerId == p.id }

/** More than two of a mage's creatures here: the oldest go (plugin/m.s.dat:6-17, f_necro.dat:30-40). */
private suspend fun Game.releaseExtras(p: Game.Player, onlySummoned: Boolean, now: Long) {
    val mine = subordinates(p).filter { !onlySummoned || it.key.startsWith("n.s.") }
    for (n in mine.drop(2)) release(n, now, byOwner = false)
}

/**
 * A subordinate leaves (f_owner.dat:11-27, f_speakowner.dat:163-168): home if
 * it has one; otherwise, when its time ran out, back to where it respawns or
 * gone; when let go by the owner, gone if it was to vanish, else it stays wild.
 */
internal suspend fun Game.release(npc: World.Npc, now: Long, byOwner: Boolean) {
    val o = npc.owner ?: return
    players[o.ownerId]?.let { it.log("${npc.name} покинул вас"); notify(it.id) }
    npc.owner = null
    npc.enemies.clear()
    npc.npcTarget = null
    when {
        o.home != null && o.home in content.locations -> world.moveNpc(npc, o.home)
        byOwner && !o.vanish -> {}
        !byOwner && npc.proto.respawn != null -> world.moveNpc(npc, npc.proto.respawn!!.location)
        else -> world.removeNpc(npc.key, npc.location)
    }
}

/**
 * One tick of a subordinate (f_owner.dat): its time, the fairy's healing, the
 * escort's flag, following its leader into the next location, guarding.
 * True if it left this location (moved or went away).
 */
internal suspend fun Game.ownerTick(npc: World.Npc, loc: String, now: Long): Boolean {
    val o = npc.owner ?: return false
    val owner = players[o.ownerId]
    if (npc.criminal && owner != null && owner.location == loc && owner.isActive(now) && !owner.criminal(now)) commitCrime(owner, "преступник", now)
    if ((o.until > 0 && now > o.until) || (now > o.idleUntil && !loc.startsWith("c."))) {
        release(npc, now, byOwner = false)
        return true
    }
    // The fairy heals her mistress every 20 s (f_owner.dat:29-33, n.he.feja: 5..15).
    if (npc.key.startsWith("n.he.") && owner != null && owner.location == loc && !owner.ghost && now >= o.healAt && owner.hp < owner.hpMax) {
        val gain = dice.roll(5, 15).coerceAtMost(owner.hpMax - owner.hp)
        owner.hp += gain
        o.healAt = now + 20
        val words = (content.npcs[npc.proto.template]?.get("h_s") as? JsonPrimitive)?.contentOrNull ?: "Irt Ani"
        owner.log("${npc.name}: $words"); owner.log("${npc.name}: жизнь +$gain")
        tellOthers(loc, owner.id, "${npc.name}: $words")
        notify(owner.id)
    }
    val flag = o.flag
    if (flag != null && owner != null) Pets.y(loc)?.let { y -> if (y < o.flagBelowY) { setState(owner.id, flag, "1", null); o.flag = null } }
    // Following: into the neighbouring location, not after a ghost, not into a crowd of the leader's own.
    val leader = o.follow?.let { players[it] }
    if (leader != null && leader.location != loc && leader.isActive(now) && !leader.ghost &&
        content.locations[loc]?.exits?.any { it.target == leader.location } == true) {
        val there = world.npcsIn(leader.location).count { it.owner?.follow == leader.id || it.owner?.ownerId == leader.id }
        if (there <= Pets.MAX_HERE) {
            npc.enemies.clear()
            npc.npcTarget = null
            if (world.moveNpc(npc, leader.location)) { o.idleUntil = now + Pets.IDLE; return true }
        }
    }
    // Guarding: whoever strikes the guarded one (or its subordinates) becomes the enemy — but not for a criminal in town.
    val gid = o.guard ?: return false
    val guarded = players[gid]?.takeIf { it.location == loc && !it.ghost && it.isActive(now) } ?: return false
    if (content.locations[loc]?.zone == 1 && guarded.criminal(now)) return false
    if (npc.enemies.isNotEmpty() || npc.npcTarget != null) return false
    val ownKeys = world.npcsIn(loc).filter { it.owner?.ownerId == gid }.map { it.key }.toSet()
    val attacker = players.values.firstOrNull { q ->
        q.id != gid && q.id != o.ownerId && q.location == loc && !q.ghost && q.isActive(now) &&
            (q.attackTarget == "u:$gid" || q.attackTarget in ownKeys)
    }
    if (attacker != null) { npc.enemies += attacker.id; return false }
    world.npcsIn(loc).firstOrNull { n -> n.key != npc.key && n.owner?.ownerId != gid && (gid in n.enemies || n.npcTarget in ownKeys) }
        ?.let { npc.npcTarget = it.key }
    return false
}

// ---- talking to your own ---------------------------------------------------------------

/** f_speakowner.dat: orders to a pet, a horse, a mercenary, a summoned creature. */
internal suspend fun Game.petDialog(p: Game.Player, npc: World.Npc, topic: String, arg: String?): DialogView {
    val o = npc.owner!!
    val now = clock()
    val name = npc.name
    val back = listOf(DialogOption("Ещё указания", "begin"))
    val npcsHere = world.npcsIn(p.location)
    fun say(text: String, options: List<DialogOption> = back) = DialogView(npc.key, name, text, options = options)
    fun others(players: Boolean, npcs: Boolean): List<DialogOption> = buildList {
        if (players) for (q in this@petDialog.players.values) if (q.id != p.id && q.location == p.location && !q.ghost && q.isActive(now)) add(DialogOption(q.name, topic, "u:${q.id}"))
        if (npcs) for (n in npcsHere) if (n.key != npc.key) add(DialogOption(n.name, topic, n.key))
    }
    /** At most three subordinates may be tied to one character here (f_speakowner.dat:49-56). */
    fun crowded(targetId: Long): Boolean = npcsHere.count { it.owner?.let { w -> w.ownerId == targetId || w.follow == targetId || w.guard == targetId } == true } > 2
    return when (topic) {
        "sedlo" -> when {
            !npc.key.startsWith(Pets.HORSE) -> say("Это не лошадь")
            p.ghost -> say("Вы призрак, лошадь вас боится и не подпускает близко")
            p.mount != null -> say("Вы и так на коне")
            now < p.busyUntil -> say("Вы должны отдохнуть")
            else -> {
                mountHorse(p, npc, now)
                DialogView(npc.key, name, "Вы сели на лошадь и теперь вы всадник")
            }
        }
        "guardme" -> { o.guard = p.id; o.guardNpc = null; say("$name теперь будет защищать вас") }
        "nelez" -> { o.guard = null; o.guardNpc = null; npc.enemies.clear(); npc.npcTarget = null; say("$name не будет вмешиваться в бой") }
        "guard" -> if (arg == null) say("Выберите, кого защищать:", others(players = true, npcs = false) + back) else {
            val id = arg.removePrefix("u:").toLongOrNull()
            when {
                id == null || players[id]?.location != p.location -> say("Его здесь нет")
                crowded(id) -> say("Наёмникам слишком тесно (можно не более 3 в одном месте)")
                else -> { o.guard = id; say("$name с этого момента будет защищать кого вы указали.") }
            }
        }
        "attack" -> if (arg == null) say("Выберите, кого атаковать:", others(players = true, npcs = true) + back) else when {
            !Law.mayFight(p, null, now) -> say("Нет цели")
            arg.startsWith("u:") -> {
                val q = players[arg.removePrefix("u:").toLongOrNull()]?.takeIf { it.location == p.location && !it.ghost && it.id != p.id }
                if (q == null) say("Нет цели") else {
                    // Setting a pet on an innocent is the owner's crime (f_speakowner.dat:76-80).
                    if (!p.criminal(now) && !guiltyPlayer(q, p, now)) commitCrime(p, "преступник", now)
                    npc.enemies.clear(); npc.enemies += q.id; npc.npcTarget = null
                    say("$name атакует кого вы указали.")
                }
            }
            else -> {
                val t = world.npc(p.location, arg)?.takeIf { it.key != npc.key }
                if (t == null) say("Нет цели") else {
                    val town = content.locations[p.location]?.zone == 1
                    val innocent = !t.key.startsWith("n.c.") && !t.criminal && !t.key.startsWith("n.a.")
                    if (!p.criminal(now) && p.id !in t.enemies && (innocent || (town && t.key.startsWith("n.a.")))) {
                        commitCrime(p, "преступник", now)
                        if (town) npc.criminal = true
                    }
                    npc.enemies.clear(); npc.npcTarget = t.key
                    say("$name атакует кого вы указали.")
                }
            }
        }
        "followme" -> { o.follow = p.id; say("$name теперь будет следовать за вами") }
        "stay" -> { o.follow = null; say("$name будет ждать здесь, пока не позовёте") }
        "follow" -> if (arg == null) say("Выберите, за кем следовать:", others(players = true, npcs = false) + back) else {
            val id = arg.removePrefix("u:").toLongOrNull()
            when {
                id == null || players[id]?.location != p.location -> say("Его здесь нет")
                crowded(id) -> say("Наёмникам слишком тесно (можно не более 3 в одном месте)")
                else -> { o.follow = id; o.guard = id; say("$name с этого момента будет следовать за кем вы указали.") }
            }
        }
        "name" -> when {
            !(npc.key.startsWith("n.a.") || npc.key.startsWith("n.z.") || npc.key.startsWith("n.s.")) ->
                say("Давать имена можно только животным или зомби, у людей уже есть имена ;-)")
            arg.isNullOrBlank() -> DialogView(npc.key, name, "Напишите новое имя (до 10 букв):", inputTopic = "name", options = back)
            else -> {
                val clean = arg.filter { it.isLetterOrDigit() }.take(10)
                if (clean.isEmpty()) say("Не указано имя") else { npc.customName = clean; say("Имя изменено на ${npc.name}") }
            }
        }
        "lask" -> when {
            o.until == 0L -> say("$name будет вам предан всегда, нет нужды в поощрении")
            o.until > now + 240 -> say("$name не собирается покидать вас в ближайшие несколько минут, нет смысла уговаривать его остаться ещё (попробуйте, когда срок службы будет на исходе).")
            else -> {
                p.busyUntil = now + 10
                if (dice.roll(0, 100) < p.skill("animaltaming") * 10) {
                    o.until += dice.roll(60, 60 + p.skill("animallore") * 60)
                    say("Кажется, вы стали $name нравиться немного больше")
                } else say("Ваша попытка не произвела на $name впечатления")
            }
        }
        "info" -> if (o.until == 0L) say("$name никогда вас не покинет") else {
            val left = (o.until - now) / 60.0
            val text = if (left < 60 * 6) {
                // Animal lore makes the guess exact (f_speakowner.dat:150-162).
                val error = dice.roll(0, 10 * (10 - 2 * p.skill("animallore"))) * left / 100
                "${Math.round(left - error).coerceAtLeast(0)} - ${Math.round(left + error)} минут"
            } else "примерно ${Math.round(left / 60)} часа"
            say("$name покинет вас через $text")
        }
        "free" -> { release(npc, now, byOwner = true); DialogView(npc.key, name, "$name покинул вас") }
        else -> say("Слушаю, хозяин.", buildList {
            if (npc.key.startsWith(Pets.HORSE)) add(DialogOption("Сесть в седло", "sedlo"))
            add(DialogOption("Защищай меня", "guardme")); add(DialogOption("Не лезь в драку", "nelez"))
            add(DialogOption("Атакуй…", "attack")); add(DialogOption("Защищай…", "guard"))
            add(DialogOption("Следуй за мной", "followme")); add(DialogOption("Стой здесь", "stay")); add(DialogOption("Следуй за…", "follow"))
            add(DialogOption("Приласкать", "lask")); add(DialogOption("Состояние", "info"))
            if (npc.key.startsWith("n.a.") || npc.key.startsWith("n.z.") || npc.key.startsWith("n.s.")) add(DialogOption("Изменить имя", "name"))
            add(DialogOption("Нам пора расстаться", "free"))
        })
    }
}

// ---- horses ------------------------------------------------------------------------------

/** Into the saddle (f_speakowner.dat:16-31, f_usekon.dat): the horse leaves the location, its HP and name go with the rider. */
internal suspend fun Game.mountHorse(p: Game.Player, horse: World.Npc, now: Long) {
    p.mount = Mount(horse.hp, horse.customName)
    p.busyUntil = now + 3
    world.removeNpc(horse.key, horse.location)
    setState(p.id, "mount", "${horse.hp}|${horse.customName ?: ""}", null)
    refreshStats(p)
    tellOthers(p.location, p.id, "${p.name} сел на лошадь")
}

/**
 * A horse appears beside a character who left the saddle ([hour] — it leaves
 * in an hour: dismounting, knocked off) or died in it (stays, f_kill.dat:43-53).
 */
internal suspend fun Game.leaveHorse(p: Game.Player, now: Long, hour: Boolean) {
    val m = p.mount ?: return
    p.mount = null
    clearState(p.id, "mount")
    val proto = world.proto(Pets.HORSE) ?: return
    val horse = world.spawnProto("${Pets.HORSE}.${dice.roll(99, 99999)}", proto, p.location, now, 0)
    horse.hp = m.hp.coerceIn(1, proto.hpMax)
    horse.customName = m.name
    adopt(horse, p, follow = true, guard = false, until = if (hour) now + 3600 else 0, vanish = hour, now = now)
    refreshStats(p)
}

/** Knocked out of the saddle (plugin/m.kon.dat, technique p.vs): the rider rests 5 s, the horse stays his for an hour. */
internal suspend fun Game.unhorse(t: Game.Player, now: Long) {
    if (t.mount == null) return
    leaveHorse(t, now, hour = true)
    t.busyUntil = now + 5
    t.log("Вы выбиты из седла!")
    tellOthers(t.location, t.id, "${t.name} выбит из седла")
    notify(t.id)
}

// ---- skills: taming, necromancy, dismounting ----------------------------------------------

/** Skill commands of subordinates: animaltaming (target), necro (a corpse id), dismount. */
internal suspend fun Game.petSkill(p: Game.Player, skill: String, target: String?, now: Long) {
    when (skill) {
        "dismount" -> {
            if (p.mount == null) { p.log("Вы не на коне"); return }
            leaveHorse(p, now, hour = true)
            tellOthers(p.location, p.id, "${p.name} спешился")
            p.log("Вы спешились и держите коня за уздечку")
        }
        "animaltaming" -> tame(p, target, now)
        "necro" -> raise(p, target, now)
        else -> throw ApiException(HttpStatusCode.BadRequest, Errors.UNKNOWN_ABILITY)
    }
}

private fun Game.tameDifficulty(npc: World.Npc): Int =
    ((content.npcs[npc.proto.template]?.get("char") as? JsonObject)?.get("tame_difficulty") as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0

/** f_useskill.dat:15-33: tame = 10·(taming + 1 − difficulty), animal lore needed; follows for 1…(1 + lore·10) minutes. */
private suspend fun Game.tame(p: Game.Player, target: String?, now: Long) {
    val npc = target?.let { world.npc(p.location, it) }?.takeIf { it.key.startsWith("n.a.") }
        ?: run { p.log("Нет цели или вы пытаетесь приручить не животное"); return }
    val diff = tameDifficulty(npc)
    if (diff <= 0) { p.log("Это существо не приручаемо"); return }
    // Someone else's pet cannot be taken over (the old engine did not check).
    if (npc.owner != null && npc.owner?.ownerId != p.id) { p.log("${npc.name} принадлежит другому"); return }
    val tame = 10 * (p.skill("animaltaming") + 1 - diff)
    if (tame <= 0 || p.skill("animallore") <= 0) { p.log("У вас слишком низкие навыки изучения и/или приручения животных"); return }
    p.busyUntil = now + 5
    if (dice.roll(0, 100) <= tame) {
        adopt(npc, p, follow = true, guard = false, until = now + 60 + dice.roll(0, p.skill("animallore") * 600), vanish = false, now = now)
        p.count(Stat.TAMED)
        p.log("Вы приручили ${npc.name}")
        addExp(p, dice.roll(0, diff))
    } else p.log("Не удалось приручить ${npc.name}")
}

/** f_necro.dat: a monster's or animal's corpse rises as a zombie for 5–20 minutes. */
private suspend fun Game.raise(p: Game.Player, corpseId: String?, now: Long) {
    val s = p.skill("necro")
    if (s == 0) { p.log("Ваш навык некроманта равен нулю, вы не умеете поднимать мёртвых."); return }
    val corpse = corpseId?.let { world.corpse(p.location, it, now) } ?: run { p.log("Нет цели"); return }
    val template = corpse.template?.takeIf { it.startsWith("n.c.") || it.startsWith("n.a.") }
        ?: run { p.log("Поднимать можно только трупы монстров и животных"); return }
    if (p.mana < 6) { p.log("Недостаточно маны (надо 6)"); return }
    p.mana -= 6
    p.busyUntil = now + 10
    val title = corpse.name.removePrefix("труп: ")
    val bonus = if (p.equipped.any { "..gt" in it }) 10 else 0
    if (dice.roll(0, 100) > s * 5 + p.int * 2 + bonus) {
        p.log("Вам не удалось поднять из мёртвых труп $title")
        tellOthers(p.location, p.id, "${p.name} пытался поднять из мёртвых труп $title")
        return
    }
    releaseExtras(p, onlySummoned = false, now)
    world.removeCorpse(p.location, corpse.id)
    val base = world.proto(template) ?: return
    val hp = Math.round(base.hpMax * 0.7).toInt().coerceAtLeast(1)
    val stats = base.stats.copy(dmgMin = Math.round(base.stats.dmgMin * 0.7).toInt(), dmgMax = Math.round(base.stats.dmgMax * 0.7).toInt(), expValue = 0)
    val proto = World.Proto(template, "$title-зомби", hp, stats, emptyMap(), emptyList(), emptyMap(), null, null)
    val key = "n.z.$template." + (1..4).map { 'a' + dice.roll(0, 25) }.joinToString("")
    val zombie = world.spawnProto(key, proto, p.location, now, 0)
    adopt(zombie, p, follow = true, guard = true, until = now + dice.roll(300, 300 + (s + p.int) * 90), vanish = true, now = now)
    p.log("Вы подняли из мёртвых $title")
    tellOthers(p.location, p.id, "${p.name} поднял из мёртвых $title")
    if (dice.roll(1, 100) < 5) addExp(p, 1)
    if ((content.locations[p.location]?.zone ?: 0) != 0) commitCrime(p, "преступник", now)
}

// ---- spells: summoning, charming, knocking off a horse ---------------------------------------

/** plugin/m.s.dat: a creature follows and guards the mage for 1–10 minutes; the demon is a criminal. */
internal suspend fun Game.summon(p: Game.Player, spell: String, now: Long) {
    val (name, hp, stats) = Pets.SUMMONS[spell] ?: run { p.log("Заклинания $spell не существует"); return }
    if (spell == "m.s.tree" && !world.hasFixture(p.location, "i.s.tree")) { p.log("Рядом нет деревьев."); return }
    if (spell == "m.s.volna" && p.location !in water()) { p.log("Рядом нет воды."); return }
    releaseExtras(p, onlySummoned = true, now)
    val proto = World.Proto(spell, name, hp, stats, emptyMap(), emptyList(), emptyMap(), null, null)
    var key: String
    do { key = "n.${spell.removePrefix("m.")}.${dice.roll(1, 99)}" } while (world.npc(p.location, key) != null)
    val npc = world.spawnProto(key, proto, p.location, now, 0)
    adopt(npc, p, follow = true, guard = true, until = now + dice.roll(60, 600), vanish = true, now = now)
    if (spell == "m.s.demon") { npc.criminal = true; commitCrime(p, "вызвал демона", now) }
    p.log("Появляется $name")
    tellOthers(p.location, p.id, "Появился $name")
}

/** Water for «Волна» (plugin/loc_water): the places where a bottle fills. */
private fun Game.water(): Set<String> =
    Crafting.strings((content.crafting.special["i.f.b.empty"] as? JsonObject)?.get("locations")).toSet()

/** plugin/m.charm.dat: an animal without an owner follows and guards the mage for 1–5 minutes (the firebird rarely). */
internal suspend fun Game.charm(p: Game.Player, npc: World.Npc, now: Long) {
    if (!npc.key.startsWith("n.a.")) { p.log("Заклинание действует только на животных"); return }
    if (npc.owner != null) { p.log("Это животное принадлежит другому"); return }
    if (npc.key.startsWith("n.a.b.jarpt") && dice.roll(0, 100) >= 10) { p.log("Повышенный иммунитет к приручению"); return }
    adopt(npc, p, follow = true, guard = true, until = now + dice.roll(60, 300), vanish = false, now = now)
    p.log("${npc.name} зачарован")
}

/** A summoning scroll i.ms_<npc>[_name] (plugin/i.ms.dat): a creature that follows and guards for good. */
internal suspend fun Game.summonScroll(p: Game.Player, itemId: String, now: Long): Boolean {
    if (!itemId.startsWith("i.ms_")) return false
    val rest = itemId.removePrefix("i.ms_")
    val template = rest.substringBefore('_')
    val given = rest.substringAfter('_', "").takeIf { it.isNotBlank() }
    if (!(template.startsWith("n.c.") || template.startsWith("n.a.")) || template !in content.npcs) { p.log("Призвать можно только монстров и животных"); return true }
    val base = world.proto(template) ?: return true
    changeItem(p, itemId, -1)
    val name = given ?: base.name
    val proto = World.Proto(template, "призванный $name", base.hpMax, base.stats, emptyMap(), emptyList(), base.butcher, null, null)
    val npc = world.spawnProto("n.a.${dice.roll(99, 99999)}", proto, p.location, now, 0)
    adopt(npc, p, follow = true, guard = false, until = 0, vanish = false, now = now)
    p.log("Вы призвали $name")
    tellOthers(p.location, p.id, "${p.name} призвал $name")
    return true
}

// ---- dialog handlers -------------------------------------------------------------------------

private fun JsonObject.template(): String? = str("template") ?: str("npc")

/** Guard checks of subordinate handlers: false skips the rule. */
internal suspend fun Game.petGuard(p: Game.Player, a: JsonObject): Boolean = when (a.str("handler")) {
    // «You already have one bought here»: any of the listed kinds (or [npc]) of yours stands here.
    "pet-owned-here" -> {
        val kinds = Crafting.strings(a["npcs"]).ifEmpty { listOfNotNull(a.template()) }
        world.npcsIn(p.location).any { n -> n.owner?.ownerId == p.id && kinds.any { k -> n.key == "$k.${p.id}" } }
    }
    "pet-free" -> world.npc(p.location, a.template()!!)?.owner == null && world.npc(p.location, a.template()!!) != null
    "pet-return", "marten-unicorn" -> world.npc(p.location, a.template() ?: "n.a.edinorog.1")?.owner?.ownerId == p.id
    "sacrifice-pet" -> world.npcsIn(p.location).any { it.owner?.ownerId == p.id && it.key.startsWith(a.template()!!) }
    "hire-mercenary" -> a.template()?.startsWith("n.o.") == true ||
        !(Dialogs.truthy(a["perPlayer"]) && world.allNpcs().any { it.key == "${a.template()}.${p.id}" })
    "escort" -> world.npc(p.location, a.template()!!)?.let { it.owner == null || it.owner?.ownerId == p.id } == true
    else -> true
}

/** Subordinate handlers (buy, hire, give back, sacrifice, escort); a thrown HandlerFailed shows its text. */
internal suspend fun Game.petHandler(p: Game.Player, a: JsonObject, vars: MutableMap<String, String>): String? {
    val now = clock()
    when (a.str("handler")) {
        "buy-pet" -> {
            val template = a.template()!!
            val key = "$template.${p.id}"
            if (world.allNpcs().any { it.key == key }) throw Game.HandlerFailed("Извини, у тебя уже есть такое животное")
            val proto = world.proto(template) ?: throw Game.HandlerFailed("Животных сейчас нет")
            val npc = world.spawnProto(key, proto, p.location, now, 0)
            adopt(npc, p, follow = true, guard = Dialogs.truthy(a["guard"]) && "guard" in a, until = 0, vanish = false, now = now)
        }
        "pet-free", "pet-owned-here", "marten-unicorn" -> {}
        "sell-pet" -> {
            val npc = world.npc(p.location, a.template()!!) ?: throw Game.HandlerFailed("Её здесь нет")
            adopt(npc, p, follow = true, guard = false, until = now + (a.int("for") ?: 1800), vanish = false, now = now, home = a.str("home"))
        }
        "pet-return" -> world.npc(p.location, a.template()!!)?.let { n ->
            n.owner = null
            world.despawn(n, now, a.str("respawn"), a.int("min") ?: 600, a.int("max") ?: 1200)
        }
        "sacrifice-pet" -> world.npcsIn(p.location).firstOrNull { it.owner?.ownerId == p.id && it.key.startsWith(a.template()!!) }
            ?.let { world.despawn(it, now) }
        "hire-mercenary" -> {
            val template = a.template()!!
            if (template.startsWith("n.o.")) { hireCastleGuard(p, template); return null }
            val proto = world.proto(template) ?: throw Game.HandlerFailed("Сейчас все заняты")
            val name = Pets.randomName(dice) + " " + (a.str("title") ?: "[наемник]")
            val hired = World.Proto(template, name, proto.hpMax, proto.stats, emptyMap(), emptyList(), emptyMap(), null, null)
            val npc = world.spawnProto("$template.${p.id}", hired, p.location, now, 0)
            adopt(npc, p, follow = true, guard = !("guard" in a) || Dialogs.truthy(a["guard"]), until = now + (a.int("for") ?: 10800),
                vanish = !("vanish" in a) || Dialogs.truthy(a["vanish"]), now = now)
            vars["name"] = name
        }
        "hire-fairy" -> {
            val key = "n.he.feja.${p.id}"
            if (world.allNpcs().any { it.key == key }) throw Game.HandlerFailed("Извини, у тебя уже есть фея")
            val points = a.int("points") ?: 1
            if (p.points < points) throw Game.HandlerFailed("У тебя не достаточно очков опыта, приходи когда станешь опытнее.")
            p.points -= points
            val proto = world.proto("n.he.feja") ?: throw Game.HandlerFailed("Фей сейчас нет")
            val npc = world.spawnProto(key, proto, p.location, now, 0)
            adopt(npc, p, follow = true, guard = true, until = now + (a.int("hours") ?: 2) * 3600L, vanish = true, now = now)
            refreshStats(p)
        }
        "kasten-squad" -> {
            // Four of Ditrih's guards go with you for a minute and a half (quest 21).
            val proto = world.proto("n.strditrih") ?: return null
            for (i in 1..4) {
                val npc = world.spawnProto("n.strditrih$i.${p.id}", proto, p.location, now, 0)
                adopt(npc, p, follow = true, guard = true, until = now + 90, vanish = true, now = now)
            }
        }
        "escort" -> {
            val npc = world.npc(p.location, a.template()!!) ?: throw Game.HandlerFailed("Прощай, <imja>")
            adopt(npc, p, follow = true, guard = false, until = now + (a.int("minutes") ?: 30) * 60L, vanish = false, now = now,
                flag = a.str("outsideFlag"), flagBelowY = Pets.DUNGEON_Y)
        }
    }
    return null
}

/** Half the experience of a kill by a pet, summoned creature or zombie goes to whom it follows (f_kill.dat:115-135). */
internal suspend fun Game.petKillExp(killer: World.Npc, victim: World.Npc) {
    val o = killer.owner ?: return
    if (!(killer.key.startsWith("n.s.") || killer.key.startsWith("n.z.") || killer.key.startsWith("n.a."))) return
    val to = o.follow?.let { players[it] } ?: return
    addExp(to, victim.stats.expValue / 2)
}
