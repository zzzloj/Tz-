import SwiftUI
import Shared

/// Account, forum, pages and moderation: what the old site did, in the app.
struct SiteActions {
    let closeAccount: () -> Void
    let changePassword: (String, String) -> Void
    let makeRecovery: (String) -> Void
    let setAbout: (String) -> Void
    let deleteAccount: (String) -> Void
    let openSection: (ForumSection) -> Void
    let openTopic: (ForumTopic) -> Void
    let forumPage: (Int) -> Void
    let forumBack: () -> Void
    let closeForum: () -> Void
    let newTopic: (String, String) -> Void
    let reply: (String) -> Void
    let editPost: (ForumPost, String) -> Void
    let moderateTopic: (String) -> Void
    let renameTopic: (String) -> Void
    let deletePost: (ForumPost) -> Void
    let openPage: (PageSummary) -> Void
    let closePage: () -> Void
    let closePages: () -> Void
    let adminOp: (String, String, String, String, Int, Int) -> Void
    let closeAdmin: () -> Void
    let search: (String) -> Void
    let openHit: (ForumHit) -> Void
    let openUnread: (ForumTopic) -> Void
    let follow: (Bool) -> Void
    let openAdmin: (() -> Void)?
    let signOut: () -> Void
}

enum SiteFormat {
    static let formatter: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "ru_RU")
        f.dateFormat = "dd.MM.yyyy HH:mm"
        return f
    }()

    static func date(_ unix: Int64) -> String { formatter.string(from: Date(timeIntervalSince1970: TimeInterval(unix))) }

    /// The pages' little markdown (**bold**).
    static func rich(_ text: String) -> AttributedString {
        (try? AttributedString(markdown: text, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace))) ?? AttributedString(text)
    }
}

// MARK: - Building blocks in the game's style

/// The top of a page: back, title, something on the right.
struct PageHeader<Trailing: View>: View {
    let title: String
    let onBack: (() -> Void)?
    var sub: String? = nil
    @ViewBuilder var trailing: () -> Trailing
    @Environment(\.tz) private var c

    var body: some View {
        HStack {
            if let back = onBack { ActionButton(label: "назад", enabled: true, action: back, icon: "west") }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(TzType.heading).foregroundStyle(c.title).lineLimit(2)
                if let s = sub, !s.isEmpty { Text(s).font(TzType.small).foregroundStyle(c.textMuted) }
            }
            Spacer()
            trailing()
        }
    }
}

extension PageHeader where Trailing == EmptyView {
    init(title: String, onBack: (() -> Void)?, sub: String? = nil) {
        self.init(title: title, onBack: onBack, sub: sub) { EmptyView() }
    }
}

struct SectionLabel: View {
    let text: String
    var note: String? = nil
    @Environment(\.tz) private var c

    var body: some View {
        HStack {
            Text(text).font(TzType.label).foregroundStyle(c.accent)
            Spacer()
            if let n = note { Text(n).font(TzType.small).foregroundStyle(c.textMuted) }
        }
        .padding(.top, CGFloat(Design.Space.shared.M))
    }
}

/// A row of a list page: icon, title, second line, a badge; tappable.
struct ListLine<Trailing: View>: View {
    let title: String
    var sub: String? = nil
    var icon: String? = nil
    var danger = false
    var badge: String? = nil
    var action: (() -> Void)? = nil
    @ViewBuilder var trailing: () -> Trailing
    @Environment(\.tz) private var c

    var body: some View {
        let row = HStack(spacing: CGFloat(Design.Space.shared.S)) {
            if let i = icon { TzIcon(key: i, color: danger ? c.danger : c.link) }
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(TzType.name).foregroundStyle(danger ? c.danger : c.title).lineLimit(2)
                if let s = sub { Text(s).font(TzType.small).foregroundStyle(c.textMuted).lineLimit(2) }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if let b = badge {
                Text(b).font(TzType.number).foregroundStyle(c.onPrimary).padding(.horizontal, 7).padding(.vertical, 1).background(Capsule().fill(c.primary))
            }
            trailing()
        }
        .padding(.horizontal, CGFloat(Design.Space.shared.S)).padding(.vertical, CGFloat(Design.Space.shared.XS))
        .frame(minHeight: CGFloat(Design.Size.shared.TOUCH))
        .tzPanel(c)
        if let a = action { Button(action: a) { row }.buttonStyle(.plain) } else { row }
    }
}

