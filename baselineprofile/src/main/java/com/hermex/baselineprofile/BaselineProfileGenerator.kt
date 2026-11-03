package com.hermex.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the startup hot paths into app/src/release/generated/baselineProfilers so ART
 * pre-compiles them at install time. Deliberately launch-focused: startup is where a
 * baseline profile pays for itself; deeper journeys re-warm on their own quickly enough.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() = baselineProfileRule.collect(
        packageName = "com.hermex.v3",
        // The default is ~50 iterations; 10 keeps this an afternoon-tool run rather than
        // a coffee-break one, and the profile merges across iterations anyway.
        maxIterations = 10,
    ) {
        startActivityAndWait()
        // Touch the home surface so Compose first-composition code lands in the profile.
        device.wait(Until.findObject(By.text("New session")), 10_000)
    }
}
