package tz.shared

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
        val STALE = setOf(Errors.NOT_AN_EXIT, Errors.NO_SUCH_ITEM, Errors.NOT_IN_INVENTORY, Errors.NO_CHARACTER)
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
