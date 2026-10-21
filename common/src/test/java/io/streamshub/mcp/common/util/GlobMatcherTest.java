/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link GlobMatcher} prefix/glob name matching.
 */
class GlobMatcherTest {

    GlobMatcherTest() {
    }

    @Test
    void testNoWildcardIsPrefix() {
        assertTrue(GlobMatcher.matches("mcp", "mcp-topic-alpha"));
        assertTrue(GlobMatcher.matches("mcp-topic-alpha", "mcp-topic-alpha"));
        assertFalse(GlobMatcher.matches("other", "mcp-topic-alpha"));
    }

    @Test
    void testStarWildcard() {
        assertTrue(GlobMatcher.matches("mcp-*", "mcp-topic"));
        assertTrue(GlobMatcher.matches("mcp-*-alpha", "mcp-topic-alpha"));
        assertTrue(GlobMatcher.matches("*alpha", "mcp-alpha"));
        assertFalse(GlobMatcher.matches("mcp-*", "other-topic"));
    }

    @Test
    void testQuestionMarkMatchesExactlyOneChar() {
        assertTrue(GlobMatcher.matches("topic-?", "topic-a"));
        assertFalse(GlobMatcher.matches("topic-?", "topic-ab"));
        assertFalse(GlobMatcher.matches("topic-?", "topic-"));
    }

    @Test
    void testRegexMetacharactersAreLiteral() {
        assertFalse(GlobMatcher.matches("a.b", "aXb"));
        assertTrue(GlobMatcher.matches("a.*", "a.b"));
        assertFalse(GlobMatcher.matches("a.*", "aXb"));
    }

    @Test
    void testCaseSensitiveAndNullValue() {
        assertFalse(GlobMatcher.matches("MCP", "mcp-topic"));
        assertFalse(GlobMatcher.matches("mcp*", null));
    }

    @Test
    void testPathologicalGlobDoesNotCatastrophicallyBacktrack() {
        // A pattern with many '*' separated by a literal, matched against a value that cannot
        // satisfy the trailing literal, triggers catastrophic backtracking in a naive glob->regex
        // translation. The matcher must run in linear time because nameFilter is untrusted input.
        String pattern = "a*a*a*a*a*a*a*a*a*a*a*a*X";
        String value = "a".repeat(60);
        assertFalse(
            assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> GlobMatcher.matches(pattern, value)),
            "pattern with an unsatisfiable trailing literal must not match");
    }
}
