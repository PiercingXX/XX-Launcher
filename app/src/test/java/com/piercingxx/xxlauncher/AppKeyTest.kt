package com.piercingxx.xxlauncher

import com.piercingxx.xxlauncher.data.AppKey
import com.piercingxx.xxlauncher.data.rewriteEmbeddedUserToken
import com.piercingxx.xxlauncher.util.USER_MANAGED
import com.piercingxx.xxlauncher.util.USER_PERSONAL
import com.piercingxx.xxlauncher.util.userSerialToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppKeyTest {

    @Test
    fun twoPartKeyIsPackageAndUser() {
        val key = AppKey.parse("com.example.app|personal")
        assertEquals("com.example.app", key.packageName)
        assertNull(key.shortcutId)
        assertEquals(USER_PERSONAL, key.userToken)
        assertEquals("com.example.app|personal", key.encoded())
    }

    @Test
    fun threePartKeyKeepsTheShortcutId() {
        val key = AppKey.parse("com.example.app|new_note|managed")
        assertEquals("com.example.app", key.packageName)
        assertEquals("new_note", key.shortcutId)
        assertEquals(USER_MANAGED, key.userToken)
        assertEquals("com.example.app|new_note|managed", key.encoded())
    }

    @Test
    fun rewriteUserTokenRewritesTheLastSegmentOnly() {
        assertEquals(
            "com.example.app|u10",
            AppKey.rewriteUserToken("com.example.app|managed", USER_MANAGED, "u10"),
        )
        assertEquals(
            "com.example.app|new_note|u10",
            AppKey.rewriteUserToken("com.example.app|new_note|managed", USER_MANAGED, "u10"),
        )
        assertEquals(
            "com.example.app|personal",
            AppKey.rewriteUserToken("com.example.app|personal", USER_MANAGED, "u10"),
        )
    }

    @Test
    fun rewriteEmbeddedUserTokenHitsTheThirdField() {
        assertEquals(
            "pkg|act|u10",
            rewriteEmbeddedUserToken("pkg|act|managed", USER_MANAGED, "u10"),
        )
        assertEquals(
            "pkg|act|u10|sid",
            rewriteEmbeddedUserToken("pkg|act|managed|sid", USER_MANAGED, "u10"),
        )
        assertEquals("pkg|act", rewriteEmbeddedUserToken("pkg|act", USER_MANAGED, "u10"))
    }

    @Test
    fun serialTokenUsesTheUPrefix() {
        assertEquals("u10", userSerialToken(10L))
    }
}
