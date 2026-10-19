/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

/**
 * Matches a string against a prefix or glob pattern.
 *
 * <p>A pattern containing no wildcard is a case-sensitive prefix match. A pattern containing
 * {@code *} (any sequence, including empty) or {@code ?} (exactly one character) is matched,
 * anchored and case-sensitively, over the whole value; every other character is a literal.</p>
 *
 * <p>Glob matching uses a two-pointer scan that backtracks only to the most recent {@code *}
 * (O(pattern × value) worst case) rather than translating to a regular expression.
 * {@code nameFilter} is untrusted client input, and a glob-to-regex translation (each {@code *}
 * becoming {@code .*}) is prone to catastrophic backtracking (ReDoS) on crafted patterns; the
 * two-pointer scan has no such failure mode.</p>
 */
public final class GlobMatcher {

    private GlobMatcher() {
    }

    /**
     * Test whether {@code value} matches {@code pattern}.
     *
     * @param pattern the prefix or glob pattern (non-null, non-blank; callers normalize)
     * @param value   the value to test, or null
     * @return true if {@code value} is non-null and matches {@code pattern}
     */
    public static boolean matches(final String pattern, final String value) {
        if (value == null) {
            return false;
        }
        if (pattern.indexOf('*') < 0 && pattern.indexOf('?') < 0) {
            return value.startsWith(pattern);
        }
        return globMatches(pattern, value);
    }

    /**
     * Anchored glob match using greedy two-pointer backtracking to the most recent {@code *}.
     * Runs in O(pattern × value) worst case with no exponential blow-up, so crafted patterns
     * cannot trigger catastrophic (ReDoS-style) backtracking.
     */
    private static boolean globMatches(final String pattern, final String value) {
        int patternLength = pattern.length();
        int valueLength = value.length();
        int p = 0;
        int v = 0;
        int starIndex = -1;
        int valueAfterStar = 0;
        while (v < valueLength) {
            if (p < patternLength && (pattern.charAt(p) == '?' || pattern.charAt(p) == value.charAt(v))) {
                p++;
                v++;
            } else if (p < patternLength && pattern.charAt(p) == '*') {
                starIndex = p;
                valueAfterStar = v;
                p++;
            } else if (starIndex >= 0) {
                p = starIndex + 1;
                valueAfterStar++;
                v = valueAfterStar;
            } else {
                return false;
            }
        }
        while (p < patternLength && pattern.charAt(p) == '*') {
            p++;
        }
        return p == patternLength;
    }
}
