// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.content.Context;
import android.os.SystemClock;
import android.widget.Toast;

/**
 * Back on a screen that shows what a sender sends (mirroring, video, a document, photos, sound): the first press only
 * says that a second one stops, the second within {@link #WINDOW_MS} stops. A stray Back used to end the show at once,
 * and coming back to it found nothing.
 */
final class BackGuard {
    static final long WINDOW_MS = 3_000;

    private long armedAt = -1;
    private Toast hint;

    /** Whether a Back pressed at {@code now} (uptime ms) is the second one, the one that stops. */
    boolean press(long now) {
        if (armedAt >= 0 && now - armedAt <= WINDOW_MS) {
            armedAt = -1;
            return true;
        }
        armedAt = now;
        return false;
    }

    /** {@link #press} now; on a first press the hint is shown, on the second it is taken away. */
    boolean confirm(Context context) {
        boolean stop = press(SystemClock.uptimeMillis());
        if (hint != null) hint.cancel();
        hint = null;
        if (!stop) {
            hint = Toast.makeText(context, R.string.back_again_to_stop, Toast.LENGTH_SHORT);
            hint.show();
        }
        return stop;
    }
}
