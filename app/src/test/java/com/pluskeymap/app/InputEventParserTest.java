package com.pluskeymap.app;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;

public class InputEventParserTest {
    @Test public void parsesDeviceSamplesAndIgnoresUnrelatedOrRepeatEvents() {
        InputEventParser.Event down = InputEventParser.parse(
                "[  402534.866307] EV_KEY       BTN_TRIGGER_HAPPY32  DOWN");
        InputEventParser.Event up = InputEventParser.parse(
                "[  402535.030773] EV_KEY       BTN_TRIGGER_HAPPY32  UP");
        assertTrue(down.down);
        assertFalse(up.down);
        assertEquals(164, up.timeMs - down.timeMs);
        assertNull(InputEventParser.parse("[  402535.030773] EV_SYN SYN_REPORT 00000000"));
        assertNull(InputEventParser.parse("[  402535.030773] EV_KEY KEY_VOLUMEUP DOWN"));
        assertNull(InputEventParser.parse("[  402535.030773] EV_KEY BTN_TRIGGER_HAPPY32 REPEAT"));
        assertNull(InputEventParser.parse("[ bad ] EV_KEY BTN_TRIGGER_HAPPY32 DOWN"));
    }

    @Test public void locatesCapabilityInsteadOfHardcodingEventNumber() {
        String caps = "add device 1: /dev/input/event0\n  name:     \"gpio-keys\"\n"
                + " KEY (0001): KEY_VOLUMEDOWN\n"
                + "add device 2: /dev/input/event7\n  name:     \"gpio-keys\"\n"
                + " KEY (0001): BTN_TRIGGER_HAPPY32\n";
        assertEquals(Arrays.asList("/dev/input/event7"), InputEventParser.findDevices(caps));
        assertTrue(InputEventParser.findDevices(caps.replace("gpio-keys", "keyboard")).isEmpty());
    }

    @Test public void ambiguousDevicesAreReturnedForCallerToReject() {
        assertEquals(2, InputEventParser.findDevices(
                "add device 1: /dev/input/event1\nname: \"gpio-keys\"\nBTN_TRIGGER_HAPPY32\n"
                + "add device 2: /dev/input/event9\nname: \"gpio-keys\"\nBTN_TRIGGER_HAPPY32\n").size());
    }
}
