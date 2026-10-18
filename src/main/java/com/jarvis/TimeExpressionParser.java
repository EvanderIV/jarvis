package com.jarvis;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses spoken durations ("an hour and a half", "ninety seconds") and clock times
 * ("seven thirty", "quarter to eight", "noon tomorrow") out of Vosk transcriptions.
 * Vosk spells numbers out as words, so everything here works on word tokens;
 * plain digits are accepted too.
 */
public final class TimeExpressionParser {

    private TimeExpressionParser() {}

    private static final Map<String, Integer> SMALL_NUMBERS = new HashMap<>();
    private static final Map<String, Integer> TENS = new HashMap<>();
    private static final Map<String, Integer> UNIT_SECONDS = new HashMap<>();
    static {
        String[] small = { "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
                "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
                "eighteen", "nineteen" };
        for (int i = 0; i < small.length; i++) SMALL_NUMBERS.put(small[i], i);
        String[] tens = { "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety" };
        for (int i = 0; i < tens.length; i++) TENS.put(tens[i], (i + 2) * 10);

        for (String u : new String[] { "second", "seconds", "sec", "secs" }) UNIT_SECONDS.put(u, 1);
        for (String u : new String[] { "minute", "minutes", "min", "mins" }) UNIT_SECONDS.put(u, 60);
        for (String u : new String[] { "hour", "hours" }) UNIT_SECONDS.put(u, 3600);
    }

    /** A parsed number and the token index just past it. */
    private static final class NumberMatch {
        final int value;
        final int next;
        NumberMatch(int value, int next) { this.value = value; this.next = next; }
    }

    static String[] tokenize(String text) {
        String cleaned = text.toLowerCase()
                .replaceAll("(\\d+):(\\d{2})", "$1 colon $2")
                .replace("o'clock", "oclock")
                .replaceAll("[^a-z0-9\\s]", " ")
                .trim();
        return cleaned.isEmpty() ? new String[0] : cleaned.split("\\s+");
    }

    /**
     * Parses a number starting at token i: digits, "five", "twenty three",
     * "one hundred twenty". "a"/"an" count as 1 only when allowArticle is set.
     */
    private static NumberMatch parseNumber(String[] t, int i, boolean allowArticle) {
        if (i >= t.length) return null;
        String w = t[i];
        if (w.matches("\\d{1,4}")) return new NumberMatch(Integer.parseInt(w), i + 1);
        if (allowArticle && (w.equals("a") || w.equals("an"))) return new NumberMatch(1, i + 1);

        int value;
        int j = i;
        if (SMALL_NUMBERS.containsKey(w)) {
            value = SMALL_NUMBERS.get(w);
            j++;
        } else if (TENS.containsKey(w)) {
            value = TENS.get(w);
            j++;
            if (j < t.length && SMALL_NUMBERS.containsKey(t[j]) && SMALL_NUMBERS.get(t[j]) > 0
                    && SMALL_NUMBERS.get(t[j]) < 10) {
                value += SMALL_NUMBERS.get(t[j]);
                j++;
            }
        } else {
            return null;
        }

        // "one hundred [and] twenty"
        if (j < t.length && t[j].equals("hundred") && value > 0 && value < 10) {
            value *= 100;
            j++;
            int k = (j < t.length && t[j].equals("and")) ? j + 1 : j;
            NumberMatch rest = parseNumber(t, k, false);
            if (rest != null && rest.value < 100) {
                value += rest.value;
                j = rest.next;
            }
        }
        return new NumberMatch(value, j);
    }

    private static boolean matchesAt(String[] t, int i, String... words) {
        if (i + words.length > t.length) return false;
        for (int k = 0; k < words.length; k++) {
            if (!t[i + k].equals(words[k])) return false;
        }
        return true;
    }

    private static Integer unitAt(String[] t, int i) {
        return i < t.length ? UNIT_SECONDS.get(t[i]) : null;
    }

