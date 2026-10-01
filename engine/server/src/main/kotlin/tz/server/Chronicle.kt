package tz.server

import kotlinx.coroutines.sync.withLock
import tz.shared.ChronicleEntry
import tz.shared.ChronicleView

/**
 * The world chronicle (owner's decision 01.10, «Мир · События»): castles taken,
 * attacked and left empty, the leadership flag, the demon, bounties, weddings.
 * The server writes it; a clan's own events are seen only by the clan.
 */
internal object ChronicleRules {
    /** How long the chronicle shows an event. */
    const val DAYS = 7
    const val LIMIT = 200
    /** The same line again within this time is not repeated (a siege at the gate). */
    const val REPEAT_SECONDS = 600L
}

internal suspend fun Game.chronicle(text: String, clanId: Long? = null) {
    val now = clock()
    db.tx { c ->
        val repeated = c.prepareStatement("SELECT 1 FROM chronicle WHERE text = ? AND at > ? AND clan_id IS NOT DISTINCT FROM ?").use { st ->
            st.setString(1, text); st.setLong(2, now - ChronicleRules.REPEAT_SECONDS)
            if (clanId == null) st.setNull(3, java.sql.Types.BIGINT) else st.setLong(3, clanId)
            st.executeQuery().use { it.next() }
        }
        if (!repeated) c.prepareStatement("INSERT INTO chronicle (at, text, clan_id) VALUES (?, ?, ?)").use { st ->
            st.setLong(1, now); st.setString(2, text)
            if (clanId == null) st.setNull(3, java.sql.Types.BIGINT) else st.setLong(3, clanId)
            st.executeUpdate()
        }
    }
}

/** GET /api/chronicle: the last 7 days, newest first; the reader's clan events marked. */
suspend fun Game.chronicleView(account: Account): ChronicleView {
    val clan = lock.withLock { player(account).clanId }
    val since = clock() - ChronicleRules.DAYS * 86400L
    val entries = db.tx { c ->
        c.prepareStatement(
            "SELECT at, text, clan_id FROM chronicle WHERE at > ? AND (clan_id IS NULL OR clan_id = ?) ORDER BY at DESC, id DESC LIMIT ${ChronicleRules.LIMIT}"
        ).use { st ->
            st.setLong(1, since); st.setLong(2, clan ?: -1)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(ChronicleEntry(rs.getLong(1), rs.getString(2), clan = rs.getLong(3).let { !rs.wasNull() })) }
            }
        }
    }
    return ChronicleView(entries)
}