extension ListLine where Trailing == EmptyView {
    init(title: String, sub: String? = nil, icon: String? = nil, danger: Bool = false, badge: String? = nil, action: (() -> Void)? = nil) {
        self.init(title: title, sub: sub, icon: icon, danger: danger, badge: badge, action: action) { EmptyView() }
    }
}

/// A text field in a bronze frame.
struct TzField: View {
    let label: String
    @Binding var text: String
    var secure = false
    var lines = 1
    @Environment(\.tz) private var c

    var body: some View {
        Group {
            if secure { SecureField(label, text: $text) }
            else if lines > 1 { TextField(label, text: $text, axis: .vertical).lineLimit(lines...8) }
            else { TextField(label, text: $text) }
        }
        .textInputAutocapitalization(.never)
        .padding(CGFloat(Design.Space.shared.S))
        .background(c.surfaceSunken)
        .clipShape(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.M)))
        .overlay(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.M)).stroke(c.border, lineWidth: 1))
    }
}

/// Forum text: lines starting with «>» are a quote, **bold** works.
struct PostText: View {
    let text: String
    @Environment(\.tz) private var c

    private var blocks: [(Bool, String)] {
        var out: [(Bool, [String])] = []
        for line in text.components(separatedBy: "\n") {
            let quote = line.hasPrefix(">")
            let body = quote ? String(line.dropFirst()).trimmingCharacters(in: .whitespaces) : line
            if out.last?.0 == quote { out[out.count - 1].1.append(body) } else { out.append((quote, [body])) }
        }
        return out.map { ($0.0, $0.1.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)) }.filter { !$0.1.isEmpty }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: CGFloat(Design.Space.shared.XS)) {
            ForEach(Array(blocks.enumerated()), id: \.offset) { _, b in
                if b.0 {
                    HStack(spacing: 0) {
                        Rectangle().fill(c.accent).frame(width: 3)
                        Text(SiteFormat.rich(b.1)).font(TzType.small).foregroundStyle(c.textMuted).padding(CGFloat(Design.Space.shared.S))
                        Spacer(minLength: 0)
                    }
                    .background(c.surfaceSunken)
                } else {
                    Text(SiteFormat.rich(b.1)).font(TzType.body).foregroundStyle(c.text)
                }
            }
        }
    }
}

private struct PrimaryButton: View {
    let title: String
    let enabled: Bool
    let action: () -> Void
    var danger = false
    @Environment(\.tz) private var c

    var body: some View {
        Button(action: action) {
            Text(title).font(TzType.button).foregroundStyle(danger ? c.onDanger : c.onPrimary)
                .frame(maxWidth: .infinity, minHeight: CGFloat(Design.Size.shared.TOUCH))
                .background(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.M)).fill(danger ? c.dangerFill : c.primary))
        }
        .buttonStyle(.plain).disabled(!enabled).opacity(enabled ? 1 : 0.45)
    }
}

// MARK: - Account

struct AccountView_: View {
    let account: AccountView
    let info: String?
    let busy: Bool
    let act: SiteActions
    @State private var open: String? = nil
    @State private var about = ""
    @State private var old = ""
    @State private var fresh = ""
    @State private var password = ""
    @State private var deletePassword = ""
    @AppStorage("theme") private var theme = "system"
    @Environment(\.tz) private var c

    private func toggle(_ k: String) { open = open == k ? nil : k }

