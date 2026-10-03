package com.greenaccesse.app.auth

import com.greenaccesse.app.storage.InMemoryStore
import com.greenaccesse.app.storage.SecureStore
import com.greenaccesse.app.testing.TestCipher
import com.greenaccesse.app.util.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAuthServiceTest {
    private var now = 1_700_000_000_000L
    private val clock = Clock { now }
    private val backing = InMemoryStore()
    private val cipher = TestCipher()

    private fun service() = LocalAuthService(SecureStore(backing, cipher), PasswordHasher(1_000), clock, Dispatchers.Unconfined)

    @Test fun `first run requires account creation`() = runBlocking {
        val auth = service()
        assertFalse(auth.isAccountConfigured())
        assertEquals(AuthError.AccountNotConfigured, (auth.login("owner", "password1".toCharArray(), false) as AuthResult.Failure).error)
    }

    @Test fun `create account then login`() = runBlocking {
        val auth = service()
        assertTrue(auth.createAccount("Owner@Gym.com", "S3cret-pass".toCharArray(), false) is AuthResult.Success)
        assertTrue(auth.isAccountConfigured())
        auth.logout()
        assertNull(auth.session.value)
        val r = auth.login("  owner@gym.com ", "S3cret-pass".toCharArray(), false)
        assertEquals("owner@gym.com", (r as AuthResult.Success).session.username)
        assertNotNull(auth.session.value)
    }

    @Test fun `wrong password is rejected`() = runBlocking {
        val auth = service()
        auth.createAccount("owner", "S3cret-pass".toCharArray(), false)
        assertEquals(AuthError.InvalidCredentials, (auth.login("owner", "wrong-pass".toCharArray(), false) as AuthResult.Failure).error)
        assertEquals(AuthError.InvalidCredentials, (auth.login("intruder", "S3cret-pass".toCharArray(), false) as AuthResult.Failure).error)
    }

    @Test fun `password is never stored in plaintext`() = runBlocking {
        val auth = service()
        auth.createAccount("owner", "S3cret-pass".toCharArray(), true)
        val raw = listOf("auth.account", "auth.session").mapNotNull { backing.getString(it) }.joinToString()
        assertFalse(raw.contains("S3cret-pass"))
        assertFalse(raw.contains("owner")) // whole record is encrypted at rest
    }

    @Test fun `repeated failures lock the account temporarily`() = runBlocking {
        val auth = service()
        auth.createAccount("owner", "S3cret-pass".toCharArray(), false)
        repeat(LocalAuthService.MAX_FREE_ATTEMPTS) { auth.login("owner", "nope-nope".toCharArray(), false) }
        val locked = auth.login("owner", "S3cret-pass".toCharArray(), false)
        assertTrue((locked as AuthResult.Failure).error is AuthError.LockedOut)
        now += LocalAuthService.BASE_LOCKOUT_MS + 1
        assertTrue(auth.login("owner", "S3cret-pass".toCharArray(), false) is AuthResult.Success)
    }

    @Test fun `remember me survives a process restart`() = runBlocking {
        service().createAccount("owner", "S3cret-pass".toCharArray(), true)
        val restarted = service()
        assertEquals("owner", restarted.restoreSession()?.username)
    }

    @Test fun `session without remember me ends with the process`() = runBlocking {
        service().createAccount("owner", "S3cret-pass".toCharArray(), false)
        assertNull(service().restoreSession())
    }

    @Test fun `remembered session expires`() = runBlocking {
        service().createAccount("owner", "S3cret-pass".toCharArray(), true)
        now += LocalAuthService.REMEMBER_ME_TTL_MS + 1
        assertNull(service().restoreSession())
    }

    @Test fun `logout clears remembered session`() = runBlocking {
        val auth = service()
        auth.createAccount("owner", "S3cret-pass".toCharArray(), true)
        auth.logout()
        assertNull(service().restoreSession())
    }

    @Test fun `reset removes the account`() = runBlocking {
        val auth = service()
        auth.createAccount("owner", "S3cret-pass".toCharArray(), true)
        auth.resetAccount()
        assertFalse(auth.isAccountConfigured())
        assertNull(auth.session.value)
    }

    @Test fun `input validation`() = runBlocking {
        val auth = service()
        assertTrue((auth.createAccount("ab", "S3cret-pass".toCharArray(), false) as AuthResult.Failure).error is AuthError.InvalidInput)
        assertTrue((auth.createAccount("owner", "short".toCharArray(), false) as AuthResult.Failure).error is AuthError.InvalidInput)
        assertTrue((auth.createAccount("own er", "S3cret-pass".toCharArray(), false) as AuthResult.Failure).error is AuthError.InvalidInput)
        assertTrue(auth.createAccount("owner", "S3cret-pass".toCharArray(), false) is AuthResult.Success)
        assertEquals(AuthError.AccountAlreadyExists, (auth.createAccount("other", "S3cret-pass".toCharArray(), false) as AuthResult.Failure).error)
    }

    @Test fun `tampered storage is treated as missing rather than crashing`() = runBlocking {
        val auth = service()
        auth.createAccount("owner", "S3cret-pass".toCharArray(), true)
        backing.putString("auth.session", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        assertNull(service().restoreSession())
    }
}
