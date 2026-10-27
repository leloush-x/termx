package app.termx.terminal.input

import org.junit.Assert.*
import org.junit.Test

class ModifierStateTest {
    @Test fun tapOneShotLockAndUnlock() {
        val state = ModifierState()
        state.tap(ModKey.CTRL); assertEquals(ModMode.ONE_SHOT, state.mode(ModKey.CTRL))
        state.consumeOneShots(); assertEquals(ModMode.OFF, state.mode(ModKey.CTRL))
        state.longPress(ModKey.SHIFT); state.consumeOneShots(); assertEquals(ModMode.LOCKED, state.mode(ModKey.SHIFT))
        state.tap(ModKey.SHIFT); assertEquals(ModMode.OFF, state.mode(ModKey.SHIFT))
    }

    @Test fun appliesTextModifiers() {
        assertEquals("?", ModifierKeys.apply("/", false, false, true))
        assertEquals("\u0003", ModifierKeys.apply("c", true, false, false))
        assertEquals("\u001Bb", ModifierKeys.apply("b", false, true, false))
    }
}
