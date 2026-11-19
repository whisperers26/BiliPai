package com.android.purebilibili.feature.plugin

/** Continuous buffering that counts as stuck during ordinary playback. */
internal const val STUCK_BUFFERING_MS = 1_000L

/** Continuous buffering that counts as stuck right after start-up, a seek or a source change. */
internal const val JUMP_STUCK_BUFFERING_MS = 2_500L

/** Short stalls in ordinary playback that trigger a race when they land inside the window. */
internal const val REBUFFER_TRIGGER_COUNT = 2
internal const val REBUFFER_WINDOW_MS = 15_000L

/** A stall that starts this soon after a seek or source change is the jump's, not the CDN's. */
private const val JUMP_GRACE_MS = 2_000L

/**
 * Turns the foreground player's buffering transitions into CDN race triggers, kept free of
 * Android so it can be tested. Feeding the same state twice is harmless, so the player listener
 * and the periodic tick can both report it.
 *
 * Only stalls in ordinary playback count. Start-up (before the first ready of a source), seeks,
 * skips, source changes and CDN switches always rebuffer, so a stall that starts within a short
 * grace after one of them is excused and only counts as stuck after a longer wait.
 */
internal class CdnRebufferTracker(private val clock: () -> Long) {
    private val stallsAtMs = ArrayDeque<Long>()
    private var readySinceSource = true
    private var jumpAtMs = Long.MIN_VALUE / 2
    private var bufferingSinceMs: Long? = null
    private var excused = false

    /** Increments with every new buffering episode, so a delayed stuck check can tell it is stale. */
    var episode: Int = 0
        private set

    /**
     * Reports whether the player is buffering while it wants to play, and whether it is ready.
     * Returns true when this call starts a stall that completes [REBUFFER_TRIGGER_COUNT] in
     * [REBUFFER_WINDOW_MS].
     */
    @Synchronized
    fun observe(buffering: Boolean, ready: Boolean): Boolean {
        val now = clock()
        if (ready) readySinceSource = true
        if (!buffering) {
            bufferingSinceMs = null
            return false
        }
        if (bufferingSinceMs != null) return false
        bufferingSinceMs = now
        episode++
        excused = !readySinceSource || now - jumpAtMs <= JUMP_GRACE_MS
        if (excused) return false
        stallsAtMs.addLast(now)
        while (stallsAtMs.isNotEmpty() && now - stallsAtMs.first() > REBUFFER_WINDOW_MS) {
            stallsAtMs.removeFirst()
        }
        return stallsAtMs.size >= REBUFFER_TRIGGER_COUNT
    }

    /** A seek, skip or CDN switch: the stall it causes, or the one in progress, is excused. */
    @Synchronized
    fun jumped() {
        jumpAtMs = clock()
        if (bufferingSinceMs != null) excused = true
    }

    /** New media or a new play URL set: nothing counts until it first gets ready. */
    @Synchronized
    fun newSource() {
        readySinceSource = false
        stallsAtMs.clear()
        jumped()
    }

    /** A race was started; the next trigger needs fresh stalls. */
    @Synchronized
    fun raced() = stallsAtMs.clear()

    @Synchronized
    fun stuckThresholdMs(): Long = if (excused) JUMP_STUCK_BUFFERING_MS else STUCK_BUFFERING_MS

    @Synchronized
    fun stuck(): Boolean {
        val since = bufferingSinceMs ?: return false
        return clock() - since >= stuckThresholdMs()
    }
}
