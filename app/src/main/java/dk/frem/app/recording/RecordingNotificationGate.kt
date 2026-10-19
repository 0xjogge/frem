package dk.frem.app.recording

/** Use monotonic time; only a session/state change may bypass the regular update interval. */
internal class RecordingNotificationGate {
    private var lastPostedAt: Long? = null
    private var lastSessionId: String? = null
    private var lastState: RecordingState? = null

    fun due(sessionId: String?, state: RecordingState?, nowMillis: Long): Boolean {
        val previous = lastPostedAt ?: return true
        return sessionId != lastSessionId || state != lastState || nowMillis - previous >= 5_000
    }

    fun posted(sessionId: String?, state: RecordingState?, nowMillis: Long) {
        lastSessionId = sessionId
        lastState = state
        lastPostedAt = nowMillis
    }
}
