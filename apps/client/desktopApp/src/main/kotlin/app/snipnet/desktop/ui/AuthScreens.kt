package app.snipnet.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.snipnet.desktop.auth.AuthMode
import app.snipnet.desktop.auth.AuthStateHolder
import app.snipnet.desktop.di.AppContainer

/** Combined sign-in and registration form backed by an [AuthStateHolder] that lives as long as the screen. */
@Composable
fun LoginScreen(container: AppContainer) {
    val holder = remember { container.authStateHolder() }
    DisposableEffect(holder) { onDispose { holder.close() } }
    val state by holder.state.collectAsState()
    val notice by container.session.notice.collectAsState()
    val registering = state.mode == AuthMode.REGISTER

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (registering) "Create account" else "Sign in",
                style = MaterialTheme.typography.headlineMedium,
            )
            notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            OutlinedTextField(
                value = state.email,
                onValueChange = holder::setEmail,
                label = { Text("Email") },
                singleLine = true,
                isError = state.emailError != null,
                supportingText = state.emailError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.password,
                onValueChange = holder::setPassword,
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                isError = state.passwordError != null,
                supportingText =
                    (
                        state.passwordError
                            ?: if (registering) "At least ${AuthStateHolder.MIN_PASSWORD_LENGTH} characters." else null
                    )?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { holder.submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Button(
                onClick = holder::submit,
                enabled = !state.submitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (state.submitting) {
                        "Please wait..."
                    } else if (registering) {
                        "Create account"
                    } else {
                        "Sign in"
                    },
                )
            }
            TextButton(
                onClick = { holder.setMode(if (registering) AuthMode.LOGIN else AuthMode.REGISTER) },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) {
                Text(if (registering) "Already have an account? Sign in" else "New here? Create an account")
            }
        }
    }
}
