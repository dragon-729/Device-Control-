package com.droidlink.services

/** Pure helpers mapping normalised remote coordinates to this device's pixels. */
object Geometry {
    /** 0..1 -> 0..(size-1), clamped (a malicious or buggy peer cannot target off-screen points). */
    fun toPixel(normalized: Float, size: Int): Float {
        if (normalized.isNaN()) return 0f
        return normalized.coerceIn(0f, 1f) * (size - 1).coerceAtLeast(0)
    }

    /**
     * Finger path for a scroll. "down" reveals content further down the page, so the finger moves up.
     * Returns [x1, y1, x2, y2] in pixels or null for an unknown direction.
     */
    fun scrollPath(direction: String, width: Int, height: Int): FloatArray? {
        val cx = width / 2f
        val cy = height / 2f
        val dy = height * 0.30f
        val dx = width * 0.30f
        return when (direction) {
            "down" -> floatArrayOf(cx, cy + dy, cx, cy - dy)
            "up" -> floatArrayOf(cx, cy - dy, cx, cy + dy)
            "right" -> floatArrayOf(cx + dx, cy, cx - dx, cy)
            "left" -> floatArrayOf(cx - dx, cy, cx + dx, cy)
            else -> null
        }
    }

    fun clampDuration(requested: Long?, default: Long, min: Long = 50, max: Long = 3000): Long =
        (requested ?: default).coerceIn(min, max)
}

/** Pure text-editing maths used by the accessibility text input (testable without Android). */
object TextEditing {
    data class Edit(val text: String, val cursor: Int)

    private fun range(length: Int, selStart: Int, selEnd: Int): Pair<Int, Int> {
        val a = if (selStart in 0..length) selStart else length
        val b = if (selEnd in 0..length) selEnd else length
        return minOf(a, b) to maxOf(a, b)
    }

    fun insert(current: String, selStart: Int, selEnd: Int, inserted: String): Edit {
        val (s, e) = range(current.length, selStart, selEnd)
        return Edit(current.substring(0, s) + inserted + current.substring(e), s + inserted.length)
    }

    fun backspace(current: String, selStart: Int, selEnd: Int): Edit {
        val (s, e) = range(current.length, selStart, selEnd)
        return when {
            e > s -> Edit(current.substring(0, s) + current.substring(e), s)
            s > 0 -> Edit(current.substring(0, s - 1) + current.substring(e), s - 1)
            else -> Edit(current, 0)
        }
    }
}