    var body: some View {
        VStack(alignment: .leading, spacing: CGFloat(Design.Space.shared.XS)) {
            PageHeader(title: "Аккаунт", onBack: act.closeAccount,
                       sub: account.login + (account.character.map { " · \($0)" } ?? "") + (account.role == "admin" ? " · администратор" : account.role == "moder" ? " · модератор" : ""))
            if Double(account.mutedUntil) > Date().timeIntervalSince1970 {
                Text("Вам запрещено писать до \(SiteFormat.date(account.mutedUntil))").font(TzType.small).foregroundStyle(c.danger)
            }
            if let info { Text(info).foregroundStyle(c.accent) }
            if let code = account.recoveryCode {
                VStack(alignment: .leading) {
                    Text("Ваш код восстановления").font(TzType.label).foregroundStyle(c.accent)
                    Text(code).font(TzType.heading).foregroundStyle(c.title).textSelection(.enabled)
                    Text("Запишите его: он показывается один раз.").font(TzType.small).foregroundStyle(c.textMuted)
                }.padding(CGFloat(Design.Space.shared.M)).frame(maxWidth: .infinity, alignment: .leading).tzPanel(c)
            }
            SectionLabel(text: "Тема")
            Picker("Тема", selection: $theme) {
                Text("Как в системе").tag("system")
                Text("Ночь").tag("night")
                Text("Пергамент").tag("parchment")
            }.pickerStyle(.segmented)

            SectionLabel(text: "Безопасность")
            ListLine(title: "Сменить пароль", icon: "settings", action: { toggle("pw") })
            if open == "pw" {
                TzField(label: "Текущий пароль", text: $old, secure: true)
                TzField(label: "Новый пароль (от \(Rules.shared.PASSWORD_MIN) символов)", text: $fresh, secure: true)
                PrimaryButton(title: "Сменить пароль", enabled: !busy && !old.isEmpty && !fresh.isEmpty) { act.changePassword(old, fresh); old = ""; fresh = "" }
            }
            ListLine(title: "Код восстановления", sub: account.hasRecovery ? "выдан; новый заменит старый" : "ещё нет — без него забытый пароль не вернуть",
                     icon: "mail", danger: !account.hasRecovery, action: { toggle("code") })
            if open == "code" {
                TzField(label: "Пароль", text: $password, secure: true)
                PrimaryButton(title: "Получить код", enabled: !busy && !password.isEmpty) { act.makeRecovery(password); password = "" }
            }

            SectionLabel(text: "Профиль")
            ListLine(title: "О себе", sub: account.about.isEmpty ? "видят те, кто вас осматривает" : account.about, icon: "tab-hero",
                     action: { about = account.about; toggle("about") })
            if open == "about" {
                TzField(label: "О себе", text: $about, lines: 3)
                PrimaryButton(title: "Сохранить", enabled: !busy && about != account.about) { act.setAbout(String(about.prefix(Int(Rules.shared.ABOUT_MAX)))) }
            }

            if let admin = act.openAdmin {
                SectionLabel(text: "Модерация")
                ListLine(title: "Панель модератора", sub: "мут, бан, телепорт" + (account.role == "admin" ? ", подарки, роли" : ""), icon: "clan", action: admin)
            }

            SectionLabel(text: "Выход")
            ListLine(title: "Выйти из игры", icon: "flee", action: act.signOut)
            ListLine(title: "Удалить аккаунт", sub: "персонаж и все вещи исчезнут навсегда", danger: true, action: { toggle("delete") })
            if open == "delete" {
                TzField(label: "Пароль для подтверждения", text: $deletePassword, secure: true)
                PrimaryButton(title: "Удалить навсегда", enabled: !busy && !deletePassword.isEmpty, action: { act.deleteAccount(deletePassword) }, danger: true)
            }
        }
    }
}

// MARK: - Forum

