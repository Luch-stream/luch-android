// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public class LuchIdTest {
    @Test
    public void aReinstallKeepsItsId() {
        assertEquals(Luch.newId("9f3c2a1b4d5e6f70", "ru.toxblh.luch"), Luch.newId("9f3c2a1b4d5e6f70", "ru.toxblh.luch"));
    }

    @Test
    public void theDevBuildAndOtherDevicesDiffer() {
        String release = Luch.newId("9f3c2a1b4d5e6f70", "ru.toxblh.luch");
        assertNotEquals(release, Luch.newId("9f3c2a1b4d5e6f70", "ru.toxblh.luch.dev"));
        assertNotEquals(release, Luch.newId("0123456789abcdef", "ru.toxblh.luch"));
    }

    @Test
    public void withoutAndroidIdItIsRandom() {
        assertNotEquals(Luch.newId(null, "ru.toxblh.luch"), Luch.newId(null, "ru.toxblh.luch"));
        assertEquals(36, Luch.newId("", "ru.toxblh.luch").length());
    }
}
