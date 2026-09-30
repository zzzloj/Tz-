package tz.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import tz.shared.DialogOption
import tz.shared.Rules
import java.io.File

/**
 * Dialogs of content/dialogs plus the declarative logic of content/logic
 * (content/logic/README.md) that replaces the old `eval:` PHP.
 */
class Dialogs(private val content: Content, dir: File) {
    class Rule(
        val conditions: List<JsonObject>,
        val actions: List<JsonObject>,
        val text: String?,
        val options: List<Choice>?,
        val hide: Set<String>,
        val goto: String?,
    )

    /** A choice in logic: shown only when its conditions hold. */
    class Choice(val label: String, val goto: String, val arg: String?, val conditions: List<JsonObject>)

    class Timer(val scope: String, val min: Int, val max: Int, val about: String)

    /** Dialog id → topic → rules. */
    val logic = HashMap<String, Map<String, List<Rule>>>()
    val timers = HashMap<String, Timer>()
    /** Joke files content/raw/speak/h.* (n.Garry and others tell a random line). */
    val jokes = HashMap<String, List<String>>()
    val problems = ArrayList<String>()
    /** Engine values of dialogs that were PHP (raw/speak): trade lines like "buy": "1|1200". */
    val rawTopics = HashMap<String, Map<String, String>>()

    /** Topic names of logic files seen so far, so a topic may point to one defined later in the same file. */
    private val logicTopicsPending = HashMap<String, MutableSet<String>>()

    private val deferred = ArrayList<() -> Unit>()

    private val skillKeys = Rules.SKILLS.map { it.first }.toSet() + setOf("exp", "points")

