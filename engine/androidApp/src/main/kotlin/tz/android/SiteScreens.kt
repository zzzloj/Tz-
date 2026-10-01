package tz.android

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

@Composable
fun AccountPanel(a: AccountView, info: String?, busy: Boolean, act: SiteActions) {
    val small = MaterialTheme.typography.bodySmall
    Panel("Аккаунт ${a.login}", act.closeAccount) {
        a.character?.let { Text("Персонаж: $it", style = small) }
        val theme = LocalThemeChoice.current
        Text("Тема", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("system" to "Как в системе", "night" to "Ночь", "parchment" to "Пергамент").forEach { (key, title) ->
                FilterChip(selected = theme.mode == key, onClick = { theme.set(key) }, label = { Text(title) })
            }
        }
        if (a.role != "player") Text(if (a.role == "admin") "Вы администратор" else "Вы модератор", style = small)
        if (a.mutedUntil * 1000 > System.currentTimeMillis()) Text("Вам запрещено писать до ${date(a.mutedUntil)}", style = small, color = MaterialTheme.colorScheme.error)
        info?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        a.recoveryCode?.let { code ->
            Surface(tonalElevation = 4.dp, shape = MaterialTheme.shapes.small) {
                Text(code, Modifier.padding(12.dp), style = MaterialTheme.typography.headlineSmall)
            }
        }

        HorizontalDivider()
        Text("О себе (видят те, кто вас осматривает)", style = MaterialTheme.typography.labelMedium)
        var about by remember(a.about) { mutableStateOf(a.about) }
        OutlinedTextField(about, { about = it.take(Rules.ABOUT_MAX) }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { act.setAbout(about) }, enabled = !busy && about != a.about) { Text("Сохранить") }

        HorizontalDivider()
        Text("Смена пароля", style = MaterialTheme.typography.labelMedium)
        var old by remember { mutableStateOf("") }
        var fresh by remember { mutableStateOf("") }
        Password(old, "Текущий пароль") { old = it }
        Password(fresh, "Новый пароль (от ${Rules.PASSWORD_MIN} символов)") { fresh = it }
        Button(onClick = { act.changePassword(old, fresh); old = ""; fresh = "" }, enabled = !busy && old.isNotEmpty() && fresh.isNotEmpty()) { Text("Сменить пароль") }

        HorizontalDivider()
        Text("Код восстановления", style = MaterialTheme.typography.labelMedium)
        Text(
            if (a.hasRecovery) "Код уже выдан. Новый код заменит старый." else "Кода ещё нет. Без него забытый пароль не восстановить: почту игра не спрашивает.",
            style = small,
        )
        var pw by remember { mutableStateOf("") }
        Password(pw, "Пароль") { pw = it }
        OutlinedButton(onClick = { act.makeRecovery(pw); pw = "" }, enabled = !busy && pw.isNotEmpty()) { Text("Получить код") }

        HorizontalDivider()
        var deleting by remember { mutableStateOf(false) }
        if (!deleting) TextButton(onClick = { deleting = true }) { Text("Удалить аккаунт…", color = MaterialTheme.colorScheme.error) }
        else {
            Text("Персонаж и все его вещи исчезнут навсегда. Введите пароль, чтобы подтвердить.", style = small, color = MaterialTheme.colorScheme.error)
            var dpw by remember { mutableStateOf("") }
            Password(dpw, "Пароль") { dpw = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { act.deleteAccount(dpw) }, enabled = !busy && dpw.isNotEmpty()) { Text("Удалить навсегда") }
                TextButton(onClick = { deleting = false }) { Text("Отмена") }
            }
        }
    }
}

@Composable
private fun Pager(page: Int, pages: Int, busy: Boolean, onPage: (Int) -> Unit) {
    if (pages <= 1) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onPage(page - 1) }, enabled = !busy && page > 0) { Text("←") }
        Text("стр. ${page + 1} из $pages", style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = { onPage(page + 1) }, enabled = !busy && page < pages - 1) { Text("→") }
    }
}

