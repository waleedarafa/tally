package com.fakeprovider;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The provider's behaviour, set through {@code POST /admin/mode} (brief section 6). Modes combine:
 * {@code ["SLOW(200)", "LIE"]}. {@code NORMAL}, or an empty list, resets everything.
 */
record Modes(long slowMillis, boolean timeoutAfterCharge, boolean outage, int rateLimitPerSecond,
        int declineSoftPercent, int declineHardPercent, boolean lie) {

    static final Modes NORMAL = new Modes(0, false, false, 0, 0, 0, false);

    private static final Pattern MODE = Pattern.compile("([A-Z_]+)(?:\\((\\d+)\\))?");

    static Modes parse(List<String> modes) {
        Modes m = NORMAL;
        for (String text : modes) {
            Matcher matcher = MODE.matcher(text.strip());
            if (!matcher.matches()) {
                throw new IllegalArgumentException("Unknown mode " + text);
            }
            String name = matcher.group(1);
            int value = matcher.group(2) == null ? -1 : Integer.parseInt(matcher.group(2));
            m = switch (name) {
                case "NORMAL" -> NORMAL;
                case "SLOW" -> new Modes(required(name, value), m.timeoutAfterCharge, m.outage, m.rateLimitPerSecond,
                        m.declineSoftPercent, m.declineHardPercent, m.lie);
                case "TIMEOUT_AFTER_CHARGE" -> new Modes(m.slowMillis, true, m.outage, m.rateLimitPerSecond,
                        m.declineSoftPercent, m.declineHardPercent, m.lie);
                case "OUTAGE" -> new Modes(m.slowMillis, m.timeoutAfterCharge, true, m.rateLimitPerSecond,
                        m.declineSoftPercent, m.declineHardPercent, m.lie);
                case "RATE_LIMIT" -> new Modes(m.slowMillis, m.timeoutAfterCharge, m.outage, required(name, value),
                        m.declineSoftPercent, m.declineHardPercent, m.lie);
                case "DECLINE_SOFT" -> new Modes(m.slowMillis, m.timeoutAfterCharge, m.outage, m.rateLimitPerSecond,
                        percent(name, value), m.declineHardPercent, m.lie);
                case "DECLINE_HARD" -> new Modes(m.slowMillis, m.timeoutAfterCharge, m.outage, m.rateLimitPerSecond,
                        m.declineSoftPercent, percent(name, value), m.lie);
                case "LIE" -> new Modes(m.slowMillis, m.timeoutAfterCharge, m.outage, m.rateLimitPerSecond,
                        m.declineSoftPercent, m.declineHardPercent, true);
                default -> throw new IllegalArgumentException("Unknown mode " + text);
            };
        }
        return m;
    }

    private static int required(String name, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " needs a positive value, for example " + name + "(100)");
        }
        return value;
    }

    private static int percent(String name, int value) {
        if (value < 0 || value > 100) {
            throw new IllegalArgumentException(name + " needs a percentage from 0 to 100");
        }
        return value;
    }
}
