package com.droidlink.network

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Minimal duplex channel used by file transfer & routers (lets tests substitute a fake). */
interface RemoteChannel {
    fun send(message: RemoteMessage): Boolean
    fun sendBinary(bytes: ByteArray): Boolean
    /** Bytes queued but not yet written to the network (used for back-pressure and adaptive quality). */
    fun backlog(): Long
}

/**
 * WebSocket to the relay for one approved session.
 * The access token travels in the Authorization header (never in the URL, so it is not logged).
 * Events are delivered through an unbounded channel, so no control message is ever dropped.
 */
class SessionSocket(private val http: OkHttpClient) : RemoteChannel {
    sealed interface Event {
        data object Open : Event
        data class Text(val raw: String, val message: RemoteMessage?) : Event
        class Binary(val bytes: ByteArray) : Event
        data class Closed(val code: Int, val reason: String) : Event
        data class Failure(val error: Throwable) : Event
    }

    private val channel = Channel<Event>(Channel.UNLIMITED)
    val events: Flow<Event> = channel.receiveAsFlow()

    @Volatile
    private var socket: WebSocket? = null

    fun connect(serverUrl: String, sessionId: String, accessToken: String) {
        val request = Request.Builder()
            .url(serverUrl.trimEnd('/') + "/api/v1/ws/sessions/$sessionId")
            .header("Authorization", "Bearer $accessToken")
            .build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                channel.trySend(Event.Open)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                channel.trySend(Event.Text(text, decodeMessage(text)))
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                channel.trySend(Event.Binary(bytes.toByteArray()))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                channel.trySend(Event.Closed(code, reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                channel.trySend(Event.Failure(t))
            }
        })
    }

    override fun send(message: RemoteMessage): Boolean = socket?.send(message.encode()) ?: false

    override fun sendBinary(bytes: ByteArray): Boolean = socket?.send(bytes.toByteString()) ?: false

    override fun backlog(): Long = socket?.queueSize() ?: 0L

    fun close(code: Int = 1000, reason: String = "bye") {
        socket?.close(code, reason)
        socket = null
        channel.close()
    }
}
