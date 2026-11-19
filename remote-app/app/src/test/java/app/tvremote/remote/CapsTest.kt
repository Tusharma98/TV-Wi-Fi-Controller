package app.tvremote.remote

import org.junit.Assert.*
import org.junit.Test

/** What an IME can and cannot inject, how commands are parsed, and what the person is told when something cannot be sent. */
class CapsTest {
    private val caps = setOf("keys", "text", "vol", "media")

    @Test fun editorKeysNeedAFocusedField() {
        for (k in listOf(19, 20, 21, 22, 23, 4, 66, 67)) { assertTrue(ImeCaps.supportsKey(k, caps, true)); assertFalse("key $k without a field", ImeCaps.supportsKey(k, caps, false)) }
    }
    @Test fun volumeAndMediaWorkWithoutAField() {
        for (k in listOf(24, 25, 164, 85, 87, 126)) assertTrue(ImeCaps.supportsKey(k, caps, false))
    }
    @Test fun powerRecentsMenuSearchAreNeverClaimed() {
        for (k in listOf(26, 187, 82, 84, 176)) assertFalse("key $k", ImeCaps.supportsKey(k, caps + "home", true))
    }
    @Test fun homeOnlyWhenTheTvAnnouncesIt() { assertFalse(ImeCaps.supportsKey(3, caps, true)); assertTrue(ImeCaps.supportsKey(3, caps + "home", false)) }
    @Test fun nothingWithoutAnnouncedCaps() { assertFalse(ImeCaps.supportsKey(19, emptySet(), true)) }

    @Test fun commandParsing() {
        assertEquals(19, (Cmd.parse("K 19") as Cmd.Key).code)
        val t = Cmd.parse("T a+b%21") as Cmd.Text; assertEquals("a b!", t.text); assertFalse(t.replace)
        assertTrue((Cmd.parse("V x") as Cmd.Text).replace)
        for (l in listOf("M 1 2", "C", "LIST", "K x", "FIELD", "")) assertTrue(l, Cmd.parse(l) is Cmd.Other)
        assertEquals("M", (Cmd.parse("M 1 2") as Cmd.Other).name)
    }

    @Test fun gamepadButtonsMapToTvKeys() { assertEquals(23, Transport.mapKey(96)); assertEquals(4, Transport.mapKey(97)); assertEquals(3, Transport.mapKey(108)); assertEquals(19, Transport.mapKey(19)) }

    @Test fun hintsExplainWhyAKeyWasNotSent() {
        assertEquals(Hints.NO_FIELD, Hints.forKey(19, caps))        // supported key, no field focused
        assertEquals(Hints.NO_HOME, Hints.forKey(3, caps))
        assertEquals(Hints.NO_KEY, Hints.forKey(26, caps))           // Power
        assertEquals(Hints.NO_KEY, Hints.forKey(19, emptySet()))     // TV did not announce key support
    }
    @Test fun housekeepingLinesStaySilent() { for (l in listOf("FIELD", "MOUSE", "ICON", "")) assertTrue(l in Hints.SILENT) }

    // ---- Accessibility add-on ----
    private val a11y = caps + "a11y"
    @Test fun accessibilityUnlocksSystemKeysWithoutAFocusedField() {
        for (k in listOf(3, 4, 19, 20, 21, 22, 23, 26, 66, 82, 84, 176, 187)) assertTrue("key $k", ImeCaps.supportsKey(k, a11y, false))
        assertFalse("Backspace still needs a focused field", ImeCaps.supportsKey(67, a11y, false))
    }
    @Test fun withoutAccessibilityNothingChangesForTheKeyboard() {
        for (k in listOf(3, 26, 82, 84, 176, 187)) assertFalse("key $k", ImeCaps.supportsKey(k, caps, true))
        assertTrue(ImeCaps.supportsKey(19, caps, true)); assertFalse(ImeCaps.supportsKey(19, caps, false))
    }
    @Test fun extensionCommandSets() {
        for (c in listOf("M", "C", "S", "MOUSE", "LIST", "ICON", "L", "CLEAR", "FILE")) assertTrue(c, c in ImeCaps.A11Y_CMDS)
        assertTrue("W" in ImeCaps.FREE_CMDS && "A11YSET" in ImeCaps.FREE_CMDS)
        assertTrue("W" !in ImeCaps.A11Y_CMDS)                                    // Wi-Fi settings shortcut works with Accessibility off
        assertEquals("W", (Cmd.parse("W") as Cmd.Other).name)
    }
}
