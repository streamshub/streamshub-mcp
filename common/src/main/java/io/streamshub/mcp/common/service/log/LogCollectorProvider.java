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
 * <p>Providers apply the {@code filter} and {@code keywords} parameters themselves:
 * providers with server-side filtering (e.g., Loki via LogQL, OpenSearch) put them in
 * the backend query, and providers without it (e.g., Kubernetes pod logs) filter the
 * fetched lines with {@link LogFilterUtils}. {@link LogCollectionService} does not
 * filter again; it counts errors, deduplicates and aggregates the returned lines.</p>
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

    /**
     * Describe a limit the provider applied that the request did not ask for (for example a
     * default time window), or null. {@link LogCollectionService} adds a non-null note to the
     * {@code warnings} of the result, so the caller knows what was not searched.
     *
     * @param request a fetch request of the collection (namespace, filters and time range)
     * @return a short note for the response warnings, or null when the request was honoured as given
     */
    default String searchScopeNote(final LogFetchRequest request) {
        return null;
    }

    /**
     * Explain an empty result for a request that named a {@code container}, or return null.
     * {@link LogCollectionService} calls this at most once per collection, only when the
     * request has a container and no pod returned any line, and adds a non-null hint to the
     * {@code warnings} of the result. Providers that map the container through a configurable
     * label or field (for example Loki) can use it to point at a wrong mapping, and may run a
     * cheap probe query to tell a wrong mapping from a filtered search or a short time window
     * that genuinely matched nothing.
     * Providers without such a mapping return null (the default).
     *
     * @param request a fetch request of the collection (namespace, first pod, container, filters, time range)
     * @return a short hint for the response warnings, or null
     */
    default String emptyContainerHint(final LogFetchRequest request) {
        return null;
    }
}
