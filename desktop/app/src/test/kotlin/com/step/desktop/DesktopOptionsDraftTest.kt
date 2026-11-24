package com.step.desktop

import org.junit.Assert.*
import org.junit.Test

class DesktopOptionsDraftTest {
    @Test fun anotherWindowSavingDoesNotOverwriteUnsavedShortcutInput() {
        val draft=DesktopOptionsDraft()
        draft.observeSavedShortcut("Ctrl+Shift+Space")
        draft.shortcut="Ctrl+Alt+F9"

        draft.observeSavedShortcut("Ctrl+Shift+F8")

        assertEquals("Ctrl+Alt+F9",draft.shortcut)
        assertTrue(draft.dirty)
        // The new persisted value becomes the baseline even while our input is retained.
        draft.shortcut="Ctrl+Shift+F8"
        assertFalse(draft.dirty)
    }

    @Test fun acceptingTheCurrentDraftAsSavedClearsItsUnsavedState() {
        val draft=DesktopOptionsDraft()
        draft.observeSavedShortcut("Ctrl+Shift+Space")
        draft.shortcut="Ctrl+Alt+F9"
        assertTrue(draft.dirty)

        draft.observeSavedShortcut("Ctrl+Alt+F9")

        assertEquals("Ctrl+Alt+F9",draft.shortcut)
        assertFalse(draft.dirty)
    }

    @Test fun acceptingTrimmedShortcutNormalizesInputAndClearsItsUnsavedState() {
        val draft=DesktopOptionsDraft()
        draft.observeSavedShortcut("Ctrl+Shift+Space")
        draft.shortcut="  Ctrl+Alt+F9  "
        assertTrue(draft.dirty)

        draft.observeSavedShortcut("Ctrl+Alt+F9")

        assertEquals("Ctrl+Alt+F9",draft.shortcut)
        assertFalse(draft.dirty)
    }

    @Test fun anUneditedWindowFollowsChangesSavedInAnotherWindow() {
        val draft=DesktopOptionsDraft()
        draft.observeSavedShortcut("Ctrl+Shift+Space")
        assertEquals("Ctrl+Shift+Space",draft.shortcut)
        assertFalse(draft.dirty)

        draft.observeSavedShortcut("Ctrl+Shift+F8")

        assertEquals("Ctrl+Shift+F8",draft.shortcut)
        assertFalse(draft.dirty)
    }
}
