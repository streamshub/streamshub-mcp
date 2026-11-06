/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafka;

import io.streamshub.mcp.strimzi.dto.kafka.KafkaClusterResponse;
import io.streamshub.mcp.strimzi.dto.kafka.UpgradePreflightFinding;
import io.streamshub.mcp.strimzi.dto.kafkatopic.KafkaTopicResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic upgrade pre-flight checks that do not depend on LLM judgement:
 * topic durability against rolling restarts, unclean leader election, and the Kafka version path.
 */
public final class UpgradePreflightChecks {

    /** Check name for topic durability findings. */
    public static final String CHECK_TOPIC_DURABILITY = "topic_durability";

    /** Check name for unclean leader election findings. */
    public static final String CHECK_UNCLEAN_ELECTION = "unclean_leader_election";

    /** Check name for version path findings. */
    public static final String CHECK_VERSION_PATH = "version_path";

    private static final String MIN_ISR = "min.insync.replicas";
    private static final String DEFAULT_REPLICATION_FACTOR = "default.replication.factor";
    private static final int KAFKA_DEFAULT_REPLICATION_FACTOR = 1;
    private static final int FIRST_BARE_KAFKA_MAJOR = 2;
    private static final String UNCLEAN = "unclean.leader.election.enable";
    private static final int MAX_LISTED = 10;
    private static final Pattern KAFKA_VERSION =
        Pattern.compile("^\\s*(kafka)?\\s*v?(\\d{1,9})\\.(\\d{1,9})(?:\\.(\\d{1,9}))?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern METADATA_VERSION = Pattern.compile("^(\\d{1,9})\\.(\\d{1,9}).*$");

    private UpgradePreflightChecks() {
    }

    /**
     * Check topic durability and unclean leader election.
     *
     * <p>A rolling restart takes one broker down at a time. A topic whose effective
     * {@code min.insync.replicas} is not lower than its replication factor rejects writes from
     * {@code acks=all} producers while a replica is down; a single-replica topic is unavailable
     * while its broker restarts. The effective value is the topic override, else the broker
     * default; when neither is set Kafka's default of 1 applies. A topic without
     * {@code spec.replicas} uses the broker's {@code default.replication.factor}, else Kafka's
     * default of 1 (so it is reported as a single-replica topic).</p>
     *
     * @param brokerConfig the {@code spec.kafka.config} map, or null when unavailable
     * @param topics       the KafkaTopic resources of the cluster
     * @return the findings, empty when nothing needs attention
     */
    public static List<UpgradePreflightFinding> topicFindings(final Map<String, Object> brokerConfig,
                                                              final List<KafkaTopicResponse> topics) {
        List<UpgradePreflightFinding> findings = new ArrayList<>();
        Integer brokerMinIsr = configInt(brokerConfig, MIN_ISR);
        Integer brokerReplicationFactor = configInt(brokerConfig, DEFAULT_REPLICATION_FACTOR);

        if (brokerConfig != null && asBool(brokerConfig.get(UNCLEAN))) {
            findings.add(UpgradePreflightFinding.warning(CHECK_UNCLEAN_ELECTION,
                "Broker config enables unclean.leader.election.enable: an out-of-sync replica may become leader "
                    + "during the rolling restart and acknowledged records can be lost."));
        }

        List<String> blocked = new ArrayList<>();
        List<String> singleReplica = new ArrayList<>();
        List<String> uncleanTopics = new ArrayList<>();

        for (KafkaTopicResponse topic : topics) {
            Map<String, Object> cfg = topic.configuration();
            if (cfg != null && asBool(cfg.get(UNCLEAN))) {
                uncleanTopics.add(topic.name());
            }
            int replicas;
            if (topic.replicas() != null) {
                replicas = topic.replicas();
            } else if (brokerReplicationFactor != null) {
                replicas = brokerReplicationFactor;
            } else {
                replicas = KAFKA_DEFAULT_REPLICATION_FACTOR;
            }
            if (replicas == 1) {
                singleReplica.add(topic.name());
                continue;
            }
            Integer topicMinIsr = configInt(cfg, MIN_ISR);
            Integer minIsr = topicMinIsr != null ? topicMinIsr : brokerMinIsr;
            if (minIsr != null && minIsr >= replicas) {
                blocked.add(topic.name() + " (replicas=" + replicas + ", min.insync.replicas=" + minIsr + ")");
            }
        }

        if (!blocked.isEmpty()) {
            findings.add(UpgradePreflightFinding.blocker(CHECK_TOPIC_DURABILITY,
                blocked.size() + " topic(s) have min.insync.replicas >= replication factor, so producers using "
                    + "acks=all cannot write while one broker restarts: " + list(blocked) + "."));
        }
        if (!singleReplica.isEmpty()) {
            findings.add(UpgradePreflightFinding.warning(CHECK_TOPIC_DURABILITY,
                singleReplica.size() + " topic(s) have a single replica and are unavailable while their broker "
                    + "restarts: " + list(singleReplica) + "."));
        }
        if (!uncleanTopics.isEmpty()) {
            findings.add(UpgradePreflightFinding.warning(CHECK_UNCLEAN_ELECTION,
                uncleanTopics.size() + " topic(s) enable unclean.leader.election.enable and may lose acknowledged "
                    + "records during the rolling restart: " + list(uncleanTopics) + "."));
        }
        return findings;
    }

    /**
     * Check the Kafka version path.
     *
     * <p>Compares the target with the running version and with the versions the installed Strimzi
     * operator supports, and detects an unfinalized earlier upgrade (metadata version older than the
     * running version). A target prefixed with {@code Strimzi}, or a bare version whose major is below 2
     * (Strimzi versions are {@code 0.x} and {@code 1.x}; Kafka is 2.x and later), is not checked; a
     * Kafka target below 2.0 needs the explicit {@code Kafka} prefix.</p>
     *
     * @param cluster           the cluster status
     * @param targetVersion     the user-supplied target (for example {@code Kafka 4.2.0}), may be null
     * @param supportedVersions Kafka versions supported by the installed operator, may be null or empty
     * @return the findings, empty when nothing needs attention
     */
    public static List<UpgradePreflightFinding> versionFindings(final KafkaClusterResponse cluster,
                                                                final String targetVersion,
                                                                final List<String> supportedVersions) {
        List<UpgradePreflightFinding> findings = new ArrayList<>();
        String running = cluster.runningKafkaVersion() != null ? cluster.runningKafkaVersion() : cluster.kafkaVersion();
        int[] runningParts = running != null ? parseVersion(running) : null;

        int[] metadataParts = parseMetadataVersion(cluster.kafkaMetadataVersion());
        if (runningParts != null && metadataParts != null && compare(metadataParts, runningParts, 2) < 0) {
            findings.add(UpgradePreflightFinding.warning(CHECK_VERSION_PATH,
                "Metadata version " + cluster.kafkaMetadataVersion() + " is older than the running Kafka version "
                    + running + ": an earlier upgrade has not been finalized. Finalize it before upgrading further."));
        }

        String target = parseKafkaTarget(targetVersion);
        if (target == null) {
            return findings;
        }
        int[] targetParts = parseVersion(target);

        if (supportedVersions != null && !supportedVersions.isEmpty() && !isSupported(target, supportedVersions)) {
            findings.add(UpgradePreflightFinding.blocker(CHECK_VERSION_PATH,
                "Target Kafka version " + target + " is not supported by the installed Strimzi operator "
                    + "(supported: " + String.join(", ", supportedVersions) + "). Upgrade the operator first."));
        }
        if (runningParts != null && targetParts != null) {
            int cmp = compare(targetParts, runningParts, targetParts[2] < 0 ? 2 : 3);
            if (cmp < 0) {
                findings.add(UpgradePreflightFinding.warning(CHECK_VERSION_PATH,
                    "Target Kafka version " + target + " is lower than the running version " + running
                        + ". This is a downgrade, which is restricted once the metadata version has been finalized."));
            } else if (cmp == 0 && targetParts[2] >= 0) {
                findings.add(UpgradePreflightFinding.warning(CHECK_VERSION_PATH,
                    "Target Kafka version " + target + " equals the running version " + running
                        + "; there is nothing to upgrade."));
            }
        }
        return findings;
    }

    /**
     * Extract a Kafka version from the user-supplied target.
     *
     * @param targetVersion the user-supplied target, may be null
     * @return the version text, or null when the target is absent, a Strimzi version, or unparseable
     */
    static String parseKafkaTarget(final String targetVersion) {
        if (targetVersion == null) {
            return null;
        }
        Matcher m = KAFKA_VERSION.matcher(targetVersion);
        if (!m.matches()) {
            return null;
        }
        boolean explicitKafka = m.group(1) != null;
        if (!explicitKafka && Integer.parseInt(m.group(2)) < FIRST_BARE_KAFKA_MAJOR) {
            return null;
        }
        return m.group(2) + "." + m.group(3) + (m.group(4) != null ? "." + m.group(4) : "");
    }

    private static boolean isSupported(final String target, final List<String> supported) {
        if (supported.contains(target)) {
            return true;
        }
        if (target.chars().filter(c -> c == '.').count() == 1) {
            return supported.stream().anyMatch(v -> v.startsWith(target + "."));
        }
        return false;
    }

    /** Parse {@code major.minor[.patch]} into {@code [major, minor, patch]}; patch is -1 when absent. */
    private static int[] parseVersion(final String version) {
        Matcher m = KAFKA_VERSION.matcher(version);
        if (!m.matches()) {
            return null;
        }
        return new int[] {
            Integer.parseInt(m.group(2)),
            Integer.parseInt(m.group(3)),
            m.group(4) != null ? Integer.parseInt(m.group(4)) : -1
        };
    }

    private static int[] parseMetadataVersion(final String metadataVersion) {
        if (metadataVersion == null) {
            return null;
        }
        Matcher m = METADATA_VERSION.matcher(metadataVersion.trim());
        if (!m.matches()) {
            return null;
        }
        return new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), 0};
    }

    private static int compare(final int[] a, final int[] b, final int parts) {
        for (int i = 0; i < parts; i++) {
            int x = Math.max(a[i], 0);
            int y = Math.max(b[i], 0);
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static String list(final List<String> names) {
        if (names.size() <= MAX_LISTED) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, MAX_LISTED)) + " and " + (names.size() - MAX_LISTED) + " more";
    }

    private static Integer configInt(final Map<String, Object> config, final String key) {
        return config != null ? asInt(config.get(key)) : null;
    }

    private static Integer asInt(final Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static boolean asBool(final Object value) {
        return value != null && "true".equals(value.toString().trim().toLowerCase(Locale.ROOT));
    }
}
