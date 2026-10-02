package tz.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.regex.Pattern

/**
 * The painted art of content/art (content/art/README.md): which picture goes
 * with a location, an NPC or a monster (by the rules files), and the files
 * themselves for the apps, under /art/… (stage 16).
 *
 * Paths given to the apps are relative to /art: "locations/loc-road",
 * "npcs/npc-beginner", "mobs/mob-wolf"; items are asked for by id at
 * /art/item/<id> and the server finds the base picture of a variant.
 */
class Art(contentDir: File) {
    val root: File = contentDir.resolve("art").canonicalFile

    private class Rules(val rules: List<Pair<Pattern, String>>, val default: String?, val undead: Pattern?, val fallback: Map<String, String> = emptyMap())

    private fun rules(name: String): Rules {
        val f = root.resolve(name)
        if (!f.isFile) return Rules(emptyList(), null, null)
        val o = Json.parseToJsonElement(f.readText()).jsonObject
        fun pattern(rx: String) = Pattern.compile(rx, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE)
        val list = (o["rules"] as? JsonArray).orEmpty().mapNotNull { r ->
            val pair = r.jsonArray
            val rx = (pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val key = (pair.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            pattern(rx) to key
        }
        val fallback = (o["fallback"] as? kotlinx.serialization.json.JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
        return Rules(list, (o["default"] as? JsonPrimitive)?.contentOrNull, (o["undead"] as? JsonPrimitive)?.contentOrNull?.let(::pattern), fallback)
    }

    private val locations = rules("locations.json")
    private val npcs = rules("npcs.json")
    private val mobs = rules("mobs.json")

    private fun exists(path: String) = root.resolve("$path.webp").isFile

    private fun match(r: Rules, name: String): String? = r.rules.firstOrNull { it.first.matcher(name).find() }?.second

    /** Picture of a location by its name; the name stops before castle gate state («*», «[», «:», «#»). */
    fun location(name: String): String? {
        val clean = name.split('*', '[', ':', '#').first().trim()
        val key = match(locations, clean) ?: locations.default ?: return null
        return listOfNotNull(key, locations.fallback[key], locations.default).map { "locations/$it" }.firstOrNull(::exists)
    }

    /** Portrait of an NPC, a monster or an animal; a risen corpse («…-зомби») gets the living one's portrait. */
    fun creature(name: String): Pair<String, Boolean>? {
        val undead = npcs.undead?.matcher(name)?.find() == true
        match(npcs, name)?.let { k -> if (exists("npcs/$k")) return "npcs/$k" to undead }
        match(mobs, name)?.let { k -> if (exists("mobs/$k")) return "mobs/$k" to undead }
        return null
    }

    /** The picture file of an item id: the id itself, then its base (before «..», «_», «-N-»). */
    fun itemFile(id: String): File? {
        if (!id.matches(Regex("[A-Za-z0-9._-]+"))) return null
        val candidates = listOf(id, id.substringBefore(".."), id.substringBefore('_'), id.substringBefore('_').substringBefore('-').substringBefore(".."))
        return candidates.distinct().map { root.resolve("items/$it.webp") }.firstOrNull { it.isFile }
    }

    /** A file under content/art for /art/<dir>/<name>; nothing outside it, only pictures. */
    fun file(dir: String, name: String): File? {
        if (dir !in setOf("locations", "npcs", "mobs", "items", "brand")) return null
        if (!name.matches(Regex("[A-Za-z0-9._-]+\\.(webp|png)"))) return null
        val f = root.resolve(dir).resolve(name).canonicalFile
        return f.takeIf { it.isFile && it.path.startsWith(root.path + File.separator) }
    }

    companion object {
        val WEBP = ContentType("image", "webp")

        /** A week: pictures change only with a new build, and the ETag catches that. */
        const val MAX_AGE = 7 * 24 * 3600
    }
}

/** GET /art/item/{id}, /art/{dir}/{file}: pictures for the apps and the site, cached by the clients. */
fun Route.artRoutes(art: Art) {
    suspend fun io.ktor.server.application.ApplicationCall.picture(f: File?) {
        if (f == null) { respond(HttpStatusCode.NotFound); return }
        val tag = "\"${f.length().toString(16)}-${f.lastModified().toString(16)}\""
        response.header(HttpHeaders.CacheControl, "public, max-age=${Art.MAX_AGE}")
        response.header(HttpHeaders.ETag, tag)
        if (request.headers[HttpHeaders.IfNoneMatch] == tag) { respond(HttpStatusCode.NotModified); return }
        respondBytes(f.readBytes(), if (f.name.endsWith(".png")) ContentType.Image.PNG else Art.WEBP)
    }
    get("/art/item/{id}") { call.picture(art.itemFile(call.parameters["id"].orEmpty().removeSuffix(".webp"))) }
    get("/art/{dir}/{file}") { call.picture(art.file(call.parameters["dir"].orEmpty(), call.parameters["file"].orEmpty())) }
}
