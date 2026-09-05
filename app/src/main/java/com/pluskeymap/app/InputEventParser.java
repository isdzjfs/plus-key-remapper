package com.pluskeymap.app;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Only the tested Plus key is accepted; other input devices never reach the app. */
final class InputEventParser {
    static final String KEY_LABEL = "BTN_TRIGGER_HAPPY32";
    private static final Pattern DEVICE = Pattern.compile("add device \\d+: (/dev/input/event\\d+)");
    private static final Pattern KEY = Pattern.compile(
            "\\[\\s*(\\d+)\\.(\\d{1,6})\\]\\s+EV_KEY\\s+BTN_TRIGGER_HAPPY32\\s+(DOWN|UP)\\s*$");

    static List<String> findDevices(String capabilities) {
        List<String> devices = new ArrayList<>();
        String path = null;
        boolean gpio = false;
        boolean target = false;
        for (String line : capabilities.split("\n")) {
            Matcher device = DEVICE.matcher(line);
            if (device.find()) {
                if (path != null && gpio && target) devices.add(path);
                path = device.group(1);
                gpio = false;
                target = false;
            } else {
                if (line.trim().matches("name:\\s+\"gpio-keys\"")) gpio = true;
                if (line.contains(KEY_LABEL)) target = true;
            }
        }
        if (path != null && gpio && target) devices.add(path);
        return devices;
    }

    static Event parse(String line) {
        Matcher match = KEY.matcher(line);
        if (!match.find()) return null;
        try {
            String micros = (match.group(2) + "000000").substring(0, 6);
            long ms = Math.addExact(Math.multiplyExact(Long.parseLong(match.group(1)), 1000),
                    Long.parseLong(micros) / 1000);
            return new Event("DOWN".equals(match.group(3)), ms);
        } catch (ArithmeticException | NumberFormatException ignored) {
            return null;
        }
    }

    static final class Event {
        final boolean down;
        final long timeMs;
        Event(boolean down, long timeMs) { this.down = down; this.timeMs = timeMs; }
    }
}
