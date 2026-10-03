package com.greenaccesse.app.pairing

import com.greenaccesse.app.storage.SecureStore
import com.greenaccesse.app.util.AppJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

/** The Green Access PC this phone is paired with. [deviceSecretB64] is only ever stored encrypted (Android Keystore). */
@Serializable
data class PairedPc(
    val pcId: String,
    val pcName: String,
    val deviceSecretB64: String,
    val pairedAtMillis: Long,
    val verificationCode: String,
    val lastConnectedAtMillis: Long? = null,
) {
    override fun toString(): String = "PairedPc(pcId=$pcId, pcName=$pcName)" // never log the secret
}

interface PairingRepository {
    val pairedPc: StateFlow<PairedPc?>
    suspend fun load(): PairedPc?
    suspend fun save(pc: PairedPc)
    suspend fun markConnected(atMillis: Long)
    suspend fun clear()
}

class SecurePairingRepository(
    private val store: SecureStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PairingRepository {
    private val _paired = MutableStateFlow<PairedPc?>(null)
    override val pairedPc: StateFlow<PairedPc?> = _paired.asStateFlow()

    override suspend fun load(): PairedPc? = withContext(ioDispatcher) {
        val pc = store.getString(KEY)?.let { runCatching { AppJson.decodeFromString(PairedPc.serializer(), it) }.getOrNull() }
        _paired.value = pc
        pc
    }

    override suspend fun save(pc: PairedPc) = withContext(ioDispatcher) {
        store.putString(KEY, AppJson.encodeToString(PairedPc.serializer(), pc))
        _paired.value = pc
    }

    override suspend fun markConnected(atMillis: Long) {
        val current = _paired.value ?: return
        save(current.copy(lastConnectedAtMillis = atMillis))
    }

    override suspend fun clear() = withContext(ioDispatcher) {
        store.remove(KEY)
        _paired.value = null
    }

    private companion object {
        const val KEY = "pairing.pc"
    }
}

class InMemoryPairingRepository(initial: PairedPc? = null) : PairingRepository {
    private val _paired = MutableStateFlow(initial)
    override val pairedPc: StateFlow<PairedPc?> = _paired.asStateFlow()
    override suspend fun load(): PairedPc? = _paired.value
    override suspend fun save(pc: PairedPc) { _paired.value = pc }
    override suspend fun markConnected(atMillis: Long) { _paired.value = _paired.value?.copy(lastConnectedAtMillis = atMillis) }
    override suspend fun clear() { _paired.value = null }
}
