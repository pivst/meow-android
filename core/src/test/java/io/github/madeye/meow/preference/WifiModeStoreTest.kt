package io.github.madeye.meow.preference

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WifiModeStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun `atomic fresh reads preserve significant unicode and spaces`() {
        val file = File(tmp.root, "wifi")
        val saved = WifiModeConfig(true, listOf(WifiModeRule(" 家 ", "direct")))
        WifiModeStore(file).save(saved)
        assertEquals(saved, WifiModeStore(file).load())
        assertNull(saved.match(true, "家"))
        assertNull(saved.match(false, " 家 "))
        assertNotNull(saved.match(true, " 家 "))
        assertEquals(listOf("wifi"), tmp.root.list()!!.toList())
    }
    @Test fun `malformed oversized and missing files disable automation`() {
        val file = File(tmp.root, "wifi"); val store = WifiModeStore(file)
        assertFalse(store.load().enabled)
        file.writeText("invalid"); assertFalse(store.load().enabled)
        file.writeText("x".repeat(32769)); assertFalse(store.load().enabled)
    }
    @Test fun `names are exact and duplicate or invalid modes are rejected`() {
        val c = WifiModeConfig(true, listOf(WifiModeRule("Home", "rule")))
        assertNull(c.match(true, "home"))
        assertFalse(c.copy(rules = c.rules + c.rules).valid())
        assertFalse(WifiModeRule("", "direct").valid())
        assertFalse(WifiModeRule("x", "bad").valid())
        assertFalse(WifiModeRule("家".repeat(11), "direct").valid())
        assertTrue(WifiModeRule("a".repeat(32), "global").valid())
    }
}
