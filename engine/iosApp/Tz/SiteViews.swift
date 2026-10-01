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

struct AccountView_: View {
    let account: AccountView
    let info: String?
    let busy: Bool
    let act: SiteActions
    @State private var about = ""
    @State private var old = ""
    @State private var fresh = ""
    @State private var password = ""
    @State private var deleting = false
    @State private var deletePassword = ""
    @AppStorage("theme") private var theme = "system"

    var body: some View {
        Section("Тема") {
            Picker("Тема", selection: $theme) {
                Text("Как в системе").tag("system")
                Text("Ночь").tag("night")
                Text("Пергамент").tag("parchment")
            }
            .pickerStyle(.segmented)
        }
        Section("Аккаунт \(account.login)") {
            if let c = account.character { Text("Персонаж: \(c)").font(.footnote) }
            if account.role != "player" { Text(account.role == "admin" ? "Вы администратор" : "Вы модератор").font(.footnote) }
            if Double(account.mutedUntil) > Date().timeIntervalSince1970 {
                Text("Вам запрещено писать до \(SiteFormat.date(account.mutedUntil))").font(.footnote).foregroundStyle(.red)
            }
            if let info { Text(info).foregroundStyle(Color.accentColor) }
            if let code = account.recoveryCode {
                Text(code).font(.title2.monospaced()).textSelection(.enabled)
            }
            Button("закрыть") { act.closeAccount() }
        }
        Section("О себе (видят те, кто вас осматривает)") {
            TextField("О себе", text: $about, axis: .vertical)
                .onAppear { about = account.about }
            Button("Сохранить") { act.setAbout(String(about.prefix(Int(Rules.shared.ABOUT_MAX)))) }.disabled(busy || about == account.about)
        }
        Section("Смена пароля") {
            SecureField("Текущий пароль", text: $old)
            SecureField("Новый пароль (от \(Rules.shared.PASSWORD_MIN) символов)", text: $fresh)
            Button("Сменить пароль") { act.changePassword(old, fresh); old = ""; fresh = "" }.disabled(busy || old.isEmpty || fresh.isEmpty)
        }
        Section("Код восстановления") {
            Text(account.hasRecovery ? "Код уже выдан. Новый код заменит старый."
                 : "Кода ещё нет. Без него забытый пароль не восстановить: почту игра не спрашивает.").font(.footnote)
            SecureField("Пароль", text: $password)
            Button("Получить код") { act.makeRecovery(password); password = "" }.disabled(busy || password.isEmpty)
        }
        Section {
            if !deleting {
                Button("Удалить аккаунт…", role: .destructive) { deleting = true }
            } else {
                Text("Персонаж и все его вещи исчезнут навсегда. Введите пароль, чтобы подтвердить.").font(.footnote).foregroundStyle(.red)
                SecureField("Пароль", text: $deletePassword)
                Button("Удалить навсегда", role: .destructive) { act.deleteAccount(deletePassword) }.disabled(busy || deletePassword.isEmpty)
                Button("Отмена") { deleting = false }
            }
        }
    }
}

struct ForumView_: View {
    let forum: ForumView
    let busy: Bool
    let act: SiteActions
    @State private var reply = ""
    @State private var title = ""
    @State private var text = ""
    @State private var rename = ""

    var body: some View {
        Section {
            HStack {
                if forum.section != nil { Button("←") { act.forumBack() }.disabled(busy).buttonStyle(.borderless) }
                Text(forum.topic?.title ?? forum.section?.title ?? "Форум").font(.headline)
                Spacer()
                Button("закрыть") { act.closeForum() }.buttonStyle(.borderless)
            }
        }
        if let topic = forum.topic {
            topicBody(topic)
        } else if let section = forum.section {
            sectionBody(section)
        } else {
            Section {
                ForEach(forum.sections, id: \.id) { s in
                    Button { act.openSection(s) } label: {
                        VStack(alignment: .leading) {
                            Text(s.title)
                            Text("\(s.info) · тем \(s.topics), сообщений \(s.posts)").font(.caption).foregroundStyle(.secondary)
                        }
                    }.disabled(busy)
                }
                if !forum.canWrite { Text("Писать на форуме могут вошедшие в игру.").font(.footnote) }
            }
        }
    }

