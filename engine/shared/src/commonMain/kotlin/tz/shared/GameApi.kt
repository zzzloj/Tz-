package tz.shared

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Client for the game server, shared by the Android and iOS apps. */
class GameApi(
    private val baseUrl: String,
    client: HttpClient? = null,
) {
    private val http: HttpClient = (client ?: HttpClient()).config {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    suspend fun location(id: String): LocationView =
        http.get("${baseUrl.trimEnd('/')}/api/locations/$id").body()

    suspend fun start(): LocationView = location(Protocol.START_LOCATION)
}
