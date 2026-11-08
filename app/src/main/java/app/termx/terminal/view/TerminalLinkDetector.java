package app.termx.terminal.view;

import com.termux.terminal.WcWidth;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds a web URL under a terminal display column. */
final class TerminalLinkDetector {
    private static final Pattern URL = Pattern.compile("(?i)(?<![A-Za-z0-9_])(?:https?://|www\\.)[^\\s<>\\\"']+");

    private TerminalLinkDetector() { }

    static String findLinkAtColumn(String text, int targetColumn) {
        if (text == null || targetColumn < 0) return null;
        Matcher matcher = URL.matcher(text);
        while (matcher.find()) {
            int end = matcher.end();
            while (end > matcher.start() && isTrailingPunctuation(text.charAt(end - 1))) end--;
            int startColumn = columns(text, 0, matcher.start());
            int endColumn = columns(text, 0, end);
            if (targetColumn >= startColumn && targetColumn < endColumn) {
                String link = text.substring(matcher.start(), end);
                return link.regionMatches(true, 0, "www.", 0, 4) ? "https://" + link : link;
            }
        }
        return null;
    }

    private static int columns(String text, int start, int end) {
        int column = 0;
        for (int i = start; i < end;) {
            int codePoint = text.codePointAt(i);
            column += Math.max(0, WcWidth.width(codePoint));
            i += Character.charCount(codePoint);
        }
        return column;
    }

    private static boolean isTrailingPunctuation(char c) {
        return c == '.' || c == ',' || c == ';' || c == ':' || c == '!' || c == '?' || c == ')' || c == ']' || c == '}';
    }
}
