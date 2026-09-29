package tz.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Parity with the old engine: the real game/1/f_calcparam.dat (PHP) and
 * Formulas.player() compute the parameters of the same random characters.
 * Needs `php` and `python3` (CI has both); skipped otherwise.
 */
class CalcparamParityTest {
    private val content by lazy { Content.load(contentDir()) }
    private val repo = contentDir().absoluteFile.parentFile

    private fun available(cmd: String) = try {
        ProcessBuilder(cmd, "--version").redirectErrorStream(true).start().waitFor() == 0
    } catch (e: Exception) { false }

    @Test
    fun playerParametersMatchTheOldEngine() {
        if (!available("php") || !available("python3")) { println("parity: php/python3 missing, skipping"); return }
        val tmp = Files.createTempDirectory("tz-parity").toFile()
        val game = File(tmp, "game1")
        run("python3", File(repo, "tools/content/build.py").path, File(repo, "content").path, game.path)

        val random = Random(7)
        val weapons = content.items.keys.filter { it.startsWith("i.w.") && '_' !in it && '-' !in it }.sorted()
        val armour = content.items.keys.filter { it.startsWith("i.a.") && '_' !in it && '-' !in it }.sorted()
        data class Case(val skills: Skills, val equip: List<String>, val mounted: Boolean)
        val cases = (1..400).map {
            val s = Skills.of(random.nextInt(1, 6), random.nextInt(1, 6), random.nextInt(1, 6), 0, random.nextInt(0, 4))
            for (i in listOf(Skills.HAND, Skills.COLD, Skills.RANGED, Skills.PARRY, Skills.DODGE, Skills.MRES, Skills.MDODGE))
                s[i] = random.nextInt(0, 6)
            val equip = mutableListOf<String>()
            if (random.nextInt(4) > 0) equip += weapons[random.nextInt(weapons.size)]
            repeat(random.nextInt(0, 4)) {
                val a = armour[random.nextInt(armour.size)]
                if (equip.none { it.take(6) == a.take(6) }) equip += a
            }
            Case(s, equip, random.nextInt(5) == 0)
        }

        val input = buildJsonArray {
            for (c in cases) add(buildJsonObject {
                put("skills", buildJsonArray { for (i in 0 until Skills.SIZE) add(JsonPrimitive(c.skills[i])) })
                put("equip", buildJsonArray { c.equip.forEach { add(JsonPrimitive(it)) } })
                put("mounted", c.mounted)
            })
        }
        val script = File(tmp, "calc.php").apply {
            writeText(CalcparamParityTest::class.java.getResource("/calcparam.php")!!.readText())
        }
        val output = run(
            "php", script.path,
            File(repo, "game/lib/legacy.php").path, game.path, File(repo, "game/1/f_calcparam.dat").path,
            stdin = input.toString(),
        )
        val results = Json.parseToJsonElement(output).jsonArray
        assertEquals(cases.size, results.size)

        var checked = 0
        for ((c, r) in cases.zip(results)) {
            val war = r.jsonObject["war"]!!.jsonPrimitive.content.split('|')
            val char = r.jsonObject["char"]!!.jsonPrimitive.content.split('|')
            val k = Formulas.player(c.skills, c.equip, { content.items[it] }, c.mounted)
            val php = listOf(war[0], war[1], war[2], war[3], war[4], war[5], war[6], war[7], war[8], war[9], war[10], war[11], war[12], war[13], war[14])
            val kt = listOf(k.hit, k.dmgMin, k.dmgMax, k.delay, if (k.ranged) 1 else 0, k.armor, k.dodge, k.parry, k.shieldArmor,
                k.magicDodge, k.magicParry, k.magicResist, k.verb, k.expValue, k.ammo).map { it.toString() }
            assertEquals(php, kt, "skills=${(0 until 16).map { c.skills[it] }} equip=${c.equip} mounted=${c.mounted}")
            assertEquals(char[2].toInt(), tz.shared.Rules.hpMax(c.skills[Skills.STR]))
            assertEquals(char[4].toInt(), tz.shared.Rules.manaMax(c.skills[Skills.INT]))
            checked++
        }
        println("parity: $checked characters identical to f_calcparam.dat")
        assertTrue(checked == cases.size)
    }

    private fun run(vararg cmd: String, stdin: String? = null): String {
        val p = ProcessBuilder(*cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        if (stdin != null) p.outputStream.use { it.write(stdin.toByteArray()) } else p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        check(code == 0) { "${cmd.joinToString(" ")} exited with $code: ${out.take(2000)}" }
        return out
    }

}
