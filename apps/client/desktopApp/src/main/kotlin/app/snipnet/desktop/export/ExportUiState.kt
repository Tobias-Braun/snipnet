package app.snipnet.desktop.export

import java.nio.file.Path

/**
 * State of the export dialog.
 *
 * @property open whether the dialog is shown.
 * @property options the current choices; they survive closing the dialog so a second export starts where the
 *   first one ended.
 * @property running whether an export is in progress; the options are locked meanwhile.
 * @property progress fraction of the running export in 0..1.
 * @property result the files written by the last successful export.
 * @property error why the last export failed.
 * @property edlUnsupportedFps nominal frame rate of the original when it is too high for EDL (see
 *   [ProjectFiles.edlUnsupportedFps]), null while unknown or when EDL works; the dialog disables EDL for it.
 */
data class ExportUiState(
    val open: Boolean = false,
    val options: ExportOptions = ExportOptions(),
    val running: Boolean = false,
    val progress: Double = 0.0,
    val result: List<Path>? = null,
    val error: String? = null,
    val edlUnsupportedFps: Int? = null,
)