@Composable
fun ForumPanel(f: ForumView, busy: Boolean, act: SiteActions) {
    val small = MaterialTheme.typography.bodySmall
    val topic = f.topic
    val section = f.section
    val title = topic?.title ?: section?.title ?: "Форум"
    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (section != null) TextButton(onClick = act.forumBack, enabled = !busy) { Text("←") }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = act.closeForum) { Text("закрыть") }
            }
            when {
                topic != null -> {
                    Text(listOfNotNull(if (topic.pinned) "закреплена" else null, if (topic.closed) "закрыта" else null).joinToString(" · "), style = small)
                    f.posts.forEach { p -> PostView(p, f.moderator, busy, act) }
                    Pager(f.page, f.pages, busy, act.forumPage)
                    if (f.canWrite) {
                        var text by remember(topic.id, f.posts.size) { mutableStateOf("") }
                        OutlinedTextField(text, { text = it.take(Rules.POST_MAX) }, label = { Text("Ответ") }, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { act.reply(text) }, enabled = !busy && text.isNotBlank()) { Text("Ответить") }
                    } else if (topic.closed) Text("Тема закрыта", style = small)
                    if (f.moderator) {
                        Text("Модерация:", style = MaterialTheme.typography.labelMedium)
                        Row {
                            TextButton(onClick = { act.moderateTopic(if (topic.closed) "open" else "close") }, enabled = !busy) { Text(if (topic.closed) "открыть" else "закрыть") }
                            TextButton(onClick = { act.moderateTopic(if (topic.pinned) "unpin" else "pin") }, enabled = !busy) { Text(if (topic.pinned) "открепить" else "закрепить") }
                            TextButton(onClick = { act.moderateTopic("delete") }, enabled = !busy) { Text("удалить тему", color = MaterialTheme.colorScheme.error) }
                        }
                        var name by remember(topic.id, topic.title) { mutableStateOf(topic.title) }
                        OutlinedTextField(name, { name = it.take(Rules.TITLE_MAX) }, label = { Text("Название темы") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        TextButton(onClick = { act.renameTopic(name) }, enabled = !busy && name.isNotBlank() && name != topic.title) { Text("переименовать") }
                    }
                }
                section != null -> {
                    if (section.info.isNotBlank()) Text(section.info, style = small)
                    if (f.topics.isEmpty()) Text("Тем пока нет", style = small)
                    f.topics.forEach { t ->
                        TextButton(onClick = { act.openTopic(t) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text((if (t.pinned) "📌 " else "") + (if (t.closed) "🔒 " else "") + t.title)
                                Text("${t.author} · ${t.posts} сообщ. · ${date(t.updated)}" + (t.lastAuthor?.let { " · $it" } ?: ""), style = small)
                            }
                        }
                    }
                    Pager(f.page, f.pages, busy, act.forumPage)
                    if (f.canWrite && (!section.staffOnly || f.moderator)) {
                        var t by remember(section.id) { mutableStateOf("") }
                        var text by remember(section.id) { mutableStateOf("") }
                        Text("Новая тема", style = MaterialTheme.typography.labelMedium)
                        OutlinedTextField(t, { t = it.take(Rules.TITLE_MAX) }, label = { Text("Название") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(text, { text = it.take(Rules.POST_MAX) }, label = { Text("Сообщение") }, modifier = Modifier.fillMaxWidth())
                        Button(onClick = { act.newTopic(t, text) }, enabled = !busy && t.isNotBlank() && text.isNotBlank()) { Text("Создать тему") }
                    }
                }
                else -> {
                    f.sections.forEach { s ->
                        TextButton(onClick = { act.openSection(s) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(s.title)
                                Text("${s.info} · тем ${s.topics}, сообщений ${s.posts}", style = small)
                            }
                        }
                    }
                    if (!f.canWrite) Text("Писать на форуме могут вошедшие в игру.", style = small)
                }
            }
        }
    }
}

@Composable
private fun PostView(p: ForumPost, moderator: Boolean, busy: Boolean, act: SiteActions) {
    val small = MaterialTheme.typography.bodySmall
    var editing by remember(p.id) { mutableStateOf(false) }
    HorizontalDivider()
    Text("${p.author} · ${date(p.created)}" + (p.editedBy?.let { " · изменено: $it" } ?: ""), style = MaterialTheme.typography.labelMedium)
    if (editing) {
        var text by remember(p.id) { mutableStateOf(p.text) }
        OutlinedTextField(text, { text = it.take(Rules.POST_MAX) }, modifier = Modifier.fillMaxWidth())
        Row {
            TextButton(onClick = { act.editPost(p, text); editing = false }, enabled = !busy && text.isNotBlank()) { Text("сохранить") }
            TextButton(onClick = { editing = false }) { Text("отмена") }
        }
    } else Text(p.text, style = MaterialTheme.typography.bodyMedium)
    if ((p.mine || moderator) && !editing) Row {
        TextButton(onClick = { editing = true }, enabled = !busy) { Text("изменить", style = small) }
        if (moderator) TextButton(onClick = { act.deletePost(p) }, enabled = !busy) { Text("удалить", style = small, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun PagesPanel(pages: List<PageSummary>, page: PageView?, busy: Boolean, act: SiteActions) {
    if (page != null) Panel(page.title, act.closePage) {
        page.text.split("\n\n").forEach { Text(rich(it), style = MaterialTheme.typography.bodyMedium) }
    } else Panel("Помощь и правила", act.closePages) {
        pages.forEach { p -> TextButton(onClick = { act.openPage(p) }, enabled = !busy) { Text(p.title) } }
    }
}

@Composable
fun AdminPanel(a: AdminView, busy: Boolean, act: SiteActions) {
    val small = MaterialTheme.typography.bodySmall
    val admin = a.role == "admin"
    var target by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("60") }
    Panel(if (admin) "Администрирование" else "Модерация", act.closeAdmin) {
        a.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        OutlinedTextField(target, { target = it }, label = { Text("Имя персонажа") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(reason, { reason = it }, label = { Text("Причина или текст") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            minutes, { minutes = it.filter(Char::isDigit).take(6) }, label = { Text("Минут (0 — навсегда, только админ)") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        )
        val m = minutes.toIntOrNull() ?: 0
        val named = !busy && target.isNotBlank()
        Row {
            TextButton(onClick = { act.adminOp("mute", target, reason, "", 1, m) }, enabled = named) { Text("мут") }
            TextButton(onClick = { act.adminOp("unmute", target, "", "", 1, 0) }, enabled = named) { Text("снять мут") }
            TextButton(onClick = { act.adminOp("kick", target, "", "", 1, 0) }, enabled = named) { Text("выгнать") }
        }
        Row {
            TextButton(onClick = { act.adminOp("ban", target, reason, "", 1, m) }, enabled = named) { Text("бан", color = MaterialTheme.colorScheme.error) }
            if (admin) TextButton(onClick = { act.adminOp("unban", target, "", "", 1, 0) }, enabled = named) { Text("снять бан") }
            TextButton(onClick = { act.adminOp("summon", target, "", "", 1, 0) }, enabled = named) { Text("призвать") }
        }
        Text("Телепорт (${if (target.isBlank()) "себя" else target}):", style = MaterialTheme.typography.labelMedium)
        a.places.chunked(3).forEach { row ->
            Row { row.forEach { p -> TextButton(onClick = { act.adminOp("teleport", target, p.value, "", 1, 0) }, enabled = !busy) { Text(p.label, style = small) } } }
        }
        TextButton(onClick = { act.adminOp("broadcast", "", reason, "", 1, 0) }, enabled = !busy && reason.isNotBlank()) { Text("Сказать всем (текст из поля «Причина»)") }
        if (admin) {
            var item by remember { mutableStateOf("") }
            var count by remember { mutableStateOf("1") }
            Text("Администратор:", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(item, { item = it.trim() }, label = { Text("Предмет (i.money…)") }, singleLine = true, modifier = Modifier.weight(2f))
                OutlinedTextField(count, { count = it.filter(Char::isDigit).take(6) }, label = { Text("Сколько") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row {
                TextButton(onClick = { act.adminOp("give", target, "", item, count.toIntOrNull() ?: 1, 0) }, enabled = named && item.isNotBlank()) { Text("выдать") }
                TextButton(onClick = { act.adminOp("role", target, "moder", "", 1, 0) }, enabled = named) { Text("сделать модератором") }
                TextButton(onClick = { act.adminOp("role", target, "player", "", 1, 0) }, enabled = named) { Text("снять") }
            }
            if (a.gifts.isNotEmpty()) {
                Text("Подарки у Эдварда в Переулке (имя «*» — всем):", style = MaterialTheme.typography.labelMedium)
                Text("Новым персонажам: " + (a.gifts.firstOrNull { it.value == a.newGift }?.label ?: "нет"), style = small)
                a.gifts.forEach { g ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(g.label, style = small, modifier = Modifier.weight(1f))
                        TextButton(onClick = { act.adminOp("gift", target, "", g.value, 1, 0) }, enabled = named) { Text("выдать") }
                        TextButton(onClick = { act.adminOp("giftNew", "", "", g.value, 1, 0) }, enabled = !busy && a.newGift != g.value) { Text("новым") }
                    }
                }
                if (a.newGift != null) TextButton(onClick = { act.adminOp("giftNew", "", "", "", 1, 0) }, enabled = !busy) { Text("не дарить новым") }
            }
        }
        Text("Журнал:", style = MaterialTheme.typography.labelMedium)
        if (a.log.isEmpty()) Text("пусто", style = small)
        a.log.forEach { Text(it, style = small) }
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
