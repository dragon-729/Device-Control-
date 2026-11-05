package com.droidlink.services

import com.droidlink.domain.model.TransferRecord
import com.droidlink.domain.model.TransferStatus
import com.droidlink.network.RemoteChannel
import com.droidlink.network.RemoteMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Random

private class MemoryIo : FileIo {
    val sources = mutableMapOf<String, Pair<String, ByteArray>>()
    val outputs = mutableMapOf<String, ByteArrayOutputStream>()
    val deleted = mutableListOf<String>()
    override fun displayName(handle: String) = sources[handle]?.first ?: "unknown"
    override fun size(handle: String) = sources[handle]?.second?.size?.toLong() ?: -1L
    override fun openInput(handle: String): InputStream = ByteArrayInputStream(sources.getValue(handle).second)
    override fun openOutput(handle: String): OutputStream = ByteArrayOutputStream().also { outputs[handle] = it }
    override fun delete(handle: String) { deleted += handle; outputs.remove(handle) }
}

/** Directly connects two managers; hooks let tests corrupt or intercept traffic. */
private class LinkedChannel : RemoteChannel {
    lateinit var peer: FileTransferManager
    var corruptChunk: Int = -1
    var onChunk: (Int) -> Unit = {}
    var binaryCount = 0
    var backlogBytes = 0L
    override fun send(message: RemoteMessage): Boolean { peer.onMessage(message); return true }
    override fun sendBinary(bytes: ByteArray): Boolean {
        binaryCount++
        val data = bytes.copyOf()
        if (binaryCount == corruptChunk) data[data.size - 1] = (data[data.size - 1] + 1).toByte()
        peer.onBinary(data)
        onChunk(binaryCount)
        return true
    }
    override fun backlog() = backlogBytes
}

@OptIn(ExperimentalCoroutinesApi::class)
class FileTransferManagerTest {
    private class Side(scope: TestScope, val io: MemoryIo, val channel: LinkedChannel, allowed: () -> Boolean = { true }) {
        val records = mutableMapOf<String, TransferRecord>()
        val manager = FileTransferManager(
            sessionId = "s1", channel = channel, io = io, scope = scope.backgroundScope,
            isAllowed = allowed, onPersist = { records[it.id] = it },
            ioDispatcher = StandardTestDispatcher(scope.testScheduler),
        )
    }

    private fun TestScope.pair(senderAllowed: () -> Boolean = { true }, receiverAllowed: () -> Boolean = { true }): Pair<Side, Side> {
        val ca = LinkedChannel(); val cb = LinkedChannel()
        val a = Side(this, MemoryIo(), ca, senderAllowed)
        val b = Side(this, MemoryIo(), cb, receiverAllowed)
        ca.peer = b.manager // what A sends arrives at B and vice versa
        cb.peer = a.manager
        return a to b
    }

    private fun randomBytes(n: Int) = ByteArray(n).also { Random(42).nextBytes(it) }

    @Test fun transfersAFileIntactAndBothSidesReportCompletion() = runTest {
        val (a, b) = pair()
        val content = randomBytes(300_000)
        a.io.sources["src"] = "photo.jpg" to content

        val id = a.manager.send("src")!!
        runCurrent()
        assertEquals("photo.jpg", b.manager.offers.value.single().fileName)
        assertTrue(b.manager.accept(id, "dst"))
        advanceUntilIdle()

        assertArrayEquals(content, b.io.outputs.getValue("dst").toByteArray())
        assertEquals(TransferStatus.COMPLETED, a.records.getValue(id).status)
        assertEquals(TransferStatus.COMPLETED, b.records.getValue(id).status)
        assertEquals(content.size.toLong(), b.records.getValue(id).transferredBytes)
        assertTrue(b.manager.offers.value.isEmpty())
    }

    @Test fun declinedOfferEndsWithoutSendingData() = runTest {
        val (a, b) = pair()
        a.io.sources["src"] = "a.txt" to randomBytes(1000)
        val id = a.manager.send("src")!!
        runCurrent()
        b.manager.reject(id)
        advanceUntilIdle()
        assertEquals(TransferStatus.REJECTED, a.records.getValue(id).status)
        assertEquals(TransferStatus.REJECTED, b.records.getValue(id).status)
        assertEquals(0, a.channel.binaryCount)
    }