struct ForumView_: View {
    let forum: ForumView
    let busy: Bool
    let act: SiteActions
    @State private var reply = ""
    @State private var title = ""
    @State private var text = ""
    @State private var rename = ""
    @State private var query = ""
    @State private var writing = false
    @Environment(\.tz) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: CGFloat(Design.Space.shared.XS)) {
            if let q = forum.query {
                PageHeader(title: "Поиск: «\(q)»", onBack: act.closeForum, sub: forum.hits.isEmpty ? "ничего не нашлось" : "найдено \(forum.hits.count)")
                searchBox
                if q.count < 3 { Text("Нужно хотя бы 3 буквы.").font(TzType.small).foregroundStyle(c.textMuted) }
                ForEach(forum.hits, id: \.postId) { h in
                    ListLine(title: h.topic.title, sub: "\(h.author): \(h.snippet)", icon: "look", action: { act.openHit(h) })
                }
            } else if let topic = forum.topic {
                topicBody(topic)
            } else if let section = forum.section {
                sectionBody(section)
            } else {
                PageHeader(title: "Форум", onBack: act.closeForum, sub: "тот же форум, что на сайте")
                searchBox
                if !forum.replies.isEmpty {
                    SectionLabel(text: "Ответы в ваших темах", note: "\(forum.replies.count)")
                    ForEach(forum.replies, id: \.id) { t in
                        ListLine(title: t.title, sub: "последнее: \(t.lastAuthor ?? t.author) · \(SiteFormat.date(t.updated))", icon: "mail", badge: "новое", action: { act.openUnread(t) })
                    }
                }
                SectionLabel(text: "Разделы")
                ForEach(forum.sections, id: \.id) { s in
                    ListLine(title: s.title, sub: "\(s.topics) тем · \(s.posts) сообщ." + (s.info.isEmpty ? "" : " · \(s.info)"), icon: "talk",
                             badge: s.unread > 0 ? "\(s.unread)" : nil, action: { act.openSection(s) })
                }
                if !forum.canWrite { Text("Писать на форуме могут вошедшие в игру.").font(TzType.small).foregroundStyle(c.textMuted) }
            }
        }
    }

    private var searchBox: some View {
        HStack {
            TzField(label: "Поиск по форуму", text: $query)
            ActionButton(label: "искать", enabled: !busy && query.trimmingCharacters(in: .whitespaces).count >= 3, action: { act.search(query) }, icon: "look")
        }
        .onAppear { query = forum.query ?? query }
    }

    @ViewBuilder private func pager() -> some View {
        if forum.pages > 1 {
            HStack {
                Spacer()
                ActionButton(label: "предыдущая страница", enabled: !busy && forum.page > 0, action: { act.forumPage(Int(forum.page) - 1) }, icon: "west")
                Text("стр. \(forum.page + 1) из \(forum.pages)").font(TzType.label).foregroundStyle(c.textMuted)
                ActionButton(label: "следующая страница", enabled: !busy && forum.page < forum.pages - 1, action: { act.forumPage(Int(forum.page) + 1) }, icon: "east")
                Spacer()
            }
        }
    }

    @ViewBuilder private func sectionBody(_ section: ForumSection) -> some View {
        PageHeader(title: section.title, onBack: act.forumBack, sub: section.info)
        if forum.topics.isEmpty { Text("Тем пока нет.").foregroundStyle(c.textMuted) }
        ForEach(forum.topics, id: \.id) { t in
            ListLine(title: (t.pinned ? "📌 " : "") + (t.closed ? "🔒 " : "") + t.title,
                     sub: "\(t.author) · \(t.posts) сообщ. · \(SiteFormat.date(t.updated))" + (t.lastAuthor.map { " · последнее: \($0)" } ?? ""),
                     badge: t.unread ? "новое" : nil, action: { if t.unread { act.openUnread(t) } else { act.openTopic(t) } })
        }
        pager()
        if forum.canWrite && (!section.staffOnly || forum.moderator) {
            if !writing {
                PrimaryButton(title: "Новая тема", enabled: !busy) { writing = true }
            } else {
                SectionLabel(text: "Новая тема")
                TzField(label: "Название", text: $title)
                TzField(label: "Сообщение", text: $text, lines: 4)
                HStack {
                    PrimaryButton(title: "Создать тему", enabled: !busy && !title.trimmingCharacters(in: .whitespaces).isEmpty && !text.trimmingCharacters(in: .whitespaces).isEmpty) {
                        act.newTopic(title, text); title = ""; text = ""; writing = false
                    }
                    Button("Отмена") { writing = false }
                }
            }
        }
    }

    @ViewBuilder private func topicBody(_ topic: ForumTopic) -> some View {
        PageHeader(title: topic.title, onBack: act.forumBack,
                   sub: [forum.section?.title, topic.pinned ? "закреплена" : nil, topic.closed ? "закрыта" : nil].compactMap { $0 }.joined(separator: " · ")) {
            if forum.canWrite || topic.followed { Button(topic.followed ? "не следить" : "следить") { act.follow(!topic.followed) }.disabled(busy) }
        }
        pager()
        ForEach(forum.posts, id: \.id) { p in
            PostRow(post: p, moderator: forum.moderator, canWrite: forum.canWrite, busy: busy, act: act) { q in reply = q + "\n\n" + reply }
        }
        pager()
        if forum.canWrite {
            TzField(label: "Ответ", text: $reply, lines: 3)
            PrimaryButton(title: "Ответить", enabled: !busy && !reply.trimmingCharacters(in: .whitespaces).isEmpty) { act.reply(reply); reply = "" }
        } else if topic.closed {
            Text("Тема закрыта.").font(TzType.small).foregroundStyle(c.textMuted)
        }
        if forum.moderator {
            SectionLabel(text: "Модерация")
            HStack {
                Button(topic.closed ? "открыть" : "закрыть") { act.moderateTopic(topic.closed ? "open" : "close") }
                Spacer()
                Button(topic.pinned ? "открепить" : "закрепить") { act.moderateTopic(topic.pinned ? "unpin" : "pin") }
                Spacer()
                Button("удалить тему", role: .destructive) { act.moderateTopic("delete") }
            }.disabled(busy)
            HStack {
                TzField(label: "Новое название", text: $rename)
                Button("сохранить") { act.renameTopic(rename); rename = "" }.disabled(busy || rename.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }
}

struct PostRow: View {
    let post: ForumPost
    let moderator: Bool
    let canWrite: Bool
    let busy: Bool
    let act: SiteActions
    let onQuote: (String) -> Void
    @State private var editing = false
    @State private var text = ""
    @Environment(\.tz) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: CGFloat(Design.Space.shared.XS)) {
            HStack {
                Text(post.author).font(TzType.name).foregroundStyle(c.title)
                Spacer()
                if post.unread { Text("новое").font(TzType.label).foregroundStyle(c.accent) }
                Text(SiteFormat.date(post.created)).font(TzType.small).foregroundStyle(c.textFaint)
            }
            if editing {
                TzField(label: "Сообщение", text: $text, lines: 3)
                HStack {
                    Button("сохранить") { act.editPost(post, text); editing = false }.disabled(busy)
                    Button("отмена") { editing = false }
                }
            } else {
                PostText(text: post.text)
            }
            if let e = post.editedBy { Text("изменено: \(e)").font(TzType.small).foregroundStyle(c.textFaint) }
            if !editing {
                HStack(spacing: CGFloat(Design.Space.shared.M)) {
                    if canWrite {
                        Button("цитировать") {
                            let own = post.text.components(separatedBy: "\n").filter { !$0.hasPrefix(">") }.joined(separator: "\n")
                                .trimmingCharacters(in: .whitespacesAndNewlines).prefix(300)
                            onQuote("> \(post.author):\n" + own.components(separatedBy: "\n").map { "> " + $0 }.joined(separator: "\n"))
                        }.disabled(busy)
                    }
                    if post.mine || moderator { Button("изменить") { text = post.text; editing = true }.disabled(busy) }
                    if moderator { Button("удалить", role: .destructive) { act.deletePost(post) }.disabled(busy) }
                }
                .font(TzType.small)
            }
        }
        .padding(CGFloat(Design.Space.shared.S))
        .tzPanel(c)
        .overlay(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.L)).stroke(post.unread ? c.accent : Color.clear, lineWidth: 1))
    }
}

