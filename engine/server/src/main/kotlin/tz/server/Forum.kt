package tz.server

import io.ktor.http.HttpStatusCode
import tz.shared.Errors
import tz.shared.ForumPost
import tz.shared.ForumRequest
import tz.shared.ForumSection
import tz.shared.ForumTopic
import tz.shared.ForumView
import tz.shared.Rules
import java.sql.Connection
import java.sql.ResultSet
import java.util.concurrent.ConcurrentHashMap

/**
 * The forum (the old forum/index.php): sections, topics, posts. Anyone may
 * read; signed-in players who are not muted write; moderators delete, close,
 * pin and rename (fmoders). Posts are signed with the character's name.
 */
class Forum(private val db: Db, private val clock: () -> Long = { System.currentTimeMillis() / 1000 }) {
    private val lastPost = ConcurrentHashMap<Long, Long>()

    companion object {
        /** Seconds between two posts of one player. */
        const val POST_PAUSE = 10

        fun clean(text: String, max: Int): String =
            text.replace("\r\n", "\n").replace(Regex("<[^>]*>"), "")
                .replace(Regex("[\\u0000-\\u0008\\u000b-\\u001f<>]"), "")
                .replace(Regex("\n{3,}"), "\n\n").trim().take(max)
    }

    private fun pages(count: Int) = maxOf(1, (count + Rules.FORUM_PAGE - 1) / Rules.FORUM_PAGE)

    private fun canWrite(account: Account?) = account != null && !account.muted

    suspend fun sections(account: Account?): ForumView = db.tx { c ->
        ForumView(sections = sectionList(c), moderator = account?.moderator == true, canWrite = canWrite(account))
    }

    private fun sectionList(c: Connection, only: Int? = null): List<ForumSection> =
        c.prepareStatement(
            "SELECT s.id, s.title, s.info, s.staff_only, " +
                "(SELECT count(*) FROM forum_topics t WHERE t.section_id = s.id), " +
                "(SELECT count(*) FROM forum_posts p JOIN forum_topics t ON t.id = p.topic_id WHERE t.section_id = s.id) " +
                "FROM forum_sections s " + (if (only != null) "WHERE s.id = ? " else "") + "ORDER BY s.position DESC, s.id"
        ).use { st ->
            if (only != null) st.setInt(1, only)
            st.executeQuery().use { rs ->
                buildList { while (rs.next()) add(ForumSection(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(5), rs.getInt(6), rs.getBoolean(4))) }
            }
        }

    suspend fun section(account: Account?, id: Int, page: Int): ForumView = db.tx { c -> sectionView(c, account, id, page) }

