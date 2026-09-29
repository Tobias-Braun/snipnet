package app.snipnet.shared.store

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A driver that forwards everything to [delegate] and counts [close] calls. Deleting a SQLite file works on macOS and
 * Linux even while a connection is open, so a leaked connection can only be detected by observing the driver itself.
 */
private class CloseCountingDriver(
    private val delegate: SqlDriver,
) : SqlDriver by delegate {
    var closeCalls = 0
        private set

    override fun close() {
        closeCalls++
        delegate.close()
    }
}

@OptIn(ExperimentalPathApi::class)
class ClosableDatabaseTest {
    @Test
    fun closeClosesTheUnderlyingDriverExactlyOnce() {
        val driver = CloseCountingDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val db = closableDatabaseOn(driver)
        assertEquals(0, driver.closeCalls)

        db.close()

        assertEquals(1, driver.closeCalls)
    }

    @Test
    fun theDatabaseStaysUsableUntilItIsClosed() {
        val driver = CloseCountingDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))
        val db = closableDatabaseOn(driver)

        assertTrue(
            db.database.projectQueries
                .selectOwnerless()
                .executeAsList()
                .isEmpty(),
        )
        assertEquals(0, driver.closeCalls)
        db.close()
    }

    @Test
    fun aFileBackedDatabaseCanBeReopenedAfterClose() {
        val dir = Files.createTempDirectory("snipnet-closable")
        try {
            repeat(2) { openClosableDatabase(dir.resolve("nested/snipnet.db")).close() }
        } finally {
            dir.deleteRecursively()
        }
    }
}
