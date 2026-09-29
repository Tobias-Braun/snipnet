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
fun openDatabase(file: Path): SnipnetDatabase {
    Files.createDirectories(file.toAbsolutePath().parent)
    return databaseOn(JdbcSqliteDriver("jdbc:sqlite:${file.toAbsolutePath()}"))
}

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