    private fun sectionView(c: Connection, account: Account?, id: Int, page: Int): ForumView {
        val section = sectionList(c, id).firstOrNull() ?: throw ApiException(HttpStatusCode.NotFound, Errors.NOT_FOUND)
        val pages = pages(section.topics)
        val p = page.coerceIn(0, pages - 1)
        val topics = c.prepareStatement(
            "$TOPIC_SELECT WHERE t.section_id = ? ORDER BY t.pinned DESC, t.bumped_at DESC, t.id DESC LIMIT ? OFFSET ?"
        ).use { st ->
            st.setInt(1, id); st.setInt(2, Rules.FORUM_PAGE); st.setInt(3, p * Rules.FORUM_PAGE)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(topic(rs)) } }
        }
        return ForumView(section = section, topics = topics, page = p, pages = pages, moderator = account?.moderator == true, canWrite = canWrite(account))
    }

    private val TOPIC_SELECT =
        "SELECT t.id, t.section_id, t.title, t.author, extract(epoch FROM t.bumped_at)::bigint, " +
            "(SELECT count(*) FROM forum_posts p WHERE p.topic_id = t.id), t.pinned, t.closed, " +
            "(SELECT p.author FROM forum_posts p WHERE p.topic_id = t.id ORDER BY p.id DESC LIMIT 1) FROM forum_topics t"

    private fun topic(rs: ResultSet) = ForumTopic(
        rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getInt(6), rs.getBoolean(7), rs.getBoolean(8), rs.getString(9),
    )

    /** [page] -1 — the last page. */
    suspend fun topic(account: Account?, id: Long, page: Int): ForumView = db.tx { c -> topicView(c, account, id, page) }

    private fun topicView(c: Connection, account: Account?, id: Long, page: Int): ForumView {
        val topic = c.prepareStatement("$TOPIC_SELECT WHERE t.id = ?").use { st ->
            st.setLong(1, id); st.executeQuery().use { rs -> if (rs.next()) topic(rs) else null }
        } ?: throw ApiException(HttpStatusCode.NotFound, Errors.NOT_FOUND)
        val pages = pages(topic.posts)
        val p = if (page < 0) pages - 1 else page.coerceIn(0, pages - 1)
        val posts = c.prepareStatement(
            "SELECT id, author, text, extract(epoch FROM created_at)::bigint, edited_by, author_id FROM forum_posts WHERE topic_id = ? ORDER BY id LIMIT ? OFFSET ?"
        ).use { st ->
            st.setLong(1, id); st.setInt(2, Rules.FORUM_PAGE); st.setInt(3, p * Rules.FORUM_PAGE)
            st.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val author = rs.getLong(6).takeIf { !rs.wasNull() }
                        add(ForumPost(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getString(5), author != null && author == account?.id))
                    }
                }
            }
        }
        return ForumView(
            section = sectionList(c, topic.section).firstOrNull(), topic = topic, posts = posts, page = p, pages = pages,
            moderator = account?.moderator == true, canWrite = canWrite(account) && (!topic.closed || account?.moderator == true),
        )
    }

    private fun authorName(c: Connection, account: Account): String =
        c.prepareStatement("SELECT name FROM characters WHERE account_id = ? AND world_id = 1").use { st ->
            st.setLong(1, account.id); st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        } ?: account.login

    private fun checkPace(account: Account) {
        if (account.moderator) return
        val now = clock()
        val last = lastPost[account.id] ?: 0
        if (now - last < POST_PAUSE) throw ApiException(HttpStatusCode.TooManyRequests, Errors.TOO_FAST)
        lastPost[account.id] = now
    }

    suspend fun act(account: Account, r: ForumRequest): ForumView {
        fun mod() { if (!account.moderator) throw ApiException(HttpStatusCode.Forbidden, Errors.FORBIDDEN) }
        fun bad(): Nothing = throw ApiException(HttpStatusCode.BadRequest, Errors.BAD_REQUEST)
        if (r.op in setOf("topic", "post", "edit") && account.muted) throw ApiException(HttpStatusCode.Forbidden, Errors.MUTED)
        return db.tx { c ->
            fun topicRow(id: Long): Triple<Int, Boolean, Long?> = c.prepareStatement("SELECT section_id, closed, author_id FROM forum_topics WHERE id = ?").use { st ->
                st.setLong(1, id); st.executeQuery().use { rs -> if (rs.next()) Triple(rs.getInt(1), rs.getBoolean(2), rs.getLong(3).takeIf { !rs.wasNull() }) else null }
            } ?: throw ApiException(HttpStatusCode.NotFound, Errors.NOT_FOUND)
            fun update(sql: String, id: Long) = c.prepareStatement(sql).use { it.setLong(1, id); it.executeUpdate() }
            when (r.op) {
                "topic" -> {
                    val sectionId = r.section ?: bad()
                    val section = sectionList(c, sectionId).firstOrNull() ?: throw ApiException(HttpStatusCode.NotFound, Errors.NOT_FOUND)
                    if (section.staffOnly) mod()
                    val title = clean(r.title, Rules.TITLE_MAX).replace('\n', ' ')
                    val text = clean(r.text, Rules.POST_MAX)
                    if (title.isEmpty() || text.isEmpty()) bad()
                    val dup = c.prepareStatement("SELECT 1 FROM forum_topics WHERE section_id = ? AND lower(title) = lower(?)").use { st ->
                        st.setInt(1, sectionId); st.setString(2, title); st.executeQuery().use { it.next() }
                    }
                    if (dup) throw ApiException(HttpStatusCode.Conflict, Errors.SAID_ALREADY)
                    checkPace(account)
                    val name = authorName(c, account)
                    val id = c.prepareStatement("INSERT INTO forum_topics (section_id, title, author_id, author) VALUES (?, ?, ?, ?) RETURNING id").use { st ->
                        st.setInt(1, sectionId); st.setString(2, title); st.setLong(3, account.id); st.setString(4, name)
                        st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                    }
                    insertPost(c, id, account, name, text)
                    topicView(c, account, id, 0)
                }
                "post" -> {
                    val id = r.topic ?: bad()
                    val (_, closed, _) = topicRow(id)
                    if (closed && !account.moderator) throw ApiException(HttpStatusCode.Forbidden, Errors.TOPIC_LOCKED)
                    val text = clean(r.text, Rules.POST_MAX)
                    if (text.isEmpty()) bad()
                    val last = c.prepareStatement("SELECT author_id, text FROM forum_posts WHERE topic_id = ? ORDER BY id DESC LIMIT 1").use { st ->
                        st.setLong(1, id); st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) to rs.getString(2) else null }
                    }
                    if (last != null && last.first == account.id && last.second == text) throw ApiException(HttpStatusCode.Conflict, Errors.SAID_ALREADY)
                    checkPace(account)
                    insertPost(c, id, account, authorName(c, account), text)
                    update("UPDATE forum_topics SET bumped_at = now() WHERE id = ?", id)
                    topicView(c, account, id, -1)
                }
                "edit" -> {
                    val postId = r.post ?: bad()
                    val (topicId, author) = c.prepareStatement("SELECT topic_id, author_id FROM forum_posts WHERE id = ?").use { st ->
                        st.setLong(1, postId); st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) to rs.getLong(2).takeIf { !rs.wasNull() } else null }
                    } ?: throw ApiException(HttpStatusCode.NotFound, Errors.NOT_FOUND)
                    if (author != account.id) mod()
                    val text = clean(r.text, Rules.POST_MAX)
                    if (text.isEmpty()) bad()
                    c.prepareStatement("UPDATE forum_posts SET text = ?, edited_by = ? WHERE id = ?").use {
                        it.setString(1, text); it.setString(2, authorName(c, account)); it.setLong(3, postId); it.executeUpdate()
                    }
                    topicView(c, account, topicId, pageOf(c, topicId, postId))
                }
                "delete" -> {
                    mod()
                    if (r.post != null) {
                        val topicId = c.prepareStatement("SELECT topic_id FROM forum_posts WHERE id = ?").use { st ->
                            st.setLong(1, r.post); st.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else null }
                        } ?: throw ApiException(HttpStatusCode.NotFound, Errors.NOT_FOUND)
                        val page = pageOf(c, topicId, r.post)
                        update("DELETE FROM forum_posts WHERE id = ?", r.post)
                        // The first post gone and nothing left: the topic goes too.
                        val left = c.prepareStatement("SELECT count(*) FROM forum_posts WHERE topic_id = ?").use { st ->
                            st.setLong(1, topicId); st.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
                        }
                        if (left > 0) return@tx topicView(c, account, topicId, page)
                        val (sectionId, _, _) = topicRow(topicId)
                        update("DELETE FROM forum_topics WHERE id = ?", topicId)
                        sectionView(c, account, sectionId, 0)
                    } else {
                        val id = r.topic ?: bad()
                        val (sectionId, _, _) = topicRow(id)
                        update("DELETE FROM forum_topics WHERE id = ?", id)
                        sectionView(c, account, sectionId, 0)
                    }
                }
                "close", "open", "pin", "unpin" -> {
                    mod()
                    val id = r.topic ?: bad()
                    topicRow(id)
                    update(
                        when (r.op) {
                            "close" -> "UPDATE forum_topics SET closed = true WHERE id = ?"
                            "open" -> "UPDATE forum_topics SET closed = false WHERE id = ?"
                            "pin" -> "UPDATE forum_topics SET pinned = true WHERE id = ?"
                            else -> "UPDATE forum_topics SET pinned = false WHERE id = ?"
                        }, id,
                    )
                    topicView(c, account, id, 0)
                }
                "rename" -> {
                    mod()
                    val id = r.topic ?: bad()
                    topicRow(id)
                    val title = clean(r.title, Rules.TITLE_MAX).replace('\n', ' ')
                    if (title.isEmpty()) bad()
                    c.prepareStatement("UPDATE forum_topics SET title = ? WHERE id = ?").use { it.setString(1, title); it.setLong(2, id); it.executeUpdate() }
                    topicView(c, account, id, 0)
                }
                else -> bad()
            }
        }
    }

    private fun insertPost(c: Connection, topic: Long, account: Account, name: String, text: String) =
        c.prepareStatement("INSERT INTO forum_posts (topic_id, author_id, author, text) VALUES (?, ?, ?, ?)").use { st ->
            st.setLong(1, topic); st.setLong(2, account.id); st.setString(3, name); st.setString(4, text); st.executeUpdate()
        }

    private fun pageOf(c: Connection, topic: Long, post: Long): Int =
        c.prepareStatement("SELECT count(*) FROM forum_posts WHERE topic_id = ? AND id < ?").use { st ->
            st.setLong(1, topic); st.setLong(2, post); st.executeQuery().use { rs -> rs.next(); rs.getInt(1) / Rules.FORUM_PAGE }
        }

    /** The latest news topics (the staff-only section) for the site and the notice boards. */
    suspend fun news(limit: Int = 10): List<Pair<ForumTopic, String>> = db.tx { c ->
        val section = c.prepareStatement("SELECT id FROM forum_sections WHERE staff_only ORDER BY position DESC LIMIT 1").use { st ->
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null }
        } ?: return@tx emptyList()
        val topics = c.prepareStatement("$TOPIC_SELECT WHERE t.section_id = ? ORDER BY t.created_at DESC LIMIT ?").use { st ->
            st.setInt(1, section); st.setInt(2, limit)
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(topic(rs)) } }
        }
        topics.map { t ->
            t to (c.prepareStatement("SELECT text FROM forum_posts WHERE topic_id = ? ORDER BY id LIMIT 1").use { st ->
                st.setLong(1, t.id); st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else "" }
            })
        }
    }

    /** The news section id, for the notice boards. */
    suspend fun newsSection(): Int? = db.tx { c ->
        c.prepareStatement("SELECT id FROM forum_sections WHERE staff_only ORDER BY position DESC LIMIT 1").use { st ->
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else null }
        }
    }
}
