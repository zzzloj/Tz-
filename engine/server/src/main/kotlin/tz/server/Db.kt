package tz.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.sql.Connection

/** PostgreSQL access: a connection pool, transactions and schema migrations. */
class Db(jdbcUrl: String, user: String?, password: String?) : AutoCloseable {
    private val pool = HikariDataSource(HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        if (user != null) username = user
        if (password != null) this.password = password
        maximumPoolSize = 10
        isAutoCommit = false
    })

    /** Runs [block] in a transaction on the IO dispatcher; rolls back on any exception. */
    suspend fun <T> tx(block: (Connection) -> T): T = withContext(Dispatchers.IO) { txBlocking(block) }

    fun <T> txBlocking(block: (Connection) -> T): T = pool.connection.use { c ->
        try {
            val result = block(c)
            c.commit()
            result
        } catch (e: Throwable) {
            c.rollback()
            throw e
        }
    }

    /**
     * Applies db/V<n>__*.sql from the classpath in order, once each
     * (table schema_version). Listed in [MIGRATIONS]; add new files there.
     */
    fun migrate() = txBlocking { c ->
        c.createStatement().use { it.execute("CREATE TABLE IF NOT EXISTS schema_version (version INT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())") }
        c.createStatement().use { it.execute("LOCK TABLE schema_version IN EXCLUSIVE MODE") }
        val done = c.createStatement().use { st ->
            st.executeQuery("SELECT version FROM schema_version").use { rs -> buildSet { while (rs.next()) add(rs.getInt(1)) } }
        }
        for ((version, file) in MIGRATIONS) {
            if (version in done) continue
            val sql = Db::class.java.getResource("/db/$file")?.readText() ?: error("migration $file not found")
            c.createStatement().use { it.execute(sql) }
            c.prepareStatement("INSERT INTO schema_version (version) VALUES (?)").use { it.setInt(1, version); it.executeUpdate() }
        }
    }

    override fun close() = pool.close()

    companion object {
        val MIGRATIONS = listOf(1 to "V1__accounts.sql", 2 to "V2__inventory.sql", 3 to "V3__combat.sql", 4 to "V4__dialogs.sql", 5 to "V5__bank.sql", 6 to "V6__social.sql", 7 to "V7__castles.sql", 8 to "V8__pvp.sql", 9 to "V9__stats.sql", 10 to "V10__site.sql", 11 to "V11__chronicle.sql", 12 to "V12__forum.sql", 13 to "V13__clan_vault.sql")

        /**
         * Accepts a JDBC URL or the postgres://user:pass@host:port/db form that
         * Railway puts into DATABASE_URL.
         */
        fun fromUrl(url: String): Db {
            if (url.startsWith("jdbc:")) return Db(url, null, null)
            val uri = URI(url.trim())
            require(!uri.host.isNullOrEmpty() && !uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") {
                "DATABASE_URL must look like postgres://user:password@host:port/database (host or database missing)"
            }
            val (user, password) = (uri.rawUserInfo ?: ":").split(":", limit = 2).map { java.net.URLDecoder.decode(it, "UTF-8") }
                .let { it[0] to it.getOrElse(1) { "" } }
            val port = if (uri.port == -1) 5432 else uri.port
            val query = uri.rawQuery?.let { "?$it" } ?: ""
            return Db("jdbc:postgresql://${uri.host}:$port${uri.rawPath}$query", user, password)
        }
    }
}