    init {
        val raw = dir.resolve("raw/speak")
        for (f in raw.listFiles { f -> f.name.startsWith("h.") } ?: emptyArray()) {
            jokes[f.name] = f.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        }
        val logicDir = dir.resolve("logic")
        logicDir.resolve("timers.json").takeIf { it.isFile }?.let { f ->
            for ((key, v) in Json.parseToJsonElement(f.readText()).jsonObject) {
                val o = v as? JsonObject ?: run { problems += "timers.json: $key is not an object"; null } ?: continue
                val scope = o.str("scope") ?: "player"
                if (scope != "player" && scope != "world") problems += "timers.json: $key: scope must be player or world"
                val min = o.int("min") ?: 0
                val max = o.int("max") ?: min
                if (min <= 0 || max < min) problems += "timers.json: $key: bad min/max"
                timers[key] = Timer(scope, min, max, o.str("about") ?: "")
            }
        }
        for (f in (logicDir.listFiles { f -> f.name.endsWith(".json") && f.name != "timers.json" } ?: emptyArray()).sortedBy { it.name }) {
            val id = f.name.removeSuffix(".json")
            try {
                val o = Json.parseToJsonElement(f.readText()).jsonObject
                (o["engineTopics"] as? JsonObject)?.let { e ->
                    rawTopics[id] = e.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: "" }
                }
                val topics = o["topics"] as? JsonObject ?: run { problems += "logic/$id: no topics"; continue }
                logic[id] = topics.mapValues { (topic, rules) ->
                    ((rules as? JsonArray) ?: JsonArray(listOf(rules))).map { parseRule(it.jsonObject) }
                        .also { validateTopic(id, topic, it) }
                }
            } catch (e: Exception) {
                problems += "logic/$id: ${e.message}"
            }
        }
    }

    private fun parseRule(o: JsonObject) = Rule(
        conditions = (o["if"] as? JsonArray).orEmpty().map { it.jsonObject },
        actions = (o["do"] as? JsonArray).orEmpty().map { it.jsonObject },
        text = o.str("text"),
        options = (o["options"] as? JsonArray)?.map { e ->
            val x = e.jsonObject
            Choice(x.str("label") ?: "", x.str("goto") ?: "", x.str("arg"), (x["if"] as? JsonArray).orEmpty().map { it.jsonObject })
        },
        hide = (o["hide"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet(),
        goto = o.str("goto"),
    )

    // ---- validation -------------------------------------------------------------------

    private fun topicExists(dialog: String, topic: String) =
        topic == "end" || topic in Content.ENGINE_TOPICS || logic[dialog]?.containsKey(topic) == true ||
            (content.dialogs[dialog]?.get("topics") as? JsonObject)?.containsKey(topic) == true ||
            logicTopicsPending[dialog]?.contains(topic) == true

    private fun validateTopic(dialog: String, topic: String, rules: List<Rule>) {
        logicTopicsPending.getOrPut(dialog) { HashSet() } += topic
        val where = "logic/$dialog/$topic"
        for (r in rules) {
            r.conditions.forEach { checkCondition(where, it) }
            r.actions.forEach { checkAction(where, it) }
            r.options?.forEach { o -> o.conditions.forEach { checkCondition(where, it) } }
        }
        deferred += {
            for (r in rules) {
                r.goto?.let { if (!topicExists(dialog, it)) problems += "$where: goto missing topic \"$it\"" }
                r.options?.forEach { if (!topicExists(dialog, it.goto)) problems += "$where: option goes to missing topic \"${it.goto}\"" }
            }
        }
    }

    /** Checks that need all files loaded (topics defined in other rules). Called once after init. */
    fun finishValidation(): Dialogs {
        deferred.forEach { it() }
        deferred.clear()
        return this
    }

    private fun checkItem(where: String, id: String?) {
        if (id == null) { problems += "$where: item id missing"; return }
        if (content.items[Formulas.baseId(id).substringBefore("..")] == null && content.items[id] == null)
            problems += "$where: unknown item $id"
    }

    private fun checkTimer(where: String, key: String?) {
        if (key == null || key !in timers) problems += "$where: unknown timer $key (add it to timers.json)"
    }

    private fun checkCondition(where: String, c: JsonObject) {
        when (c.keys.firstOrNull { it in CONDITIONS }) {
            "has", "lacks" -> checkItem(where, c.str(c.keys.first { it == "has" || it == "lacks" }))
            "equipped", "here", "notHere" -> {}
            "skill" -> if (c.str("skill") !in skillKeys) problems += "$where: unknown skill ${c.str("skill")}"
            "ready", "waiting" -> checkTimer(where, c.str(if ("ready" in c) "ready" else "waiting"))
            "any" -> (c["any"] as? JsonArray).orEmpty().forEach { checkCondition(where, it.jsonObject) }
            "not" -> (c["not"] as? JsonObject)?.let { checkCondition(where, it) } ?: run { problems += "$where: not needs a condition" }
            "npcAt", "noNpcAt" -> c.str("location")?.let { if (it !in content.locations) problems += "$where: unknown location $it" }
            null -> problems += "$where: unknown condition $c"
            else -> {}
        }
    }

    private fun checkAction(where: String, a: JsonObject) {
        when (a.keys.firstOrNull { it in ACTIONS }) {
            "take", "give", "place", "giveNpc" -> checkItem(where, a.str(a.keys.first { it in setOf("take", "give", "place", "giveNpc") }))
            "start", "stop" -> checkTimer(where, a.str(if ("start" in a) "start" else "stop"))
            "teach" -> {
                val what = a.str("teach") ?: ""
                if (!what.startsWith("m.") && !what.startsWith("p.") && what !in skillKeys) problems += "$where: teach unknown skill $what"
            }
            "teleport" -> if (a.str("teleport") !in content.locations) problems += "$where: unknown location ${a.str("teleport")}"
            "spawn" -> {
                val t = a.str("spawn") ?: ""
                if (t !in content.npcs) problems += "$where: unknown NPC template $t"
                a.str("location")?.let { if (it !in content.locations) problems += "$where: unknown location $it" }
            }
            "say" -> if (a.str("say") !in jokes) problems += "$where: unknown joke file ${a.str("say")}"
            null -> problems += "$where: unknown action $a"
            else -> {}
        }
    }

    /** Topics with eval: that have no logic here; the player sees «not moved yet». */
    fun untranslated(): List<String> = buildList {
        for ((id, d) in content.dialogs) {
            val topics = d["topics"] as? JsonObject ?: continue
            for ((topic, v) in topics) {
                if (isEval(v) && logic[id]?.containsKey(topic) != true) add("$id/$topic")
            }
        }
        for ((id, topics) in logic) for ((topic, rules) in topics) {
            val waiting = rules.flatMap { r -> r.actions.filter { it.containsKey("handler") && !supported(it) }.mapNotNull { it.str("handler") } }.toSet()
            if (waiting.isNotEmpty()) add("$id/$topic (handler ${waiting.joinToString()})")
        }
    }.sorted()

    /** A topic with PHP in its text or in any option label. */
    fun isEval(v: JsonElement?): Boolean = when (v) {
        is JsonPrimitive -> v.contentOrNull?.startsWith("eval:") == true
        is JsonObject -> v.str("text")?.startsWith("eval:") == true ||
            (v["options"] as? JsonArray).orEmpty().any { (it as? JsonObject)?.str("label")?.startsWith("eval:") == true }
        else -> false
    }

    /** The topic as written in content/dialogs: text and options (null if there is no such topic). */
    fun baseTopic(dialog: String, topic: String): Pair<String, List<DialogOption>>? {
        val v = (content.dialogs[dialog]?.get("topics") as? JsonObject)?.get(topic) ?: return null
        return when (v) {
            is JsonPrimitive -> (v.contentOrNull ?: "") to emptyList()
            is JsonObject -> (v.str("text") ?: "") to (v["options"] as? JsonArray).orEmpty().mapNotNull { e ->
                val x = e as? JsonObject ?: return@mapNotNull null
                DialogOption(x.str("label") ?: "", x.str("goto") ?: "")
            }
            else -> null
        }
    }

    fun hasDialog(id: String) = id in content.dialogs || id in logic

    companion object {
        val CONDITIONS = setOf(
            "has", "lacks", "money", "equipped", "skill", "newbie", "ready", "waiting", "flag", "noflag",
            "known", "unknown", "here", "notHere", "npcAt", "noNpcAt", "arg", "chance", "sex", "ghost", "any", "not",
        )
        val ACTIONS = setOf(
            "take", "give", "exp", "start", "stop", "set", "clear", "learn", "teach", "teleport", "spawn", "remove",
            "place", "removeHere", "resurrect", "heal", "say", "journal", "handler", "giveNpc",
        )

        /**
         * Handlers written in the server so far (Game.kt). Topics using other
         * handlers show «not moved yet» until their stage: pets and
         * mercenaries, clans and castles, PvP, weddings, arena.
         */
        val HANDLERS = setOf("arena-count", "hide-item-random", "repair-boat", "lower-int", "npc-hand-over", "require-pk",
            "clan-status", "clan-leave", "clan-name-input", "clan-create", "clan-restore",
            "castle-keeper-access", "castle-rune-list", "castle-contract", "castle-teleport",
            "arena-enter", "bounty-list", "bounty-form", "bounty-place", "bounty-claim",
            "hire-mercenary", "buy-pet", "pet-owned-here", "pet-free", "sell-pet", "pet-return", "marten-unicorn", "sacrifice-pet",
            "hire-fairy", "kasten-squad", "escort",
            "wedding", "tomrak-armor", "tomrak-life", "gred-bouquet-give", "gred-bouquet-take", "thieves-contract", "smsCode", "claim-dublons")

        /** A handler action the server can run; mercenaries only as castle guards so far (n.o.*). */
        fun supported(a: JsonObject): Boolean {
            val h = a.str("handler") ?: return true
            return h in HANDLERS
        }

        /** Old texts are WML: line breaks as <br/>, occasional tags. The apps show plain text. */
        fun plain(text: String): String = text
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</?p>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<(?!imja>)[^>]+>"), "")
            .replace("{{sid}}", "")
            .replace("&quot;", "\"").replace("&amp;", "&")
            .lines().joinToString("\n") { it.trim() }.trim()

        fun truthy(e: JsonElement?): Boolean = when (e) {
            null -> true
            is JsonPrimitive -> e.booleanOrNull ?: (e.intOrNull?.let { it != 0 } ?: true)
            else -> true
        }
    }
}
