package app.snipnet.desktop.auth

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Persists the API bearer token as a plain file at [file] that only the current user can read (mode 600).
 *
 * The file is created with the restrictive permissions from the start (a temp sibling that is then moved over the
 * target) so the token is never world-readable, not even briefly. On file systems without POSIX permissions
 * (Windows) the per-user profile directory the file lives in is the protection, and the permission step is skipped.
 */
class TokenStore(
    private val file: Path,
) {
    /** The stored token, or null when there is none or the file cannot be read. */
    fun load(): String? = runCatching { Files.readString(file).trim().takeIf { it.isNotEmpty() } }.getOrNull()

    fun save(token: String) {
        Files.createDirectories(file.toAbsolutePath().parent)
        val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.deleteIfExists(temp)
        try {
            Files.createFile(temp, PosixFilePermissions.asFileAttribute(OWNER_ONLY))
        } catch (_: UnsupportedOperationException) {
            Files.createFile(temp)
        }
        Files.writeString(temp, token)
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
    }

    fun clear() {
        Files.deleteIfExists(file)
    }

    private companion object {
        val OWNER_ONLY = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    }
}
