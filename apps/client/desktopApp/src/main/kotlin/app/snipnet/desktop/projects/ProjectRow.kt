package app.snipnet.desktop.projects

import app.snipnet.shared.model.JobStatus
import app.snipnet.shared.model.Video
import app.snipnet.shared.model.VideoStatus
import app.snipnet.shared.store.Project
import java.nio.file.Files
import java.nio.file.Path

/** The step of the import and analysis pipeline a background task is working on. */
enum class Stage { PROXY, UPLOADING, ANALYZING }

/**
 * In-memory state of a running or failed pipeline step of one project. A non-null [error] means the step stopped and
 * offers a retry. Tasks are deliberately not persisted: after a restart the row is derived from what is on disk and
 * on the server again, and an interrupted step simply shows up as failed.
 *
 * @property progress fraction in 0..1, or null while it is unknown.
 */
data class TaskState(
    val stage: Stage,
    val progress: Double? = null,
    val error: String? = null,
)

/** What the projects list shows as the badge of a project. */
enum class ProjectStatus {
    /** Transcoding the proxy. */
    PROXY,
    UPLOADING,

    /** Uploaded; waiting for the court to be marked and the analysis to be started. */
    READY,
    ANALYZING,
    ANALYZED,
    FAILED,

    /** The server state has not been fetched yet, or could not be. */
    UNKNOWN,
}

/** One line of the projects list, combined from the local project, the server's video and the running task. */
data class ProjectRow(
    val project: Project,
    val status: ProjectStatus,
    val progress: Double?,
    val error: String?,
    val hasCourt: Boolean,
) {
    val title: String get() = Path.of(project.originalPath).fileName?.toString() ?: project.originalPath

    /** True while a step can still be cancelled: transcoding and uploading are local work the user may abort. */
    val cancellable: Boolean get() = status == ProjectStatus.PROXY || status == ProjectStatus.UPLOADING

    /**
     * False while an analysis runs: the server refuses to delete a video with a queued or running job, so removing the
     * project then would strand the server video (see [ImportPipeline.remove]).
     */
    val removable: Boolean get() = status != ProjectStatus.ANALYZING

    companion object {
        /**
         * Decides the badge. A running or failed [task] wins over everything else because it is the freshest fact;
         * without one the server's [video] status is authoritative, and only when there is neither the local files
         * say how far an import got before it was interrupted.
         *
         * @param serverChecked whether the server's video list has been fetched successfully, which lets a video that
         * is missing from it count as deleted instead of merely unknown.
         */
        fun derive(
            project: Project,
            video: Video?,
            task: TaskState?,
            serverChecked: Boolean,
        ): ProjectRow {
            val hasCourt = video?.court != null || project.court != null
            val badge =
                when {
                    task != null -> fromTask(task)
                    video != null -> fromVideo(video)
                    else -> fromLocalFiles(project, serverChecked)
                }
            return ProjectRow(project, badge.status, badge.progress, badge.error, hasCourt)
        }

        private class Badge(
            val status: ProjectStatus,
            val progress: Double? = null,
            val error: String? = null,
        )

        private fun fromTask(task: TaskState): Badge {
            if (task.error != null) return Badge(ProjectStatus.FAILED, error = task.error)
            val status =
                when (task.stage) {
                    Stage.PROXY -> ProjectStatus.PROXY
                    Stage.UPLOADING -> ProjectStatus.UPLOADING
                    Stage.ANALYZING -> ProjectStatus.ANALYZING
                }
            return Badge(status, task.progress)
        }

        private fun fromVideo(video: Video): Badge =
            when (video.status) {
                VideoStatus.ANALYZED -> Badge(ProjectStatus.ANALYZED)
                VideoStatus.ANALYZING -> Badge(ProjectStatus.ANALYZING, video.latestJob?.progress)
                VideoStatus.UPLOADED -> Badge(ProjectStatus.READY)
                VideoStatus.CREATED -> Badge(ProjectStatus.FAILED, error = "The upload was interrupted.")
                VideoStatus.FAILED ->
                    Badge(ProjectStatus.FAILED, error = video.latestJob?.error ?: "The analysis failed.")
            }

        private fun fromLocalFiles(
            project: Project,
            serverChecked: Boolean,
        ): Badge {
            val proxyReady = project.proxyPath?.let { Files.isRegularFile(Path.of(it)) } == true
            val error =
                when {
                    !proxyReady -> "The import was interrupted."
                    project.remoteVideoId == null -> "The upload was interrupted."
                    serverChecked -> "The video no longer exists on the server."
                    else -> return Badge(ProjectStatus.UNKNOWN)
                }
            return Badge(ProjectStatus.FAILED, error = error)
        }

        /** A job that has not reached a final state. */
        fun isActive(status: JobStatus) = status == JobStatus.QUEUED || status == JobStatus.RUNNING
    }
}

/** The container formats the import accepts, which are the ones the bundled ffmpeg is expected to decode. */
object SupportedVideo {
    val extensions = setOf("mp4", "mov", "mkv", "m4v")

    /** A user-readable reason why [path] cannot be imported, or null when it can. */
    fun problem(path: Path): String? {
        val name = path.fileName?.toString() ?: path.toString()
        return when {
            path.fileName
                ?.toString()
                ?.substringAfterLast('.', "")
                ?.lowercase() !in extensions ->
                "$name is not a supported video (use ${extensions.joinToString(", ") { ".$it" }})."
            !Files.isRegularFile(path) -> "$name is not a file."
            else -> null
        }
    }
}
