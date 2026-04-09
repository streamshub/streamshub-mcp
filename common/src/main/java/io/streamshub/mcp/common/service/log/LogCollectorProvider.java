/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.log;

/**
 * Provider interface for fetching raw log output from a pod or log source.
 *
 * <p>The default implementation ({@code streamshub-kubernetes}) reads logs
 * directly from Kubernetes pod logs via the Fabric8 client. Custom
 * implementations can query external log aggregation systems (e.g., Loki,
 * Elasticsearch) by providing an alternative CDI bean.</p>
 *
 * <p>To create a custom provider:</p>
 * <ol>
 *   <li>Implement this interface in an {@code @ApplicationScoped} bean</li>
 *   <li>Annotate it with
 *       {@code @LookupIfProperty(name = "mcp.log.provider", stringValue = "my-provider")}</li>
 *   <li>Set {@code mcp.log.provider=my-provider} in configuration</li>
 * </ol>
 *
 * <p>Filtering, deduplication, keyword matching, and progress callbacks
 * are handled by {@link LogCollectionService} and are not the responsibility
 * of the provider.</p>
 */
public interface LogCollectorProvider {

    /**
     * Fetch raw log output from a single pod or log source.
     *
     * @param namespace    the Kubernetes namespace of the pod
     * @param podName      the name of the pod
     * @param tailLines    the number of lines to tail (implementations should request
     *                     one extra line for has-more detection)
     * @param sinceSeconds optional time range in seconds; null for no time restriction
     * @param previous     if true, retrieve logs from the previous container instance
     * @return the raw log string, or null if no logs are available
     */
    String fetchLogs(String namespace, String podName,
                     int tailLines, Integer sinceSeconds, Boolean previous);
}
