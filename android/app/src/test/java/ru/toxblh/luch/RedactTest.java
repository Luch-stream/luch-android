// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RedactTest {
    @Test
    public void addressesBecomeDocumentationOnesTheSameEachTime() {
        Redact r = new Redact();
        assertEquals("hello from 192.0.2.1 and 192.0.2.2, again 192.0.2.1.",
                r.apply("hello from 192.168.1.20 and 10.0.0.7, again 192.168.1.20."));
        // The same address in a later file of the report is still the same placeholder.
        assertEquals("video from 192.0.2.2:5004", r.apply("video from 10.0.0.7:5004"));
    }

    @Test
    public void ipv6InItsForms() {
        Redact r = new Redact();
        assertEquals("a 192.0.2.1 b 192.0.2.2 c 192.0.2.3 d 192.0.2.4",
                r.apply("a fe80::1c2b:3aff:fe4d:5e6f%wlan0 b 2001:db8:0:0:1:2:3:4 c ::ffff:10.1.2.3 d 2a02:6b8::5"));
    }

    @Test
    public void loopbackTimesAndCodeStay() {
        Redact r = new Redact();
        String s = "2026-01-31 20:15:02.123+0100 I luch-conn: bridge 127.0.0.1 ::1 0.0.0.0 at Foo.java:12 "
                + "version 0.4 build 1.2.3 std::string 14:03:12";
        assertEquals(s, r.apply(s));
    }

    @Test
    public void notAnAddress() {
        Redact r = new Redact();
        assertEquals("1.2.3.4.5 999.1.1.1 v10.0.0", r.apply("1.2.3.4.5 999.1.1.1 v10.0.0"));
    }

    @Test
    public void macsBecomeLocalOnes() {
        Redact r = new Redact();
        assertEquals("bt 02:00:00:00:00:01 then 02:00:00:00:00:02 and 02:00:00:00:00:01",
                r.apply("bt AA:BB:CC:DD:EE:FF then 11-22-33-44-55-66 and aa:bb:cc:dd:ee:ff"));
    }

    @Test
    public void namesWhereverTheyAreAnyCase() {
        Redact r = new Redact();
        assertTrue(r.name("Living room TV", "This device"));
        assertTrue(r.name("Linux Laptop", "Computer 1"));
        assertTrue(r.name("Linux Laptop 2", "Computer 2"));
        assertFalse(r.name("Linux Laptop", "Computer 3"));   // given already
        assertFalse(r.name(" ", "Computer 3"));
        assertEquals("hello from Computer 2 (Computer 1 left), paired with Computer 1 on This device",
                r.apply("hello from Linux Laptop 2 (Linux Laptop left), paired with linux laptop on Living room TV"));
    }
}
