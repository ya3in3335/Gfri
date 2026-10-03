package com.greenaccesse.app.presentation.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.greenaccesse.app.R
import com.greenaccesse.app.auth.AuthError
import com.greenaccesse.app.auth.AuthResult
import com.greenaccesse.app.auth.AuthService
import com.greenaccesse.app.auth.LocalAuthService
import com.greenaccesse.app.presentation.appViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val loading: Boolean = true,
    val setupMode: Boolean = false,
    val username: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val rememberMe: Boolean = true,
    val submitting: Boolean = false,
    val error: String? = null,
)

class LoginViewModel(
    private val auth: AuthService,
    private val wipeAll: suspend () -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch { refreshMode() }
    }

    private suspend fun refreshMode() {
        val configured = auth.isAccountConfigured()
        _state.update { it.copy(loading = false, setupMode = !configured) }
    }

    fun onUsername(v: String) = _state.update { it.copy(username = v.take(64), error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v.take(128), error = null) }
    fun onConfirm(v: String) = _state.update { it.copy(confirmPassword = v.take(128), error = null) }
    fun onRememberMe(v: Boolean) = _state.update { it.copy(rememberMe = v) }

    fun submit() {
        val s = _state.value
        if (s.submitting) return
        if (s.setupMode && s.password != s.confirmPassword) {
            _state.update { it.copy(error = "Passwords do not match") }
            return
        }
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            val pw = s.password.toCharArray()
            val result = if (s.setupMode) auth.createAccount(s.username, pw, s.rememberMe) else auth.login(s.username, pw, s.rememberMe)
            _state.update {
                when (result) {
                    is AuthResult.Success -> it.copy(submitting = false, password = "", confirmPassword = "")
                    is AuthResult.Failure -> it.copy(submitting = false, password = "", confirmPassword = "", error = result.error.toMessage())
                }
            }
        }
    }

    fun resetEverything() {
        viewModelScope.launch {
            wipeAll()
            _state.value = LoginUiState(loading = false, setupMode = true)
        }
    }

    private fun AuthError.toMessage(): String = when (this) {
        AuthError.InvalidCredentials -> "Incorrect username or password"
        AuthError.AccountNotConfigured -> "No account exists on this phone yet"
        AuthError.AccountAlreadyExists -> "An account already exists on this phone"
        is AuthError.InvalidInput -> reason
        is AuthError.LockedOut -> "Too many attempts. Try again in ${(retryAfterMillis + 999) / 1000} s"
        is AuthError.Unexpected -> "Login failed: $message"
    }
}

@Composable
fun LoginScreen() {
    val vm = appViewModel { c -> LoginViewModel(c.auth, c::wipeAllLocalData) }
    val state by vm.state.collectAsStateWithLifecycle()
    var showPassword by remember { mutableStateOf(false) }
    var showForgot by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            Image(painterResource(R.drawable.ic_brand_mark), contentDescription = null, modifier = Modifier.size(84.dp))
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(32.dp))

            if (state.loading) {
                CircularProgressIndicator()
                return@Column
            }

            Column(Modifier.widthIn(max = 480.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (state.setupMode) {
                    Text(
                        "Create the operator account for this phone. Credentials are stored only on this device, protected by the Android Keystore.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = state.username,
                    onValueChange = vm::onUsername,
                    label = { Text("Email / Username") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = vm::onPassword,
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = if (state.setupMode) ImeAction.Next else ImeAction.Done,
                    ),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                            )
                        }
                    },
                    supportingText = if (state.setupMode) {
                        { Text("At least ${LocalAuthService.MIN_PASSWORD_LENGTH} characters") }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.setupMode) {
                    OutlinedTextField(
                        value = state.confirmPassword,
                        onValueChange = vm::onConfirm,
                        label = { Text("Confirm password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = state.rememberMe, onCheckedChange = vm::onRememberMe)
                    Text("Remember me", style = MaterialTheme.typography.bodyMedium)
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Button(
                    onClick = vm::submit,
                    enabled = !state.submitting && state.username.isNotBlank() && state.password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    if (state.submitting) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(if (state.setupMode) "Create account" else "Login")
                    }
                }
                if (!state.setupMode) {
                    TextButton(onClick = { showForgot = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("Forgot password?")
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Green Access Companion",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    if (showForgot) {
        AlertDialog(
            onDismissRequest = { showForgot = false },
            title = { Text("Forgot password") },
            text = {
                Text(
                    "This operator account exists only on this phone and its password cannot be recovered.\n\n" +
                        "You can reset the phone: the account, the pairing with the Green Access PC and the local scan history " +
                        "will be erased. You will then create a new account and pair again using a new code from the PC.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showForgot = false; vm.resetEverything() }) {
                    Text("Reset phone", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showForgot = false }) { Text("Cancel") } },
        )
    }
}
