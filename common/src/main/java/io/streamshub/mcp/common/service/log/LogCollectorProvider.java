/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.log;

import io.streamshub.mcp.common.dto.LogFetchRequest;

/**
 * Provider interface for fetching log output from a pod or log source.
 *
 * <p>The default implementation ({@code streamshub-kubernetes}) reads logs
 * directly from Kubernetes pod logs via the Fabric8 client. Custom
 * implementations can query external log aggregation systems (e.g., Loki,
 * OpenSearch) by providing an alternative CDI bean.</p>
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
     * <p>All inputs are supplied via {@link io.streamshub.mcp.common.dto.LogFetchRequest}.
     * Implementations should request one extra line beyond {@code tailLines} for has-more
     * detection. The {@code filter}/{@code keywords} fields are filtering hints (see class
     * Javadoc). The {@code container} field, when non-null and non-blank, narrows collection
     * to that single container; providers that cannot narrow should ignore it gracefully.</p>
     *
     * @param request the per-pod fetch request
     * @return the log string (possibly pre-filtered), or null if no logs are available
     */
    String fetchLogs(LogFetchRequest request);
}
