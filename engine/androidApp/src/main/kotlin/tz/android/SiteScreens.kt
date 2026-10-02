package tz.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import tz.shared.Design
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import tz.shared.AccountView
import tz.shared.AdminView
import tz.shared.ForumPost
import tz.shared.ForumSection
import tz.shared.ForumTopic
import tz.shared.ForumView
import tz.shared.PageSummary
import tz.shared.PageView
import tz.shared.Rules
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Account, forum, pages and moderation: what the old site did, in the app. */
class SiteActions(
    val closeAccount: () -> Unit = {},
    val changePassword: (String, String) -> Unit = { _, _ -> },
    val makeRecovery: (String) -> Unit = {},
    val setAbout: (String) -> Unit = {},
    val deleteAccount: (String) -> Unit = {},
    val openSection: (ForumSection) -> Unit = {},
    val openTopic: (ForumTopic) -> Unit = {},
    val forumPage: (Int) -> Unit = {},
    val forumBack: () -> Unit = {},
    val closeForum: () -> Unit = {},
    val newTopic: (String, String) -> Unit = { _, _ -> },
    val reply: (String) -> Unit = {},
    val editPost: (ForumPost, String) -> Unit = { _, _ -> },
    val moderateTopic: (String) -> Unit = {},
    val renameTopic: (String) -> Unit = {},
    val deletePost: (ForumPost) -> Unit = {},
    val openPage: (PageSummary) -> Unit = {},
    val closePage: () -> Unit = {},
    val closePages: () -> Unit = {},
    val adminOp: (op: String, target: String, text: String, item: String, count: Int, minutes: Int) -> Unit = { _, _, _, _, _, _ -> },
    val closeAdmin: () -> Unit = {},
    val search: (String) -> Unit = {},
    val openHit: (tz.shared.ForumHit) -> Unit = {},
    val openUnread: (ForumTopic) -> Unit = {},
    val follow: (Boolean) -> Unit = {},
    val openAdmin: (() -> Unit)? = null,
    val signOut: () -> Unit = {},
)

private val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.forLanguageTag("ru"))
fun date(unix: Long): String = dateFormat.format(Date(unix * 1000))

/** The pages' little markdown: **bold**. */
fun rich(text: String): AnnotatedString = buildAnnotatedString {
    var rest = text
    while (true) {
        val a = rest.indexOf("**")
        val b = if (a >= 0) rest.indexOf("**", a + 2) else -1
        if (a < 0 || b < 0) { append(rest); break }
        append(rest.substring(0, a))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(rest.substring(a + 2, b)) }
        rest = rest.substring(b + 2)
    }
}

@Composable
private fun Password(value: String, label: String, onChange: (String) -> Unit) = OutlinedTextField(
    value, onChange, label = { Text(label) }, singleLine = true,
    visualTransformation = PasswordVisualTransformation(),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    modifier = Modifier.fillMaxWidth(),
)

// ---- building blocks in the game's style -------------------------------------------------------

/** The top of a page: back, title, something on the right. */
@Composable
fun PageHeader(title: String, onBack: (() -> Unit)?, sub: String? = null, trailing: @Composable () -> Unit = {}) {
    val c = Tz.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        onBack?.let { ActionButton("назад", true, it, icon = "west") }
        Column(Modifier.weight(1f).padding(horizontal = Design.Space.S.dp)) {
            Text(title, style = Tz.type.heading, color = c.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, style = Tz.type.small, color = c.textMuted) }
        }
        trailing()
    }
}

@Composable
fun SectionLabel(text: String, note: String? = null) {
    val c = Tz.colors
    Row(Modifier.fillMaxWidth().padding(top = Design.Space.M.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = Tz.type.label, color = c.accent)
        note?.let { Text(it, style = Tz.type.small, color = c.textMuted) }
    }
}

