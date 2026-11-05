package com.droidlink.security

import com.droidlink.domain.model.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGuardTest {
    private var now = 1_000L
    private val guard = SessionGuard { now }

    @Test fun nothingIsAllowedWithoutAGrant() {
        Scope.entries.forEach { assertFalse(guard.isAllowed(it)) }
        assertFalse(guard.isActive())
    }

    @Test fun onlyGrantedScopesAreAllowed() {
        guard.activate(SessionGuard.Grant("s1", setOf(Scope.VIEW), 10_000))
        assertTrue(guard.isAllowed(Scope.VIEW))
        assertFalse(guard.isAllowed(Scope.CONTROL))
        assertFalse(guard.isAllowed(Scope.FILES))
    }

    @Test fun grantExpires() {
        guard.activate(SessionGuard.Grant("s1", setOf(Scope.VIEW, Scope.CONTROL), 5_000))
        now = 4_999
        assertTrue(guard.isAllowed(Scope.CONTROL))
        now = 5_000
        assertFalse(guard.isAllowed(Scope.CONTROL))
        assertNull(guard.grant.value)
    }

    @Test fun emergencyRevokeStopsEverything() {
        guard.activate(SessionGuard.Grant("s1", Scope.entries.toSet(), 10_000))
        guard.revoke()
        Scope.entries.forEach { assertFalse(guard.isAllowed(it)) }
    }

    @Test fun pausingControlKeepsViewing() {
        guard.activate(SessionGuard.Grant("s1", setOf(Scope.VIEW, Scope.CONTROL), 10_000))
        guard.downgrade(Scope.CONTROL)
        assertFalse(guard.isAllowed(Scope.CONTROL))
        assertTrue(guard.isAllowed(Scope.VIEW))
        assertEquals(setOf(Scope.VIEW), guard.grant.value!!.scopes)
    }
}
