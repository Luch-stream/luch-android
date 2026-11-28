// SPDX-License-Identifier: GPL-3.0-or-later
package ru.toxblh.luch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ReceiverChoiceTest {
    private static Luch.Receiver r(String id, String name, String address) {
        return new Luch.Receiver(id, name, "", address, 47800, "", 47802);
    }

    private static final Luch.Receiver LIVING = r("a", "Living room TV", "192.168.1.10");
    private static final Luch.Receiver BEDROOM = r("b", "Bedroom projector", "192.168.1.11");
    private static final Luch.Receiver KITCHEN = r("c", "Kitchen TV", "192.168.1.12");

    @Test
    public void theOnlyOneIsTakenAtOnce() {
        List<Luch.Receiver> one = ReceiverChoice.order(Collections.singletonList(LIVING), null);
        assertSame(LIVING, ReceiverChoice.auto(one));
        assertNull(ReceiverChoice.auto(ReceiverChoice.order(Collections.emptyList(), null)));
    }

    @Test
    public void severalAreAskedAboutWithTheLastOneOnTop() {
        List<Luch.Receiver> o = ReceiverChoice.order(Arrays.asList(LIVING, BEDROOM, KITCHEN), "c");
        assertNull(ReceiverChoice.auto(o));
        assertEquals(Arrays.asList(KITCHEN, LIVING, BEDROOM), o);
        // a last one that is not around now changes nothing
        assertEquals(Arrays.asList(LIVING, BEDROOM, KITCHEN),
                ReceiverChoice.order(Arrays.asList(LIVING, BEDROOM, KITCHEN), "gone"));
    }

    /** The same TV found twice is one entry, under its IPv4 address; then it is the only one and taken at once. */
    @Test
    public void aTvSeenTwiceIsOneEntry() {
        Luch.Receiver v6 = r("a", "Living room TV", "fe80::1%wlan0");
        List<Luch.Receiver> o = ReceiverChoice.order(Arrays.asList(v6, LIVING), null);
        assertEquals(1, o.size());
        assertSame(LIVING, ReceiverChoice.auto(o));
        assertSame(LIVING, ReceiverChoice.order(Arrays.asList(LIVING, v6), null).get(0));
    }
}
