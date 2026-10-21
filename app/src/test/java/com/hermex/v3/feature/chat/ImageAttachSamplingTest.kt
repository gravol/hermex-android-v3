package com.hermex.v3.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sampling math for the image-attach decode path.
 *
 * The attach bug this accompanies was NOT in this math: downscaleAndDecode gated its
 * early return on the RESULT of an inJustDecodeBounds probe, which BitmapFactory always
 * answers with null, so every gallery/camera pick failed with "Couldn't read image".
 * These tests lock the part that is expressible without a device (the sample-size ladder)
 * so a future edit cannot silently upscale past the limit. Device proof for the decode
 * itself lives in the QA note (BitmapFactory cannot run on the JVM).
 */
class ImageAttachSamplingTest {

    @Test
    fun `small images are not subsampled`() {
        assertEquals(1, decodeSampleSize(800, 600, 1600))
        assertEquals(1, decodeSampleSize(1600, 1600, 1600))
    }

    @Test
    fun `large images step down by powers of two until under the limit`() {
        assertEquals(2, decodeSampleSize(3200, 2400, 1600))
        assertEquals(4, decodeSampleSize(4032, 3024, 1600))   // typical phone camera still
        assertEquals(8, decodeSampleSize(9000, 9000, 1600))
    }

    @Test
    fun `one oversized dimension still forces sampling`() {
        assertEquals(2, decodeSampleSize(1200, 3200, 1600))
    }

    @Test
    fun `result is always a power of two that honours the limit`() {
        for (w in intArrayOf(1, 320, 1600, 1601, 4032, 12000)) {
            for (h in intArrayOf(1, 240, 1600, 4032, 12000)) {
                val s = decodeSampleSize(w, h, 1600)
                assertTrue("sample must be >= 1", s >= 1)
                assertTrue("width $w overshot with sample $s", w / s <= 1600)
                assertTrue("height $h overshot with sample $s", h / s <= 1600)
                // minimal: halving the sample would overshoot
                if (s > 1) assertTrue(w / (s / 2) > 1600 || h / (s / 2) > 1600)
            }
        }
    }
}
