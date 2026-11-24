/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import java.util.Comparator;
import java.util.stream.IntStream;

/**
 * Shares a byte budget fairly across items that can use it only in steps, such as whole log lines.
 *
 * <p>Pass 1 fills the items in ascending size order: each item gets an equal share of what is still unspent,
 * so an item under its share stays whole, and the bytes an item cannot use roll on to the larger items after
 * it. Later passes hand what is still unspent to the items that were cut, the same way, until a pass adds
 * nothing. When every item can use any number of bytes, the result equals a plain fair share (water-filling).
 * Used for the pod sections of one log text ({@link TextTruncator}) and for the large text fields of one
 * response ({@link ResponseSizeLimitGuardrail}).</p>
 *
 * <p>Contract of {@link #allocate}: one allowance per item, in input order. A whole item gets an allowance
 * equal to its size; a cut item keeps {@code usage.used(i, allowance[i])}, and an allowance of 0 means it keeps
 * nothing. What the items keep never adds up to more than the budget. A budget of 0 or less gives every item
 * an allowance of 0. Items of equal size are ordered by index, so the result is deterministic.</p>
 */
final class FairShare {

    /**
     * Upper bound on allocation passes. Pass 1 is already one fair-share fill and later passes only spend
     * what is left, which shrinks quickly; the bound stops a pathological {@link Usage} from looping, and
     * every pass leaves an allocation within budget.
     */
    static final int MAX_PASSES = 16;

    private FairShare() {
    }

    /**
     * The bytes an item actually keeps when it is allowed a number of bytes.
     */
    @FunctionalInterface
    interface Usage {

        /**
         * Bytes item {@code item} keeps when allowed {@code allowance} bytes: at most {@code allowance}, and
         * the same for the same arguments. Never called with an allowance at or above the item's size.
         *
         * @param item      the item index
         * @param allowance the bytes the item may use
         * @return the bytes the item keeps
         */
        long used(int item, long allowance);
    }

    /**
     * Share a budget across items, handing bytes an item cannot use to the others.
     *
     * @param sizes  the full size of each item
     * @param budget the bytes to share
     * @param usage  the bytes an item keeps for a given allowance
     * @return the allowance of each item, in input order
     */
    static long[] allocate(final long[] sizes, final long budget, final Usage usage) {
        int count = sizes.length;
        int[] order = IntStream.range(0, count).boxed()
            .sorted(Comparator.<Integer>comparingLong(i -> sizes[i]).thenComparingInt(i -> i))
            .mapToInt(Integer::intValue)
            .toArray();
        long[] allowance = new long[count];
        long[] used = new long[count];
        long spent = 0;
        boolean progressed = true;
        for (int pass = 0; pass < MAX_PASSES && progressed && spent < budget; pass++) {
            progressed = false;
            long remaining = budget - spent;
            int open = openItems(sizes, used);
            for (int i : order) {
                if (used[i] >= sizes[i]) {
                    continue;
                }
                long share = remaining / open;
                open--;
                long candidate = Math.min(sizes[i], used[i] + share);
                long kept = candidate >= sizes[i] ? sizes[i] : usage.used(i, candidate);
                if (kept > used[i] && kept <= candidate) {
                    remaining -= kept - used[i];
                    spent += kept - used[i];
                    used[i] = kept;
                    allowance[i] = candidate;
                    progressed = true;
                }
            }
        }
        return allowance;
    }

    private static int openItems(final long[] sizes, final long[] used) {
        int open = 0;
        for (int i = 0; i < sizes.length; i++) {
            if (used[i] < sizes[i]) {
                open++;
            }
        }
        return open;
    }
}
