/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.log;

import java.util.List;

/**
 * Provider interface for fetching log output from a pod or log source.
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
 * <p>The {@code filter} and {@code keywords} parameters are filtering hints.
 * Providers that support server-side filtering (e.g., Loki via LogQL) should
 * use them to reduce data transfer. Providers that do not support server-side
 * filtering (e.g., Kubernetes pod logs) should ignore them —
 * {@link LogCollectionService} applies client-side filtering as a fallback.</p>
 */
public interface LogCollectorProvider {

    /**
     * Fetch log output from a single pod or log source.
     *
     * @param namespace    the Kubernetes namespace of the pod
     * @param podName      the name of the pod
     * @param tailLines    the number of lines to tail (implementations should request
     *                     one extra line for has-more detection)
     * @param sinceSeconds optional relative time range in seconds; null for no time restriction.
     *                     Mutually exclusive with startTime/endTime.
     * @param previous     if true, retrieve logs from the previous container instance
     * @param filter       optional filter: "errors", "warnings", or a regex pattern;
     *                     providers that support server-side filtering should apply this
     * @param keywords     optional list of keywords for line matching;
     *                     providers that support server-side filtering should apply this
     * @param startTime    optional absolute start time in ISO 8601 format (use with endTime)
     * @param endTime      optional absolute end time in ISO 8601 format (use with startTime)
     * @return the log string (possibly pre-filtered), or null if no logs are available
     */
    String fetchLogs(String namespace, String podName,
                     int tailLines, Integer sinceSeconds, Boolean previous,
                     String filter, List<String> keywords,
                     String startTime, String endTime);
}
