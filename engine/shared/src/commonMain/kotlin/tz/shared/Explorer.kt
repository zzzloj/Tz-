package tz.shared

/**
 * Screen logic for walking the world, shared by both apps: current
 * location, loading and error state, and moving through exits.
 */
class Explorer(private val api: GameApi) {
    var location: LocationView? = null
        private set
    var error: String? = null
        private set
    var loading: Boolean = false
        private set

    suspend fun start() = load { api.start() }

    suspend fun go(exit: ExitView) = load { api.location(exit.target) }

    private suspend fun load(block: suspend () -> LocationView) {
        loading = true
        error = null
        try {
            location = block()
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        } finally {
            loading = false
        }
    }
}
