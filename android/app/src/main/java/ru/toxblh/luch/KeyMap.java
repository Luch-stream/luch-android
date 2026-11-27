// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import android.view.KeyEvent;

/**
 * Android key codes as Linux evdev codes (PROTOCOL.md, section 4h), and the gamepad's buttons and sticks as the gamepad
 * record. Android's key codes come from the scan codes through a key layout file (Generic.kl) and stand for a key's
 * place, not its letter, so this table is that file read backwards; the sender's keyboard layout makes the letters.
 * Only constants of android.view (inlined by the compiler): tested on the JVM.
 */
final class KeyMap {
    private KeyMap() {
    }

    private static final int[] TABLE = new int[400];   // above every key code there is (KeyEvent.getMaxKeyCode() is not a constant)

    private static void map(int android, int linux) {
        TABLE[android] = linux;
    }

    static {
        // letters: KEY_A … KEY_Z are in keyboard order, not in the alphabet's
        int[] letters = {30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44};
        for (int i = 0; i < 26; i++) map(KeyEvent.KEYCODE_A + i, letters[i]);
        map(KeyEvent.KEYCODE_0, 11);
        for (int i = 1; i <= 9; i++) map(KeyEvent.KEYCODE_0 + i, 1 + i);   // KEY_1 = 2 … KEY_9 = 10
        map(KeyEvent.KEYCODE_ESCAPE, 1);
        map(KeyEvent.KEYCODE_MINUS, 12);
        map(KeyEvent.KEYCODE_EQUALS, 13);
        map(KeyEvent.KEYCODE_DEL, 14);   // Backspace
        map(KeyEvent.KEYCODE_TAB, 15);
        map(KeyEvent.KEYCODE_LEFT_BRACKET, 26);
        map(KeyEvent.KEYCODE_RIGHT_BRACKET, 27);
        map(KeyEvent.KEYCODE_ENTER, 28);
        map(KeyEvent.KEYCODE_CTRL_LEFT, 29);
        map(KeyEvent.KEYCODE_SEMICOLON, 39);
        map(KeyEvent.KEYCODE_APOSTROPHE, 40);
        map(KeyEvent.KEYCODE_GRAVE, 41);
        map(KeyEvent.KEYCODE_SHIFT_LEFT, 42);
        map(KeyEvent.KEYCODE_BACKSLASH, 43);
        map(KeyEvent.KEYCODE_COMMA, 51);
        map(KeyEvent.KEYCODE_PERIOD, 52);
        map(KeyEvent.KEYCODE_SLASH, 53);
        map(KeyEvent.KEYCODE_SHIFT_RIGHT, 54);
        map(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 55);
        map(KeyEvent.KEYCODE_ALT_LEFT, 56);
        map(KeyEvent.KEYCODE_SPACE, 57);
        map(KeyEvent.KEYCODE_CAPS_LOCK, 58);
        for (int i = 0; i < 10; i++) map(KeyEvent.KEYCODE_F1 + i, 59 + i);   // KEY_F1 = 59 … KEY_F10 = 68
        map(KeyEvent.KEYCODE_F11, 87);
        map(KeyEvent.KEYCODE_F12, 88);
        map(KeyEvent.KEYCODE_NUM_LOCK, 69);
        map(KeyEvent.KEYCODE_SCROLL_LOCK, 70);
        map(KeyEvent.KEYCODE_NUMPAD_7, 71);
        map(KeyEvent.KEYCODE_NUMPAD_8, 72);
        map(KeyEvent.KEYCODE_NUMPAD_9, 73);
        map(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 74);
        map(KeyEvent.KEYCODE_NUMPAD_4, 75);
        map(KeyEvent.KEYCODE_NUMPAD_5, 76);
        map(KeyEvent.KEYCODE_NUMPAD_6, 77);
        map(KeyEvent.KEYCODE_NUMPAD_ADD, 78);
        map(KeyEvent.KEYCODE_NUMPAD_1, 79);
        map(KeyEvent.KEYCODE_NUMPAD_2, 80);
        map(KeyEvent.KEYCODE_NUMPAD_3, 81);
        map(KeyEvent.KEYCODE_NUMPAD_0, 82);
        map(KeyEvent.KEYCODE_NUMPAD_DOT, 83);
        map(KeyEvent.KEYCODE_NUMPAD_ENTER, 96);
        map(KeyEvent.KEYCODE_CTRL_RIGHT, 97);
        map(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 98);
        map(KeyEvent.KEYCODE_SYSRQ, 99);
        map(KeyEvent.KEYCODE_ALT_RIGHT, 100);
        map(KeyEvent.KEYCODE_MOVE_HOME, 102);
        map(KeyEvent.KEYCODE_DPAD_UP, 103);
        map(KeyEvent.KEYCODE_PAGE_UP, 104);
        map(KeyEvent.KEYCODE_DPAD_LEFT, 105);
        map(KeyEvent.KEYCODE_DPAD_RIGHT, 106);
        map(KeyEvent.KEYCODE_MOVE_END, 107);
        map(KeyEvent.KEYCODE_DPAD_DOWN, 108);
        map(KeyEvent.KEYCODE_PAGE_DOWN, 109);
        map(KeyEvent.KEYCODE_INSERT, 110);
        map(KeyEvent.KEYCODE_FORWARD_DEL, 111);
        map(KeyEvent.KEYCODE_NUMPAD_EQUALS, 117);
        map(KeyEvent.KEYCODE_BREAK, 119);   // Pause
        map(KeyEvent.KEYCODE_NUMPAD_COMMA, 121);
        map(KeyEvent.KEYCODE_META_LEFT, 125);
        map(KeyEvent.KEYCODE_META_RIGHT, 126);
        map(KeyEvent.KEYCODE_MENU, 127);   // the context-menu key (KEY_COMPOSE)
        map(KeyEvent.KEYCODE_KANA, 90);
        map(KeyEvent.KEYCODE_HENKAN, 92);
        map(KeyEvent.KEYCODE_MUHENKAN, 94);
        map(KeyEvent.KEYCODE_YEN, 124);
        map(KeyEvent.KEYCODE_RO, 89);
        map(KeyEvent.KEYCODE_ZENKAKU_HANKAKU, 85);
    }

