package com.nemoclaw.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loader bitmap centralizzato: chiavi, sampling, skip frame identici,
 * politica LRU (tramite SimpleLruCache pura, stessa eviction della bitmapCache).
 */
class BitmapImageLoaderTest {

    @Test
    fun cacheKeyVariesByWidth() {
        val a = BitmapImageLoader.cacheKey("screen", 480)
        val b = BitmapImageLoader.cacheKey("screen", 960)
        assertNotEquals(a, b)
    }

    @Test
    fun cacheKeyBytesIncludesSizeAndWidth() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val a = BitmapImageLoader.cacheKey(bytes, 480)
        val b = BitmapImageLoader.cacheKey(bytes, 960)
        val c = BitmapImageLoader.cacheKey(byteArrayOf(1, 2, 3, 5), 480)
        assertNotEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun simpleLruHitAndMiss() {
        val cache = BitmapImageLoader.SimpleLruCache<String, Int>(2)
        // miss su cache vuota
        assertEquals(null, cache.cachedGet("k1"))
        cache.cachedPut("k1", 1)
        // hit dopo put
        assertEquals(1, cache.cachedGet("k1"))
        // miss su chiave assente
        assertEquals(null, cache.cachedGet("missing"))
    }

    @Test
    fun simpleLruEvictsEldest() {
        val cache = BitmapImageLoader.SimpleLruCache<String, Int>(2)
        cache.cachedPut("a", 1)
        cache.cachedPut("b", 2)
        // tocca "a" cosi' "b" diventa eldest? No: access-order, get(a) rende "b" eldest.
        assertEquals(1, cache.cachedGet("a"))
        cache.cachedPut("c", 3)
        // "b" deve essere stato evicted, "a" e "c" restano (hit).
        assertEquals(null, cache.cachedGet("b"))
        assertEquals(1, cache.cachedGet("a"))
        assertEquals(3, cache.cachedGet("c"))
    }

    @Test
    fun skipIdenticalFrames() {
        val first = byteArrayOf(10, 20, 30, 40)
        val same = byteArrayOf(10, 20, 30, 40)
        val different = byteArrayOf(10, 20, 30, 41)
        val shorter = byteArrayOf(10, 20, 30)

        assertTrue(BitmapImageLoader.isSameFrame(first, same))
        assertFalse(BitmapImageLoader.isSameFrame(first, different))
        assertFalse(BitmapImageLoader.isSameFrame(first, shorter))
        assertFalse(BitmapImageLoader.isSameFrame(null, same))
        assertFalse(BitmapImageLoader.isSameFrame(first, null))
    }

    @Test
    fun shouldSkipFrameUsesSignature() {
        val bytes = byteArrayOf(1, 2, 3)
        val sig = BitmapImageLoader.frameSignature(bytes)
        assertTrue(BitmapImageLoader.shouldSkipFrame(sig, byteArrayOf(1, 2, 3)))
        assertFalse(BitmapImageLoader.shouldSkipFrame(sig, byteArrayOf(1, 2, 4)))
        assertFalse(BitmapImageLoader.shouldSkipFrame(null, bytes))
        assertFalse(BitmapImageLoader.shouldSkipFrame(sig, null))
    }

    @Test
    fun samplingReducesLargeImage() {
        // 2000px con richiesta 500 -> sample >= 2 e dimensioni stimate <= richiesta (larghezza).
        val sample = BitmapImageLoader.calculateInSampleSize(2000, 1500, 500)
        assertTrue(sample >= 2)
        val (w, h) = BitmapImageLoader.sampledDimensions(2000, 1500, sample)
        assertTrue(w <= 1000)
        assertTrue(h <= 1500)
        assertTrue(w < 2000)
    }

    @Test
    fun samplingKeepsSmallImage() {
        assertEquals(1, BitmapImageLoader.calculateInSampleSize(400, 300, 480))
    }

    @Test
    fun samplingClampsToMaxDimension() {
        val sample = BitmapImageLoader.calculateInSampleSize(5000, 4000, 960)
        val (w, h) = BitmapImageLoader.sampledDimensions(5000, 4000, sample)
        assertTrue(w <= BitmapImageLoader.MAX_BITMAP_DIMENSION)
        assertTrue(h <= BitmapImageLoader.MAX_BITMAP_DIMENSION)
    }

    @Test
    fun shimmerGateOnlyWhenLoadingAndVisible() {
        assertTrue(shouldShowShimmer(isLoadingOrStreaming = true, isVisible = true))
        assertFalse(shouldShowShimmer(isLoadingOrStreaming = true, isVisible = false))
        assertFalse(shouldShowShimmer(isLoadingOrStreaming = false, isVisible = true))
        assertFalse(shouldShowShimmer(isLoadingOrStreaming = false, isVisible = false))
    }

    @Test
    fun voiceParticleBudgetIsReduced() {
        assertEquals(220, VoiceParticleCount)
    }
}
