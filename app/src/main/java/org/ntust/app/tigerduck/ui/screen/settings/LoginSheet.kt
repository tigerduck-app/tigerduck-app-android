package org.ntust.app.tigerduck.ui.screen.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.ntust.app.tigerduck.R

/**
 * Login prompt rendered as an AlertDialog — matches the class-detail popup
 * style used elsewhere in the app. The original username / password fields
 * live inside the dialog body.
 */
@Composable
fun LoginSheet(
    title: String,
    subtitle: String? = null,
    usernamePlaceholder: String,
    passwordPlaceholder: String,
    initialUsername: String = "",
    uppercaseInput: Boolean = false,
    isLoggingIn: Boolean,
    loginError: String?,
    onLogin: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var username by rememberSaveable(initialUsername) { mutableStateOf(initialUsername) }
    var password by rememberSaveable { mutableStateOf("") }

    val passwordFocusRequester = remember { FocusRequester() }
    val usernameFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val canSubmit = username.isNotBlank() && password.isNotBlank() && !isLoggingIn

    fun submit() {
        if (!canSubmit) return
        keyboardController?.hide()
        onLogin(username.trim(), password)
    }

    LaunchedEffect(Unit) {
        if (username.isBlank()) usernameFocusRequester.requestFocus()
        else passwordFocusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = { if (!isLoggingIn) onDismiss() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = username,
                    onValueChange = { raw ->
                        val stripped = raw.filter { ch -> !ch.isWhitespace() }
                        username = if (uppercaseInput) stripped.uppercase() else stripped
                    },
                    label = { Text(usernamePlaceholder) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(usernameFocusRequester),
                    keyboardOptions = KeyboardOptions(
                        capitalization = if (uppercaseInput) KeyboardCapitalization.Characters
                            else KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Next,
                    ),
                    keyboardActions = KeyboardActions(onNext = {
                        focusManager.moveFocus(FocusDirection.Down)
                    }),
                    enabled = !isLoggingIn,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(passwordPlaceholder) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(passwordFocusRequester),
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Go,
                    ),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    enabled = !isLoggingIn,
                )

                if (subtitle != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (loginError != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            loginError,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { submit() },
                enabled = canSubmit,
            ) {
                if (isLoggingIn) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(stringResource(R.string.action_login))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isLoggingIn) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}
