package com.formulatracker.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BasicAuthorizationTest {
    @Test
    fun aBlankLoginSendsNoHeader() {
        assertNull(basicAuthorization("", "secret"))
        assertNull(basicAuthorization("app", " "))
    }

    @Test
    fun aLoginBecomesABasicHeader() {
        assertEquals("Basic YXBwOnNlY3JldA==", basicAuthorization("app", "secret"))
    }
}