// MARK: - Pages

struct PagesView_: View {
    let pages: [PageSummary]
    let page: PageView?
    let busy: Bool
    let act: SiteActions
    @Environment(\.tz) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: CGFloat(Design.Space.shared.XS)) {
            if let page {
                PageHeader(title: page.title, onBack: act.closePage)
                VStack(alignment: .leading, spacing: CGFloat(Design.Space.shared.S)) {
                    ForEach(Array(page.text.components(separatedBy: "\n\n").enumerated()), id: \.offset) { _, para in
                        Text(SiteFormat.rich(para)).font(TzType.body)
                    }
                }
                .padding(CGFloat(Design.Space.shared.M)).frame(maxWidth: .infinity, alignment: .leading).tzPanel(c)
            } else {
                PageHeader(title: "Об игре и правила", onBack: act.closePages)
                ForEach(pages, id: \.id) { p in ListLine(title: p.title, icon: "look", action: { act.openPage(p) }) }
            }
        }
    }
}

// MARK: - Moderation

struct AdminView_: View {
    let admin: AdminView
    let busy: Bool
    let act: SiteActions
    @State private var target = ""
    @State private var reason = ""
    @State private var minutes = "60"
    @State private var item = ""
    @State private var count = "1"
    @Environment(\.tz) private var c

    private var isAdmin: Bool { admin.role == "admin" }
    private var named: Bool { !busy && !target.trimmingCharacters(in: .whitespaces).isEmpty }
    private var m: Int { Int(minutes) ?? 0 }

