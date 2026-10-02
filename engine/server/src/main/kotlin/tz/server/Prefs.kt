package tz.server

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.withLock
import tz.shared.AbilityView
import tz.shared.Errors
import tz.shared.GameView
import tz.shared.InventoryItemView
import tz.shared.PrefsRequest

/**
 * The player's screen settings (stage 16, docs/design.md «Раскладка экрана»):
 * three combat buttons after the plain blow and four belt cells for food and
 * potions. Kept in character_state "ui.slots" / "ui.belt" as comma lists.
 */
internal const val SLOTS = 3
internal const val BELT = 4

/** Chosen buttons, or the first spells and techniques learnt (stances are held, not struck). */
internal fun slotsOf(p: Game.Player, abilities: List<AbilityView>): List<String> =
    p.slots?.let { pad(it, SLOTS) } ?: pad(abilities.filter { it.kind != "stance" }.map { it.id }.take(SLOTS), SLOTS)

/** Chosen cells, or the potions and then the food carried. */
internal fun beltOf(p: Game.Player, inventory: List<InventoryItemView>): List<String> =
    p.belt?.let { pad(it, BELT) } ?: pad(
        inventory.filter { it.usable && it.id.startsWith("i.f.") && it.id != "i.f.b.empty" }
            .sortedBy { if (it.id.startsWith("i.f.b.")) 0 else 1 }.map { it.id }.distinct().take(BELT), BELT,
    )

private fun pad(list: List<String>, n: Int) = List(n) { list.getOrNull(it).orEmpty() }

private val ID = Regex("[A-Za-z0-9._-]{1,64}")

/** POST /api/game/prefs. A button must be a spell or technique the character knows; a belt cell any item id. */
suspend fun Game.prefs(account: Account, req: PrefsRequest): GameView = lock.withLock {
    val p = player(account)
    fun bad(): Nothing = throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
    req.slots?.let { slots ->
        if (slots.size > SLOTS) bad()
        if (slots.isEmpty()) { p.slots = null; clearState(p.id, "ui.slots") } else {
            val known = abilities(p, clock()).filter { it.kind != "stance" }.map { it.id }.toSet()
            if (slots.any { it.isNotEmpty() && it !in known }) bad()
            p.slots = pad(slots, SLOTS)
            setState(p.id, "ui.slots", p.slots!!.joinToString(","), null)
        }
    }
    req.belt?.let { belt ->
        if (belt.size > BELT) bad()
        if (belt.isEmpty()) { p.belt = null; clearState(p.id, "ui.belt") } else {
            if (belt.any { it.isNotEmpty() && (!it.startsWith("i.") || !ID.matches(it)) }) bad()
            p.belt = pad(belt, BELT)
            setState(p.id, "ui.belt", p.belt!!.joinToString(","), null)
        }
    }
    viewLocked(p)
}
