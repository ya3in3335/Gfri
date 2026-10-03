package com.greenaccesse.app.pairing

import com.greenaccesse.app.network.ConnectionManager
import com.greenaccesse.app.protocol.DeviceIdentity
import com.greenaccesse.app.protocol.ErrorCodes
import com.greenaccesse.app.protocol.HandshakeCrypto
import com.greenaccesse.app.protocol.PairingClient
import com.greenaccesse.app.protocol.ProtocolException
import com.greenaccesse.app.protocol.RemoteProtocolException
import com.greenaccesse.app.security.Crypto
import com.greenaccesse.app.settings.SettingsRepository
import com.greenaccesse.app.transport.Endpoint
import com.greenaccesse.app.transport.HostValidator
import com.greenaccesse.app.transport.Transport
import com.greenaccesse.app.transport.TransportException
import com.greenaccesse.app.transport.TransportKind
import com.greenaccesse.app.util.Clock

sealed interface PairingResult {
    data class Success(val pc: PairedPc) : PairingResult
    data class Failure(val message: String) : PairingResult
}

/**
 * Pairs this phone with a Green Access Bridge using the 6-digit code displayed on the PC.
 * Designed so that QR-code pairing can be added later: a QR code would simply carry host, port and code
 * (plus, in a future protocol version, a longer secret) and call [pair] with them.
 */
class PairingService(
    private val identity: DeviceIdentity,
    private val repository: PairingRepository,
    private val settings: SettingsRepository,
    private val connectionManager: ConnectionManager,
    private val transportFactory: (TransportKind) -> Transport,
    private val clock: Clock = Clock.SYSTEM,
) {
    suspend fun pair(host: String, port: Int, transportKind: TransportKind, code: String): PairingResult {
        val result = attemptPairing(host, port, transportKind, code)
        // Success: connect to the new PC. Failure: reconnect to the previously paired PC, if any.
        if (result is PairingResult.Success || repository.pairedPc.value != null) connectionManager.connect()
        return result
    }

    private suspend fun attemptPairing(host: String, port: Int, transportKind: TransportKind, code: String): PairingResult {
        val cleanCode = code.filter { it.isDigit() }
        if (!HandshakeCrypto.isValidPairingCode(cleanCode)) return PairingResult.Failure("Enter the 6-digit code shown on the Green Access PC.")
        val cleanHost = host.trim()
        val transport = transportFactory(transportKind)
        val endpoint = transport.resolve(Endpoint(cleanHost, port))
        if (!HostValidator.isValidHost(endpoint.host)) return PairingResult.Failure("Enter a valid PC address (for example 192.168.1.20).")
        if (!HostValidator.isValidPort(endpoint.port)) return PairingResult.Failure("Enter a valid port (1-65535).")

        // Stop any existing session first: pairing replaces the previous PC.
        connectionManager.shutdown()
        val connection = try {
            transport.open(endpoint)
        } catch (e: TransportException) {
            return PairingResult.Failure("Cannot reach Green Access at $endpoint. ${e.message ?: ""}".trim())
        }
        return try {
            val outcome = PairingClient(identity).pair(connection, cleanCode)
            val pc = PairedPc(
                pcId = outcome.pcId,
                pcName = outcome.pcName,
                deviceSecretB64 = Crypto.b64(outcome.deviceSecret),
                pairedAtMillis = clock.nowMillis(),
                verificationCode = outcome.verificationCode,
            )
            outcome.deviceSecret.fill(0)
            repository.save(pc)
            settings.update { it.copy(serverHost = cleanHost, serverPort = port, transport = transportKind) }
            PairingResult.Success(pc)
        } catch (e: RemoteProtocolException) {
            PairingResult.Failure(
                when (e.code) {
                    ErrorCodes.INVALID_CODE -> "Incorrect pairing code. Check the code shown on the PC."
                    ErrorCodes.PAIRING_CLOSED -> "Pairing is not active on the PC. Open pairing in Green Access and try again."
                    ErrorCodes.TOO_MANY_ATTEMPTS -> "Too many attempts. Generate a new pairing code on the PC."
                    else -> e.message ?: "Pairing rejected by the PC."
                },
            )
        } catch (e: ProtocolException) {
            PairingResult.Failure(e.message ?: "Pairing failed.")
        } catch (e: TransportException) {
            PairingResult.Failure(e.message ?: "Connection lost during pairing.")
        } finally {
            connection.close("pairing finished")
        }
    }

    /** Revokes the pairing on both sides (PC notified when reachable) and forgets the secret locally. */
    suspend fun revoke() {
        connectionManager.sendUnpair()
        repository.clear()
    }
}
