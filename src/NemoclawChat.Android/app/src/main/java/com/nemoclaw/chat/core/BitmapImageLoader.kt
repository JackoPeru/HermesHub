package com.nemoclaw.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import java.io.File

/**
 * Loader centralizzato per anteprime bitmap (screen frame, anteprime bot, thumbnail).
 *
 * - Cache LRU con chiave = sorgente + maxWidth, ~1/8 heap.
 * - NESSUN recycle esplicito in eviction: le bitmap potrebbero essere ancora
 *   mostrate da Compose (asImageBitmap); il recycle causerebbe crash
 *   "Canvas: trying to use a recycled bitmap".
 * - Decode con inJustDecodeBounds + inSampleSize (sampling potenza di 2).
 */
object BitmapImageLoader {
    internal const val DEFAULT_SCREEN_REQ_WIDTH = 960
    internal const val MAX_BITMAP_DIMENSION = 2048
    private const val MIN_REQ_WIDTH = 48

    private val maxCacheKb: Int =
        ((Runtime.getRuntime().maxMemory() / 1024) / 8)
            .toInt()
            .coerceIn(4 * 1024, 32 * 1024)

    private val bitmapCache = object : LruCache<String, Bitmap>(maxCacheKb) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return (value.byteCount / 1024).coerceAtLeast(1)
        }
        // Volutamente NESSUN recycle in entryRemoved: la bitmap evicted
        // potrebbe essere ancora referenziata dalla UI.
    }

    /**
     * Pressione memoria dal sistema (via Application.onTrimMemory): sgonfia
     * la cache invece di farsi killare con stream/coda non persistiti dentro.
     */
    fun onTrimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            bitmapCache.evictAll()
        } else if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            bitmapCache.trimToSize(maxCacheKb / 2)
        }
    }

    /** Firma leggera per skip re-decode di frame identici (hash + lunghezza). */
    data class FrameSignature(val length: Int, val hash: Int)

    fun frameSignature(bytes: ByteArray?): FrameSignature? {
        if (bytes == null || bytes.isEmpty()) return null
        return FrameSignature(bytes.size, bytes.contentHashCode())
    }

    /**
     * True se i due frame sono identici (stessa lunghezza + stesso hash +
     * contentEquals di conferma). Null -> false (non skippare: serve decode/clear).
     */
    fun isSameFrame(prev: ByteArray?, next: ByteArray?): Boolean {
        if (prev == null || next == null) return false
        if (prev.size != next.size) return false
        if (prev.contentHashCode() != next.contentHashCode()) return false
        return prev.contentEquals(next)
    }

    /** True se il nuovo frame puo' saltare il re-decode (identico al precedente). */
    fun shouldSkipFrame(last: FrameSignature?, bytes: ByteArray?): Boolean {
        if (last == null || bytes == null || bytes.isEmpty()) return false
        return frameSignature(bytes) == last
    }

    fun cacheKey(source: String, reqWidth: Int): String {
        val cap = reqWidth.coerceAtLeast(MIN_REQ_WIDTH)
        return "$source|$cap"
    }

    fun cacheKey(bytes: ByteArray, reqWidth: Int): String {
        val cap = reqWidth.coerceAtLeast(MIN_REQ_WIDTH)
        return "b:${bytes.size}:${bytes.contentHashCode()}|$cap"
    }

    /**
     * Sampling potenza di 2 (come da docs BitmapFactory):
     * dimezza finche' la larghezza stimata resta >= reqWidth, poi clamp
     * assoluto a MAX_BITMAP_DIMENSION. Riusa la logica esistente
     * (width/cap + clamp 2048 di loadRemoteBitmapAttempt/decodeAttachmentPreview).
     */
    fun calculateInSampleSize(outWidth: Int, outHeight: Int, reqWidth: Int): Int {
        if (outWidth <= 0 || outHeight <= 0) return 1
        val cap = reqWidth.coerceAtLeast(MIN_REQ_WIDTH)
        var sample = 1
        while ((outWidth / (sample * 2)) >= cap) {
            sample *= 2
        }
        while ((outWidth / sample) > MAX_BITMAP_DIMENSION ||
            (outHeight / sample) > MAX_BITMAP_DIMENSION
        ) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    /** Dimensioni stimate dopo il sampling (puro, testabile). */
    fun sampledDimensions(outWidth: Int, outHeight: Int, sample: Int): Pair<Int, Int> {
        val s = sample.coerceAtLeast(1)
        return (outWidth / s).coerceAtLeast(1) to (outHeight / s).coerceAtLeast(1)
    }

    fun decodeSampled(bytes: ByteArray, reqWidth: Int): Bitmap? {
        if (bytes.isEmpty()) return null
        val cap = reqWidth.coerceAtLeast(MIN_REQ_WIDTH)
        val key = cacheKey(bytes, cap)
        bitmapCache.get(key)?.let { cached ->
            if (!cached.isRecycled) return cached
        }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, cap)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: return null
            bitmapCache.put(key, decoded)
            decoded
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    fun decodeSampled(source: String, reqWidth: Int): Bitmap? {
        if (source.isBlank()) return null
        val cap = reqWidth.coerceAtLeast(MIN_REQ_WIDTH)
        // File locale?
        val file = File(source).takeIf { it.isFile }
        if (file != null) {
            val key = cacheKey(source, cap)
            bitmapCache.get(key)?.let { cached ->
                if (!cached.isRecycled) return cached
            }
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
                val sample = calculateInSampleSize(bounds.outWidth, bounds.outHeight, cap)
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = BitmapFactory.decodeFile(file.absolutePath, opts)
                    ?: return null
                bitmapCache.put(key, decoded)
                decoded
            } catch (_: Exception) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
        }
        // Data-URI base64 (stessa convenzione di decodeAttachmentPreview)?
        val payload = source.substringAfter(',', missingDelimiterValue = "")
        if (payload.isBlank()) return null
        val bytes = runCatching { Base64.decode(payload, Base64.DEFAULT) }.getOrNull()
            ?: return null
        return decodeSampled(bytes, cap)
    }

    /** Decode condiviso per screen frame (default 960 = larghezza view tipica). */
    fun decodeScreenFrame(bytes: ByteArray?, reqWidth: Int = DEFAULT_SCREEN_REQ_WIDTH): Bitmap? {
        if (bytes == null || bytes.isEmpty()) return null
        return decodeSampled(bytes, reqWidth)
    }

    /**
     * LRU generica pura (stessa politica di eviction access-order della bitmapCache),
     * testabile su JVM senza framework Android. La bitmapCache reale resta
     * android.util.LruCache; questa classe ne rispecchia hit/miss/eviction.
     */
    internal class SimpleLruCache<K, V>(private val maxEntries: Int) :
        LinkedHashMap<K, V>(maxEntries.coerceAtLeast(1), 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxEntries
        }

        fun cachedGet(key: K): V? = get(key)

        fun cachedPut(key: K, value: V) {
            put(key, value)
        }
    }
}
