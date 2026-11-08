package com.termux.terminal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class TerminalOscEventTest {
    @Test public void parsesOsc9NotificationAndStripsControls() {
        TerminalOscEvent event = TerminalOscEvent.parse(9, "Build\u001b[31m finished\nnow");
        assertEquals(TerminalOscEvent.Type.NOTIFICATION, event.type);
        assertEquals("Terminal", event.title);
        assertEquals("Build finished now", event.body);
    }

    @Test public void parsesOsc777NotificationAndBoundsFields() {
        TerminalOscEvent event = TerminalOscEvent.parse(777, "notify;Agent\u202e;Needs input");
        assertEquals("Agent", event.title);
        assertEquals("Needs input", event.body);
        assertNull(TerminalOscEvent.parse(777, "open;bad;payload"));
    }

    @Test public void parsesOsc133CommandExitAndRejectsMalformedStatus() {
        TerminalOscEvent event = TerminalOscEvent.parse(133, "D;127");
        assertEquals(TerminalOscEvent.Type.COMMAND_FINISHED, event.type);
        assertEquals(127, event.exitCode);
        assertNull(TerminalOscEvent.parse(133, "D;999"));
        assertNull(TerminalOscEvent.parse(133, "C;0"));
    }

    @Test public void ignoresProgressAndOversizedPayloads() {
        assertNull(TerminalOscEvent.parse(9, "4;1;50"));
        assertNull(TerminalOscEvent.parse(9, new String(new char[4097]).replace('\0', 'x')));
    }
}
