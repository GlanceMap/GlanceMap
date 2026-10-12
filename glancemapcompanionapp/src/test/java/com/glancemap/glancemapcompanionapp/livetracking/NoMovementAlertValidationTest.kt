package com.glancemap.glancemapcompanionapp.livetracking

import com.glancemap.glancemapcompanionapp.testsupport.englishCompanionText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoMovementAlertValidationTest {
    @Test
    fun acceptsDisabledAndValuesAtLeastTenMinutes() {
        assertNull(validateNoMovementAlertMinutes(::englishCompanionText, "-1"))
        assertNull(validateNoMovementAlertMinutes(::englishCompanionText, "10"))
        assertNull(validateNoMovementAlertMinutes(::englishCompanionText, "60"))
    }

    @Test
    fun rejectsBlankInvalidAndTooSmallValues() {
        val expected = "No-movement alert must be at least 10 minutes, or disabled."

        assertEquals(expected, validateNoMovementAlertMinutes(::englishCompanionText, ""))
        assertEquals(expected, validateNoMovementAlertMinutes(::englishCompanionText, "invalid"))
        assertEquals(expected, validateNoMovementAlertMinutes(::englishCompanionText, "9"))
        assertEquals(expected, validateNoMovementAlertMinutes(::englishCompanionText, "-2"))
    }
}