    /**
     * Returns the total duration in seconds described in the text, or null if none.
     * Handles compound phrases ("one hour and thirty minutes"), halves ("two and a
     * half minutes", "half an hour", "an hour and a half"), and — as a fallback for
     * garbled transcriptions — a number anywhere before a unit word.
     */
    public static Integer parseDurationSeconds(String text) {
        String[] t = tokenize(text);
        double total = 0;
        boolean found = false;

        int i = 0;
        while (i < t.length) {
            // "half an hour" / "half a minute"
            if (t[i].equals("half") && i + 2 < t.length
                    && (t[i + 1].equals("a") || t[i + 1].equals("an")) && unitAt(t, i + 2) != null) {
                total += 0.5 * unitAt(t, i + 2);
                found = true;
                i += 3;
                continue;
            }
            // "half hour" / "a half hour"
            if (t[i].equals("half") && unitAt(t, i + 1) != null) {
                total += 0.5 * unitAt(t, i + 1);
                found = true;
                i += 2;
                continue;
            }

            NumberMatch n = parseNumber(t, i, true);
            if (n != null) {
                double value = n.value;
                int j = n.next;
                if (matchesAt(t, j, "and", "a", "half")) {
                    value += 0.5;
                    j += 3;
                }
                Integer unit = unitAt(t, j);
                if (unit != null) {
                    total += value * unit;
                    j++;
                    if (matchesAt(t, j, "and", "a", "half")) {
                        total += 0.5 * unit;
                        j += 3;
                    }
                    found = true;
                    i = j;
                    continue;
                }
            }
            i++;
        }

        if (!found) {
            // Fallback: first number (not an article) anywhere before the first unit word.
            Integer firstNumber = null;
            for (int k = 0; k < t.length; k++) {
                Integer unit = unitAt(t, k);
                if (unit != null) {
                    if (firstNumber != null) {
                        total = firstNumber * unit;
                        found = true;
                    }
                    break;
                }
                if (firstNumber == null) {
                    NumberMatch n = parseNumber(t, k, false);
                    if (n != null && n.value > 0) firstNumber = n.value;
                }
            }
        }

        if (!found || total <= 0) return null;
        return (int) Math.round(total);
    }

    /** An hour/minute pair with an optional explicit meridiem (true = PM). */
    private static final class ClockMatch {
        final int hour;
        final int minute;
        final Boolean pm;
        ClockMatch(int hour, int minute, Boolean pm) { this.hour = hour; this.minute = minute; this.pm = pm; }
    }

    private static boolean isHour(NumberMatch n) {
        return n != null && n.value >= 0 && n.value <= 23;
    }

    /** Reads "am"/"pm"/"a m"/"in the morning"/"tonight"... at index i. Returns {isPm, tokensConsumed} or null. */
    private static int[] parseMeridiem(String[] t, int i) {
        if (matchesAt(t, i, "a", "m") || matchesAt(t, i, "a", "dot", "m")) return new int[] { 0, t[i + 1].equals("dot") ? 3 : 2 };
        if (matchesAt(t, i, "p", "m") || matchesAt(t, i, "p", "dot", "m")) return new int[] { 1, t[i + 1].equals("dot") ? 3 : 2 };
        if (matchesAt(t, i, "am")) return new int[] { 0, 1 };
        if (matchesAt(t, i, "pm")) return new int[] { 1, 1 };
        if (matchesAt(t, i, "in", "the", "morning")) return new int[] { 0, 3 };
        if (matchesAt(t, i, "in", "the", "afternoon")) return new int[] { 1, 3 };
        if (matchesAt(t, i, "in", "the", "evening")) return new int[] { 1, 3 };
        if (matchesAt(t, i, "at", "night")) return new int[] { 1, 2 };
        if (matchesAt(t, i, "tonight")) return new int[] { 1, 1 };
        return null;
    }

    private static ClockMatch withMeridiem(String[] t, int j, int hour, int minute) {
        int[] mer = parseMeridiem(t, j);
        Boolean pm = mer == null ? null : mer[0] == 1;
        return new ClockMatch(hour, minute, pm);
    }

