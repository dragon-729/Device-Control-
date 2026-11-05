package com.droidlink.network

import com.droidlink.domain.model.AppEntry
import com.droidlink.domain.model.DeviceInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer

/** JSON used for REST bodies and WebSocket control messages. */
val ProtocolJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    isLenient = false
}

/** Message type tags (must match backend/app/services/relay_policy.py). */
object MsgType {
    const val TAP = "tap"
    const val DOUBLE_TAP = "dtap"
    const val LONG_PRESS = "long"
    const val SWIPE = "swipe"
    const val SCROLL = "scroll"
    const val NAV = "nav"
    const val TEXT = "text"
    const val KEY = "key"
    const val LAUNCH = "launch"
    const val APPS_REQ = "apps_req"
    const val APPS = "apps"
    const val QUALITY = "quality"
    const val INFO_REQ = "info_req"
    const val INFO = "info"
    const val FILE_OFFER = "file_offer"
    const val FILE_ACCEPT = "file_accept"
    const val FILE_REJECT = "file_reject"
    const val FILE_DONE = "file_done"
    const val FILE_CANCEL = "file_cancel"
    const val ACK = "ack"
    const val ERROR = "error"
    const val TERMINATE = "terminate"
    const val PEER = "peer"
}

object NavAction {
    const val BACK = "back"
    const val HOME = "home"
    const val RECENTS = "recents"
    const val NOTIFICATIONS = "notifications"
    const val QUICK_SETTINGS = "quick_settings"
}

object KeyAction {
    const val BACKSPACE = "backspace"
    const val ENTER = "enter"
    const val SELECT_ALL = "select_all"
    const val COPY = "copy"
    const val PASTE = "paste"
    const val CUT = "cut"
    const val NEXT_FIELD = "next_field"
    const val PREV_FIELD = "prev_field"
}

/** One flat message type keeps the wire format simple; unused fields are omitted from the JSON. */
@Serializable
data class RemoteMessage(
    val t: String,
    val x: Float? = null,
    val y: Float? = null,
    val x2: Float? = null,
    val y2: Float? = null,
    val d: Long? = null,
    val a: String? = null,
    val s: String? = null,
    val k: String? = null,
    val pkg: String? = null,
    val id: String? = null,
    val name: String? = null,
    val size: Long? = null,
    val fps: Int? = null,
    val q: Int? = null,
    val scale: Int? = null,
    val msg: String? = null,
    val state: String? = null,
    val apps: List<AppEntry>? = null,
    val info: DeviceInfo? = null,
)

fun RemoteMessage.encode(): String = ProtocolJson.encodeToString(RemoteMessage.serializer(), this)

fun decodeMessage(raw: String): RemoteMessage? = try {
    ProtocolJson.decodeFromString(RemoteMessage.serializer(), raw)
} catch (e: Exception) {
    null
}

/** Binary frames: first byte selects the kind (relay checks it for authorisation). */
object BinaryFrames {
    const val KIND_SCREEN: Byte = 0x01
    const val KIND_FILE_CHUNK: Byte = 0x02
    const val SCREEN_HEADER = 9
    const val MAX_CHUNK = 48 * 1024

    class ScreenFrame(val width: Int, val height: Int, val jpeg: ByteArray)
    class FileChunk(val transferId: String, val data: ByteArray)

    fun encodeScreen(width: Int, height: Int, jpeg: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(SCREEN_HEADER + jpeg.size)
        buf.put(KIND_SCREEN).putInt(width).putInt(height).put(jpeg)
        return buf.array()
    }

    fun decodeScreen(bytes: ByteArray): ScreenFrame? {
        if (bytes.size <= SCREEN_HEADER || bytes[0] != KIND_SCREEN) return null
        val buf = ByteBuffer.wrap(bytes)
        buf.get()
        val w = buf.int
        val h = buf.int
        if (w <= 0 || h <= 0 || w > 16384 || h > 16384) return null
        return ScreenFrame(w, h, bytes.copyOfRange(SCREEN_HEADER, bytes.size))
    }

    fun encodeChunk(transferId: String, data: ByteArray, length: Int = data.size): ByteArray {
        val id = transferId.toByteArray(Charsets.UTF_8)
        require(id.size in 1..64) { "transfer id length" }
        val out = ByteArray(2 + id.size + length)
        out[0] = KIND_FILE_CHUNK
        out[1] = id.size.toByte()
        System.arraycopy(id, 0, out, 2, id.size)
        System.arraycopy(data, 0, out, 2 + id.size, length)
        return out
    }

    fun decodeChunk(bytes: ByteArray): FileChunk? {
        if (bytes.size < 3 || bytes[0] != KIND_FILE_CHUNK) return null
        val idLen = bytes[1].toInt() and 0xFF
        if (idLen == 0 || bytes.size < 2 + idLen) return null
        val id = String(bytes, 2, idLen, Charsets.UTF_8)
        return FileChunk(id, bytes.copyOfRange(2 + idLen, bytes.size))
    }
}
