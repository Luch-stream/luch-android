// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

/**
 * about.txt of a report: what the person wrote first, then "Key: value" lines, then longer blocks indented under a
 * title. Empty facts and blocks are left out, so the file only says what is known. Plain Java, for the JVM tests.
 */
final class About {
    private final StringBuilder b = new StringBuilder();
    private boolean afterBlock;

    About(String title, String whatHappened) {
        b.append(title).append('\n');
        block("What happened", whatHappened == null || whatHappened.trim().isEmpty() ? "(nothing written)"
                : whatHappened.trim());
    }

    About fact(String key, Object value) {
        String v = value == null ? "" : String.valueOf(value).trim();
        if (v.isEmpty()) return this;
        if (afterBlock) b.append('\n');
        afterBlock = false;
        b.append(key).append(": ").append(v.replace("\n", " ")).append('\n');
        return this;
    }

    About block(String title, String text) {
        if (text == null || text.trim().isEmpty()) return this;
        b.append('\n').append(title).append(":\n");
        for (String line : text.trim().split("\r?\n")) b.append("  ").append(line).append('\n');
        afterBlock = true;
        return this;
    }

    String text() {
        return b.toString();
    }
}
