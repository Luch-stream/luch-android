// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

public class RemoteTest {
    @Test
    public void mediaKeysByName() {
        assertEquals("play_pause", Remote.mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        assertEquals("next", Remote.mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT));
        assertEquals("previous", Remote.mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
        assertEquals("seek_back", Remote.mediaKey(KeyEvent.KEYCODE_MEDIA_REWIND));
        assertNull(Remote.mediaKey(KeyEvent.KEYCODE_DPAD_CENTER));
    }

    @Test
    public void volumeInPercentAndBack() {
        assertEquals(0, Remote.percent(0, 15));
        assertEquals(100, Remote.percent(15, 15));
        assertEquals(47, Remote.percent(7, 15));
        assertEquals(7, Remote.steps(47, 15));
        assertEquals(1, Remote.steps(2, 15)); // a little is not silence
        assertEquals(0, Remote.steps(0, 15));
        assertEquals(15, Remote.steps(250, 15));
        assertEquals(0, Remote.percent(3, 0));
    }

    @Test
    public void youtubeIdsAndLinks() {
        assertTrue(Remote.youtubeId("dQw4w9WgXcQ"));
        assertFalse(Remote.youtubeId("dQw4w9WgXcQ&list=x"));
        assertFalse(Remote.youtubeId("../../etc"));
        assertFalse(Remote.youtubeId(null));
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=90s", Remote.youtubeLink("dQw4w9WgXcQ", 90.7));
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", Remote.youtubeLink("dQw4w9WgXcQ", 0.4));
    }

    @Test
    public void documentPagesFitTheScreen() {
        // A4 portrait on a 1920x1080 screen: the whole page by its height, or the screen's width.
        assertArrayEquals(new int[]{763, 1080}, DocActivity.target(595, 842, 1920, 1080, false));
        int[] wide = DocActivity.target(595, 842, 1920, 1080, true);
        assertEquals(1920, wide[0]);
        assertEquals(2717, wide[1]);
        // A very long page by its width stays within four screens of pixels.
        int[] tall = DocActivity.target(600, 60000, 1920, 1080, true);
        assertTrue((long) tall[0] * tall[1] <= 4L * 1920 * 1080 + 4000);
    }
}