    private func chip(_ title: String, _ enabled: Bool, danger: Bool = false, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title).font(TzType.button).foregroundStyle(danger ? c.danger : c.text)
                .padding(.horizontal, CGFloat(Design.Space.shared.M)).frame(minHeight: 38)
                .overlay(RoundedRectangle(cornerRadius: CGFloat(Design.Radius.shared.M)).stroke(danger ? c.danger : c.border, lineWidth: 1))
        }
        .buttonStyle(.plain).disabled(!enabled).opacity(enabled ? 1 : 0.45)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: CGFloat(Design.Space.shared.XS)) {
            PageHeader(title: isAdmin ? "Администрирование" : "Модерация", onBack: act.closeAdmin, sub: admin.role)
            if let msg = admin.message { Text(msg).foregroundStyle(c.accent) }
            TzField(label: "Имя персонажа", text: $target)
            TzField(label: "Причина или текст", text: $reason)
            TzField(label: "Минут (0 — навсегда, только админ)", text: $minutes).keyboardType(.numberPad)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 4)], alignment: .leading, spacing: 4) {
                chip("мут", named) { act.adminOp("mute", target, reason, "", 1, m) }
                chip("снять мут", named) { act.adminOp("unmute", target, "", "", 1, 0) }
                chip("выгнать", named) { act.adminOp("kick", target, "", "", 1, 0) }
                chip("бан", named, danger: true) { act.adminOp("ban", target, reason, "", 1, m) }
                if isAdmin { chip("снять бан", named) { act.adminOp("unban", target, "", "", 1, 0) } }
                chip("призвать", named) { act.adminOp("summon", target, "", "", 1, 0) }
                chip("сказать всем", !busy && !reason.trimmingCharacters(in: .whitespaces).isEmpty) { act.adminOp("broadcast", "", reason, "", 1, 0) }
            }
            SectionLabel(text: "Телепорт", note: target.isEmpty ? "себя" : target)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 100), spacing: 4)], alignment: .leading, spacing: 4) {
                ForEach(admin.places, id: \.value) { p in chip(p.label, !busy) { act.adminOp("teleport", target, p.value, "", 1, 0) } }
            }
            if isAdmin {
                SectionLabel(text: "Выдать предмет")
                HStack {
                    TzField(label: "Предмет (i.money…)", text: $item)
                    TzField(label: "Сколько", text: $count).keyboardType(.numberPad).frame(width: 90)
                }
                HStack {
                    chip("выдать", named && !item.isEmpty) { act.adminOp("give", target, "", item, Int(count) ?? 1, 0) }
                    chip("модератором", named) { act.adminOp("role", target, "moder", "", 1, 0) }
                    chip("снять роль", named) { act.adminOp("role", target, "player", "", 1, 0) }
                }
                if !admin.gifts.isEmpty {
                    SectionLabel(text: "Подарки у Эдварда", note: "имя «*» — всем")
                    ForEach(admin.gifts, id: \.value) { g in
                        let on = admin.newGift == g.value
                        ListLine(title: g.label, sub: on ? "получает каждый новый персонаж" : nil) {
                            Button("выдать") { act.adminOp("gift", target, "", g.value, 1, 0) }.disabled(!named)
                            Button(on ? "новым ✓" : "новым") { act.adminOp("giftNew", "", "", on ? "" : g.value, 1, 0) }.disabled(busy)
                        }
                    }
                }
            }
            SectionLabel(text: "Журнал")
            if admin.log.isEmpty { Text("пусто").font(TzType.small).foregroundStyle(c.textMuted) }
            ForEach(Array(admin.log.enumerated()), id: \.offset) { _, line in Text(line).font(TzType.log).foregroundStyle(c.logSystem) }
        }
    }
}

/// Forgot the password: the recovery code sets a new one.
struct RecoverView: View {
    let busy: Bool
    let onRecover: (String, String, String) -> Void
    let onCancel: () -> Void
    @State private var login = ""
    @State private var code = ""
    @State private var password = ""

    var body: some View {
        Section("Восстановление пароля") {
            Text("Введите код восстановления, полученный в разделе «Аккаунт».").font(.footnote)
            TextField("Логин", text: $login).textInputAutocapitalization(.never).autocorrectionDisabled()
            TextField("Код (XXXX-XXXX-XXXX-XXXX)", text: $code).textInputAutocapitalization(.characters).autocorrectionDisabled()
            SecureField("Новый пароль", text: $password)
            Button("Сменить пароль") { onRecover(login, code, password) }.disabled(busy || login.isEmpty || code.isEmpty || password.isEmpty)
            Button("Отмена") { onCancel() }
        }
    }
}
