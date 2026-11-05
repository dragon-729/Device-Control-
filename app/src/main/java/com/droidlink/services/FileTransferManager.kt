package com.droidlink.services

import com.droidlink.domain.model.TransferDirection
import com.droidlink.domain.model.TransferRecord
import com.droidlink.domain.model.TransferStatus
import com.droidlink.network.BinaryFrames
import com.droidlink.network.MsgType
import com.droidlink.network.RemoteChannel
import com.droidlink.network.RemoteMessage
import com.droidlink.security.InputValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Storage abstraction over the Storage Access Framework; handles are content:// URIs as strings. */
interface FileIo {
    fun displayName(handle: String): String
    fun size(handle: String): Long
    fun openInput(handle: String): InputStream
    fun openOutput(handle: String): OutputStream
    fun delete(handle: String)
}

data class IncomingOffer(val id: String, val fileName: String, val size: Long)

/**
 * Symmetric file transfer over an authorised session channel (either side can send or receive).
 *
 *  - The receiver must explicitly accept each offer and chooses the destination with the system file picker.
 *  - Data is sent in 48 KB binary chunks with back-pressure, hashed with SHA-256 on both ends and verified.
 *  - Either side can cancel at any time; interrupted transfers are marked FAILED and partial files deleted.
 */
class FileTransferManager(
    private val sessionId: String,
    private val channel: RemoteChannel,
    private val io: FileIo,
    private val scope: CoroutineScope,
    private val isAllowed: () -> Boolean,
    private val onPersist: (TransferRecord) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val idFactory: () -> String = { UUID.randomUUID().toString().replace("-", "").take(16) },
) {
    private class Outgoing(val handle: String, var record: TransferRecord) {
        val accepted = CompletableDeferred<Boolean>()
        val result = CompletableDeferred<String>() // "ok" or an error code
        var job: Job? = null
    }

    private class Incoming(var record: TransferRecord) {
        var sink: OutputStream? = null
        var handle: String? = null
        var received = 0L
        val digest: MessageDigest = MessageDigest.getInstance("SHA-256")
        var lastPublish = 0L
    }

    private val outgoing = ConcurrentHashMap<String, Outgoing>()
    private val incoming = ConcurrentHashMap<String, Incoming>()

    private val _live = MutableStateFlow<Map<String, TransferRecord>>(emptyMap())
    /** Live progress of every transfer in this session (newest first when sorted by startedAt). */
    val live: StateFlow<Map<String, TransferRecord>> = _live.asStateFlow()

    private val _offers = MutableStateFlow<List<IncomingOffer>>(emptyList())
    /** Offers waiting for the owner to accept or decline. */
    val offers: StateFlow<List<IncomingOffer>> = _offers.asStateFlow()

    // ---- sending ----------------------------------------------------------------------------------
    /** Starts sending the file behind [handle]; returns the transfer id or null when not permitted. */
    fun send(handle: String): String? {
        if (!isAllowed()) return null
        val size = try { io.size(handle) } catch (e: Exception) { -1L }
        val name = InputValidator.sanitizeFileName(try { io.displayName(handle) } catch (e: Exception) { "file" })
        val id = idFactory()
        val record = newRecord(id, name, maxOf(size, 0), TransferDirection.UPLOAD, TransferStatus.PENDING)
        if (size < 0 || size > MAX_FILE_BYTES) {
            publish(record.copy(status = TransferStatus.FAILED, detail = "File is unreadable or larger than 2 GB", finishedAt = clock()), true)
            return id
        }
        val out = Outgoing(handle, record)
        outgoing[id] = out
        publish(record, true)
        out.job = scope.launch { runSend(id, out, name, size) }
        return id
    }

    private suspend fun runSend(id: String, out: Outgoing, name: String, size: Long) {
        try {
            channel.send(RemoteMessage(t = MsgType.FILE_OFFER, id = id, name = name, size = size))
            val accepted = withTimeoutOrNull(OFFER_TIMEOUT_MS) { out.accepted.await() }
            if (accepted != true) {
                val declined = accepted == false
                finish(out, if (declined) TransferStatus.REJECTED else TransferStatus.FAILED,
                    if (declined) "Declined by the other device" else "No response from the other device")
                return
            }
            publish(out.record.copy(status = TransferStatus.IN_PROGRESS), true)
            val digest = MessageDigest.getInstance("SHA-256")
            var sent = 0L
            withContext(ioDispatcher) {
                io.openInput(out.handle).use { input ->
                    val buffer = ByteArray(BinaryFrames.MAX_CHUNK)
                    var lastReport = 0L
                    while (isActive) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        while (channel.backlog() > MAX_BACKLOG) {
                            delay(15)
                            if (!isActive) return@use
                        }
                        digest.update(buffer, 0, n)
                        if (!channel.sendBinary(BinaryFrames.encodeChunk(id, buffer, n))) throw IOException("Connection lost")
                        sent += n
                        val now = clock()
                        if (now - lastReport >= PROGRESS_INTERVAL_MS) {
                            lastReport = now
                            publish(out.record.copy(transferredBytes = sent, status = TransferStatus.IN_PROGRESS), false)
                        }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            if (sent != size) throw IOException("File changed while sending")
            channel.send(RemoteMessage(t = MsgType.FILE_DONE, id = id, size = sent, s = hex(digest.digest())))
            when (withTimeoutOrNull(ACK_TIMEOUT_MS) { out.result.await() }) {
                "ok" -> finish(out, TransferStatus.COMPLETED, null, sent)
                null -> finish(out, TransferStatus.FAILED, "The other device did not confirm the transfer", sent)
                else -> finish(out, TransferStatus.FAILED, "Integrity check failed on the other device", sent)
            }
        } catch (e: CancellationException) {
            channel.send(RemoteMessage(t = MsgType.FILE_CANCEL, id = id))
            finish(out, TransferStatus.CANCELLED, "Cancelled")
            throw e
        } catch (e: IOException) {
            channel.send(RemoteMessage(t = MsgType.FILE_CANCEL, id = id))
            finish(out, TransferStatus.FAILED, e.message ?: "Transfer interrupted")
        } catch (e: SecurityException) {
            finish(out, TransferStatus.FAILED, "Access to the file was denied")
        } finally {
            outgoing.remove(id)
        }
    }

    private fun finish(out: Outgoing, status: TransferStatus, detail: String?, transferred: Long = out.record.transferredBytes) {
        publish(out.record.copy(status = status, detail = detail, transferredBytes = transferred, finishedAt = clock()), true)
    }

    // ---- receiving --------------------------------------------------------------------------------
    fun accept(id: String, destinationHandle: String): Boolean {
        val inc = incoming[id] ?: return false
        if (inc.sink != null || !isAllowed()) return false
        try {
            inc.sink = io.openOutput(destinationHandle)
        } catch (e: Exception) {
            reject(id)
            return false
        }
        inc.handle = destinationHandle
        _offers.update { list -> list.filterNot { it.id == id } }
        publish(inc.record.copy(status = TransferStatus.IN_PROGRESS), true)
        channel.send(RemoteMessage(t = MsgType.FILE_ACCEPT, id = id))
        return true
    }

    fun reject(id: String) {
        val inc = incoming.remove(id) ?: return
        _offers.update { list -> list.filterNot { it.id == id } }
        channel.send(RemoteMessage(t = MsgType.FILE_REJECT, id = id))
        publish(inc.record.copy(status = TransferStatus.REJECTED, detail = "Declined", finishedAt = clock()), true)
    }

    fun cancel(id: String) {
        outgoing[id]?.job?.cancel()
        val inc = incoming[id] ?: return
        channel.send(RemoteMessage(t = MsgType.FILE_CANCEL, id = id))
        abortIncoming(id, inc, TransferStatus.CANCELLED, "Cancelled")
    }

    // ---- inbound traffic --------------------------------------------------------------------------
    fun onMessage(msg: RemoteMessage) {
        val id = msg.id ?: return
        when (msg.t) {
            MsgType.FILE_OFFER -> onOffer(id, msg)
            MsgType.FILE_ACCEPT -> outgoing[id]?.accepted?.complete(true)
            MsgType.FILE_REJECT -> outgoing[id]?.accepted?.complete(false)
            MsgType.FILE_DONE -> onDone(id, msg)
            MsgType.FILE_CANCEL -> {
                outgoing[id]?.job?.cancel()
                incoming[id]?.let { abortIncoming(id, it, TransferStatus.CANCELLED, "Cancelled by the other device") }
            }
            MsgType.ACK -> outgoing[id]?.result?.complete("ok")
            MsgType.ERROR -> outgoing[id]?.result?.complete(msg.msg ?: "error")
        }
    }

    private fun onOffer(id: String, msg: RemoteMessage) {
        val size = msg.size ?: -1
        if (!isAllowed() || incoming.containsKey(id) || size < 0 || size > MAX_FILE_BYTES || incoming.size >= MAX_PENDING) {
            channel.send(RemoteMessage(t = MsgType.FILE_REJECT, id = id))
            return
        }
        val name = InputValidator.sanitizeFileName(msg.name.orEmpty())
        val record = newRecord(id, name, size, TransferDirection.DOWNLOAD, TransferStatus.PENDING)
        incoming[id] = Incoming(record)
        _offers.update { it + IncomingOffer(id, name, size) }
        publish(record, true)
    }

    fun onBinary(bytes: ByteArray) {
        val chunk = BinaryFrames.decodeChunk(bytes) ?: return
        val inc = incoming[chunk.transferId] ?: return
        val sink = inc.sink ?: return
        if (!isAllowed()) {
            abortIncoming(chunk.transferId, inc, TransferStatus.FAILED, "Session no longer authorised")
            return
        }
        if (inc.received + chunk.data.size > inc.record.totalBytes) {
            channel.send(RemoteMessage(t = MsgType.ERROR, id = chunk.transferId, msg = "size_exceeded"))
            abortIncoming(chunk.transferId, inc, TransferStatus.FAILED, "Sender sent more data than announced")
            return
        }
        try {
            sink.write(chunk.data)
        } catch (e: IOException) {
            channel.send(RemoteMessage(t = MsgType.FILE_CANCEL, id = chunk.transferId))
            abortIncoming(chunk.transferId, inc, TransferStatus.FAILED, "Could not write to the chosen location")
            return
        }
        inc.digest.update(chunk.data)
        inc.received += chunk.data.size
        val now = clock()
        if (now - inc.lastPublish >= PROGRESS_INTERVAL_MS) {
            inc.lastPublish = now
            publish(inc.record.copy(transferredBytes = inc.received, status = TransferStatus.IN_PROGRESS), false)
        }
    }

    private fun onDone(id: String, msg: RemoteMessage) {
        val inc = incoming.remove(id) ?: return
        val sink = inc.sink
        try { sink?.flush(); sink?.close() } catch (_: IOException) { }
        val ok = inc.received == inc.record.totalBytes && hex(inc.digest.digest()) == msg.s
        if (ok) {
            channel.send(RemoteMessage(t = MsgType.ACK, id = id, msg = "file_ok"))
            publish(inc.record.copy(transferredBytes = inc.received, status = TransferStatus.COMPLETED, finishedAt = clock()), true)
        } else {
            inc.handle?.let { runCatching { io.delete(it) } }
            channel.send(RemoteMessage(t = MsgType.ERROR, id = id, msg = "hash_mismatch"))
            publish(inc.record.copy(transferredBytes = inc.received, status = TransferStatus.FAILED, detail = "Integrity check failed - file deleted", finishedAt = clock()), true)
        }
    }

    private fun abortIncoming(id: String, inc: Incoming, status: TransferStatus, detail: String) {
        incoming.remove(id)
        _offers.update { list -> list.filterNot { it.id == id } }
        try { inc.sink?.close() } catch (_: IOException) { }
        inc.handle?.let { runCatching { io.delete(it) } }
        publish(inc.record.copy(transferredBytes = inc.received, status = status, detail = detail, finishedAt = clock()), true)
    }

    /** Called when the session ends or the socket drops: nothing may stay "in progress". */
    fun abortAll(reason: String) {
        outgoing.values.forEach { it.job?.cancel() }
        incoming.entries.toList().forEach { (id, inc) -> abortIncoming(id, inc, TransferStatus.FAILED, reason) }
    }

    // ---- helpers ----------------------------------------------------------------------------------
    private fun newRecord(id: String, name: String, size: Long, dir: TransferDirection, status: TransferStatus) =
        TransferRecord(id, sessionId, name, size, 0, dir, status, clock(), null, null)

    private fun publish(record: TransferRecord, persist: Boolean) {
        _live.update { it + (record.id to record) }
        outgoing[record.id]?.record = record
        incoming[record.id]?.record = record
        if (persist) onPersist(record)
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_BACKLOG = 1_000_000L
        const val MAX_PENDING = 8
        const val OFFER_TIMEOUT_MS = 90_000L
        const val ACK_TIMEOUT_MS = 30_000L
        const val PROGRESS_INTERVAL_MS = 200L
    }
}
