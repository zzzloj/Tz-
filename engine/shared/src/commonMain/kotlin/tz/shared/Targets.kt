package tz.shared

/** A button in a «на кого?» list: what to show and what to send as the target. */
data class TargetChoice(val label: String, val value: String)

/**
 * What a spell, technique or item can be aimed at here (AbilityView.target,
 * InventoryItemView.target), for both apps. The server checks again.
 */
object Targets {
    fun choices(kind: String?, game: GameView, exceptItem: String? = null): List<TargetChoice> {
        val me = TargetChoice("на себя", game.character.name)
        val npcs = game.location.npcs.map { TargetChoice(it.name, it.id) }
        val living = game.people.filter { !it.ghost }.map { TargetChoice(it.name, it.name) }
        val everyone = game.people.map { TargetChoice(it.name + if (it.ghost) " (призрак)" else "", it.name) }
        return when (kind) {
            "creature" -> npcs + living
            "creature_self" -> listOf(me) + living + npcs
            "player" -> everyone
            "player_self" -> listOf(me) + living
            "ghost" -> game.people.filter { it.ghost }.map { TargetChoice(it.name, it.name) }
            "npc" -> npcs
            "rune" -> game.inventory.filter { it.id.startsWith("i.rr.") }.map { TargetChoice(it.name, it.id) }
            "item" -> game.inventory.filter { it.id != exceptItem }.map { TargetChoice(it.name, it.id) }
            else -> emptyList()
        }
    }
}