    /** Tries to read a clock time starting exactly at token i. */
    private static ClockMatch parseClockAt(String[] t, int i) {
        if (i >= t.length) return null;
        if (t[i].equals("noon") || matchesAt(t, i, "midday")) return new ClockMatch(12, 0, true);
        if (t[i].equals("midnight")) return new ClockMatch(0, 0, false);

        // "half past seven", "quarter past six", "quarter to eight", "ten past five", "twenty to nine"
        int minutesBefore = -1;
        int j = i;
        if (t[i].equals("half")) { minutesBefore = 30; j = i + 1; }
        else if (t[i].equals("quarter")) { minutesBefore = 15; j = i + 1; }
        else if (matchesAt(t, i, "a", "quarter")) { minutesBefore = 15; j = i + 2; }
        else {
            NumberMatch m = parseNumber(t, i, false);
            if (m != null && m.value > 0 && m.value < 60) {
                int k = m.next;
                if (k < t.length && (t[k].equals("minutes") || t[k].equals("minute"))) k++;
                if (k < t.length && (t[k].equals("past") || t[k].equals("after") || t[k].equals("to") || t[k].equals("till"))) {
                    minutesBefore = m.value;
                    j = k;
                }
            }
        }
        if (minutesBefore > 0 && j < t.length) {
            boolean past = t[j].equals("past") || t[j].equals("after");
            boolean to = t[j].equals("to") || t[j].equals("till");
            if (past || to) {
                NumberMatch h = parseNumber(t, j + 1, false);
                if (isHour(h) && h.value >= 1) {
                    int hour = h.value;
                    int minute = minutesBefore;
                    if (to) {
                        hour = (hour + 23) % 24;
                        minute = 60 - minutesBefore;
                    }
                    return withMeridiem(t, h.next, hour, minute);
                }
            }
        }

        // "seven", "seven thirty", "seven oh five", "seven o'clock", "7 colon 30", "seven forty five pm"
        NumberMatch h = parseNumber(t, i, false);
        if (!isHour(h)) return null;
        int hour = h.value;
        int k = h.next;
        int minute = 0;
        if (k < t.length && t[k].equals("colon")) k++;
        if (k < t.length && t[k].equals("oclock")) {
            k++;
        } else if (k < t.length && (t[k].equals("oh") || t[k].equals("o"))) {
            NumberMatch m = parseNumber(t, k + 1, false);
            if (m != null && m.value < 10) { minute = m.value; k = m.next; }
        } else if (k < t.length && t[k].matches("\\d{2}")) {
            int m = Integer.parseInt(t[k]);
            if (m < 60) { minute = m; k++; }
        } else {
            NumberMatch m = parseNumber(t, k, false);
            // Only accept "thirty", "forty five", "fifteen"... — not a second single digit
            // ("four seven" is more likely a misheard "for seven" than 4:07).
            if (m != null && m.value >= 10 && m.value < 60) { minute = m.value; k = m.next; }
        }
        // "five minutes" / "an hour" is a duration, not a clock time
        if (unitAt(t, h.next) != null || unitAt(t, k) != null) return null;
        return withMeridiem(t, k, hour, minute);
    }

    /**
     * Finds a clock time in the text and resolves it to the next matching moment after now.
     * Prefers times introduced by "at"/"for" ("wake me up at seven"). When no am/pm is given,
     * the soonest upcoming of the two readings wins (at 11pm, "seven" means 7am).
     * "tomorrow" forces the next calendar day. Returns null if no time was found.
     */
    public static LocalDateTime parseClockTime(String text, LocalDateTime now) {
        String[] t = tokenize(text);
        boolean tomorrow = false;
        for (String w : t) if (w.equals("tomorrow")) tomorrow = true;

        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < t.length - 1; i++) {
            if (t[i].equals("at") || t[i].equals("for")) candidates.add(i + 1);
        }
        for (int i = 0; i < t.length; i++) candidates.add(i);

        ClockMatch match = null;
        for (int idx : candidates) {
            match = parseClockAt(t, idx);
            if (match != null) break;
        }
        if (match == null) return null;

        List<LocalTime> readings = new ArrayList<>();
        if (match.pm != null) {
            int h = match.hour % 12 + (match.pm ? 12 : 0);
            if (match.hour > 12) h = match.hour; // "nineteen pm" — trust the 24h value
            readings.add(LocalTime.of(h, match.minute));
        } else if (match.hour >= 13 || match.hour == 0) {
            readings.add(LocalTime.of(match.hour, match.minute));
        } else {
            readings.add(LocalTime.of(match.hour % 12, match.minute));      // AM reading
            readings.add(LocalTime.of(match.hour % 12 + 12, match.minute)); // PM reading
        }

        LocalDate startDay = tomorrow ? now.toLocalDate().plusDays(1) : now.toLocalDate();
        LocalDateTime best = null;
        for (int dayOffset = 0; dayOffset < 2 && best == null; dayOffset++) {
            for (LocalTime reading : readings) {
                LocalDateTime candidate = LocalDateTime.of(startDay.plusDays(dayOffset), reading);
                if (candidate.isAfter(now) && (best == null || candidate.isBefore(best))) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    /** "1 hour 30 minutes", "5 minutes", "45 seconds". */
    public static String describeDuration(int totalSeconds) {
        int h = totalSeconds / 3600;
        int m = (totalSeconds % 3600) / 60;
        int s = totalSeconds % 60;
        List<String> parts = new ArrayList<>();
        if (h > 0) parts.add(h + (h == 1 ? " hour" : " hours"));
        if (m > 0) parts.add(m + (m == 1 ? " minute" : " minutes"));
        if (s > 0 || parts.isEmpty()) parts.add(s + (s == 1 ? " second" : " seconds"));
        return String.join(" ", parts);
    }
}
