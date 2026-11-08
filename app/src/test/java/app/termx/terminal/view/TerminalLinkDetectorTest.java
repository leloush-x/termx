package app.termx.terminal.view;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class TerminalLinkDetectorTest {
    @Test public void detectsHttpLinkOnlyWhenColumnIsOnLinkAndDropsPunctuation() {
        String row = "go https://example.com/path, now";
        assertEquals("https://example.com/path", TerminalLinkDetector.findLinkAtColumn(row, 7));
        assertNull(TerminalLinkDetector.findLinkAtColumn(row, 4));
        assertNull(TerminalLinkDetector.findLinkAtColumn(row, 30));
    }

    @Test public void normalizesWwwAndCountsWideCharactersAsTwoColumns() {
        String row = "中 www.example.org";
        assertEquals("https://www.example.org", TerminalLinkDetector.findLinkAtColumn(row, 5));
        assertNull(TerminalLinkDetector.findLinkAtColumn(row, 2));
    }

    @Test public void ignoresUnsupportedSchemes() {
        assertNull(TerminalLinkDetector.findLinkAtColumn("javascript:alert(1)", 5));
        assertNull(TerminalLinkDetector.findLinkAtColumn("file:///tmp/a", 7));
    }
}
