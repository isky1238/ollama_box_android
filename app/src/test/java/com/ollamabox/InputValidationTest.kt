package com.ollamabox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputValidationTest {
    @Test
    fun sanitizesProviderFileNames() {
        assertEquals("model.gguf", InputValidation.sanitizeModelName("../../model.gguf"))
        assertEquals("bad_name.gguf", InputValidation.sanitizeModelName("bad:name.gguf"))
    }

    @Test
    fun validatesNativeSettingsRanges() {
        assertTrue(InputValidation.validCtxSize(2048))
        assertFalse(InputValidation.validCtxSize(0))
        assertTrue(InputValidation.validThreadCount(8))
        assertFalse(InputValidation.validThreadCount(65))
    }
}
