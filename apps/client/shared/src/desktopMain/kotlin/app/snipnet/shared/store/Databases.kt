package app.snipnet.shared.store

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.snipnet.shared.store.db.SnipnetDatabase
import java.nio.file.Files
import java.nio.file.Path

/**
 * Opens (creating and migrating as needed) the database file at [file], creating parent directories first.
 * The schema version is tracked in SQLite's `user_version` pragma, which the JDBC driver does not manage itself.
 */
fun openDatabase(file: Path): SnipnetDatabase = openClosableDatabase(file).database

/**
 * A database together with the driver that owns its connection. [close] releases the connection, which the plain
 * [SnipnetDatabase] cannot do; a caller that outlives its database (a test, say) needs it to free the file.
 */
class ClosableDatabase(
    val database: SnipnetDatabase,
    private val driver: SqlDriver,
) : AutoCloseable {
    override fun close() = driver.close()
}

/** Like [openDatabase], but returns a handle that can close the underlying connection. */
fun openClosableDatabase(file: Path): ClosableDatabase {
    Files.createDirectories(file.toAbsolutePath().parent)
    return closableDatabaseOn(JdbcSqliteDriver("jdbc:sqlite:${file.toAbsolutePath()}"))
}

/**
 * Creates or migrates the schema on [driver] and wraps it in a [ClosableDatabase] that closes [driver]. Split out of
 * [openClosableDatabase] so tests can hand in a driver they observe: deleting an open SQLite file succeeds on macOS and
 * Linux, so watching the driver is the only portable way to see that a connection was released.
 */
fun closableDatabaseOn(driver: SqlDriver): ClosableDatabase = ClosableDatabase(databaseOn(driver), driver)

/** A throwaway database that lives only as long as the returned driver connection; used by tests. */
fun openInMemoryDatabase(): SnipnetDatabase = databaseOn(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))

private fun databaseOn(driver: SqlDriver): SnipnetDatabase {
    val version = driver.currentVersion()
    if (version == 0L) {
        SnipnetDatabase.Schema.create(driver)
        driver.execute(null, "PRAGMA user_version = ${SnipnetDatabase.Schema.version}", 0)
    } else if (version < SnipnetDatabase.Schema.version) {
        SnipnetDatabase.Schema.migrate(driver, version, SnipnetDatabase.Schema.version)
        driver.execute(null, "PRAGMA user_version = ${SnipnetDatabase.Schema.version}", 0)
    }
    return SnipnetDatabase(driver)
}

private fun SqlDriver.currentVersion(): Long =
    executeQuery(
        null,
        "PRAGMA user_version",
        { cursor ->
            QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
        },
        0,
    ).value
