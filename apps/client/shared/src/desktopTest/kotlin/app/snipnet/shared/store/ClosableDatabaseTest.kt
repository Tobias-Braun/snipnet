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

    /** The production opener creates missing parent directories, and closing it keeps what was written. */
    @Test
    fun aFileBackedDatabaseKeepsItsDataAcrossCloseAndReopen() {
        val dir = Files.createTempDirectory("snipnet-closable")
        val file = dir.resolve("nested/snipnet.db")
        try {
            openClosableDatabase(file).use { it.database.projectQueries.insertPendingVideoDelete("user-1", "video-1") }

            val reopened =
                openClosableDatabase(file).use {
                    it.database.projectQueries
                        .selectPendingVideoDeletes("user-1")
                        .executeAsList()
                }

            assertEquals(listOf("video-1"), reopened)
        } finally {
            dir.deleteRecursively()
        }
    }
}
