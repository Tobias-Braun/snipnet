package app.snipnet.desktop.settings

import app.snipnet.desktop.auth.Session
import app.snipnet.desktop.state.StateHolder
import app.snipnet.shared.api.ApiError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * @property trainingConsent whether the user allows the videos they finalize to be used for model training.
 * @property saving the change is on its way to the server.
 * @property error why the last change was not stored; the toggle then shows the server's value again.
 */
data class SettingsState(
    val trainingConsent: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

/** Backs the settings dialog: reads the consent from the signed-in user and stores changes through the session. */
class SettingsStateHolder(
    private val session: Session,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) : StateHolder<SettingsState>(
        SettingsState(trainingConsent = session.user.value?.trainingConsent ?: false),
        dispatcher,
    ) {
    fun setTrainingConsent(consent: Boolean) {
        if (state.value.saving) return
        update { it.copy(trainingConsent = consent, saving = true, error = null) }
        scope.launch {
            try {
                session.setTrainingConsent(consent)
                update { it.copy(saving = false) }
            } catch (e: ApiError) {
                val stored = session.user.value?.trainingConsent ?: !consent
                update { it.copy(trainingConsent = stored, saving = false, error = e.message) }
            }
        }
    }
}
