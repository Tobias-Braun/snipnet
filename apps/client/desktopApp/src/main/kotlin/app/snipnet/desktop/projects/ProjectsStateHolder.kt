package app.snipnet.desktop.projects

import app.snipnet.desktop.state.StateHolder
import app.snipnet.shared.api.ApiError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * @property notice a message about the last import or refresh (rejected files, unreachable server), dismissed by the
 * user or replaced by the next one.
 */
data class ProjectsState(
    val rows: List<ProjectRow> = emptyList(),
    val refreshing: Boolean = false,
    val notice: String? = null,
)

/**
 * State of the projects list. The rows come from the app-wide [ImportPipeline], so a running transcode or upload
 * keeps going and shows its progress again when the screen is reopened; this holder only adds the refresh and the
 * notice line.
 */
class ProjectsStateHolder(
    private val pipeline: ImportPipeline,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : StateHolder<ProjectsState>(ProjectsState(rows = pipeline.rows.value), dispatcher) {
    init {
        scope.launch { pipeline.rows.collect { rows -> update { it.copy(rows = rows) } } }
        refresh()
    }

    fun refresh() {
        update { it.copy(refreshing = true) }
        scope.launch {
            try {
                pipeline.refresh()
                update { it.copy(refreshing = false) }
            } catch (e: ApiError) {
                update {
                    it.copy(
                        refreshing = false,
                        notice = "Could not reach the server: ${e.message} Showing local data.",
                    )
                }
            }
        }
    }

    fun import(paths: List<Path>) {
        val rejected = pipeline.import(paths)
        if (rejected.isNotEmpty()) update { it.copy(notice = rejected.joinToString(" ")) }
    }

    fun retry(projectId: String) = pipeline.retry(projectId)

    fun cancel(projectId: String) = pipeline.cancel(projectId)

    fun remove(projectId: String): Boolean = pipeline.remove(projectId)

    fun analyze(projectId: String) = pipeline.startAnalysis(projectId)

    fun dismissNotice() = update { it.copy(notice = null) }
}
