package app.snipnet.shared

object AppInfo {
    const val NAME = "Snipnet"

    /**
     * Title for the main window: the product name alone, or prefixed with the open project so several windows
     * stay distinguishable in the OS task switcher.
     */
    fun windowTitle(projectName: String? = null): String {
        val project = projectName?.trim().orEmpty()
        return if (project.isEmpty()) NAME else "$project – $NAME"
    }
}
