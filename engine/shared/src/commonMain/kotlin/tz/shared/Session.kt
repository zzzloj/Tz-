package tz.shared

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Where the player is in the app. */
enum class Screen { LOADING, SIGN_IN, CREATE_CHARACTER, PLAYING }

/** Keeps the session token between app launches (Keychain on iOS, app storage on Android). */
interface TokenStore {
    fun load(): String?
    fun save(token: String?)
}

/**
 * Screen logic shared by both apps: sign in or register, create a
 * character, then play. The apps render [screen], [game] and [error] after
 * each call and never decide game rules themselves.
 */
class Session(val api: GameApi, private val tokens: TokenStore) {
    private companion object {
        val STALE = setOf(
            Errors.NOT_AN_EXIT, Errors.NO_SUCH_ITEM, Errors.NOT_IN_INVENTORY, Errors.NO_CHARACTER,
            Errors.NO_TARGET, Errors.NO_SUCH_CORPSE, Errors.GHOST, Errors.NOT_GHOST, Errors.NO_RESURRECTION_HERE,
            Errors.TOPIC_CLOSED,
        )
        const val RECONNECT_MILLIS = 3000L
    }

    var screen: Screen = Screen.LOADING
        private set
    var game: GameView? = null
        private set
    var login: String? = null
        private set
    /** Text to show to the player, in Russian; null when the last action succeeded. */
    var error: String? = null
        private set
    var busy: Boolean = false
        private set

    /** On app start: continue a saved session, or ask to sign in. */
    suspend fun resume() = action {
        api.token = tokens.load()
        if (api.token == null) screen = Screen.SIGN_IN else enter()
    }

    suspend fun register(login: String, password: String) = action {
        val l = login.trim().lowercase()
        if (!Rules.LOGIN.matches(l)) throw ApiError(Errors.INVALID_LOGIN)
        if (password.length < Rules.PASSWORD_MIN) throw ApiError(Errors.WEAK_PASSWORD)
        tokens.save(api.register(l, password).token)
        enter()
    }

    suspend fun signIn(login: String, password: String) = action {
        tokens.save(api.login(login.trim().lowercase(), password).token)
        enter()
    }

    suspend fun createCharacter(name: String, female: Boolean) = action {
        if (!Rules.validName(name.trim())) throw ApiError(Errors.INVALID_NAME)
        api.createCharacter(name.trim(), if (female) "f" else "m")
        enter()
    }

    suspend fun go(exit: ExitView) = action {
        game = api.move(exit.target)
    }

    suspend fun take(item: GroundItemView) = action { game = api.take(item.id) }

    suspend fun drop(item: InventoryItemView) = action { game = api.drop(item.id) }

    /** Puts the item on, or takes it off if it is on. */
    suspend fun toggleEquip(item: InventoryItemView) = action {
        game = if (item.equipped) api.unequip(item.id) else api.equip(item.id)
    }

    suspend fun attack(npc: NpcView) = action { game = api.attack(npc.id) }

    suspend fun loot(corpse: CorpseView, item: GroundItemView) = action { game = api.loot(corpse.id, item.id) }

    /** Cuts meat and hides off a corpse (needs a knife in the backpack). */
    suspend fun butcher(corpse: CorpseView) = action { game = api.butcher(corpse.id) }

    suspend fun resurrect() = action { game = api.resurrect() }

    suspend fun talk(npc: NpcView) = action { game = api.talk(npc.id) }

    /** Picks an answer in the open dialog. */
    suspend fun answer(option: DialogOption) = action {
        val d = game?.dialog ?: return@action
        game = api.talk(d.npc, option.topic, option.arg)
    }

    fun closeDialog() {
        game = game?.copy(dialog = null, shop = null, bank = null, craft = null)
    }

    /** Buys from (shop mode buy/buy2) or sells to (sell) the open shop. */
    suspend fun trade(item: ShopItemView, count: Int) = action {
        val s = game?.shop ?: return@action
        game = api.shop(s.npc, s.mode, item.id, count)
    }

    suspend fun bankPut(item: InventoryItemView, count: Int) = action {
        val b = game?.bank ?: return@action
        game = api.bank(b.npc, "put", item.id, count)
    }

    suspend fun bankTake(item: InventoryItemView, count: Int) = action {
        val b = game?.bank ?: return@action
        game = api.bank(b.npc, "take", item.id, count)
    }

    /** An item waiting for its target (a gem to inlay, clothes to cut, a ghost to revive). */
    var pendingUse: InventoryItemView? = null
        private set