    /**
     * The evdev code of a key, 0 when it has none. A key code the table does not know falls back to the scan code,
     * which on Android is the Linux key code the keyboard's driver reported (a USB or Bluetooth keyboard's).
     */
    static int evdev(int keyCode, int scanCode) {
        int c = keyCode >= 0 && keyCode < TABLE.length ? TABLE[keyCode] : 0;
        if (c != 0) return c;
        return scanCode > 0 && scanCode < 0x300 ? scanCode : 0;
    }

    /**
     * Keys that stay on the receiver even when they come from a full keyboard: the sound (the TV's), the media keys
     * (they already go to the sender's player, section 4d), and what the system keeps for itself.
     */
    static boolean staysHere(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MUTE:
            case KeyEvent.KEYCODE_POWER:
            case KeyEvent.KEYCODE_SLEEP:
            case KeyEvent.KEYCODE_WAKEUP:
            case KeyEvent.KEYCODE_HOME:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_APP_SWITCH:
            case KeyEvent.KEYCODE_ASSIST:
            case KeyEvent.KEYCODE_SEARCH:
            case KeyEvent.KEYCODE_TV_POWER:
                return true;
            default:
                return Remote.mediaKey(keyCode) != null;
        }
    }

    // ---- gamepads ---------------------------------------------------------------------------------------------

    /** The gamepad record's button bit of a key code (the SDL order of section 4h), -1 for none. */
    static int padButton(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return 0;
            case KeyEvent.KEYCODE_BUTTON_B: return 1;
            case KeyEvent.KEYCODE_BUTTON_X: return 2;
            case KeyEvent.KEYCODE_BUTTON_Y: return 3;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
            case KeyEvent.KEYCODE_BACK: return 4;   // some pads say Back for their select button
            case KeyEvent.KEYCODE_BUTTON_MODE: return 5;
            case KeyEvent.KEYCODE_BUTTON_START: return 6;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return 7;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return 8;
            case KeyEvent.KEYCODE_BUTTON_L1: return 9;
            case KeyEvent.KEYCODE_BUTTON_R1: return 10;
            case KeyEvent.KEYCODE_DPAD_UP: return 11;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 12;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 13;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 14;
            default: return -1;
        }
    }

    static final int PAD_DPAD = (1 << 11) | (1 << 12) | (1 << 13) | (1 << 14);

    /** A stick's axis, -1…1, as the record's i16. */
    static short stick(float v) {
        return (short) Math.max(-32768, Math.min(32767, Math.round(v * 32767f)));
    }

    /** A trigger, 0…1, as the record's u16. */
    static int trigger(float v) {
        return InputWire.clamp16(Math.round(v * 65535f));
    }

    /**
     * The D-pad of pads that report it as a hat (two axes, -1…1) rather than as keys: its bits, from both axes. The
     * bits from keys are kept as they are when the hat is centred.
     */
    static int hat(int buttons, float hatX, float hatY, boolean hasHat) {
        if (!hasHat) return buttons;
        int b = buttons & ~PAD_DPAD;
        if (hatY < -0.5f) b |= 1 << 11;
        if (hatY > 0.5f) b |= 1 << 12;
        if (hatX < -0.5f) b |= 1 << 13;
        if (hatX > 0.5f) b |= 1 << 14;
        return b;
    }
}