    @ViewBuilder private func pager() -> some View {
        if forum.pages > 1 {
            HStack {
                Button("←") { act.forumPage(Int(forum.page) - 1) }.disabled(busy || forum.page == 0).buttonStyle(.borderless)
                Spacer()
                Text("стр. \(forum.page + 1) из \(forum.pages)").font(.footnote)
                Spacer()
                Button("→") { act.forumPage(Int(forum.page) + 1) }.disabled(busy || forum.page >= forum.pages - 1).buttonStyle(.borderless)
            }
        }
    }

    @ViewBuilder private func sectionBody(_ section: ForumSection) -> some View {
        Section {
            if !section.info.isEmpty { Text(section.info).font(.footnote) }
            if forum.topics.isEmpty { Text("Тем пока нет").font(.footnote) }
            ForEach(forum.topics, id: \.id) { t in
                Button { act.openTopic(t) } label: {
                    VStack(alignment: .leading) {
                        Text((t.pinned ? "📌 " : "") + (t.closed ? "🔒 " : "") + t.title)
                        Text("\(t.author) · \(t.posts) сообщ. · \(SiteFormat.date(t.updated))" + (t.lastAuthor.map { " · \($0)" } ?? ""))
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }.disabled(busy)
            }
            pager()
        }
        if forum.canWrite && (!section.staffOnly || forum.moderator) {
            Section("Новая тема") {
                TextField("Название", text: $title)
                TextField("Сообщение", text: $text, axis: .vertical)
                Button("Создать тему") { act.newTopic(title, text); title = ""; text = "" }
                    .disabled(busy || title.trimmingCharacters(in: .whitespaces).isEmpty || text.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }

    @ViewBuilder private func topicBody(_ topic: ForumTopic) -> some View {
        Section {
            if topic.pinned || topic.closed {
                Text([topic.pinned ? "закреплена" : nil, topic.closed ? "закрыта" : nil].compactMap { $0 }.joined(separator: " · ")).font(.caption)
            }
            ForEach(forum.posts, id: \.id) { p in
                PostRow(post: p, moderator: forum.moderator, busy: busy, act: act)
            }
            pager()
        }
        if forum.canWrite {
            Section("Ответ") {
                TextField("Сообщение", text: $reply, axis: .vertical)
                Button("Ответить") { act.reply(reply); reply = "" }.disabled(busy || reply.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        } else if topic.closed {
            Text("Тема закрыта").font(.footnote)
        }
        if forum.moderator {
            Section("Модерация") {
                HStack {
                    Button(topic.closed ? "открыть" : "закрыть") { act.moderateTopic(topic.closed ? "open" : "close") }.buttonStyle(.borderless)
                    Spacer()
                    Button(topic.pinned ? "открепить" : "закрепить") { act.moderateTopic(topic.pinned ? "unpin" : "pin") }.buttonStyle(.borderless)
                    Spacer()
                    Button("удалить тему", role: .destructive) { act.moderateTopic("delete") }.buttonStyle(.borderless)
                }.disabled(busy)
                TextField("Новое название", text: $rename)
                Button("переименовать") { act.renameTopic(rename); rename = "" }.disabled(busy || rename.trimmingCharacters(in: .whitespaces).isEmpty)
            }
        }
    }
}

struct PostRow: View {
    let post: ForumPost
    let moderator: Bool
    let busy: Bool
    let act: SiteActions
    @State private var editing = false
    @State private var text = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(post.author) · \(SiteFormat.date(post.created))" + (post.editedBy.map { " · изменено: \($0)" } ?? ""))
                .font(.caption).foregroundStyle(.secondary)
            if editing {
                TextField("Сообщение", text: $text, axis: .vertical)
                HStack {
                    Button("сохранить") { act.editPost(post, text); editing = false }.disabled(busy).buttonStyle(.borderless)
                    Button("отмена") { editing = false }.buttonStyle(.borderless)
                }
            } else {
                Text(post.text)
                if post.mine || moderator {
                    HStack {
                        Button("изменить") { text = post.text; editing = true }.disabled(busy).buttonStyle(.borderless)
                        if moderator { Button("удалить", role: .destructive) { act.deletePost(post) }.disabled(busy).buttonStyle(.borderless) }
                    }.font(.caption)
                }
            }
        }
    }
}

struct PagesView_: View {
    let pages: [PageSummary]
    let page: PageView?
    let busy: Bool
    let act: SiteActions

    var body: some View {
        if let page {
            Section(page.title) {
                ForEach(Array(page.text.components(separatedBy: "\n\n").enumerated()), id: \.offset) { _, para in
                    Text(SiteFormat.rich(para))
                }
                Button("назад") { act.closePage() }
            }
        } else {
            Section("Помощь и правила") {
                ForEach(pages, id: \.id) { p in Button(p.title) { act.openPage(p) }.disabled(busy) }
                Button("закрыть") { act.closePages() }
            }
        }
    }
}

struct AdminView_: View {
    let admin: AdminView
    let busy: Bool
    let act: SiteActions
    @State private var target = ""
    @State private var reason = ""
    @State private var minutes = "60"
    @State private var item = ""
    @State private var count = "1"

    private var isAdmin: Bool { admin.role == "admin" }
    private var named: Bool { !busy && !target.trimmingCharacters(in: .whitespaces).isEmpty }
    private var m: Int { Int(minutes) ?? 0 }

    var body: some View {
        Section(isAdmin ? "Администрирование" : "Модерация") {
            if let msg = admin.message { Text(msg).foregroundStyle(Color.accentColor) }
            TextField("Имя персонажа", text: $target)
            TextField("Причина или текст", text: $reason)
            TextField("Минут (0 — навсегда, только админ)", text: $minutes).keyboardType(.numberPad)
            HStack {
                Button("мут") { act.adminOp("mute", target, reason, "", 1, m) }.disabled(!named)
                Spacer()
                Button("снять мут") { act.adminOp("unmute", target, "", "", 1, 0) }.disabled(!named)
                Spacer()
                Button("выгнать") { act.adminOp("kick", target, "", "", 1, 0) }.disabled(!named)
            }.buttonStyle(.borderless)
            HStack {
                Button("бан", role: .destructive) { act.adminOp("ban", target, reason, "", 1, m) }.disabled(!named)
                Spacer()
                if isAdmin { Button("снять бан") { act.adminOp("unban", target, "", "", 1, 0) }.disabled(!named) }
                Spacer()
                Button("призвать") { act.adminOp("summon", target, "", "", 1, 0) }.disabled(!named)
            }.buttonStyle(.borderless)
            Button("Сказать всем (текст из поля «Причина»)") { act.adminOp("broadcast", "", reason, "", 1, 0) }
                .disabled(busy || reason.trimmingCharacters(in: .whitespaces).isEmpty)
            Button("закрыть") { act.closeAdmin() }
        }
        Section("Телепорт (\(target.isEmpty ? "себя" : target))") {
            ForEach(admin.places, id: \.value) { p in
                Button(p.label) { act.adminOp("teleport", target, p.value, "", 1, 0) }.disabled(busy)
            }
        }
        if isAdmin {
            Section("Администратор") {
                TextField("Предмет (i.money…)", text: $item).textInputAutocapitalization(.never).autocorrectionDisabled()
                TextField("Сколько", text: $count).keyboardType(.numberPad)
                Button("выдать") { act.adminOp("give", target, "", item, Int(count) ?? 1, 0) }.disabled(!named || item.isEmpty)
                Button("сделать модератором") { act.adminOp("role", target, "moder", "", 1, 0) }.disabled(!named)
                Button("снять модератора") { act.adminOp("role", target, "player", "", 1, 0) }.disabled(!named)
            }
            if !admin.gifts.isEmpty {
                Section("Подарки у Эдварда в Переулке (имя «*» — всем)") {
                    Text("Новым персонажам: " + (admin.gifts.first { $0.value == admin.newGift }?.label ?? "нет")).font(.footnote)
                    ForEach(admin.gifts, id: \.value) { g in
                        HStack {
                            Text(g.label)
                            Spacer()
                            Button("выдать") { act.adminOp("gift", target, "", g.value, 1, 0) }.disabled(!named)
                            Button("новым") { act.adminOp("giftNew", "", "", g.value, 1, 0) }.disabled(busy || admin.newGift == g.value)
                        }.buttonStyle(.borderless)
                    }
                    if admin.newGift != nil {
                        Button("не дарить новым") { act.adminOp("giftNew", "", "", "", 1, 0) }.disabled(busy)
                    }
                }
            }
        }
        Section("Журнал") {
            if admin.log.isEmpty { Text("пусто").font(.footnote) }
            ForEach(Array(admin.log.enumerated()), id: \.offset) { _, line in Text(line).font(.caption) }
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
