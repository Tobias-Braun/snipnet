package app.snipnet.desktop.video

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Disk cache for derived media (thumbnails, waveforms). Entries are keyed by the source file's path, size and
 * modification time, so editing or replacing the file invalidates them without any bookkeeping.
 */
class MediaCache(
    private val root: Path,
) {
    /** Directory for one kind of derived data of [source], created on demand. */
    fun directory(
        source: Path,
        kind: String,
    ): Path {
        val dir = root.resolve(sourceKey(source)).resolve(kind)
        Files.createDirectories(dir)
        return dir
    }

    private fun sourceKey(source: Path): String {
        val absolute = source.toAbsolutePath().normalize()
        val identity = "$absolute|${Files.size(absolute)}|${Files.getLastModifiedTime(absolute).toMillis()}"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    /**
     * Writes through a temporary sibling and renames, so a crash or cancellation mid-write never leaves a
     * truncated file that a later run would mistake for a finished cache entry. The temporary name is unique, so two
     * generators filling the same entry at once (the timeline asking twice) do not write into each other's file.
     */
    fun writeAtomically(
        target: Path,
        write: (Path) -> Unit,
    ) {
        val tmp = Files.createTempFile(target.parent, target.fileName.toString(), ".tmp")
        try {
            write(tmp)
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