    @Test fun corruptedDataIsDetectedAndPartialFileDeleted() = runTest {
        val (a, b) = pair()
        a.io.sources["src"] = "doc.pdf" to randomBytes(200_000)
        a.channel.corruptChunk = 2
        val id = a.manager.send("src")!!
        runCurrent()
        b.manager.accept(id, "dst")
        advanceUntilIdle()
        assertEquals(TransferStatus.FAILED, b.records.getValue(id).status)
        assertEquals(TransferStatus.FAILED, a.records.getValue(id).status)
        assertTrue("corrupt file must be removed", b.io.deleted.contains("dst"))
    }

    @Test fun senderCanCancelMidTransfer() = runTest {
        val (a, b) = pair()
        a.io.sources["src"] = "big.bin" to randomBytes(500_000)
        lateinit var id: String
        a.channel.onChunk = { n -> if (n == 3) a.manager.cancel(id) }
        id = a.manager.send("src")!!
        runCurrent()
        b.manager.accept(id, "dst")
        advanceUntilIdle()
        assertEquals(TransferStatus.CANCELLED, a.records.getValue(id).status)
        assertEquals(TransferStatus.CANCELLED, b.records.getValue(id).status)
        assertTrue(b.io.deleted.contains("dst"))
        assertTrue("sending stopped early", a.channel.binaryCount < 10)
    }

    @Test fun receiverCanCancelMidTransfer() = runTest {
        val (a, b) = pair()
        a.io.sources["src"] = "big.bin" to randomBytes(500_000)
        lateinit var id: String
        a.channel.onChunk = { n -> if (n == 2) b.manager.cancel(id) }
        id = a.manager.send("src")!!
        runCurrent()
        b.manager.accept(id, "dst")
        advanceUntilIdle()
        assertEquals(TransferStatus.CANCELLED, b.records.getValue(id).status)
        assertEquals(TransferStatus.CANCELLED, a.records.getValue(id).status)
    }

    @Test fun connectionLossMarksTransfersFailedAndCleansUp() = runTest {
        val (a, b) = pair()
        a.io.sources["src"] = "big.bin" to randomBytes(500_000)
        lateinit var id: String
        a.channel.onChunk = { n -> if (n == 2) b.manager.abortAll("Connection lost") }
        id = a.manager.send("src")!!
        runCurrent()
        b.manager.accept(id, "dst")
        advanceUntilIdle()
        assertEquals(TransferStatus.FAILED, b.records.getValue(id).status)
        assertEquals("Connection lost", b.records.getValue(id).detail)
        assertTrue(b.io.deleted.contains("dst"))
    }

    @Test fun nothingIsSentWhenFilesAreNotAuthorised() = runTest {
        val (a, _) = pair(senderAllowed = { false })
        a.io.sources["src"] = "a.txt" to randomBytes(10)
        assertNull(a.manager.send("src"))
        assertEquals(0, a.channel.binaryCount)
    }

    @Test fun incomingOffersAreRefusedWhenNotAuthorised() = runTest {
        val (a, b) = pair(receiverAllowed = { false })
        a.io.sources["src"] = "a.txt" to randomBytes(10)
        val id = a.manager.send("src")!!
        advanceUntilIdle()
        assertTrue(b.manager.offers.value.isEmpty())
        assertEquals(TransferStatus.REJECTED, a.records.getValue(id).status)
    }

    @Test fun unreadableFileFailsImmediately() = runTest {
        val (a, _) = pair()
        val id = a.manager.send("missing")!!
        assertEquals(TransferStatus.FAILED, a.records.getValue(id).status)
    }

    @Test fun maliciousFileNamesAreSanitisedOnTheReceivingSide() = runTest {
        val (a, b) = pair()
        a.io.sources["src"] = "../../../etc/cron.d/evil" to randomBytes(10)
        a.manager.send("src")
        runCurrent()
        assertEquals("evil", b.manager.offers.value.single().fileName)
    }

    @Test fun senderThatSendsMoreThanAnnouncedIsStopped() = runTest {
        val (a, b) = pair()
        val id = "x1"
        b.manager.onMessage(RemoteMessage(t = "file_offer", id = id, name = "f.bin", size = 10))
        b.manager.accept(id, "dst")
        b.manager.onBinary(com.droidlink.network.BinaryFrames.encodeChunk(id, ByteArray(50)))
        assertEquals(TransferStatus.FAILED, b.records.getValue(id).status)
        assertFalse(b.io.outputs.containsKey("dst") && b.io.deleted.isEmpty())
    }
}
