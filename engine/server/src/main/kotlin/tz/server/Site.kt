package tz.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The game's site (the old index.php, f_faq.php, online.php, forum/):
 * news, pages, who is online, clans and castles, and the forum to read.
 * Plain HTML from the server, no scripts; writing happens in the apps.
 */
internal fun Route.siteRoutes(content: Content, game: Game) {
    val nav = content.pages.map { it.id to it.title }
    suspend fun ApplicationCall.page(title: String, body: String) = respondText(html(title, body, nav), ContentType.Text.Html)
    get("/") {
        val news = game.forum.news(5)
        val w = game.worldView()
        call.page("Территория Зла", buildString {
            append("<p class=lead>Многопользовательская текстовая ролевая игра. Когда-то — WAP-игра для мобильных телефонов, теперь — приложение для Android и iPhone.</p>")
            append("<p class=soon>Приложения скоро появятся в Google Play и App Store.</p>")
            append("<h2>Новости</h2>")
            if (news.isEmpty()) append("<p class=muted>Новостей пока нет.</p>")
            for ((t, body) in news) append("<article><h3><a href=\"/forum/topic/${t.id}\">${esc(t.title)}</a></h3><p class=muted>${Moderation.date(t.updated)}</p>${para(body)}</article>")
            append("<h2>Сейчас в игре: ${w.online.size}</h2>")
            w.flagHolder?.let { append("<p>Флаг лидерства у <b>${esc(it)}</b>.</p>") }
                ?: w.flagLocation?.let { append("<p>Флаг лидерства лежит: ${esc(it)}.</p>") }
            append("<p><a href=\"/online\">Кто в игре, кланы и замки →</a></p>")
        })
    }
    get("/online") {
        val w = game.worldView()
        call.page("Кто в игре", buildString {
            append("<h2>Кто в игре (${w.online.size})</h2>")
            if (w.online.isEmpty()) append("<p class=muted>Никого.</p>") else {
                append("<table><tr><th>Имя</th><th>Уровень</th><th>Клан</th></tr>")
                for (o in w.online) append("<tr><td>${esc(o.name)}${o.crime?.let { " <span class=crime>[${esc(it)}]</span>" } ?: ""}</td><td>${o.level}%</td><td>${esc(o.clan ?: "")}</td></tr>")
                append("</table>")
            }
            append("<h2>Замки</h2><table>")
            for (c in w.castles) append("<tr><td>${esc(c.name)}</td><td>${esc(c.owner ?: "ничей")}</td></tr>")
            append("</table><h2>Кланы</h2>")
            if (w.clans.isEmpty()) append("<p class=muted>Кланов пока нет.</p>") else {
                append("<table><tr><th>Клан</th><th>Воинов</th></tr>")
                for (c in w.clans) append("<tr><td>${esc(c.name)}</td><td>${c.members}</td></tr>")
                append("</table>")
            }
        })
    }
    get("/pages/{id}") {
        val p = content.pages.firstOrNull { it.id == call.parameters["id"] }
        if (p == null) call.notFound() else call.page(p.title, "<h2>${esc(p.title)}</h2>" + markdown(p.text))
    }
    get("/forum") {
        val v = game.forum.sections(null)
        call.page("Форум", buildString {
            append("<h2>Форум</h2><p class=muted>Писать на форуме можно из приложения.</p><table>")
            for (s in v.sections) append("<tr><td><a href=\"/forum/${s.id}\">${esc(s.title)}</a><br><span class=muted>${esc(s.info)}</span></td><td class=num>${s.topics} тем<br>${s.posts} сообщ.</td></tr>")
            append("</table>")
        })
    }
    get("/forum/{section}") {
        val id = call.parameters["section"]?.toIntOrNull() ?: return@get call.notFound()
        val v = try { game.forum.section(null, id, call.request.queryParameters["page"]?.toIntOrNull() ?: 0) } catch (e: ApiException) { return@get call.notFound() }
        call.page(v.section?.title ?: "Форум", buildString {
            append("<p><a href=\"/forum\">Форум</a> →</p><h2>${esc(v.section?.title ?: "")}</h2><table>")
            if (v.topics.isEmpty()) append("<tr><td class=muted>Тем пока нет.</td></tr>")
            for (t in v.topics) append(
                "<tr><td>${if (t.pinned) "📌 " else ""}${if (t.closed) "🔒 " else ""}<a href=\"/forum/topic/${t.id}\">${esc(t.title)}</a>" +
                    "<br><span class=muted>${esc(t.author)}</span></td><td class=num>${t.posts}<br><span class=muted>${Moderation.date(t.updated)}</span></td></tr>"
            )
            append("</table>")
            append(pager("/forum/$id", v.page, v.pages))
        })
    }
    get("/forum/topic/{id}") {
        val id = call.parameters["id"]?.toLongOrNull() ?: return@get call.notFound()
        val v = try { game.forum.topic(null, id, call.request.queryParameters["page"]?.toIntOrNull() ?: 0) } catch (e: ApiException) { return@get call.notFound() }
        val t = v.topic ?: return@get call.notFound()
        call.page(t.title, buildString {
            append("<p><a href=\"/forum\">Форум</a> → <a href=\"/forum/${t.section}\">${esc(v.section?.title ?: "")}</a></p><h2>${esc(t.title)}</h2>")
            for (p in v.posts) append(
                "<article><p class=muted><b>${esc(p.author)}</b> · ${Moderation.date(p.created)}${p.editedBy?.let { " · изменено: ${esc(it)}" } ?: ""}</p>${para(p.text)}</article>"
            )
            append(pager("/forum/topic/$id", v.page, v.pages))
        })
    }
}

