package com.termux.terminal;

/** Sanitized notification and command-finished events supported by TerminalEmulator. */
final class TerminalOscEvent {
    enum Type { NOTIFICATION, COMMAND_FINISHED }

    final Type type;
    final String title;
    final String body;
    final int exitCode;

    private TerminalOscEvent(Type type, String title, String body, int exitCode) {
        this.type = type;
        this.title = title;
        this.body = body;
        this.exitCode = exitCode;
    }

    static TerminalOscEvent parse(int code, String payload) {
        if (payload == null || payload.length() > 4096) return null;
        if (code == 9) {
            // OSC 9;4 is a progress report, not a notification.
            if (payload.startsWith("4;")) return null;
            String body = sanitize(payload);
            return body.isEmpty() ? null : new TerminalOscEvent(Type.NOTIFICATION, "Terminal", body, 0);
        }
        if (code == 777) {
            String[] fields = payload.split(";", 3);
            if (fields.length < 3 || !"notify".equals(fields[0])) return null;
            String title = sanitize(fields[1]);
            String body = sanitize(fields[2]);
            if (body.isEmpty()) return null;
            return new TerminalOscEvent(Type.NOTIFICATION, title.isEmpty() ? "Terminal" : title, body, 0);
        }
        if (code == 133) {
            String[] fields = payload.split(";", 3);
            if (fields.length < 2 || !"D".equals(fields[0])) return null;
            try {
                int status = Integer.parseInt(fields[1]);
                if (status < 0 || status > 255) return null;
                return new TerminalOscEvent(Type.COMMAND_FINISHED, "", "", status);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String sanitize(String text) {
        StringBuilder clean = new StringBuilder(Math.min(text.length(), 160));
        for (int i = 0; i < text.length() && clean.length() < 160; i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) || c == '\u202A' || c == '\u202B' || c == '\u202D' || c == '\u202E' || c == '\u2066' || c == '\u2067' || c == '\u2068' || c == '\u2069') {
                if (Character.isWhitespace(c) && clean.length() > 0 && clean.charAt(clean.length() - 1) != ' ') clean.append(' ');
                continue;
            }
            clean.append(c);
        }
        return clean.toString().trim();
    }
}
