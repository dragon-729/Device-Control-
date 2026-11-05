package com.droidlink.services

/**
 * Chooses the effective frame rate / JPEG quality from how much data is still queued on the socket.
 * Pure logic (no Android classes) so it can be unit-tested.
 */
class AdaptiveStreamController(requestedFps: Int = 12, requestedQuality: Int = 60) {
    var requestedFps: Int = requestedFps.coerceIn(MIN_FPS, MAX_FPS)
        private set
    var requestedQuality: Int = requestedQuality.coerceIn(MIN_QUALITY, MAX_QUALITY)
        private set

    var fps: Int = this.requestedFps
        private set
    var quality: Int = this.requestedQuality
        private set

    private var calmFrames = 0

    fun configure(fps: Int, quality: Int) {
        requestedFps = fps.coerceIn(MIN_FPS, MAX_FPS)
        requestedQuality = quality.coerceIn(MIN_QUALITY, MAX_QUALITY)
        this.fps = this.fps.coerceAtMost(requestedFps)
        this.quality = this.quality.coerceAtMost(requestedQuality)
    }

    /** True when the network cannot keep up and the next frame should be skipped entirely. */
    fun shouldDrop(backlogBytes: Long): Boolean = backlogBytes > DROP_BACKLOG

    fun frameIntervalMs(): Long = 1000L / fps

    /** Call once per captured frame with the current socket backlog. */
    fun onBacklog(backlogBytes: Long) {
        when {
            backlogBytes > HIGH_BACKLOG -> {
                calmFrames = 0
                quality = (quality - 8).coerceAtLeast(MIN_QUALITY)
                fps = (fps - 2).coerceAtLeast(MIN_FPS)
            }
            backlogBytes < LOW_BACKLOG -> {
                calmFrames++
                if (calmFrames >= CALM_FRAMES_TO_RAISE) {
                    calmFrames = 0
                    quality = (quality + 4).coerceAtMost(requestedQuality)
                    fps = (fps + 1).coerceAtMost(requestedFps)
                }
            }
            else -> calmFrames = 0
        }
    }

    companion object {
        const val MIN_FPS = 2
        const val MAX_FPS = 30
        const val MIN_QUALITY = 20
        const val MAX_QUALITY = 95
        const val LOW_BACKLOG = 32L * 1024
        const val HIGH_BACKLOG = 256L * 1024
        const val DROP_BACKLOG = 768L * 1024
        const val CALM_FRAMES_TO_RAISE = 15
    }
}
