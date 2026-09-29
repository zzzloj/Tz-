package tz.server

import at.favre.lib.crypto.bcrypt.BCrypt
import io.ktor.http.HttpStatusCode
import tz.shared.CharacterView
import tz.shared.Errors
import tz.shared.Protocol
import tz.shared.Rules
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** An error the API reports to the client with a status and a code from [Errors]. */
class ApiException(val status: HttpStatusCode, val code: String) : Exception(code)

data class Account(val id: Long, val login: String, val role: String)

/** Accounts, sessions and characters. All checks happen here, not in the apps. */
class Accounts(private val db: Db, private val content: Content) {
    private val random = SecureRandom()
    private val limiter = LoginLimiter()

    // Hash used when the login does not exist, so a wrong login and a wrong
    // password take the same time.
    private val dummyHash = hash("not-a-real-password")

    suspend fun register(login: String, password: String): Pair<Account, String> {
        val normalized = login.trim().lowercase()
        if (!Rules.LOGIN.matches(normalized) || normalized in Rules.RESERVED_LOGINS)
            throw ApiException(HttpStatusCode.BadRequest, Errors.INVALID_LOGIN)
        if (password.length < Rules.PASSWORD_MIN)
            throw ApiException(HttpStatusCode.BadRequest, Errors.WEAK_PASSWORD)
        val passwordHash = hash(password)
        return db.tx { c ->
            val account = try {
                c.prepareStatement("INSERT INTO accounts (login, password_hash) VALUES (?, ?) RETURNING id, login, role").use { st ->
                    st.setString(1, normalized)
                    st.setString(2, passwordHash)
                    st.executeQuery().use { rs -> rs.next(); Account(rs.getLong(1), rs.getString(2), rs.getString(3)) }
                }
            } catch (e: SQLException) {
                if (e.sqlState == UNIQUE_VIOLATION) throw ApiException(HttpStatusCode.Conflict, Errors.LOGIN_TAKEN)
                throw e
            }
            account to newSession(c, account.id)
        }
    }

    suspend fun login(login: String, password: String): Pair<Account, String> {
        val normalized = login.trim().lowercase()
        if (!limiter.allowed(normalized)) throw ApiException(HttpStatusCode.TooManyRequests, Errors.TOO_MANY_ATTEMPTS)
        return db.tx { c ->
            val row = c.prepareStatement("SELECT id, login, role, password_hash FROM accounts WHERE lower(login) = ?").use { st ->
                st.setString(1, normalized)
                st.executeQuery().use { rs -> if (rs.next()) Account(rs.getLong(1), rs.getString(2), rs.getString(3)) to rs.getString(4) else null }
            }
            val ok = verify(password, row?.second ?: dummyHash) && row != null
            if (!ok) {
                limiter.failed(normalized)
                throw ApiException(HttpStatusCode.Unauthorized, Errors.BAD_CREDENTIALS)
            }
            limiter.succeeded(normalized)
            row!!.first to newSession(c, row.first.id)
        }
    }

    suspend fun logout(token: String) = db.tx { c ->
        c.prepareStatement("DELETE FROM sessions WHERE token_hash = ?").use { it.setBytes(1, sha256(token)); it.executeUpdate() }
    }

    /** The account behind a bearer token, or null if the token is unknown or expired. */
    suspend fun authenticate(token: String): Account? = db.tx { c ->
        c.prepareStatement(
            "SELECT a.id, a.login, a.role FROM sessions s JOIN accounts a ON a.id = s.account_id " +
                "WHERE s.token_hash = ? AND s.expires_at > now()"
        ).use { st ->
            st.setBytes(1, sha256(token))
            st.executeQuery().use { rs -> if (rs.next()) Account(rs.getLong(1), rs.getString(2), rs.getString(3)) else null }
        }
    }

    suspend fun character(account: Account): CharacterView? = db.tx { c -> loadCharacter(c, account.id) }

