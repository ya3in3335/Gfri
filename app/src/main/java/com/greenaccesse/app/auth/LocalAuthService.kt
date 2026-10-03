package com.greenaccesse.app.auth

import com.greenaccesse.app.security.Crypto
import com.greenaccesse.app.storage.SecureStore
import com.greenaccesse.app.util.AppJson
import com.greenaccesse.app.util.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Device-local operator account.
 *
 * - The password is hashed with PBKDF2 ([PasswordHasher]); only the salted hash is stored, inside a Keystore-encrypted store.
 * - Repeated failures trigger an exponential lockout to slow down guessing.
 * - "Remember me" persists a random session token (encrypted); otherwise the session lives only in memory and ends
 *   with the process.
 */
class LocalAuthService(
    private val store: SecureStore,
    private val hasher: PasswordHasher = PasswordHasher(),
    private val clock: Clock = Clock.SYSTEM,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AuthService {

    private val _session = MutableStateFlow<AuthSession?>(null)
    override val session: StateFlow<AuthSession?> = _session.asStateFlow()
    override val supportsPasswordRecovery: Boolean = false
    private val mutex = Mutex()

    override suspend fun isAccountConfigured(): Boolean = withContext(ioDispatcher) { loadAccount() != null }

    override suspend fun createAccount(username: String, password: CharArray, rememberMe: Boolean): AuthResult =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    if (loadAccount() != null) return@withLock AuthResult.Failure(AuthError.AccountAlreadyExists)
                    val normalized = normalizeUsername(username)
                    validateUsername(normalized)?.let { return@withLock AuthResult.Failure(AuthError.InvalidInput(it)) }
                    validatePassword(password)?.let { return@withLock AuthResult.Failure(AuthError.InvalidInput(it)) }
                    val account = StoredAccount(normalized, hasher.hash(password), clock.nowMillis())
                    store.putString(KEY_ACCOUNT, AppJson.encodeToString(StoredAccount.serializer(), account))
                    AuthResult.Success(startSession(normalized, rememberMe))
                } finally {
                    password.fill('\u0000')
                }
            }
        }

    override suspend fun login(username: String, password: CharArray, rememberMe: Boolean): AuthResult =
        withContext(ioDispatcher) {
            mutex.withLock {
                try {
                    val account = loadAccount() ?: return@withLock AuthResult.Failure(AuthError.AccountNotConfigured)
                    val now = clock.nowMillis()
                    val lock = loadLockout()
                    if (lock.lockedUntil > now) {
                        return@withLock AuthResult.Failure(AuthError.LockedOut(lock.lockedUntil - now))
                    }
                    val userOk = normalizeUsername(username) == account.username
                    // Always run the hash to keep timing similar whether or not the username matched.
                    val passOk = hasher.verify(password, account.password)
                    if (userOk && passOk) {
                        store.remove(KEY_LOCKOUT)
                        AuthResult.Success(startSession(account.username, rememberMe))
                    } else {
                        val failures = lock.failures + 1
                        val lockedUntil = if (failures >= MAX_FREE_ATTEMPTS) {
                            val exp = (failures - MAX_FREE_ATTEMPTS).coerceAtMost(6)
                            now + BASE_LOCKOUT_MS * (1L shl exp)
                        } else 0L
                        store.putString(KEY_LOCKOUT, AppJson.encodeToString(Lockout.serializer(), Lockout(failures, lockedUntil)))
                        AuthResult.Failure(AuthError.InvalidCredentials)
                    }
                } catch (e: Exception) {
                    AuthResult.Failure(AuthError.Unexpected(e.message ?: "Authentication error"))
                } finally {
                    password.fill('\u0000')
                }
            }
        }

    override suspend fun restoreSession(): AuthSession? = withContext(ioDispatcher) {
        _session.value?.let { if (it.isValid(clock.nowMillis())) return@withContext it }
        val raw = store.getString(KEY_SESSION) ?: return@withContext null
        val persisted = runCatching { AppJson.decodeFromString(PersistedSession.serializer(), raw) }.getOrNull()
        val account = loadAccount()
        if (persisted == null || account == null || persisted.username != account.username || !persisted.toSession().isValid(clock.nowMillis())) {
            store.remove(KEY_SESSION)
            return@withContext null
        }
        persisted.toSession().also { _session.value = it }
    }

    override suspend fun logout() = withContext(ioDispatcher) {
        store.remove(KEY_SESSION)
        _session.value = null
    }

    override suspend fun resetAccount() = withContext(ioDispatcher) {
        mutex.withLock {
            store.remove(KEY_SESSION)
            store.remove(KEY_ACCOUNT)
            store.remove(KEY_LOCKOUT)
            _session.value = null
        }
    }

    private fun startSession(username: String, persistent: Boolean): AuthSession {
        val now = clock.nowMillis()
        val ttl = if (persistent) REMEMBER_ME_TTL_MS else SESSION_TTL_MS
        val session = AuthSession(username, Crypto.b64(Crypto.randomBytes(32)), now, now + ttl, persistent)
        if (persistent) {
            store.putString(KEY_SESSION, AppJson.encodeToString(PersistedSession.serializer(), PersistedSession.from(session)))
        } else {
            store.remove(KEY_SESSION)
        }
        _session.value = session
        return session
    }

    private fun loadAccount(): StoredAccount? =
        store.getString(KEY_ACCOUNT)?.let { runCatching { AppJson.decodeFromString(StoredAccount.serializer(), it) }.getOrNull() }

    private fun loadLockout(): Lockout =
        store.getString(KEY_LOCKOUT)?.let { runCatching { AppJson.decodeFromString(Lockout.serializer(), it) }.getOrNull() } ?: Lockout()

    @Serializable
    private data class StoredAccount(val username: String, val password: PasswordHash, val createdAt: Long)

    @Serializable
    private data class Lockout(val failures: Int = 0, val lockedUntil: Long = 0)

    @Serializable
    data class PersistedSession(val username: String, val token: String, val issuedAt: Long, val expiresAt: Long) {
        fun toSession() = AuthSession(username, token, issuedAt, expiresAt, persistent = true)

        companion object {
            fun from(s: AuthSession) = PersistedSession(s.username, s.token, s.issuedAtMillis, s.expiresAtMillis)
        }
    }

    companion object {
        private const val KEY_ACCOUNT = "auth.account"
        private const val KEY_SESSION = "auth.session"
        private const val KEY_LOCKOUT = "auth.lockout"
        const val MAX_FREE_ATTEMPTS = 5
        const val BASE_LOCKOUT_MS = 30_000L
        const val SESSION_TTL_MS = 12L * 60 * 60 * 1000
        const val REMEMBER_ME_TTL_MS = 30L * 24 * 60 * 60 * 1000
        const val MIN_PASSWORD_LENGTH = 8

        fun normalizeUsername(raw: String): String = raw.trim().lowercase(Locale.ROOT)

        fun validateUsername(username: String): String? = when {
            username.length < 3 -> "Username must contain at least 3 characters"
            username.length > 64 -> "Username is too long"
            !username.all { it.isLetterOrDigit() || it in "._-@+" } -> "Username contains invalid characters"
            else -> null
        }

        fun validatePassword(password: CharArray): String? = when {
            password.size < MIN_PASSWORD_LENGTH -> "Password must contain at least $MIN_PASSWORD_LENGTH characters"
            password.size > 128 -> "Password is too long"
            password.all { it.isWhitespace() } -> "Password cannot be blank"
            else -> null
        }
    }
}

