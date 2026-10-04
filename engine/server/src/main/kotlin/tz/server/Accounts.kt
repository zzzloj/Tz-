package tz.server

import at.favre.lib.crypto.bcrypt.BCrypt
import io.ktor.http.HttpStatusCode
import tz.shared.AccountView
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

/** [mutedUntil]: no chat, letters or forum posts until then (unix time, [Accounts.now]). */
data class Account(val id: Long, val login: String, val role: String, val mutedUntil: Long = 0) {
    val moderator get() = role == Roles.MODER || role == Roles.ADMIN
    val admin get() = role == Roles.ADMIN
    val muted get() = mutedUntil > Accounts.now()
}

object Roles {
    const val PLAYER = "player"
    const val MODER = "moder"
    const val ADMIN = "admin"
}

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
            checkBan(c, row!!.first.id)
            row.first to newSession(c, row.first.id)
        }
    }

    suspend fun logout(token: String) = db.tx { c ->
        c.prepareStatement("DELETE FROM sessions WHERE token_hash = ?").use { it.setBytes(1, sha256(token)); it.executeUpdate() }
    }

    /** The account behind a bearer token, or null if the token is unknown or expired. */
    suspend fun authenticate(token: String): Account? = db.tx { c ->
        c.prepareStatement(
            "SELECT a.id, a.login, a.role, a.muted_until, a.banned_until FROM sessions s JOIN accounts a ON a.id = s.account_id " +
                "WHERE s.token_hash = ? AND s.expires_at > now()"
        ).use { st ->
            st.setBytes(1, sha256(token))
            st.executeQuery().use { rs ->
                if (!rs.next()) null
                else if (rs.getLong(5) > now()) throw ApiException(HttpStatusCode.Forbidden, Errors.BANNED)
                else Account(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4))
            }
        }
    }

    // ---- the account page ---------------------------------------------------------------

    suspend fun accountView(account: Account, recoveryCode: String? = null): AccountView = db.tx { c ->
        c.prepareStatement(
            "SELECT a.login, a.role, a.recovery_hash IS NOT NULL, a.muted_until, ch.name, ch.about FROM accounts a " +
                "LEFT JOIN characters ch ON ch.account_id = a.id AND ch.world_id = 1 WHERE a.id = ?"
        ).use { st ->
            st.setLong(1, account.id)
            st.executeQuery().use { rs ->
                if (!rs.next()) throw ApiException(HttpStatusCode.Unauthorized, Errors.UNAUTHORIZED)
                AccountView(rs.getString(1), rs.getString(2), rs.getString(5), rs.getString(6) ?: "", rs.getBoolean(3), rs.getLong(4), recoveryCode)
            }
        }
    }

    /** A new password; every other session of the account ends. */
    suspend fun changePassword(account: Account, token: String, old: String, new: String): AccountView {
        if (new.length < Rules.PASSWORD_MIN) throw ApiException(HttpStatusCode.BadRequest, Errors.WEAK_PASSWORD)
        db.tx { c ->
            checkPassword(c, account, old)
            c.prepareStatement("UPDATE accounts SET password_hash = ? WHERE id = ?").use { it.setString(1, hash(new)); it.setLong(2, account.id); it.executeUpdate() }
            c.prepareStatement("DELETE FROM sessions WHERE account_id = ? AND token_hash <> ?").use { it.setLong(1, account.id); it.setBytes(2, sha256(token)); it.executeUpdate() }
        }
        return accountView(account)
    }

    /** A new recovery code (the old one stops working); shown once. */
    suspend fun newRecoveryCode(account: Account, password: String): AccountView {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val code = (1..4).joinToString("-") { (1..4).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("") }
        db.tx { c ->
            checkPassword(c, account, password)
            c.prepareStatement("UPDATE accounts SET recovery_hash = ? WHERE id = ?").use { it.setString(1, hash(code)); it.setLong(2, account.id); it.executeUpdate() }
        }
        return accountView(account, code)
    }

    /** A new password by the recovery code: the code is spent, all sessions end, a new one starts. */
    suspend fun recover(login: String, code: String, newPassword: String): Pair<Account, String> {
        val normalized = login.trim().lowercase()
        if (newPassword.length < Rules.PASSWORD_MIN) throw ApiException(HttpStatusCode.BadRequest, Errors.WEAK_PASSWORD)
        if (!limiter.allowed("recover:$normalized")) throw ApiException(HttpStatusCode.TooManyRequests, Errors.TOO_MANY_ATTEMPTS)
        return db.tx { c ->
            val row = c.prepareStatement("SELECT id, login, role, recovery_hash FROM accounts WHERE lower(login) = ?").use { st ->
                st.setString(1, normalized)
                st.executeQuery().use { rs -> if (rs.next()) Account(rs.getLong(1), rs.getString(2), rs.getString(3)) to rs.getString(4) else null }
            }
            val clean = code.trim().uppercase().replace(" ", "")
            val ok = verify(clean, row?.second ?: dummyHash) && row?.second != null
            if (!ok) {
                limiter.failed("recover:$normalized")
                throw ApiException(HttpStatusCode.Unauthorized, Errors.WRONG_CODE)
            }
            limiter.succeeded("recover:$normalized")
            val account = row!!.first
            c.prepareStatement("UPDATE accounts SET password_hash = ?, recovery_hash = NULL WHERE id = ?").use { it.setString(1, hash(newPassword)); it.setLong(2, account.id); it.executeUpdate() }
            c.prepareStatement("DELETE FROM sessions WHERE account_id = ?").use { it.setLong(1, account.id); it.executeUpdate() }
            checkBan(c, account.id)
            account to newSession(c, account.id)
        }
    }

    /** «О себе»: up to [Rules.ABOUT_MAX] characters, no markup. */
    suspend fun setAbout(account: Account, text: String): AccountView {
        val clean = text.replace(Regex("<[^>]*>"), "").replace(Regex("[\\u0000-\\u0008\\u000b-\\u001f<>]"), "").trim().take(Rules.ABOUT_MAX)
        db.tx { c ->
            val n = c.prepareStatement("UPDATE characters SET about = ? WHERE account_id = ? AND world_id = 1").use { it.setString(1, clean); it.setLong(2, account.id); it.executeUpdate() }
            if (n == 0) throw ApiException(HttpStatusCode.Conflict, Errors.NO_CHARACTER)
        }
        return accountView(account)
    }

    /** Deletes the account and its character for good (Game forgets the character first). */
    suspend fun delete(account: Account, password: String) = db.tx { c ->
        checkPassword(c, account, password)
        c.prepareStatement("DELETE FROM accounts WHERE id = ?").use { it.setLong(1, account.id); it.executeUpdate() }
    }

    private fun checkPassword(c: Connection, account: Account, password: String) {
        val key = "account:${account.id}"
        if (!limiter.allowed(key)) throw ApiException(HttpStatusCode.TooManyRequests, Errors.TOO_MANY_ATTEMPTS)
        val hash = c.prepareStatement("SELECT password_hash FROM accounts WHERE id = ?").use { st ->
            st.setLong(1, account.id)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
        if (hash == null || !verify(password, hash)) {
            limiter.failed(key)
            throw ApiException(HttpStatusCode.Unauthorized, Errors.BAD_CREDENTIALS)
        }
        limiter.succeeded(key)
    }

    private fun checkBan(c: Connection, accountId: Long) {
        val until = c.prepareStatement("SELECT banned_until FROM accounts WHERE id = ?").use { st ->
            st.setLong(1, accountId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }
        if (until > now()) throw ApiException(HttpStatusCode.Forbidden, Errors.BANNED)
    }

    // ---- moderation ------------------------------------------------------------------

    /** Account of the character called [name]: id, login, role; null if there is none. */
    suspend fun byCharacter(name: String): Account? = db.tx { c ->
        c.prepareStatement(
            "SELECT a.id, a.login, a.role, a.muted_until FROM characters ch JOIN accounts a ON a.id = ch.account_id " +
                "WHERE ch.world_id = 1 AND lower(ch.name) = lower(?)"
        ).use { st ->
            st.setString(1, name.trim())
            st.executeQuery().use { rs -> if (rs.next()) Account(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)) else null }
        }
    }

    suspend fun setMute(accountId: Long, until: Long) = db.tx { c ->
        c.prepareStatement("UPDATE accounts SET muted_until = ? WHERE id = ?").use { it.setLong(1, until); it.setLong(2, accountId); it.executeUpdate() }
    }

    /** Bans until [until] (0 — lifts the ban); a ban ends every session at once. */
    suspend fun setBan(accountId: Long, until: Long, reason: String) = db.tx { c ->
        c.prepareStatement("UPDATE accounts SET banned_until = ?, ban_reason = ? WHERE id = ?").use {
            it.setLong(1, until); it.setString(2, reason); it.setLong(3, accountId); it.executeUpdate()
        }
        if (until > now()) c.prepareStatement("DELETE FROM sessions WHERE account_id = ?").use { it.setLong(1, accountId); it.executeUpdate() }
    }

    suspend fun setRole(accountId: Long, role: String) = db.tx { c ->
        c.prepareStatement("UPDATE accounts SET role = ? WHERE id = ?").use { it.setString(1, role); it.setLong(2, accountId); it.executeUpdate() }
    }

    /** The logins in TZ_ADMINS become administrators on start. */
    suspend fun promoteAdmins(logins: Collection<String>) {
        if (logins.isEmpty()) return
        db.tx { c ->
            c.prepareStatement("UPDATE accounts SET role = 'admin' WHERE lower(login) = ANY (?)").use { st ->
                st.setArray(1, c.createArrayOf("text", logins.map { it.trim().lowercase() }.toTypedArray()))
                st.executeUpdate()
            }
        }
    }

    suspend fun log(actor: Account, action: String, target: String, detail: String = "") = db.tx { c ->
        c.prepareStatement("INSERT INTO mod_log (actor, action, target, detail) VALUES (?, ?, ?, ?)").use {
            it.setString(1, actor.login); it.setString(2, action); it.setString(3, target); it.setString(4, detail); it.executeUpdate()
        }
    }

    suspend fun modLog(limit: Int = 50): List<String> = db.tx { c ->
        c.prepareStatement("SELECT to_char(at AT TIME ZONE 'Europe/Moscow', 'DD.MM HH24:MI'), actor, action, target, detail FROM mod_log ORDER BY id DESC LIMIT ?").use { st ->
            st.setInt(1, limit)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(listOf(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)).filter { it.isNotBlank() }.joinToString(" ")) }
            }
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
                    st.setInt(5, Rules.hpMax(Rules.ATTR_START))
                    st.setInt(6, Rules.manaMax(Rules.ATTR_START))
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
                // The gift for new characters, if the administrator set one (Edward hands it out).
                c.prepareStatement(
                    "INSERT INTO character_state (character_id, key, value, until) " +
                        "SELECT ch.id, ? || w.value, '1', NULL FROM characters ch, world_state w " +
                        "WHERE ch.account_id = ? AND ch.world_id = 1 AND w.key = ? AND (w.until IS NULL OR w.until > ?)"
                ).use { st ->
                    st.setString(1, Dialogs.GIFT_PREFIX)
                    st.setLong(2, account.id)
                    st.setString(3, Dialogs.GIFT_NEW)
                    st.setLong(4, now())
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

        /** Wall time for bans and mutes (the game's own clock may be a test clock). */
        fun now(): Long = System.currentTimeMillis() / 1000
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
