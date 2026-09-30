package tz.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tz.shared.ContentReport
import tz.shared.ExitView
import tz.shared.LocationView
import tz.shared.NpcView
import java.io.File

/**
 * The world as described by content/ (see content/README.md). Only what the
 * current API needs is typed; the rest stays as JSON until its rules move
 * to this engine.
 */
class Content(
    val locations: Map<String, Location>,
    val items: Map<String, JsonObject>,
    val npcs: Map<String, JsonObject>,
    val dialogs: Map<String, JsonObject>,
    val problems: List<String>,
    /** Location files as loaded: objects (NPCs, items) and timers of the starting world. */
    val locationData: Map<String, JsonObject> = emptyMap(),
    val dir: File = File("."),
) {
    /** Site pages (content/pages/NN_id.md, first line «# Title»), in file order. */
    val pages: List<tz.shared.PageView> by lazy {
        (dir.resolve("pages").listFiles { f -> f.name.endsWith(".md") } ?: emptyArray()).sortedBy { it.name }.map { f ->
            val lines = f.readText().trim().lines()
            val title = lines.firstOrNull()?.removePrefix("#")?.trim() ?: f.name
            tz.shared.PageView(f.name.removeSuffix(".md").substringAfter('_'), title, lines.drop(1).joinToString("\n").trim())
        }
    }

    /** Doubloon prices of the second shop (content/items_dublon, n.kenius «buy2»). */
    val dublonPrices: Map<String, JsonObject> by lazy {
        (dir.resolve("items_dublon").listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .associate { f -> f.name.removeSuffix(".json") to json.parseToJsonElement(f.readText()).jsonObject }
    }

    /** Crafting, gathering and item use (content/crafting.json). */
    val crafting: Crafting by lazy { Crafting.load(dir.resolve("crafting.json")) }

    /** Declarative dialog and quest logic, content/logic (built on first use). */
    val logic: Dialogs by lazy { Dialogs(this, dir).finishValidation() }

    /**
     * Display name of an item id. Instance ids carry suffixes after the base
     * id (maker "_…", sharpening "-N-", gems "..x"), see docs/data-fields.md §0.
     */
    fun itemName(id: String): String {
        val base = id.substringBefore('_').substringBefore('-').substringBefore("..")
        val o = items[id] ?: items[base]
        (o?.get("name") as? JsonPrimitive)?.contentOrNull?.let { return it }
        // Teleport runes: sold empty, marked by m.mark with the place (plugin/i.rr.empty, m.mark.dat).
        if (id == "i.rr.empty") return "руна телепортации (пустая)"
        if (id.startsWith("i.rr.")) return "руна телепортации: " + (locations[id.removePrefix("i.rr.")]?.name ?: id.removePrefix("i.rr."))
        return id
    }

    data class Location(
        val id: String,
        val name: String,
        val zone: Int,
        val description: String?,
        val exits: List<ExitView>,
        val npcs: List<NpcView>,
    ) {
        fun view() = LocationView(id, name, zone, description, exits, npcs)
    }

    fun report() = ContentReport(locations.size, items.size, npcs.size, dialogs.size, problems + logic.problems, logic.untranslated())

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Dialog topics handled by the engine itself (f_speak.dat): trade and bank. */
        val ENGINE_TOPICS = setOf("buy", "buy2", "sell", "tobank", "frombank")

        fun load(dir: File): Content {
            require(dir.isDirectory) { "content directory not found: $dir" }
            val problems = mutableListOf<String>()
            fun objects(sub: String): Map<String, JsonObject> =
                (dir.resolve(sub).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
                    .sortedBy { it.name }
                    .associate { f -> f.name.removeSuffix(".json") to json.parseToJsonElement(f.readText()).jsonObject }

            val locationData = objects("locations")
            val locations = locationData.mapValues { (id, o) -> location(id, o) }
            val items = objects("items")
            val npcs = objects("npcs")
            val dialogs = objects("dialogs")

            for (loc in locations.values) for (exit in loc.exits) {
                if (exit.target !in locations) problems += "location ${loc.id}: exit \"${exit.label}\" leads to missing ${exit.target}"
            }
            for ((id, d) in dialogs) {
                val topics = d["topics"] as? JsonObject ?: continue
                for ((topic, value) in topics) {
                    val options = (value as? JsonObject)?.get("options") as? JsonArray ?: continue
                    for (option in options) {
                        val goto = (option as? JsonObject)?.get("goto")?.jsonPrimitive?.contentOrNull ?: continue
                        if (goto.isNotEmpty() && goto !in topics && goto !in ENGINE_TOPICS) problems += "dialog $id/$topic: goto missing topic \"$goto\""
                    }
                }
            }
            return Content(locations, items, npcs, dialogs, problems, locationData, dir)
        }

        private fun location(id: String, o: JsonObject): Location {
            // Castle gates keep state after '*' in the name; it is never shown.
            var name = (o["name"] as? JsonPrimitive)?.contentOrNull?.substringBefore('*') ?: id
            // Castle gates kept their state in the name; an unowned one has "[knock]:guests{lock}" left.
            if (id.endsWith(".gate")) name = name.split('[', '{', ':', '#', '(').first().trim()
            val zone = (o["zone"] as? JsonPrimitive)?.intOrNull ?: 0
            val exits = (o["exits"] as? JsonArray).orEmpty().mapNotNull { e ->
                val x = e as? JsonObject ?: return@mapNotNull null
                val target = x["target"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                ExitView(x["label"]?.jsonPrimitive?.contentOrNull ?: target, target)
            }
            val npcs = (o["objects"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
                npcName(value)?.let { NpcView(key, it) }
            }
            return Location(id, name, zone, (o["description"] as? JsonPrimitive)?.contentOrNull, exits, npcs)
        }

        /** NPC entries are objects with "char"; items are plain strings. */
        private fun npcName(value: JsonElement): String? {
            val char = (value as? JsonObject)?.get("char") ?: return null
            return when (char) {
                is JsonObject -> char["name"]?.jsonPrimitive?.contentOrNull
                is JsonPrimitive -> char.contentOrNull?.substringBefore('|')
                else -> null
            }?.takeIf { it.isNotBlank() }
        }
    }
}