private fun pager(base: String, page: Int, pages: Int): String {
    if (pages <= 1) return ""
    return "<p class=pager>" + (0 until pages).joinToString(" ") { i ->
        if (i == page) "<b>${i + 1}</b>" else "<a href=\"$base?page=$i\">${i + 1}</a>"
    } + "</p>"
}

internal fun esc(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

private fun para(text: String): String = text.trim().split(Regex("\n{2,}")).joinToString("") { "<p>" + esc(it).replace("\n", "<br>") + "</p>" }

/** The little markdown of content/pages: paragraphs, numbered lists, **bold**. */
private fun markdown(text: String): String = text.trim().split(Regex("\n{2,}")).joinToString("") { block ->
    val lines = block.lines()
    fun inline(s: String) = esc(s).replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
    if (lines.all { Regex("^\\d+\\. ").containsMatchIn(it) }) "<ol>" + lines.joinToString("") { "<li>" + inline(it.substringAfter(". ")) + "</li>" } + "</ol>"
    else "<p>" + lines.joinToString("<br>") { inline(it) } + "</p>"
}

private suspend fun ApplicationCall.notFound() =
    respondText(html("Не найдено", "<h2>Не найдено</h2><p><a href=\"/\">На главную</a></p>", emptyList()), ContentType.Text.Html, HttpStatusCode.NotFound)

private fun html(title: String, body: String, pages: List<Pair<String, String>>): String = """<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title>
<style>
:root{--bg:#f6f1e7;--fg:#2a2118;--muted:#7a6a58;--accent:#8b2a1a;--line:#e0d5c3;--card:#fffaf1}
@media (prefers-color-scheme:dark){:root{--bg:#1b1612;--fg:#eee3d2;--muted:#a8977f;--accent:#e0795f;--line:#3a3028;--card:#231d17}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.55 Georgia,'Times New Roman',serif}
header,main,footer{max-width:760px;margin:0 auto;padding:0 16px}
header{padding-top:20px;border-bottom:1px solid var(--line)}header h1{margin:0;font-size:28px;letter-spacing:.5px}
header h1 a{color:var(--accent);text-decoration:none}nav{margin:8px 0 12px;display:flex;flex-wrap:wrap;gap:4px 14px;font-size:15px}
a{color:var(--accent)}h2{margin-top:28px;font-size:21px}h3{margin:0 0 2px;font-size:18px}
article{background:var(--card);border:1px solid var(--line);border-radius:6px;padding:10px 14px;margin:12px 0}
article p{margin:6px 0}.muted{color:var(--muted);font-size:14px}.lead{font-size:18px;margin-top:20px}
.soon{background:var(--card);border-left:3px solid var(--accent);padding:8px 12px}
table{width:100%;border-collapse:collapse}td,th{border-bottom:1px solid var(--line);padding:7px 6px;text-align:left;vertical-align:top}
.num{text-align:right;white-space:nowrap;font-size:14px}.crime{color:var(--accent);font-size:14px}.pager{margin:16px 0}
footer{color:var(--muted);font-size:13px;padding:24px 16px 32px}
</style></head><body>
<header><h1><a href="/">Территория Зла</a></h1><nav><a href="/">Главная</a><a href="/online">Кто в игре</a><a href="/forum">Форум</a>${pages.joinToString("") { (id, t) -> "<a href=\"/pages/$id\">${esc(t)}</a>" }}</nav></header>
<main>$body</main>
<footer>Территория Зла · 2006–2007, возрождена в ${java.time.Year.now().value}</footer>
</body></html>"""
