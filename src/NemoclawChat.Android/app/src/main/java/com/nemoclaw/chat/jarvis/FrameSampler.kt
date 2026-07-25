package com.nemoclaw.chat.jarvis

internal data class SampledFrame(
    val jpeg: ByteArray,
    val capturedAtMillis: Long,
    val signature: Long
)

internal class SceneChangeDetector(
    private val threshold: Double = 0.085
) {
    fun changed(previous: Long?, current: Long): Boolean {
        if (previous == null) return true
        val differingBits = java.lang.Long.bitCount(previous xor current)
        return differingBits / 64.0 >= threshold
    }
}

internal class FrameSampler(
    private val minimumIntervalMillis: Long = 900,
    private val maximumIntervalMillis: Long = 4_000,
    private val sceneChangeDetector: SceneChangeDetector = SceneChangeDetector()
) {
    private var lastAcceptedAt = 0L
    private var lastSignature: Long? = null

    @Synchronized
    fun shouldAccept(capturedAtMillis: Long, signature: Long, force: Boolean = false): Boolean {
        val elapsed = capturedAtMillis - lastAcceptedAt
        if (!force && elapsed < minimumIntervalMillis) return false
        val accept = force || elapsed >= maximumIntervalMillis || sceneChangeDetector.changed(lastSignature, signature)
        if (accept) {
            lastAcceptedAt = capturedAtMillis
            lastSignature = signature
        }
        return accept
    }

    @Synchronized
    fun reset() {
        lastAcceptedAt = 0L
        lastSignature = null
    }
}

internal class RollingFrameBuffer(private val capacity: Int = 3) {
    private val items = ArrayDeque<SampledFrame>()

    @Synchronized
    fun add(frame: SampledFrame) {
        items.addLast(frame)
        while (items.size > capacity.coerceAtLeast(1)) items.removeFirst()
    }

    @Synchronized
    fun latest(): SampledFrame? = items.lastOrNull()

    @Synchronized
    fun clear() = items.clear()
}

internal fun perceptualSignature(bytes: ByteArray): Long {
    if (bytes.isEmpty()) return 0L
    val buckets = LongArray(64)
    for (index in bytes.indices) {
        buckets[index and 63] += bytes[index].toInt() and 0xff
    }
    val average = buckets.average()
    var signature = 0L
    buckets.forEachIndexed { index, value ->
        if (value >= average) signature = signature or (1L shl index)
    }
    return signature
}
