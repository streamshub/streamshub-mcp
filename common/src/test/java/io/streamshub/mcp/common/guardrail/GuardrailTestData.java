/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import java.util.function.BiFunction;

/**
 * Log text in the shape that triggered the size guardrail bug: OpenShift Logging (ViaQ) JSON records
 * inside a string, including nested escaped JSON, so JSON escaping inflates the serialized size well
 * beyond the character count.
 */
final class GuardrailTestData {

    private static final String LEVEL_FIELD = "\"level\":\"default\"";

    private GuardrailTestData() {
    }

    /**
     * Build one ViaQ-style log record.
     *
     * @param pod the pod index (used in the hostname, IP and message)
     * @param i   the line index (the message ends with {@code line <i>})
     * @return the record as one line of JSON text
     */
    static String viaqLine(final int pod, final int i) {
        return "{\"@timestamp\":\"2026-11-13T11:15:59.607345973Z\",\"hostname\":\"worker-" + pod + "\","
            + "\"kubernetes\":{\"annotations\":{\"k8s.v1.cni.cncf.io/network-status\":"
            + "\"[{\\\"name\\\":\\\"ovn-kubernetes\\\",\\\"interface\\\":\\\"eth0\\\","
            + "\\\"ips\\\":[\\\"10.128.2." + pod + "\\\"],\\\"default\\\":true}]\"},"
            + "\"namespace_labels\":{\"pod-security.kubernetes.io/warn\":\"restricted\"}},"
            + "\"level\":\"default\",\"message\":\"INFO [KafkaServer id=" + pod + "] line " + i + "\"}";
    }

    /**
     * Build a larger ViaQ-style log record: {@link #viaqLine(int, int)} with an extra {@code pad} field of
     * {@code padding} escaped {@code "x"} groups. The record serializes to 13 bytes more than the plain one,
     * plus 9 bytes per group: {@code viaqLine(0, 0, 400)} is 4,037 bytes and {@code viaqLine(0, 0, 2_750)}
     * is 25,187 bytes.
     *
     * @param pod     the pod index (used in the hostname, IP and message)
     * @param i       the line index (the message ends with {@code line <i>})
     * @param padding the number of escaped {@code "x"} groups to add
     * @return the record as one line of JSON text
     */
    static String viaqLine(final int pod, final int i, final int padding) {
        return viaqLine(pod, i).replace(LEVEL_FIELD,
            "\"pad\":\"" + "\\\"x\\\"".repeat(padding) + "\"," + LEVEL_FIELD);
    }

    /**
     * Build pod logs in the {@code LogCollectionService} format: a {@code === Pod: <name> ===} header
     * per pod followed by that pod's lines, every line ending in a newline.
     *
     * @param pods        the number of pods
     * @param linesPerPod the number of lines per pod
     * @return the pod logs text
     */
    static String podLogs(final int pods, final int linesPerPod) {
        return podLogs(pods, linesPerPod, GuardrailTestData::viaqLine);
    }

    /**
     * Build pod logs in the {@code LogCollectionService} format with lines from the given factory.
     *
     * @param pods        the number of pods
     * @param linesPerPod the number of lines per pod
     * @param line        builds the line for a pod index and a line index
     * @return the pod logs text
     */
    static String podLogs(final int pods, final int linesPerPod, final BiFunction<Integer, Integer, String> line) {
        StringBuilder text = new StringBuilder();
        for (int p = 0; p < pods; p++) {
            text.append("=== Pod: mcp-cluster-broker-np1-").append(p).append(" ===\n");
            for (int i = 0; i < linesPerPod; i++) {
                text.append(line.apply(p, i)).append('\n');
            }
        }
        return text.toString();
    }
}