    suspend fun createCharacter(account: Account, name: String, sex: String): CharacterView {
        val clean = name.trim().replace(Regex("\\s+"), " ")
        if (!Rules.validName(clean)) throw ApiException(HttpStatusCode.BadRequest, Errors.INVALID_NAME)
        if (sex != "m" && sex != "f") throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
        return db.tx { c ->
            if (loadCharacter(c, account.id) != null) throw ApiException(HttpStatusCode.Conflict, Errors.CHARACTER_EXISTS)
            try {
                c.prepareStatement(
                    "INSERT INTO characters (account_id, name, sex, location, hp, mana) VALUES (?, ?, ?, ?, ?, ?)"
                ).use { st ->
                    st.setLong(1, account.id)
                    st.setString(2, clean)
                    st.setString(3, sex)
                    st.setString(4, Protocol.START_LOCATION)
                    st.setInt(5, Rules.hpMax(1))
                    st.setInt(6, Rules.manaMax(1))
                    st.executeUpdate()
                }
                c.prepareStatement(
                    "INSERT INTO character_items (character_id, item_id, count) " +
                        "SELECT id, ?, 1 FROM characters WHERE account_id = ? AND world_id = 1"
                ).use { st ->
                    st.setString(1, Rules.STARTING_KNIFE)
                    st.setLong(2, account.id)
                    st.executeUpdate()
                }
            } catch (e: SQLException) {
                if (e.sqlState == UNIQUE_VIOLATION) throw ApiException(HttpStatusCode.Conflict, Errors.NAME_TAKEN)
                throw e
            }
            loadCharacter(c, account.id)!!
        }
    }

    private fun loadCharacter(c: Connection, accountId: Long, forUpdate: Boolean = false): CharacterView? =
        c.prepareStatement(
            "SELECT id, name, sex, location, hp, mana, str, dex, intel, skill_points FROM characters " +
                "WHERE account_id = ? AND world_id = 1" + if (forUpdate) " FOR UPDATE" else ""
        ).use { st ->
            st.setLong(1, accountId)
            st.executeQuery().use { rs -> if (rs.next()) characterView(rs) else null }
        }

    private fun characterView(rs: ResultSet): CharacterView {
        val str = rs.getInt("str")
        val int = rs.getInt("intel")
        return CharacterView(
            id = rs.getLong("id"),
            name = rs.getString("name"),
            sex = rs.getString("sex"),
            location = rs.getString("location"),
            hp = rs.getInt("hp"),
            hpMax = Rules.hpMax(str),
            mana = rs.getInt("mana"),
            manaMax = Rules.manaMax(int),
            str = str,
            dex = rs.getInt("dex"),
            int = int,
            skillPoints = rs.getInt("skill_points"),
        )
    }

    private fun newSession(c: Connection, accountId: Long): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        c.prepareStatement("INSERT INTO sessions (token_hash, account_id, expires_at) VALUES (?, ?, now() + interval '30 days')").use { st ->
            st.setBytes(1, sha256(token))
            st.setLong(2, accountId)
            st.executeUpdate()
        }
        return token
    }

    private fun hash(password: String): String = BCrypt.withDefaults().hashToString(BCRYPT_COST, password.toCharArray())

    private fun verify(password: String, hash: String): Boolean =
        BCrypt.verifyer().verify(password.toCharArray(), hash).verified

    companion object {
        const val UNIQUE_VIOLATION = "23505"
        const val BCRYPT_COST = 12

        fun sha256(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
    }
}

/** At most [maxFailures] failed logins per login name in [windowMillis]. */
class LoginLimiter(private val maxFailures: Int = 10, private val windowMillis: Long = 15 * 60_000L) {
    private val failures = ConcurrentHashMap<String, MutableList<Long>>()

    fun allowed(login: String): Boolean {
        val now = System.currentTimeMillis()
        val list = failures[login] ?: return true
        synchronized(list) {
            list.removeAll { now - it > windowMillis }
            return list.size < maxFailures
        }
    }

    fun failed(login: String) {
        val list = failures.computeIfAbsent(login) { mutableListOf() }
        synchronized(list) { list += System.currentTimeMillis() }
    }

    fun succeeded(login: String) {
        failures.remove(login)
    }
}
