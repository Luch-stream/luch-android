// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CompatTest {
    @Test
    public void tunnelsAreNotTheLan() {
        for (String n : new String[]{"tun0", "wg0", "ppp0", "ipsec1", "rmnet16", "ccmni0", "clat4"}) {
            assertTrue(n, Compat.tunnel(n));
        }
        for (String n : new String[]{"wlan0", "eth0", "ap0"}) assertFalse(n, Compat.tunnel(n));
    }
}
