package com.greenaccesse.app.auth

import kotlinx.coroutines.flow.StateFlow

/**
 * Authentication abstraction.
 *
 * Version 1 ships [LocalAuthService] (a device-local operator account) so the app works without any cloud backend.
 * Future implementations (Green Access backend, REST API, Firebase, Supabase) only need to implement this interface;
 * the UI and the rest of the app depend exclusively on it.
 */
interface AuthService {
    /** The active session, or null when logged out. */
    val session: StateFlow<AuthSession?>

    /** Whether recovering a forgotten password is possible without wiping local data. */
    val supportsPasswordRecovery: Boolean

    /** True once an account exists that [login] can authenticate against. */
    suspend fun isAccountConfigured(): Boolean

    /** First-run setup of the operator account. */
    suspend fun createAccount(username: String, password: CharArray, rememberMe: Boolean): AuthResult

    suspend fun login(username: String, password: CharArray, rememberMe: Boolean): AuthResult

    /** Restores a persisted ("remember me") session after a process restart. */
    suspend fun restoreSession(): AuthSession?

    suspend fun logout()

    /** Forgot-password flow for a local account: removes the account (callers must also wipe pairing data). */
    suspend fun resetAccount()
}

data class AuthSession(
    val username: String,
    val token: String,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val persistent: Boolean,
) {
    fun isValid(nowMillis: Long): Boolean = nowMillis < expiresAtMillis
}

sealed interface AuthResult {
    data class Success(val session: AuthSession) : AuthResult
    data class Failure(val error: AuthError) : AuthResult
}

sealed interface AuthError {
    data object InvalidCredentials : AuthError
    data object AccountNotConfigured : AuthError
    data object AccountAlreadyExists : AuthError
    data class InvalidInput(val reason: String) : AuthError
    data class LockedOut(val retryAfterMillis: Long) : AuthError
    data class Unexpected(val message: String) : AuthError
}
