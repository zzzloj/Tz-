package tz.server

/** One database pool for all test classes: a pool per class ran out of PostgreSQL connections. */
object TestDb {
    val db: Db? by lazy { System.getenv("TZ_TEST_DATABASE_URL")?.let { Db.fromUrl(it).also(Db::migrate) } }
}
