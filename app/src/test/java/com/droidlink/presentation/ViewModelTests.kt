package com.droidlink.presentation

import com.droidlink.domain.model.PairingStatus
import com.droidlink.domain.model.Registration
import com.droidlink.domain.model.Scope
import com.droidlink.domain.model.SessionStatus
import com.droidlink.domain.usecase.ClaimPairingCodeUseCase
import com.droidlink.domain.usecase.RequestSessionUseCase
import com.droidlink.network.ApiException
import com.droidlink.presentation.screens.ConnectViewModel
import com.droidlink.presentation.screens.LoginViewModel
import com.droidlink.presentation.screens.PairingViewModel
import com.droidlink.testutil.FakeAuth
import com.droidlink.testutil.FakePairingRepository
import com.droidlink.testutil.FakeSecurityLog
import com.droidlink.testutil.FakeSessionRepository
import com.droidlink.testutil.MainDispatcherRule
import com.droidlink.testutil.pairedDevice
import com.droidlink.testutil.remoteSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val auth = FakeAuth()
    private val log = FakeSecurityLog()
    private fun vm(cleartext: Boolean = false) = LoginViewModel(auth, log, "Pixel 8", cleartext, "https://relay.example.com")

    @Test fun startsWithDefaults() {
        val s = vm().state.value
        assertEquals("https://relay.example.com", s.serverUrl)
        assertEquals("Pixel 8", s.deviceName)
    }

    @Test fun rejectsInsecureServerWithoutCallingTheNetwork() = runTest {
        val v = vm()
        v.onServer("http://relay.example.com")
        v.submit()
        advanceUntilIdle()
        assertNotNull(v.state.value.error)
        assertNull(auth.registered)
    }

    @Test fun rejectsInvalidDeviceName() = runTest {
        val v = vm()
        v.onName("<b>")
        v.submit()
        advanceUntilIdle()
        assertNotNull(v.state.value.error)
        assertNull(auth.registered)
    }

    @Test fun registersAndSignalsCompletion() = runTest {
        val v = vm()
        v.onInvite("code-1")
        v.submit()
        advanceUntilIdle()
        assertTrue(v.state.value.done)
        assertFalse(v.state.value.busy)
        assertEquals(Triple("https://relay.example.com", "Pixel 8", "code-1"), auth.registered)
        assertEquals("device_registered", log.entries.single().first)
    }

    @Test fun showsServerErrorsToTheUser() = runTest {
        auth.failWith = ApiException.Forbidden("invalid registration token")
        val v = vm()
        v.submit()
        advanceUntilIdle()
        assertEquals("invalid registration token", v.state.value.error)
        assertFalse(v.state.value.done)
        assertFalse(v.state.value.busy)
    }

    @Test fun offlineErrorIsActionable() = runTest {
        auth.failWith = ApiException.Offline()
        val v = vm()
        v.submit()
        advanceUntilIdle()
        assertTrue(v.state.value.error!!.contains("internet"))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repo = FakePairingRepository()
    private val log = FakeSecurityLog()
    private val registration = MutableStateFlow<Registration?>(Registration("me", "My phone", "https://relay.example.com"))

    private fun vm() = PairingViewModel(
        repo, registration, MutableStateFlow(emptyList()), ClaimPairingCodeUseCase(repo, log), log, pollIntervalMs = 1_000,
    )

    @Test fun invalidCodeIsRejectedLocallyWithoutNetwork() = runTest {
        val v = vm()
        v.claim("12")
        advanceUntilIdle()
        assertNotNull(v.state.value.error)
        assertNull(repo.claimedCode)
        assertNull(v.state.value.waitingFor)
    }

    @Test fun codeIsNormalisedBeforeClaiming() = runTest {
        val v = vm()
        v.claim("abcd-2345")
        runCurrent()
        assertEquals("ABCD2345", repo.claimedCode)
        assertNotNull(v.state.value.waitingFor)
    }

    @Test fun waitsForOwnerThenReportsApproval() = runTest {
        val v = vm()
        v.claim("ABCD2345")
        runCurrent()
        assertNotNull(v.state.value.waitingFor)
        // owner approves on the other device
        repo.list.value = listOf(repo.claimResult.copy(status = PairingStatus.ACTIVE))
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals(PairingStatus.ACTIVE, v.state.value.approved?.status)
        assertNull(v.state.value.waitingFor)
    }

    @Test fun reportsDeclinedPairing() = runTest {
        val v = vm()
        v.claim("ABCD2345")
        runCurrent()
        repo.list.value = listOf(repo.claimResult.copy(status = PairingStatus.REJECTED))
        advanceTimeBy(1_500)
        runCurrent()
        assertTrue(v.state.value.error!!.contains("declined"))
        assertNull(v.state.value.approved)
    }

    @Test fun serverErrorsAreShown() = runTest {
        repo.claimError = ApiException.NotFound("pairing code not found")
        val v = vm()
        v.claim("ABCD2345")
        advanceUntilIdle()
        assertEquals("pairing code not found", v.state.value.error)
        assertFalse(v.state.value.claiming)
    }

    @Test fun qrFromAnotherRelayServerIsRefused() = runTest {
        val v = vm()
        v.onScanned("droidlink://pair?code=ABCD2345&server=https%3A%2F%2Fevil.example.com")
        advanceUntilIdle()
        assertNull(repo.claimedCode)
        assertTrue(v.state.value.error!!.contains("different relay"))
    }

    @Test fun qrFromTheSameServerIsClaimed() = runTest {
        val v = vm()
        v.onScanned("droidlink://pair?code=ABCD2345&server=https%3A%2F%2Frelay.example.com")
        runCurrent()
        assertEquals("ABCD2345", repo.claimedCode)
    }

    @Test fun generatesCodeAndLink() = runTest {
        val v = vm()
        v.generate()
        runCurrent()
        assertEquals("ABCD2345", v.state.value.code?.code)
        assertTrue(v.state.value.link!!.startsWith("droidlink://pair?code=ABCD2345&server="))
        assertTrue(v.state.value.secondsLeft > 0)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val pairing = FakePairingRepository().also { it.list.value = listOf(pairedDevice("p1", scopes = setOf(Scope.VIEW, Scope.CONTROL))) }
    private val sessions = FakeSessionRepository()
    private val log = FakeSecurityLog()

    @Test fun preselectsEverythingTheOwnerAllowed() = runTest {
        val v = ConnectViewModel(pairing, RequestSessionUseCase(sessions, log), sessions, backgroundScope, "p1", 500)
        advanceUntilIdle()
        assertEquals(setOf(Scope.VIEW, Scope.CONTROL), v.state.value.selected)
        assertEquals("Living-room tablet", v.state.value.device?.peerName)
    }

    @Test fun unknownOrRevokedPairingIsReported() = runTest {
        pairing.list.value = listOf(pairedDevice("p1", status = PairingStatus.REVOKED))
        val v = ConnectViewModel(pairing, RequestSessionUseCase(sessions, log), sessions, backgroundScope, "p1", 500)
        advanceUntilIdle()
        assertTrue(v.state.value.notFound)
    }

    @Test fun requestWaitsForApprovalThenSignalsReady() = runTest {
        val v = ConnectViewModel(pairing, RequestSessionUseCase(sessions, log), sessions, backgroundScope, "p1", 500)
        runCurrent()
        v.request()
        runCurrent()
        assertNotNull(v.state.value.waiting)
        sessions.current = remoteSession(SessionStatus.ACTIVE)
        advanceTimeBy(600)
        runCurrent()
        assertEquals("s1", v.state.value.readySessionId)
        assertNull(v.state.value.waiting)
    }

    @Test fun declinedRequestShowsMessage() = runTest {
        val v = ConnectViewModel(pairing, RequestSessionUseCase(sessions, log), sessions, backgroundScope, "p1", 500)
        runCurrent()
        v.request()
        runCurrent()
        sessions.current = remoteSession(SessionStatus.REJECTED)
        advanceTimeBy(600)
        runCurrent()
        assertTrue(v.state.value.error!!.contains("declined"))
        assertNull(v.state.value.readySessionId)
    }

    @Test fun cannotRequestWithNoPermissionsSelected() = runTest {
        val v = ConnectViewModel(pairing, RequestSessionUseCase(sessions, log), sessions, backgroundScope, "p1", 500)
        runCurrent()
        v.toggle(Scope.VIEW); v.toggle(Scope.CONTROL)
        v.request()
        runCurrent()
        assertNull(v.state.value.waiting)
    }

    @Test fun serverRefusalIsShown() = runTest {
        sessions.requestError = ApiException.Conflict("a session is already pending or active")
        val v = ConnectViewModel(pairing, RequestSessionUseCase(sessions, log), sessions, backgroundScope, "p1", 500)
        runCurrent()
        v.request()
        runCurrent()
        assertEquals("a session is already pending or active", v.state.value.error)
    }

    @Test fun cancellingWithdrawsTheRequest() = runTest {
        val v = ConnectViewModel(pairing, RequestSessionUseCase(sessions, log), sessions, backgroundScope, "p1", 500)
        runCurrent()
        v.request()
        runCurrent()
        v.cancel()
        runCurrent()
        assertEquals(listOf("s1"), sessions.ended)
        assertNull(v.state.value.waiting)
    }
}
