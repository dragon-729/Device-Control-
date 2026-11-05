package com.droidlink.security

import com.droidlink.domain.model.Scope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for "is remote access currently authorised on this device?".
 * Every remote action (input, app launch, file write, screen frame) must pass through [isAllowed].
 * The grant is created only after the owner approved a session and is revoked by the emergency
 * disconnect, session end, expiry or socket loss.
 */
class SessionGuard(private val clock: () -> Long = System::currentTimeMillis) {
    data class Grant(val sessionId: String, val scopes: Set<Scope>, val expiresAtMs: Long)

    private val _grant = MutableStateFlow<Grant?>(null)
    val grant: StateFlow<Grant?> = _grant.asStateFlow()

    fun activate(grant: Grant) {
        _grant.value = grant
    }

    fun revoke() {
        _grant.value = null
    }

    fun isAllowed(scope: Scope): Boolean {
        val g = _grant.value ?: return false
        if (clock() >= g.expiresAtMs) {
            _grant.value = null
            return false
        }
        return scope in g.scopes
    }

    fun isActive(): Boolean = _grant.value?.let { clock() < it.expiresAtMs } == true

    /** Owner narrows what the session may do without ending it (e.g. "stop controlling"). */
    fun downgrade(remove: Scope) {
        _grant.value = _grant.value?.let { it.copy(scopes = it.scopes - remove) }
    }
}
