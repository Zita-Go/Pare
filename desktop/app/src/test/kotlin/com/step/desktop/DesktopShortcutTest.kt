package com.step.desktop
import org.junit.Assert.*
import org.junit.Test
class DesktopShortcutTest {
    @Test fun translatesTheConfiguredComboIntoWindowsModifiers() {
        assertEquals(ShortcutSpec(6,32),ShortcutSpec.parse("Ctrl+Shift+Space"))
        assertEquals(ShortcutSpec(3,65),ShortcutSpec.parse("Ctrl+Alt+A"))
        assertEquals(ShortcutSpec(5,123),ShortcutSpec.parse("Alt+Shift+F12"))
    }
    @Test fun rejectsUnmodifiedOrAmbiguousCombosBeforeRegistration() {
        for(value in listOf("A","Shift+A","Ctrl+Ctrl+A","Ctrl+Delete","Win+Space","Ctrl+")) {
            assertTrue(value,runCatching {ShortcutSpec.parse(value)}.isFailure)
        }
    }
}
