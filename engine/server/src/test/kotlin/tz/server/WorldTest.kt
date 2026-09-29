package tz.server

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorldTest {
    private val content by lazy { Content.load(contentDir()) }
    private val start = 1_800_000_000L

    @Test
    fun startingWorldHasItsNpcs() = runTest {
        val world = World(content, Random(1), start)
        // The gatekeeper stands at the starting street (content/locations/_begin.json).
        assertEquals(listOf("Привратник Уин"), world.npcsAt("_begin").map { it.name })
        assertTrue(world.npcCount() > 300, "npcs: ${world.npcCount()}")
        // Pets of the 2007 players are not in the world.
        assertTrue(world.allNpcs().none { it.key.contains(".u.") })
        println("world problems: ${world.problems.size}")
        world.problems.take(10).forEach { println("  $it") }
    }

    @Test
    fun timersSpawnNpcs() = runTest {
        val world = World(content, Random(2), start)
        assertTrue(world.allNpcs().none { it.key == "n.c.wolf.sl2" })
        world.tick(start)
        val wolf = world.allNpcs().firstOrNull { it.key == "n.c.wolf.sl2" }
        assertNotNull(wolf, "the wolf of x1159x169 spawns on the first tick")
        assertEquals("n.c.wolf", wolf.template)
    }

    @Test
    fun wanderersKeepTheirZoneAndDistance() = runTest {
        val world = World(content, Random(3), start)
        world.tick(start)
        val homes = world.allNpcs().associate { it to content.locations.getValue(it.location).zone }
        // Four simulated hours, one tick per 10 seconds.
        for (t in 1..1440) world.tick(start + t * 10L)
        var moved = 0
        for ((npc, zone) in homes) {
            if (npc.wander == null) continue
            assertEquals(zone, content.locations.getValue(npc.location).zone, "${npc.key} left its zone")
            assertTrue(npc.trail.size <= npc.wander!!.steps, "${npc.key} too far: ${npc.trail.size}")
            if (npc.trail.isNotEmpty()) moved++
        }
        assertTrue(moved > 20, "wandering NPCs away from home: $moved")
        // Nobody walks into a location that does not exist.
        assertTrue(world.allNpcs().all { it.location in content.locations })
    }

    @Test
    fun droppedItemsVanish() = runTest {
        val world = World(content, Random(4), start)
        world.drop("_begin", "i.w.k.begin", 1, start)
        assertEquals(listOf("нож"), world.itemsAt("_begin", start + 10).map { it.name })
        world.tick(start + 601)
        assertTrue(world.itemsAt("_begin", start + 601).isEmpty())
    }
}