/** A row of a list page: icon, title, second line, something on the right; tappable. */
@Composable
fun ListLine(title: String, sub: String? = null, icon: String? = null, danger: Boolean = false, badge: String? = null, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val c = Tz.colors
    Row(
        Modifier.fillMaxWidth().tzPanel(c).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = Design.Size.TOUCH.dp).padding(horizontal = Design.Space.S.dp, vertical = Design.Space.XS.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { TzIcon(it, Modifier.padding(end = Design.Space.S.dp), if (danger) c.danger else c.link) }
        Column(Modifier.weight(1f)) {
            Text(title, style = Tz.type.name, color = if (danger) c.danger else c.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, style = Tz.type.small, color = c.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        badge?.let {
            Text(it, style = Tz.type.number, color = c.onPrimary, modifier = Modifier.clip(RoundedCornerShape(50)).background(c.primary).padding(horizontal = 7.dp, vertical = 1.dp))
        }
        trailing()
    }
}

/** A segmented choice: «Как в системе · Ночь · Пергамент». */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val c = Tz.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Design.Radius.M.dp)).border(Design.Size.BORDER.dp, c.border, RoundedCornerShape(Design.Radius.M.dp))) {
        options.forEach { (key, title) ->
            val on = key == selected
            Text(
                title, style = Tz.type.tab, color = if (on) c.onPrimary else c.textMuted, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).background(if (on) c.primary else androidx.compose.ui.graphics.SolidColor(c.surface))
                    .clickable { onSelect(key) }.padding(vertical = Design.Space.S.dp),
            )
        }
    }
}

/** Forum text: lines starting with «>» are a quote (the first one names who is quoted), **bold** works. */
@Composable
fun PostText(text: String) {
    val c = Tz.colors
    val blocks = mutableListOf<Pair<Boolean, MutableList<String>>>()
    for (line in text.lines()) {
        val quote = line.startsWith(">")
        if (blocks.isEmpty() || blocks.last().first != quote) blocks += quote to mutableListOf()
        blocks.last().second += if (quote) line.removePrefix(">").trimStart() else line
    }
    Column(verticalArrangement = Arrangement.spacedBy(Design.Space.XS.dp)) {
        blocks.forEach { (quote, lines) ->
            val t = lines.joinToString("\n").trim()
            if (t.isEmpty()) return@forEach
            if (quote) Row(Modifier.fillMaxWidth().background(c.surfaceSunken)) {
                Box(Modifier.width(3.dp).heightIn(min = 20.dp).background(c.accent))
                Text(rich(t), Modifier.padding(Design.Space.S.dp), style = Tz.type.small, color = c.textMuted)
            } else Text(rich(t), style = Tz.type.body, color = c.text)
        }
    }
}

// ---- account -----------------------------------------------------------------------------------

