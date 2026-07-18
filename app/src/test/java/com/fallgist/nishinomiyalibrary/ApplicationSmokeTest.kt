package com.fallgist.nishinomiyalibrary

import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicationSmokeTest {
    @Test
    fun applicationId_isConfiguredAsDesigned() {
        assertEquals("com.fallgist.nishinomiyalibrary", BuildConfig.APPLICATION_ID)
    }
}