    /** Uses an item; one that needs a target waits for [useOn]. */
    suspend fun use(item: InventoryItemView) {
        if (item.target != null) { pendingUse = item; return }
        action { game = api.use(item.id) }
    }

    suspend fun useOn(target: String) {
        val item = pendingUse ?: return
        pendingUse = null
        action { game = api.use(item.id, target = target) }
    }

    fun cancelUse() { pendingUse = null }

    /** Sends typed text to a dialog that waits for it (a clan name). */
    suspend fun answerText(text: String) = action {
        val d = game?.dialog ?: return@action
        val topic = d.inputTopic ?: return@action
        game = api.talk(d.npc, topic, text.trim())
    }

    /** Says something here, or to the clan. */
    suspend fun say(text: String, clan: Boolean = false) = action {
        if (text.isNotBlank()) game = api.say(text.trim(), if (clan) "clan" else "here")
    }

    // ---- messages ----
    /** Contacts and messages while the mail panel is open. */
    var mail: MessagesView? = null
        private set

    suspend fun openMail() = action { mail = api.messages(); game = api.game() }

    fun closeMail() { mail = null }

    suspend fun write(to: String, text: String) = action { mail = api.message("write", to, text) }

    suspend fun addContact(name: String) = action { mail = api.message("add", name) }

    suspend fun removeContact(name: String) = action { mail = api.message("remove", name) }

    // ---- exchange ----
    suspend fun startExchange(person: PersonView) = action { game = api.exchange("start", with = person.name) }

    suspend fun offer(item: InventoryItemView, count: Int) = action { game = api.exchange("add", item = item.id, count = count) }

    suspend fun withdraw(item: ShopItemView) = action { game = api.exchange("remove", item = item.id) }

    suspend fun agree() = action { game = api.exchange("agree") }

    suspend fun cancelExchange() = action { game = api.exchange("cancel") }

    // ---- clan ----
    var clanInfo: ClanView? = null
        private set

    suspend fun openClan() = action { clanInfo = api.clan() }

    fun closeClan() { clanInfo = null }

    /** op: invite, kick, rank, head, accept, decline, leave, info (see ClanRequest). */
    suspend fun clanOp(op: String, name: String? = null, rank: String? = null, clan: String? = null, text: String? = null) = action {
        clanInfo = api.clan(op, name, rank, clan, text)
        game = api.game()
    }

    /** Picks a recipe from the open crafting menu. */
    suspend fun craft(option: CraftOptionView) = action {
        val c = game?.craft ?: return@action
        game = api.use(c.tool, recipe = option.key)
    }

    /**
     * Keeps the screen live while playing: the server signals every blow,
     * death or arrival, and the game view is re-read. Call from the UI's
     * coroutine; [onUpdate] tells the UI to redraw. Runs until cancelled.
     */
    suspend fun listen(onUpdate: () -> Unit) {
        while (true) {
            if (screen == Screen.PLAYING && api.token != null) {
                try {
                    api.events {
                        if (screen == Screen.PLAYING && !busy) {
                            try {
                                val g = game
                                game = api.game().copy(dialog = g?.dialog, shop = g?.shop, bank = g?.bank, craft = g?.craft)
                                if (mail != null) mail = api.messages()
                                onUpdate()
                            } catch (e: ApiError) { if (e.code == Errors.NO_CHARACTER) enter() }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Offline or the server restarted: try again shortly.
                }
            }
            delay(RECONNECT_MILLIS)
        }
    }

    /** Re-reads the screen: NPCs wander and other players come and go. */
    suspend fun refresh() = action { enter() }

    suspend fun signOut() = action {
        try { api.logout() } finally { forget() }
    }

    private suspend fun enter() {
        val me = api.me()
        login = me.login
        if (me.character == null) {
            game = null
            screen = Screen.CREATE_CHARACTER
        } else {
            game = api.game()
            screen = Screen.PLAYING
        }
    }

    private fun forget() {
        tokens.save(null)
        api.token = null
        game = null
        login = null
        screen = Screen.SIGN_IN
    }

    private suspend fun action(block: suspend () -> Unit) {
        busy = true
        error = null
        try {
            block()
        } catch (e: ApiError) {
            if (e.code == Errors.UNAUTHORIZED) forget()
            error = e.message
            // The screen was out of date (moved elsewhere, item already gone):
            // show the real state, keeping the message.
            if (e.code in STALE && screen == Screen.PLAYING) {
                try { enter() } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            error = "Нет связи с сервером"
            if (screen == Screen.LOADING) screen = if (api.token == null) Screen.SIGN_IN else Screen.LOADING
        } finally {
            busy = false
        }
    }
}
