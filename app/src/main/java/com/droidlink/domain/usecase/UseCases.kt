package com.droidlink.domain.usecase

import com.droidlink.domain.model.PairedDevice
import com.droidlink.domain.model.RemoteSession
import com.droidlink.domain.model.Scope
import com.droidlink.domain.repository.PairingRepository
import com.droidlink.domain.repository.SecurityLogRepository
import com.droidlink.domain.repository.SessionRepository
import com.droidlink.security.InputValidator

class InvalidPairingCodeException : Exception("That pairing code is not valid. Codes have 8 letters/digits.")

/** Validates the code locally first (no network round trip for typos), then claims it. */
class ClaimPairingCodeUseCase(private val pairing: PairingRepository, private val log: SecurityLogRepository) {
    suspend operator fun invoke(rawCode: String): PairedDevice {
        val code = InputValidator.normalizePairingCode(rawCode) ?: throw InvalidPairingCodeException()
        val result = pairing.claim(code)
        log.log("pairing_requested", "with ${result.peerName}")
        return result
    }
}

class ApprovePairingUseCase(private val pairing: PairingRepository, private val log: SecurityLogRepository) {
    suspend operator fun invoke(pairingId: String, scopes: Set<Scope>, peerName: String): PairedDevice {
        require(scopes.isNotEmpty()) { "Grant at least one permission" }
        val result = pairing.approve(pairingId, scopes)
        log.log("pairing_approved", "$peerName: ${scopes.joinToString { it.wire }}")
        return result
    }
}

class RevokePairingUseCase(private val pairing: PairingRepository, private val log: SecurityLogRepository) {
    suspend operator fun invoke(device: PairedDevice) {
        pairing.revoke(device.pairingId)
        log.log("pairing_revoked", device.peerName)
    }
}

class RequestSessionUseCase(private val sessions: SessionRepository, private val log: SecurityLogRepository) {
    suspend operator fun invoke(pairingId: String, scopes: Set<Scope>): RemoteSession {
        require(scopes.isNotEmpty()) { "Select at least one permission" }
        val s = sessions.request(pairingId, scopes)
        log.log("session_requested", "to ${s.peerName}: ${scopes.joinToString { it.wire }}")
        return s
    }
}

class ApproveSessionUseCase(private val sessions: SessionRepository, private val log: SecurityLogRepository) {
    suspend operator fun invoke(sessionId: String, scopes: Set<Scope>, peerName: String): RemoteSession {
        require(scopes.isNotEmpty()) { "Grant at least one permission" }
        val s = sessions.approve(sessionId, scopes)
        log.log("session_approved", "$peerName: ${scopes.joinToString { it.wire }}")
        return s
    }
}
