package com.greenaccesse.app.history

import com.greenaccesse.app.security.CryptoException
import com.greenaccesse.app.security.SecretCipher
import com.greenaccesse.app.util.AppJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

@Serializable
enum class ScanStatus(val label: String, val isSuccess: Boolean) {
    PENDING("Sending", false),
    SENT("Sent", true),
    DELIVERED("Delivered", true),
    REJECTED("Rejected by PC", false),
    FAILED_DISCONNECTED("Failed - PC disconnected", false),
    FAILED_TIMEOUT("Failed - no response", false),
    FAILED_ERROR("Failed", false),
}

/**
 * One local history entry. Deliberately limited to timestamp, UID, status and connection status:
 * no member information ever exists on the phone.
 */
@Serializable
data class ScanRecord(
    val id: String,
    val timestampMillis: Long,
    val uid: String,
    val status: ScanStatus,
    val connectionStatus: String,
)

interface ScanHistoryRepository {
    val records: StateFlow<List<ScanRecord>>
    suspend fun load()
    suspend fun add(record: ScanRecord)
    suspend fun updateStatus(id: String, status: ScanStatus)
    suspend fun clear()
}

/**
 * History stored in an app-private file, encrypted with the Keystore-backed [SecretCipher]. Capped to [maxEntries].
 */
class EncryptedFileScanHistory(
    private val file: File,
    private val cipher: SecretCipher,
    private val maxEntries: Int = 1000,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ScanHistoryRepository {
    private val _records = MutableStateFlow<List<ScanRecord>>(emptyList())
    override val records: StateFlow<List<ScanRecord>> = _records.asStateFlow()
    private val mutex = Mutex()
    private val serializer = ListSerializer(ScanRecord.serializer())

    override suspend fun load() = withContext(ioDispatcher) {
        mutex.withLock {
            _records.value = if (file.exists()) {
                try {
                    AppJson.decodeFromString(serializer, String(cipher.decrypt(file.readBytes()), Charsets.UTF_8))
                } catch (e: CryptoException) {
                    file.delete(); emptyList()
                } catch (e: Exception) {
                    file.delete(); emptyList()
                }
            } else emptyList()
        }
    }

    override suspend fun add(record: ScanRecord) = mutate { (listOf(record) + it).take(maxEntries) }

    override suspend fun updateStatus(id: String, status: ScanStatus) =
        mutate { list -> list.map { if (it.id == id) it.copy(status = status) else it } }

    override suspend fun clear() = mutate { emptyList() }

    private suspend fun mutate(transform: (List<ScanRecord>) -> List<ScanRecord>) = withContext(ioDispatcher) {
        mutex.withLock {
            val next = transform(_records.value)
            _records.value = next
            persist(next)
        }
    }

    private fun persist(list: List<ScanRecord>) {
        if (list.isEmpty()) {
            file.delete()
            return
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(cipher.encrypt(AppJson.encodeToString(serializer, list).toByteArray(Charsets.UTF_8)))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }
}

class InMemoryScanHistory : ScanHistoryRepository {
    private val _records = MutableStateFlow<List<ScanRecord>>(emptyList())
    override val records: StateFlow<List<ScanRecord>> = _records.asStateFlow()
    override suspend fun load() = Unit
    override suspend fun add(record: ScanRecord) { _records.value = listOf(record) + _records.value }
    override suspend fun updateStatus(id: String, status: ScanStatus) {
        _records.value = _records.value.map { if (it.id == id) it.copy(status = status) else it }
    }
    override suspend fun clear() { _records.value = emptyList() }
}
