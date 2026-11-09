/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
/**
 * A finding computed deterministically by the upgrade readiness pre-flight checks.
 *
 * @param severity {@code BLOCKER} (the upgrade must not proceed) or {@code WARNING} (needs a conscious decision)
 * @param check    the check that produced the finding (for example {@code topic_durability})
 * @param message  a human-readable description with the affected resources
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UpgradePreflightFinding(
    @JsonProperty("severity") Severity severity,
    @JsonProperty("check") String check,
    @JsonProperty("message") String message
) {

    /**
     * Severity of a pre-flight finding, serialized by name.
     */
    public enum Severity {
        /** The upgrade must not proceed. */
        BLOCKER,
        /** Needs attention but does not block the upgrade by itself. */
        WARNING
    }

    /**
     * Creates a blocking finding.
     *
     * @param check   the check name
     * @param message the description
     * @return a new finding with severity {@link Severity#BLOCKER}
     */
    public static UpgradePreflightFinding blocker(final String check, final String message) {
        return new UpgradePreflightFinding(Severity.BLOCKER, check, message);
    }

    /**
     * Creates a warning finding.
     *
     * @param check   the check name
     * @param message the description
     * @return a new finding with severity {@link Severity#WARNING}
     */
    public static UpgradePreflightFinding warning(final String check, final String message) {
        return new UpgradePreflightFinding(Severity.WARNING, check, message);
    }
}
