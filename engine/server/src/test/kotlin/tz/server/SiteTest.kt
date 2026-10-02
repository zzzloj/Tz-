package tz.server

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import tz.shared.AccountView
import tz.shared.AdminRequest
import tz.shared.GameView
import tz.shared.AuthResponse
import tz.shared.ErrorResponse
import tz.shared.Errors
import tz.shared.ForumRequest
import tz.shared.ForumView
import tz.shared.MeView
import tz.shared.Rules
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Account page, recovery, moderation, the forum, the site and the saved world. Needs TZ_TEST_DATABASE_URL. */
class SiteTest {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private val content by lazy { Content.load(contentDir()) }
        private val db: Db? get() = TestDb.db
    }

    private fun unique(prefix: String) = prefix + (1..8).map { ('a'..'z').random() }.joinToString("")
    private fun name(prefix: String) = prefix + (1..6).map { ('а'..'я').random() }.joinToString("")

    // ---- through HTTP ------------------------------------------------------------------

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) {
        val database = db ?: run { println("TZ_TEST_DATABASE_URL not set, skipping"); return }
        testApplication {
            val accounts = Accounts(database, content)
            application { game(content, accounts, Game(content, database, accounts, World(content))) }
            block()
        }
    }

    private suspend fun HttpClient.postJson(path: String, body: String, token: String? = null): HttpResponse = post(path) {
        contentType(ContentType.Application.Json)
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
        setBody(body)
    }

    private suspend fun HttpClient.getAuth(path: String, token: String?): HttpResponse = get(path) {
        if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
    }

    private suspend fun error(r: HttpResponse) = json.decodeFromString(ErrorResponse.serializer(), r.bodyAsText()).error
    private suspend fun token(r: HttpResponse): String {
        assertEquals(HttpStatusCode.OK.value / 100, r.status.value / 100, r.bodyAsText())
        return json.decodeFromString(AuthResponse.serializer(), r.bodyAsText()).token
    }

    @Test
    fun passwordRecoveryAndDeletion() = withApp {
        val login = unique("acc")
        val first = token(client.postJson("/api/auth/register", """{"login":"$login","password":"secret-123"}"""))
        val second = token(client.postJson("/api/auth/login", """{"login":"$login","password":"secret-123"}"""))
        client.postJson("/api/characters", """{"name":"${name("Акк")}","sex":"m"}""", first)

        // A recovery code needs the password; it is shown once.
        assertEquals(Errors.BAD_CREDENTIALS, error(client.postJson("/api/account", """{"op":"recovery","password":"wrong-one"}""", first)))
        val made = json.decodeFromString(AccountView.serializer(), client.postJson("/api/account", """{"op":"recovery","password":"secret-123"}""", first).bodyAsText())
        val code = assertNotNull(made.recoveryCode)
        assertTrue(Regex("^[A-Z2-9]{4}(-[A-Z2-9]{4}){3}$").matches(code), code)
        val page = json.decodeFromString(AccountView.serializer(), client.getAuth("/api/account", first).bodyAsText())
        assertTrue(page.hasRecovery)
        assertNull(page.recoveryCode)

        // Changing the password ends the other session, not this one.
        assertEquals(Errors.WEAK_PASSWORD, error(client.postJson("/api/account", """{"op":"password","password":"secret-123","newPassword":"short"}""", first)))
        assertEquals(HttpStatusCode.OK, client.postJson("/api/account", """{"op":"password","password":"secret-123","newPassword":"better-456"}""", first).status)
        assertEquals(HttpStatusCode.OK, client.getAuth("/api/me", first).status)
        assertEquals(HttpStatusCode.Unauthorized, client.getAuth("/api/me", second).status)
        assertEquals(Errors.BAD_CREDENTIALS, error(client.postJson("/api/auth/login", """{"login":"$login","password":"secret-123"}""")))

        // Recovery: the code sets a new password once, and every session ends.
        assertEquals(Errors.WRONG_CODE, error(client.postJson("/api/auth/recover", """{"login":"$login","code":"AAAA-BBBB-CCCC-DDDD","newPassword":"third-789"}""")))
        val third = token(client.postJson("/api/auth/recover", """{"login":"$login","code":"${code.lowercase()}","newPassword":"third-789"}"""))
        assertEquals(HttpStatusCode.Unauthorized, client.getAuth("/api/me", first).status)
        assertEquals(HttpStatusCode.OK, client.getAuth("/api/me", third).status)
        assertEquals(Errors.WRONG_CODE, error(client.postJson("/api/auth/recover", """{"login":"$login","code":"$code","newPassword":"fourth-000"}""")))

        // «О себе».
        val about = json.decodeFromString(AccountView.serializer(), client.postJson("/api/account", """{"op":"about","text":"Люблю <b>рыбалку</b>"}""", third).bodyAsText())
        assertEquals("Люблю рыбалку", about.about)

        // Deleting needs the password, then the account is gone.
        assertEquals(Errors.BAD_CREDENTIALS, error(client.postJson("/api/account", """{"op":"delete","password":"secret-123"}""", third)))
        assertEquals(HttpStatusCode.NoContent, client.postJson("/api/account", """{"op":"delete","password":"third-789"}""", third).status)
        assertEquals(HttpStatusCode.Unauthorized, client.getAuth("/api/me", third).status)
        assertEquals(Errors.BAD_CREDENTIALS, error(client.postJson("/api/auth/login", """{"login":"$login","password":"third-789"}""")))
    }

    @Test
    fun siteAndPages() = withApp {
        val home = client.get("/")
        assertEquals(HttpStatusCode.OK, home.status)
        assertTrue(home.bodyAsText().contains("Территория Зла"))
        assertTrue(client.get("/pages/rules").bodyAsText().contains("Правила"))
        assertEquals(HttpStatusCode.NotFound, client.get("/pages/nothing").status)
        assertTrue(client.get("/forum").bodyAsText().contains("Торговля"))
        assertTrue(client.get("/online").bodyAsText().contains("Замки"))
        val pages = client.get("/api/pages").bodyAsText()
        assertTrue(pages.contains("\"rules\""), pages)
        assertTrue(client.get("/api/pages/flag").bodyAsText().contains("+10 к жизни"))
        // The forum is read without signing in.
        val forum = json.decodeFromString(ForumView.serializer(), client.get("/api/forum").bodyAsText())
        assertTrue(forum.sections.size >= 6)
        assertTrue(!forum.canWrite)
        // Pictures for the apps, cached by ETag.
        val pic = client.get("/art/item/i.w.k.begin..3")
        assertEquals(HttpStatusCode.OK, pic.status)
        assertEquals("image/webp", pic.headers[HttpHeaders.ContentType]?.substringBefore(';'))
        val tag = assertNotNull(pic.headers[HttpHeaders.ETag])
        assertEquals(HttpStatusCode.NotModified, client.get("/art/item/i.w.k.begin..3") { header(HttpHeaders.IfNoneMatch, tag) }.status)
        assertEquals(HttpStatusCode.OK, client.get("/art/npcs/npc-beginner.webp").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/art/logic/gifts.json").status)
    }

    // ---- the game and the forum directly ------------------------------------------------------

    private class Staff(val game: Game, val accounts: Accounts, val admin: Account, val moder: Account, val player: Account, val names: List<String>, val tokens: List<String>)

    private fun withStaff(block: suspend Staff.() -> Unit) {
        val database = db ?: run { println("TZ_TEST_DATABASE_URL not set, skipping"); return }
        runBlocking {
            val accounts = Accounts(database, content)
            val names = listOf(name("Админ"), name("Модер"), name("Игрок"))
            val made = names.map { n ->
                val (a, t) = accounts.register(unique("s"), "secret-123")
                accounts.createCharacter(a, n, "m")
                a to t
            }
            accounts.promoteAdmins(listOf(made[0].first.login))
            accounts.setRole(made[1].first.id, Roles.MODER)
            val all = made.map { assertNotNull(accounts.authenticate(it.second)) }
            val game = Game(content, database, accounts, World(content, Random(1)), random = Random(1))
            for (a in all) { game.place(a, "_begin"); game.view(a) }
            Staff(game, accounts, all[0], all[1], all[2], names, made.map { it.second }).block()
        }
    }

    private suspend fun Staff.fresh(i: Int): Account = assertNotNull(accounts.authenticate(tokens[i]))

    @Test
    fun edwardHandsOutGifts() = withStaff {
        fun money(v: GameView) = v.inventory.firstOrNull { it.id == Rules.MONEY }?.count ?: 0
        val kits = game.adminView(admin).gifts.map { it.value }
        assertTrue(kits.containsAll(listOf("start", "prereg", "update", "comp")), kits.toString())
        assertTrue(game.adminView(moder).gifts.isEmpty())
        assertEquals(Errors.FORBIDDEN, assertFailsWith<ApiException> { game.admin(moder, AdminRequest("gift", names[2], item = "update")) }.code)
        assertEquals(Errors.BAD_REQUEST, assertFailsWith<ApiException> { game.admin(admin, AdminRequest("gift", names[2], item = "nothing")) }.code)
        // Nothing yet.
        var v = game.talk(player, "n.vost", "begin", null)
        v = game.talk(player, "n.vost", "tren", null)
        assertTrue(v.dialog!!.text.startsWith("Для тебя пока ничего нет"), v.dialog!!.text)
        // A gift to one player, then to everybody: two kits wait, Edward hands them out one by one.
        game.admin(admin, AdminRequest("gift", names[2], item = "update"))
        assertTrue(game.view(player).journal.any { it.contains("К обновлению") })
        val all = game.admin(admin, AdminRequest("gift", "*", item = "prereg"))
        assertTrue(all.message!!.startsWith("Подарок «За предрегистрацию» — всем"), all.message)
        val before = money(game.view(player))
        game.talk(player, "n.vost", "begin", null)
        v = game.talk(player, "n.vost", "tren", null)
        assertTrue(v.dialog!!.text.contains("Ты ждал открытия"), v.dialog!!.text)
        assertEquals(before + 2000, money(v))
        game.talk(player, "n.vost", "begin", null)
        v = game.talk(player, "n.vost", "tren", null)
        assertTrue(v.dialog!!.text.contains("Мир обновился"), v.dialog!!.text)
        assertEquals(before + 3000, money(v))
        game.talk(player, "n.vost", "begin", null)
        assertTrue(game.talk(player, "n.vost", "tren", null).dialog!!.text.startsWith("Для тебя пока ничего нет"))
        // A gift for every new character.
        assertEquals("start", game.admin(admin, AdminRequest("giftNew", item = "start")).newGift)
        val (a, t) = accounts.register(unique("s"), "secret-123")
        accounts.createCharacter(a, name("Новичок"), "f")
        val newbie = assertNotNull(accounts.authenticate(t))
        game.place(newbie, "_begin")
        game.talk(newbie, "n.vost", "begin", null)
        v = game.talk(newbie, "n.vost", "tren", null)
        assertTrue(v.dialog!!.text.startsWith("Добро пожаловать"), v.dialog!!.text)
        assertEquals(300, money(v))
        assertNull(game.admin(admin, AdminRequest("giftNew")).newGift)
    }

    @Test
    fun moderation() = withStaff {
        assertEquals("admin", admin.role)
        assertFailsWith<ApiException> { game.adminView(player) }
        // A moderator mutes a player: he cannot speak or write.
        var v = game.admin(moder, AdminRequest("mute", names[2], "флуд", minutes = 30))
        assertTrue(v.message!!.contains("не может писать"), v.message)
        assertTrue(v.log.first().contains("mute"), v.log.toString())
        val muted = fresh(2)
        assertTrue(muted.muted)
        assertTrue(game.say(muted, "привет всем", "here").journal.last().startsWith("Бан!"))
        assertEquals(Errors.MUTED, assertFailsWith<ApiException> { game.message(muted, "write", names[0], "письмо") }.code)
        assertEquals(Errors.MUTED, assertFailsWith<ApiException> { game.forum.act(muted, ForumRequest("topic", 2, title = "Тема", text = "Текст")) }.code)
        game.admin(moder, AdminRequest("unmute", names[2]))
        assertTrue(!fresh(2).muted)

        // A moderator cannot touch the administrator, nor ban for long.
        assertEquals(Errors.FORBIDDEN, assertFailsWith<ApiException> { game.admin(moder, AdminRequest("mute", names[0])) }.code)
        assertEquals(Errors.FORBIDDEN, assertFailsWith<ApiException> { game.admin(moder, AdminRequest("ban", names[2], minutes = 0)) }.code)
        assertEquals(Errors.FORBIDDEN, assertFailsWith<ApiException> { game.admin(moder, AdminRequest("give", names[2], item = Rules.MONEY, count = 5)) }.code)

        // Teleport, summon, give, broadcast.
        v = game.admin(moder, AdminRequest("teleport", names[2], "x1092x474"))
        assertEquals("x1092x474", game.view(player).location.id)
        game.admin(moder, AdminRequest("summon", names[2]))
        assertEquals("_begin", game.view(player).location.id)
        game.admin(admin, AdminRequest("give", names[2], item = Rules.MONEY, count = 77))
        assertEquals(77, game.view(player).inventory.single { it.id == Rules.MONEY }.count)
        game.admin(moder, AdminRequest("broadcast", text = "Перезапуск через 5 минут"))
        assertTrue(game.view(player).journal.contains("Всем внимание: Перезапуск через 5 минут"))

        // A ban ends the sessions and closes the door; lifting it opens it again.
        game.admin(admin, AdminRequest("ban", names[2], "читы", minutes = 0))
        assertNull(accounts.authenticate(tokens[2]))
        assertEquals(Errors.BANNED, assertFailsWith<ApiException> { accounts.login(player.login, "secret-123") }.code)
        assertTrue(game.players.values.none { it.accountId == player.id })
        game.admin(admin, AdminRequest("unban", names[2]))
        val back = accounts.login(player.login, "secret-123")
        assertEquals(player.id, back.first.id)

        // Roles: the administrator makes a moderator a player again.
        game.admin(admin, AdminRequest("role", names[1], "player"))
        assertTrue(!fresh(1).moderator)
        assertTrue(game.adminView(admin).log.size >= 8)
    }

    @Test
    fun forum() = withStaff {
        val clock = longArrayOf(1_900_000_000L)
        val forum = Forum(game.db) { clock[0] }
        val sections = forum.sections(player).sections
        val news = sections.first { it.staffOnly }
        val talk = sections.first { !it.staffOnly }
        assertEquals(Errors.FORBIDDEN, assertFailsWith<ApiException> { forum.act(player, ForumRequest("topic", news.id, title = "Моя новость", text = "Текст")) }.code)
        var v = forum.act(player, ForumRequest("topic", talk.id, title = "Где <b>найти</b> лук? " + unique(""), text = "Подскажите, где купить лук"))
        val topic = assertNotNull(v.topic)
        assertTrue(!topic.title.contains("<b>"), "forum check 1")
        assertEquals(names[2], v.posts.single().author)
        assertTrue(v.posts.single().mine, "forum check 2")
        // Too fast, then a reply; the same text twice is refused.
        assertEquals(Errors.TOO_FAST, assertFailsWith<ApiException> { forum.act(player, ForumRequest("post", topic = topic.id, text = "Ещё")) }.code)
        clock[0] += 30
        v = forum.act(player, ForumRequest("post", topic = topic.id, text = "Ау?"))
        assertEquals(2, v.posts.size)
        clock[0] += 30
        assertEquals(Errors.SAID_ALREADY, assertFailsWith<ApiException> { forum.act(player, ForumRequest("post", topic = topic.id, text = "Ау?")) }.code)
        // Moderators answer quickly, close, pin and rename.
        // The author follows the topic: a moderator's answer is a reply he has not seen.
        assertEquals(0, forum.replies(player.id))
        v = forum.act(moder, ForumRequest("post", topic = topic.id, text = "> ${names[2]}: Ау?\n\nУ Милты"))
        assertEquals(1, forum.replies(player.id))
        assertTrue(forum.sections(player).replies.any { it.id == topic.id && it.unread }, "forum check: replies")
        assertTrue(forum.section(player, talk.id, 0).topics.first { it.id == topic.id }.unread, "forum check: unread topic")
        val unseen = forum.topic(player, topic.id, -2)
        assertTrue(unseen.posts.last().unread && !unseen.posts.first().unread, "forum check: unread posts")
        assertEquals(0, forum.replies(player.id))
        assertTrue(v.posts.last().mine && !forum.topic(player, topic.id, -1).posts.last().mine, "forum check 3")
        // Unfollow; search finds the post by a word and not inside a quote's author line only.
        assertTrue(!forum.act(player, ForumRequest("unfollow", topic = topic.id)).topic!!.followed)
        val found = forum.search(player, "милты")
        assertTrue(found.hits.any { it.topic.id == topic.id && it.snippet.contains("Милты") }, found.toString())
        assertTrue(forum.search(player, "ау").hits.isEmpty(), "too short a query")
        forum.act(moder, ForumRequest("close", topic = topic.id))
        clock[0] += 30
        assertEquals(Errors.TOPIC_LOCKED, assertFailsWith<ApiException> { forum.act(player, ForumRequest("post", topic = topic.id, text = "Спасибо")) }.code)
        assertTrue(!forum.topic(player, topic.id, 0).canWrite, "forum check 4")
        v = forum.act(moder, ForumRequest("rename", topic = topic.id, title = "Лук: где купить"))
        assertEquals("Лук: где купить", v.topic!!.title)
        forum.act(moder, ForumRequest("pin", topic = topic.id))
        assertEquals(topic.id, forum.section(player, talk.id, 0).topics.first().id)
        // Editing: one's own post; another's only by a moderator.
        val mine = v.posts.first { it.author == names[2] }
        val his = v.posts.first { it.author == names[1] }
        assertEquals(Errors.FORBIDDEN, assertFailsWith<ApiException> { forum.act(player, ForumRequest("edit", post = his.id, text = "Взлом")) }.code)
        v = forum.act(player, ForumRequest("edit", post = mine.id, text = "Где купить лук?"))
        assertEquals(names[2], v.posts.first { it.id == mine.id }.editedBy)
        // Deleting a post, then the whole topic.
        v = forum.act(moder, ForumRequest("delete", post = his.id))
        assertTrue(v.posts.none { it.id == his.id }, "forum check 5")
        v = forum.act(moder, ForumRequest("delete", topic = topic.id))
        assertTrue(v.topics.none { it.id == topic.id }, "forum check 6")
        // News from moderators reach the notice board.
        forum.act(moder, ForumRequest("topic", news.id, title = "Открытие " + unique(""), text = "Игра снова работает"))
        val board = assertNotNull(game.look(player, "i.s.book.news").look)
        assertEquals("news", board.page)
        assertTrue(board.text.contains("Игра снова работает"), board.text)
    }

    @Test
    fun aboutIsSeenByOthers() = withStaff {
        accounts.setAbout(player, "Рыбак из Южных ворот")
        val look = assertNotNull(game.look(admin, names[2]).look)
        assertTrue(look.text.contains("О себе: Рыбак из Южных ворот"), look.text)
        val me = assertNotNull(game.look(admin, "i.s.book.story").look)
        assertEquals("pages", me.page)
    }

    // ---- the saved world -----------------------------------------------------------------

    @Test
    fun worldSurvivesARestart() = withStaff {
        val now = 1_900_000_000L
        val clock = longArrayOf(now)
        val world = World(content, Random(5), now)
        val g = Game(content, game.db, accounts, world, { clock[0] }, Random(5))
        g.place(player, "_begin")
        val me = g.view(player)
        val p = g.players.values.first { it.accountId == player.id }
        // A pet, a thing on the ground, a dead monster that respawns.
        val dogProto = assertNotNull(world.proto("n.a.dog"))
        val dog = world.spawnProto("n.a.dog.${p.id}", dogProto, "_begin", now, 0)
        g.adopt(dog, p, follow = true, guard = true, until = 0, vanish = false, now = now)
        dog.customName = "Шарик"
        g.drop(player, "i.w.k.begin")
        val monster = world.allNpcs().first { it.key.startsWith("n.c.") && it.proto.respawn != null && it.owner == null }
        world.kill(monster, now)
        assertTrue(me.location.id == "_begin")

        val store = WorldStore(game.db)
        store.save(g)
        val again = World(content, Random(6), now + 60)
        assertTrue(again.allNpcs().any { it.id == monster.id })
        assertTrue(store.restore(again, now + 60))
        val restored = again.allNpcs().single { it.key == dog.key }
        assertEquals(p.id, restored.owner?.ownerId)
        assertEquals("Шарик", restored.customName)
        assertTrue(again.itemsAt("_begin", now + 60).any { it.id == "i.w.k.begin" })
        assertTrue(again.allNpcs().none { it.id == monster.id }, "the dead monster waits for its respawn")
        again.tick(now + 60 + monster.proto.respawn!!.max + 1)
        assertTrue(again.allNpcs().any { it.id == monster.id }, "and comes back")
        assertTrue(again.npcCount() > 100)
    }
}
