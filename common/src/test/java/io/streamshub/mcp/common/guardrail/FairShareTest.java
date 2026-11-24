/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link FairShare}: it matches a plain fair share when items can use any number of bytes, hands
 * bytes an item cannot use (it keeps whole lines only) to the other items, never exceeds the budget, and stops
 * after a bounded number of passes.
 */
class FairShareTest {

    FairShareTest() {
    }

    @Test
    void testAllocateMatchesFairShareWhenUsageIsContinuous() {
        FairShare.Usage exact = (item, allowance) -> allowance;

        assertArrayEquals(new long[] {3_000, 30_000, 44_000},
            FairShare.allocate(new long[] {3_000, 30_000, 500_000}, 77_000, exact));
        assertArrayEquals(new long[] {35_000, 35_000},
            FairShare.allocate(new long[] {300_000, 300_000}, 70_000, exact));
        assertArrayEquals(new long[] {0, 10, 20}, FairShare.allocate(new long[] {0, 10, 20}, 30, exact));
        assertArrayEquals(new long[] {0, 0, 0}, FairShare.allocate(new long[] {0, 10, 20}, 0, exact));
    }

    @Test
    void testAllocateRollsUnusedShareOnToLaterItems() {
        long[] sizes = new long[12];
        Arrays.fill(sizes, 800_000);
        FairShare.Usage lines = (item, allowance) -> allowance / 4_000 * 4_000;

        long[] kept = keptBytes(sizes, FairShare.allocate(sizes, 70_000, lines), lines);

        // 68,000 bytes kept; a single fair-share cap of 5,833 keeps one 4,000-byte line each, 48,000 in total
        assertArrayEquals(new long[] {4_000, 4_000, 4_000, 4_000, 4_000, 4_000, 4_000,
            8_000, 8_000, 8_000, 8_000, 8_000}, kept);
    }

    @Test
    void testAllocateGivesShareAnItemCannotUseToTheOthers() {
        long[] sizes = {100_000, 100_000, 100_000, 125_000};
        // item 3's first (newest) line is 25,000 bytes, more than its share; the others have 500-byte lines
        FairShare.Usage usage = (item, allowance) -> {
            if (item < 3) {
                return allowance / 500 * 500;
            }
            return allowance < 25_000 ? 0 : 25_000 + (allowance - 25_000) / 500 * 500;
        };

        long[] kept = keptBytes(sizes, FairShare.allocate(sizes, 70_000, usage), usage);

        assertArrayEquals(new long[] {22_500, 23_500, 23_500, 0}, kept);
    }

    @Test
    void testAllocateNeverExceedsBudget() {
        Random random = new Random(42);
        for (int run = 0; run < 2_000; run++) {
            int count = 1 + random.nextInt(8);
            long[] sizes = new long[count];
            long[] lineSizes = new long[count];
            for (int i = 0; i < count; i++) {
                sizes[i] = random.nextInt(200_000);
                lineSizes[i] = 1 + random.nextInt(30_000);
            }
            long budget = random.nextInt(150_000);
            FairShare.Usage lines = (item, allowance) -> allowance / lineSizes[item] * lineSizes[item];

            long[] allowance = FairShare.allocate(sizes, budget, lines);

            long[] kept = keptBytes(sizes, allowance, lines);
            String scenario = "run " + run + ": sizes " + Arrays.toString(sizes) + ", budget " + budget;
            assertTrue(Arrays.stream(kept).sum() <= budget, scenario);
            for (int i = 0; i < count; i++) {
                assertTrue(allowance[i] <= sizes[i], scenario);
            }
            assertArrayEquals(allowance, FairShare.allocate(sizes, budget, lines), scenario);
        }
    }

    @Test
    void testAllocateStopsAfterMaxPasses() {
        int[] calls = {0};

        FairShare.allocate(new long[] {1_000_000}, 500_000, (item, allowance) -> Math.min(allowance, ++calls[0]));

        assertEquals(FairShare.MAX_PASSES, calls[0]);
    }

    @Test
    void testAllocateRejectsUsageAboveAllowance() {
        assertArrayEquals(new long[] {0},
            FairShare.allocate(new long[] {10_000}, 5_000, (item, allowance) -> allowance + 1));
    }

    private static long[] keptBytes(final long[] sizes, final long[] allowance, final FairShare.Usage usage) {
        long[] kept = new long[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            kept[i] = allowance[i] >= sizes[i] ? sizes[i] : usage.used(i, allowance[i]);
        }
        return kept;
    }
}
