// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/** Android key codes as Linux evdev codes, and the gamepad's parts as the record has them. */
public class KeyMapTest {
    @Test
    public void lettersDigitsAndTheRest() {
        assertEquals(30, KeyMap.evdev(KeyEvent.KEYCODE_A, 0));    // KEY_A
        assertEquals(48, KeyMap.evdev(KeyEvent.KEYCODE_B, 0));
        assertEquals(16, KeyMap.evdev(KeyEvent.KEYCODE_Q, 0));
        assertEquals(44, KeyMap.evdev(KeyEvent.KEYCODE_Z, 0));
        assertEquals(2, KeyMap.evdev(KeyEvent.KEYCODE_1, 0));
        assertEquals(10, KeyMap.evdev(KeyEvent.KEYCODE_9, 0));
        assertEquals(11, KeyMap.evdev(KeyEvent.KEYCODE_0, 0));
        assertEquals(28, KeyMap.evdev(KeyEvent.KEYCODE_ENTER, 0));
        assertEquals(1, KeyMap.evdev(KeyEvent.KEYCODE_ESCAPE, 0));
        assertEquals(14, KeyMap.evdev(KeyEvent.KEYCODE_DEL, 0));            // Backspace
        assertEquals(111, KeyMap.evdev(KeyEvent.KEYCODE_FORWARD_DEL, 0));   // Delete
        assertEquals(57, KeyMap.evdev(KeyEvent.KEYCODE_SPACE, 0));
        assertEquals(59, KeyMap.evdev(KeyEvent.KEYCODE_F1, 0));
        assertEquals(68, KeyMap.evdev(KeyEvent.KEYCODE_F10, 0));
        assertEquals(87, KeyMap.evdev(KeyEvent.KEYCODE_F11, 0));
        assertEquals(88, KeyMap.evdev(KeyEvent.KEYCODE_F12, 0));
        assertEquals(103, KeyMap.evdev(KeyEvent.KEYCODE_DPAD_UP, 0));
        assertEquals(108, KeyMap.evdev(KeyEvent.KEYCODE_DPAD_DOWN, 0));
        assertEquals(105, KeyMap.evdev(KeyEvent.KEYCODE_DPAD_LEFT, 0));
        assertEquals(106, KeyMap.evdev(KeyEvent.KEYCODE_DPAD_RIGHT, 0));
        assertEquals(125, KeyMap.evdev(KeyEvent.KEYCODE_META_LEFT, 0));
        assertEquals(100, KeyMap.evdev(KeyEvent.KEYCODE_ALT_RIGHT, 0));
        assertEquals(97, KeyMap.evdev(KeyEvent.KEYCODE_CTRL_RIGHT, 0));
        assertEquals(96, KeyMap.evdev(KeyEvent.KEYCODE_NUMPAD_ENTER, 0));
        assertEquals(82, KeyMap.evdev(KeyEvent.KEYCODE_NUMPAD_0, 0));
    }

    @Test
    public void everyLetterHasItsOwnCode() {
        Set<Integer> seen = new HashSet<>();
        for (int k = KeyEvent.KEYCODE_A; k <= KeyEvent.KEYCODE_Z; k++) assertTrue(seen.add(KeyMap.evdev(k, 0)));
        for (int k = KeyEvent.KEYCODE_0; k <= KeyEvent.KEYCODE_9; k++) assertTrue(seen.add(KeyMap.evdev(k, 0)));
    }

    @Test
    public void unknownKeysFallBackToTheScanCode() {
        assertEquals(30, KeyMap.evdev(KeyEvent.KEYCODE_A, 99));   // the table wins
        assertEquals(183, KeyMap.evdev(KeyEvent.KEYCODE_UNKNOWN, 183));   // KEY_F13 on a keyboard Android has no name for
        assertEquals(0, KeyMap.evdev(KeyEvent.KEYCODE_UNKNOWN, 0));
        assertEquals(0, KeyMap.evdev(KeyEvent.KEYCODE_UNKNOWN, 0x300));
        assertEquals(0, KeyMap.evdev(-5, 0));
        assertEquals(0, KeyMap.evdev(100_000, 0));
    }

    @Test
    public void systemSoundAndMediaKeysStayOnTheReceiver() {
        assertTrue(KeyMap.staysHere(KeyEvent.KEYCODE_VOLUME_UP));
        assertTrue(KeyMap.staysHere(KeyEvent.KEYCODE_HOME));
        assertTrue(KeyMap.staysHere(KeyEvent.KEYCODE_BACK));
        assertTrue(KeyMap.staysHere(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        assertFalse(KeyMap.staysHere(KeyEvent.KEYCODE_A));
        assertFalse(KeyMap.staysHere(KeyEvent.KEYCODE_ESCAPE));
        assertFalse(KeyMap.staysHere(KeyEvent.KEYCODE_DPAD_UP));
    }

    @Test
    public void gamepadButtonsInTheSdlOrder() {
        assertEquals(0, KeyMap.padButton(KeyEvent.KEYCODE_BUTTON_A));
        assertEquals(1, KeyMap.padButton(KeyEvent.KEYCODE_BUTTON_B));
        assertEquals(2, KeyMap.padButton(KeyEvent.KEYCODE_BUTTON_X));
        assertEquals(3, KeyMap.padButton(KeyEvent.KEYCODE_BUTTON_Y));
        assertEquals(4, KeyMap.padButton(KeyEvent.KEYCODE_BUTTON_SELECT));
        assertEquals(5, KeyMap.padButton(KeyEvent.KEYCODE_BUTTON_MODE));
        assertEquals(6, KeyMap.padButton(KeyEvent.KEYCODE_BUTTON_START));
        assertEquals(7, KeyMap.padButton(KeyEvent.KEYCODE_BUTTON_THUMBL));
        assertEquals(8, KeyMap.padButton(KeyEvent.KEYCODE_BUTTON_THUMBR));
        assertEquals(9, KeyMap.padButton(KeyEvent.KEYCODE_BUTTON_L1));
        assertEquals(10, KeyMap.padButton(KeyEvent.KEYCODE_BUTTON_R1));
        assertEquals(11, KeyMap.padButton(KeyEvent.KEYCODE_DPAD_UP));
        assertEquals(14, KeyMap.padButton(KeyEvent.KEYCODE_DPAD_RIGHT));
        assertEquals(-1, KeyMap.padButton(KeyEvent.KEYCODE_A));
    }

    @Test
    public void sticksTriggersAndHat() {
        assertEquals(32767, KeyMap.stick(1));
        assertEquals(-32767, KeyMap.stick(-1));
        assertEquals(-32768, KeyMap.stick(-2));
        assertEquals(0, KeyMap.stick(0));
        assertEquals(65535, KeyMap.trigger(1));
        assertEquals(0, KeyMap.trigger(-0.1f));
        int a = 1;
        assertEquals(a | 1 << 11 | 1 << 14, KeyMap.hat(a, 1, -1, true));
        assertEquals(a | 1 << 12 | 1 << 13, KeyMap.hat(a | 1 << 11, -1, 1, true));
        assertEquals(a, KeyMap.hat(a | 1 << 11, 0, 0, true));   // the hat is centred: no D-pad
        assertEquals(a | 1 << 11, KeyMap.hat(a | 1 << 11, 0, 0, false));   // no hat: keys say it
    }
}