@Composable
fun AccountPanel(a: AccountView, info: String?, busy: Boolean, act: SiteActions) {
    val c = Tz.colors
    var open by remember { mutableStateOf<String?>(null) }
    fun toggle(k: String) { open = if (open == k) null else k }
    Column(verticalArrangement = Arrangement.spacedBy(Design.Space.XS.dp)) {
        PageHeader("Аккаунт", act.closeAccount, sub = a.login + (a.character?.let { " · $it" } ?: "") + when (a.role) { "admin" -> " · администратор"; "moder" -> " · модератор"; else -> "" })
        if (a.mutedUntil * 1000 > System.currentTimeMillis()) Text("Вам запрещено писать до ${date(a.mutedUntil)}", style = Tz.type.small, color = c.danger)
        info?.let { Text(it, style = Tz.type.body, color = c.accent) }
        a.recoveryCode?.let { code ->
            Column(Modifier.fillMaxWidth().tzPanel(c).padding(Design.Space.M.dp)) {
                Text("Ваш код восстановления", style = Tz.type.label, color = c.accent)
                Text(code, style = Tz.type.heading, color = c.title)
                Text("Запишите его: он показывается один раз.", style = Tz.type.small, color = c.textMuted)
            }
        }

        SectionLabel("Тема")
        val theme = LocalThemeChoice.current
        Segmented(listOf("system" to "Как в системе", "night" to "Ночь", "parchment" to "Пергамент"), theme.mode, theme.set)

        SectionLabel("Безопасность")
        ListLine("Сменить пароль", icon = "settings", onClick = { toggle("pw") })
        if (open == "pw") {
            var old by remember { mutableStateOf("") }
            var fresh by remember { mutableStateOf("") }
            Password(old, "Текущий пароль") { old = it }
            Password(fresh, "Новый пароль (от ${Rules.PASSWORD_MIN} символов)") { fresh = it }
            Button(onClick = { act.changePassword(old, fresh); old = ""; fresh = "" }, enabled = !busy && old.isNotEmpty() && fresh.isNotEmpty()) { Text("Сменить пароль") }
        }
        ListLine("Код восстановления", if (a.hasRecovery) "выдан; новый заменит старый" else "ещё нет — без него забытый пароль не вернуть", icon = "mail", danger = !a.hasRecovery, onClick = { toggle("code") })
        if (open == "code") {
            var pw by remember { mutableStateOf("") }
            Password(pw, "Пароль") { pw = it }
            Button(onClick = { act.makeRecovery(pw); pw = "" }, enabled = !busy && pw.isNotEmpty()) { Text("Получить код") }
        }

        SectionLabel("Профиль")
        ListLine("О себе", a.about.ifBlank { "видят те, кто вас осматривает" }, icon = "tab-hero", onClick = { toggle("about") })
        if (open == "about") {
            var about by remember(a.about) { mutableStateOf(a.about) }
            OutlinedTextField(about, { about = it.take(Rules.ABOUT_MAX) }, modifier = Modifier.fillMaxWidth(), minLines = 3)
            Button(onClick = { act.setAbout(about) }, enabled = !busy && about != a.about) { Text("Сохранить") }
        }

        act.openAdmin?.let {
            SectionLabel("Модерация")
            ListLine("Панель модератора", "мут, бан, телепорт" + if (a.role == "admin") ", подарки, роли" else "", icon = "clan", onClick = it)
        }

        SectionLabel("Выход")
        ListLine("Выйти из игры", icon = "flee", onClick = act.signOut)
        ListLine("Удалить аккаунт", "персонаж и все вещи исчезнут навсегда", danger = true, onClick = { toggle("delete") })
        if (open == "delete") {
            var dpw by remember { mutableStateOf("") }
            Password(dpw, "Пароль для подтверждения") { dpw = it }
            Button(onClick = { act.deleteAccount(dpw) }, enabled = !busy && dpw.isNotEmpty()) { Text("Удалить навсегда", color = c.danger) }
        }
    }
}

// ---- forum -------------------------------------------------------------------------------------

@Composable
private fun Pager(page: Int, pages: Int, busy: Boolean, onPage: (Int) -> Unit) {
    if (pages <= 1) return
    val c = Tz.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        ActionButton("предыдущая страница", !busy && page > 0, { onPage(page - 1) }, icon = "west")
        Text("  стр. ${page + 1} из $pages  ", style = Tz.type.label, color = c.textMuted)
        ActionButton("следующая страница", !busy && page < pages - 1, { onPage(page + 1) }, icon = "east")
    }
}

