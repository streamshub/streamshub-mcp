/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Options for log collection from Kubernetes pods.
 *
 * <p>Encapsulates filtering, pagination, and callback parameters
 * for {@link io.streamshub.mcp.common.service.log.LogCollectionService#collectLogs}.</p>
 *
 * @param filter           optional filter: "errors", "warnings", or a regex pattern
 * @param keywords         optional list of keywords to match lines against (case-insensitive)
 * @param sinceSeconds     optional relative time range in seconds (mutually exclusive with startTime/endTime)
 * @param startTime        optional absolute start time in ISO 8601 format (use with endTime)
 * @param endTime          optional absolute end time in ISO 8601 format (use with startTime)
 * @param tailLines        number of log lines to tail per pod
 * @param previous         if true, retrieve logs from the previous container instance
 * @param notifier         optional callback for textual per-pod log notifications
 * @param cancelCheck      optional callback invoked before each pod; should throw to abort
 * @param progressCallback optional callback receiving (completedPods, totalPods) after each pod
 */
public record LogCollectionParams(
    String filter,
    List<String> keywords,
    Integer sinceSeconds,
    String startTime,
    String endTime,
    int tailLines,
    Boolean previous,
    Consumer<String> notifier,
    Runnable cancelCheck,
    BiConsumer<Integer, Integer> progressCallback
) {

    /**
     * Create options with only basic filtering parameters.
     *
     * @param filter       optional log filter
     * @param sinceSeconds optional time range in seconds
     * @param tailLines    number of lines to tail per pod
     * @param previous     if true, retrieve previous container logs
     * @return log collection options with no keywords or callbacks
     */
    public static LogCollectionParams of(final String filter, final Integer sinceSeconds,
                                         final int tailLines, final Boolean previous) {
        return new LogCollectionParams(filter, null, sinceSeconds, null, null,
            tailLines, previous, null, null, null);
    }

    /**
     * Create a new builder for constructing log collection options.
     *
     * @param tailLines number of log lines to tail per pod
     * @return a new builder instance
     */
    public static Builder builder(final int tailLines) {
        return new Builder(tailLines);
    }

    /**
     * Builder for constructing {@link LogCollectionParams} instances.
     */
    public static final class Builder {

        private String filter;
        private List<String> keywords;
        private Integer sinceSeconds;
        private String startTime;
        private String endTime;
        private final int tailLines;
        private Boolean previous;
        private Consumer<String> notifier;
        private Runnable cancelCheck;
        private BiConsumer<Integer, Integer> progressCallback;

        private Builder(final int tailLines) {
            this.tailLines = tailLines;
        }

        /**
         * Set the log filter.
         *
         * @param filter "errors", "warnings", regex, or null
         * @return this builder
         */
        public Builder filter(final String filter) {
            this.filter = filter;
            return this;
        }

        /**
         * Set keywords for line matching.
         *
         * @param keywords list of keywords (case-insensitive)
         * @return this builder
         */
        public Builder keywords(final List<String> keywords) {
            this.keywords = keywords;
            return this;
        }

        /**
         * Set the time range for log retrieval.
         *
         * @param sinceSeconds only return logs newer than this many seconds
         * @return this builder
         */
        public Builder sinceSeconds(final Integer sinceSeconds) {
            this.sinceSeconds = sinceSeconds;
            return this;
        }

        /**
         * Set the absolute start time for log retrieval.
         *
         * @param startTime ISO 8601 start time
         * @return this builder
         */
        public Builder startTime(final String startTime) {
            this.startTime = startTime;
            return this;
        }

        /**
         * Set the absolute end time for log retrieval.
         *
         * @param endTime ISO 8601 end time
         * @return this builder
         */
        public Builder endTime(final String endTime) {
            this.endTime = endTime;
            return this;
        }

        /**
         * Set whether to retrieve previous container logs.
         *
         * @param previous true for previous container logs
         * @return this builder
         */
        public Builder previous(final Boolean previous) {
            this.previous = previous;
            return this;
        }

        /**
         * Set the textual notification callback.
         *
         * @param notifier callback receiving per-pod progress messages
         * @return this builder
         */
        public Builder notifier(final Consumer<String> notifier) {
            this.notifier = notifier;
            return this;
        }

        /**
         * Set the cancellation check callback.
         *
         * @param cancelCheck callback invoked before each pod; should throw to abort
         * @return this builder
         */
        public Builder cancelCheck(final Runnable cancelCheck) {
            this.cancelCheck = cancelCheck;
            return this;
        }

        /**
         * Set the progress callback.
         *
         * @param progressCallback callback receiving (completedPods, totalPods)
         * @return this builder
         */
        public Builder progressCallback(final BiConsumer<Integer, Integer> progressCallback) {
            this.progressCallback = progressCallback;
            return this;
        }

        /**
         * Build the log collection options.
         *
         * @return the constructed options
         */
        public LogCollectionParams build() {
            return new LogCollectionParams(filter, keywords, sinceSeconds, startTime, endTime,
                tailLines, previous, notifier, cancelCheck, progressCallback);
        }
    }
}