@Composable
fun ForumPanel(f: ForumView, busy: Boolean, act: SiteActions) {
    val c = Tz.colors
    val topic = f.topic
    val section = f.section
    Column(verticalArrangement = Arrangement.spacedBy(Design.Space.XS.dp)) {
        when {
            f.query != null -> {
                PageHeader("Поиск: «${f.query}»", act.closeForum, sub = if (f.hits.isEmpty()) "ничего не нашлось" else "найдено ${f.hits.size}")
                SearchBox(f.query ?: "", busy, act.search)
                if ((f.query ?: "").length < 3) Text("Нужно хотя бы 3 буквы.", style = Tz.type.small, color = c.textMuted)
                f.hits.forEach { h ->
                    ListLine(h.topic.title, "${h.author}: ${h.snippet}", icon = "look", onClick = { act.openHit(h) })
                }
            }
            topic != null -> TopicPage(f, topic, busy, act)
            section != null -> {
                PageHeader(section.title, act.forumBack, sub = section.info.ifBlank { null })
                if (f.topics.isEmpty()) Text("Тем пока нет.", style = Tz.type.body, color = c.textMuted)
                f.topics.forEach { t ->
                    ListLine(
                        (if (t.pinned) "📌 " else "") + (if (t.closed) "🔒 " else "") + t.title,
                        "${t.author} · ${t.posts} сообщ. · ${date(t.updated)}" + (t.lastAuthor?.let { " · последнее: $it" } ?: ""),
                        badge = if (t.unread) "новое" else null,
                        onClick = { if (t.unread) act.openUnread(t) else act.openTopic(t) },
                    )
                }
                Pager(f.page, f.pages, busy, act.forumPage)
                if (f.canWrite && (!section.staffOnly || f.moderator)) {
                    var writing by remember(section.id) { mutableStateOf(false) }
                    if (!writing) Button(onClick = { writing = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Новая тема") }
                    else {
                        var t by remember(section.id) { mutableStateOf("") }
                        var text by remember(section.id) { mutableStateOf("") }
                        SectionLabel("Новая тема")
                        OutlinedTextField(t, { t = it.take(Rules.TITLE_MAX) }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(text, { text = it.take(Rules.POST_MAX) }, label = { Text("Сообщение") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(Design.Space.S.dp)) {
                            Button(onClick = { act.newTopic(t, text) }, enabled = !busy && t.isNotBlank() && text.isNotBlank()) { Text("Создать тему") }
                            TextButton(onClick = { writing = false }) { Text("Отмена") }
                        }
                    }
                }
            }
            else -> {
                PageHeader("Форум", act.closeForum, sub = "тот же форум, что на сайте")
                SearchBox("", busy, act.search)
                if (f.replies.isNotEmpty()) {
                    SectionLabel("Ответы в ваших темах", "${f.replies.size}")
                    f.replies.forEach { t -> ListLine(t.title, "последнее: ${t.lastAuthor ?: t.author} · ${date(t.updated)}", icon = "mail", badge = "новое", onClick = { act.openUnread(t) }) }
                }
                SectionLabel("Разделы")
                f.sections.forEach { s ->
                    ListLine(s.title, "${s.topics} тем · ${s.posts} сообщ." + if (s.info.isNotBlank()) " · ${s.info}" else "", icon = "talk",
                        badge = if (s.unread > 0) "${s.unread}" else null, onClick = { act.openSection(s) })
                }
                if (!f.canWrite) Text("Писать на форуме могут вошедшие в игру.", style = Tz.type.small, color = c.textMuted)
            }
        }
    }
}

@Composable
private fun SearchBox(initial: String, busy: Boolean, onSearch: (String) -> Unit) {
    var q by remember(initial) { mutableStateOf(initial) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(q, { q = it.take(60) }, label = { Text("Поиск по форуму") }, singleLine = true, modifier = Modifier.weight(1f))
        ActionButton("искать", !busy && q.trim().length >= 3, { onSearch(q) }, icon = "look")
    }
}

@Composable
private fun TopicPage(f: ForumView, topic: ForumTopic, busy: Boolean, act: SiteActions) {
    val c = Tz.colors
    var reply by remember(topic.id) { mutableStateOf("") }
    PageHeader(topic.title, act.forumBack, sub = listOfNotNull(f.section?.title, if (topic.pinned) "закреплена" else null, if (topic.closed) "закрыта" else null).joinToString(" · ")) {
        if (f.canWrite || topic.followed) TextButton(onClick = { act.follow(!topic.followed) }, enabled = !busy) { Text(if (topic.followed) "не следить" else "следить") }
    }
    Pager(f.page, f.pages, busy, act.forumPage)
    f.posts.forEach { p ->
        PostCard(p, f.moderator, f.canWrite, busy, act) { quoted -> reply = quoted + "\n\n" + reply }
    }
    Pager(f.page, f.pages, busy, act.forumPage)
    if (f.canWrite) {
        OutlinedTextField(reply, { reply = it.take(Rules.POST_MAX) }, label = { Text("Ответ") }, minLines = 3, modifier = Modifier.fillMaxWidth())
        Button(onClick = { act.reply(reply); reply = "" }, enabled = !busy && reply.isNotBlank()) { Text("Ответить") }
    } else if (topic.closed) Text("Тема закрыта.", style = Tz.type.small, color = c.textMuted)
    if (f.moderator) {
        SectionLabel("Модерация")
        Row {
            TextButton(onClick = { act.moderateTopic(if (topic.closed) "open" else "close") }, enabled = !busy) { Text(if (topic.closed) "открыть" else "закрыть") }
            TextButton(onClick = { act.moderateTopic(if (topic.pinned) "unpin" else "pin") }, enabled = !busy) { Text(if (topic.pinned) "открепить" else "закрепить") }
            TextButton(onClick = { act.moderateTopic("delete") }, enabled = !busy) { Text("удалить тему", color = c.danger) }
        }
        var name by remember(topic.id, topic.title) { mutableStateOf(topic.title) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(name, { name = it.take(Rules.TITLE_MAX) }, label = { Text("Название темы") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { act.renameTopic(name) }, enabled = !busy && name.isNotBlank() && name != topic.title) { Text("сохранить") }
        }
    }
}

@Composable
private fun PostCard(p: ForumPost, moderator: Boolean, canWrite: Boolean, busy: Boolean, act: SiteActions, onQuote: (String) -> Unit) {
    val c = Tz.colors
    var editing by remember(p.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().tzPanel(c).border(if (p.unread) 1.dp else 0.dp, if (p.unread) c.accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(Design.Radius.L.dp))
            .padding(Design.Space.S.dp),
        verticalArrangement = Arrangement.spacedBy(Design.Space.XS.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.author, Modifier.weight(1f), style = Tz.type.name, color = c.title)
            if (p.unread) Text("новое  ", style = Tz.type.label, color = c.accent)
            Text(date(p.created), style = Tz.type.small, color = c.textFaint)
        }
        if (editing) {
            var text by remember(p.id) { mutableStateOf(p.text) }
            OutlinedTextField(text, { text = it.take(Rules.POST_MAX) }, minLines = 3, modifier = Modifier.fillMaxWidth())
            Row {
                TextButton(onClick = { act.editPost(p, text); editing = false }, enabled = !busy && text.isNotBlank()) { Text("сохранить") }
                TextButton(onClick = { editing = false }) { Text("отмена") }
            }
        } else PostText(p.text)
        p.editedBy?.let { Text("изменено: $it", style = Tz.type.small, color = c.textFaint) }
        if (!editing) Row {
            if (canWrite) TextButton(onClick = {
                val own = p.text.lines().filterNot { it.startsWith(">") }.joinToString("\n").trim().take(300)
                onQuote("> ${p.author}:\n" + own.lines().joinToString("\n") { "> $it" })
            }, enabled = !busy) { Text("цитировать") }
            if (p.mine || moderator) TextButton(onClick = { editing = true }, enabled = !busy) { Text("изменить") }
            if (moderator) TextButton(onClick = { act.deletePost(p) }, enabled = !busy) { Text("удалить", color = c.danger) }
        }
    }
}

// ---- pages -------------------------------------------------------------------------------------

@Composable
fun PagesPanel(pages: List<PageSummary>, page: PageView?, busy: Boolean, act: SiteActions) {
    val c = Tz.colors
    Column(verticalArrangement = Arrangement.spacedBy(Design.Space.XS.dp)) {
        if (page != null) {
            PageHeader(page.title, act.closePage)
            Column(Modifier.fillMaxWidth().tzPanel(c).padding(Design.Space.M.dp), verticalArrangement = Arrangement.spacedBy(Design.Space.S.dp)) {
                page.text.split("\n\n").forEach { Text(rich(it), style = Tz.type.body, color = c.text) }
            }
        } else {
            PageHeader("Об игре и правила", act.closePages)
            pages.forEach { p -> ListLine(p.title, icon = "look", onClick = { act.openPage(p) }) }
        }
    }
}

// ---- moderation --------------------------------------------------------------------------------

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AdminPanel(a: AdminView, busy: Boolean, act: SiteActions) {
    val c = Tz.colors
    val admin = a.role == "admin"
    var target by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("60") }
    val m = minutes.toIntOrNull() ?: 0
    val named = !busy && target.isNotBlank()
    @Composable fun chip(text: String, enabled: Boolean, danger: Boolean = false, onClick: () -> Unit) =
        OutlinedButton(onClick = onClick, enabled = enabled) { Text(text, color = if (danger) c.danger else c.text, style = Tz.type.button) }
    Column(verticalArrangement = Arrangement.spacedBy(Design.Space.XS.dp)) {
        PageHeader(if (admin) "Администрирование" else "Модерация", act.closeAdmin, sub = a.role)
        a.message?.let { Text(it, style = Tz.type.body, color = c.accent) }
        OutlinedTextField(target, { target = it }, label = { Text("Имя персонажа") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(reason, { reason = it }, label = { Text("Причина или текст") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            minutes, { minutes = it.filter(Char::isDigit).take(6) }, label = { Text("Минут (0 — навсегда, только админ)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Design.Space.XS.dp)) {
            chip("мут", named) { act.adminOp("mute", target, reason, "", 1, m) }
            chip("снять мут", named) { act.adminOp("unmute", target, "", "", 1, 0) }
            chip("выгнать", named) { act.adminOp("kick", target, "", "", 1, 0) }
            chip("бан", named, danger = true) { act.adminOp("ban", target, reason, "", 1, m) }
            if (admin) chip("снять бан", named) { act.adminOp("unban", target, "", "", 1, 0) }
            chip("призвать", named) { act.adminOp("summon", target, "", "", 1, 0) }
            chip("сказать всем", !busy && reason.isNotBlank()) { act.adminOp("broadcast", "", reason, "", 1, 0) }
        }
        SectionLabel("Телепорт", if (target.isBlank()) "себя" else target)
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Design.Space.XS.dp)) {
            a.places.forEach { p -> chip(p.label, !busy) { act.adminOp("teleport", target, p.value, "", 1, 0) } }
        }
        if (admin) {
            var item by remember { mutableStateOf("") }
            var count by remember { mutableStateOf("1") }
            SectionLabel("Выдать предмет")
            Row(horizontalArrangement = Arrangement.spacedBy(Design.Space.S.dp)) {
                OutlinedTextField(item, { item = it.trim() }, label = { Text("Предмет (i.money…)") }, singleLine = true, modifier = Modifier.weight(2f))
                OutlinedTextField(count, { count = it.filter(Char::isDigit).take(6) }, label = { Text("Сколько") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(Design.Space.XS.dp)) {
                chip("выдать", named && item.isNotBlank()) { act.adminOp("give", target, "", item, count.toIntOrNull() ?: 1, 0) }
                chip("сделать модератором", named) { act.adminOp("role", target, "moder", "", 1, 0) }
                chip("снять роль", named) { act.adminOp("role", target, "player", "", 1, 0) }
            }
            if (a.gifts.isNotEmpty()) {
                SectionLabel("Подарки у Эдварда", "имя «*» — всем")
                a.gifts.forEach { g ->
                    ListLine(g.label, if (a.newGift == g.value) "получает каждый новый персонаж" else null) {
                        TextButton(onClick = { act.adminOp("gift", target, "", g.value, 1, 0) }, enabled = named) { Text("выдать") }
                        TextButton(onClick = { act.adminOp("giftNew", "", "", if (a.newGift == g.value) "" else g.value, 1, 0) }, enabled = !busy) {
                            Text(if (a.newGift == g.value) "новым ✓" else "новым")
                        }
                    }
                }
            }
        }
        SectionLabel("Журнал")
        if (a.log.isEmpty()) Text("пусто", style = Tz.type.small, color = c.textMuted)
        a.log.forEach { Text(it, style = Tz.type.log, color = c.logSystem) }
    }
}

/** Forgot the password: the recovery code sets a new one. */
@Composable
fun RecoverForm(busy: Boolean, onRecover: (String, String, String) -> Unit, onCancel: () -> Unit) {
    var login by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    Text("Восстановление пароля", style = MaterialTheme.typography.titleMedium)
    Text("Введите код восстановления, полученный в разделе «Аккаунт».", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(login, { login = it }, label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(code, { code = it.uppercase() }, label = { Text("Код (XXXX-XXXX-XXXX-XXXX)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Password(password, "Новый пароль") { password = it }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onRecover(login, code, password) }, enabled = !busy && login.isNotBlank() && code.isNotBlank() && password.isNotBlank()) { Text("Сменить пароль") }
        TextButton(onClick = onCancel) { Text("Отмена") }
    }
}
