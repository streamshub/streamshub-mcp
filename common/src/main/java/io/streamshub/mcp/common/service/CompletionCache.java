/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Short-lived cache for MCP auto-completion results.
 *
 * <p>Prevents redundant Kubernetes API calls during rapid keystroke-driven
 * completions. Each cache entry expires after a configurable TTL (default 5 seconds).</p>
 */
@ApplicationScoped
public class CompletionCache {

    private static final Logger LOG = Logger.getLogger(CompletionCache.class);

    @ConfigProperty(name = "mcp.completion.cache-ttl-seconds", defaultValue = "5")
    long cacheTtlSeconds;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    CompletionCache() {
    }

    /**
     * Get cached names or fetch from the supplier if the cache is empty or expired.
     *
     * @param cacheKey a unique key for this resource type (e.g., "kafka", "topic")
     * @param fetcher  a supplier that fetches the resource names from Kubernetes
     * @return the list of resource names (may be empty on error, never null)
     */
    public List<String> getOrFetch(final String cacheKey, final Supplier<List<String>> fetcher) {
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            return cached.names();
        }
        try {
            List<String> names = fetcher.get();
            cache.put(cacheKey, new CacheEntry(names,
                Instant.now().plusSeconds(cacheTtlSeconds)));
            return names;
        } catch (Exception e) {
            LOG.debugf("Error fetching %s names for completion: %s", cacheKey, e.getMessage());
            return List.of();
        }
    }

    private record CacheEntry(List<String> names, Instant expiry) {
        boolean isExpired() {
            return Instant.now().isAfter(expiry);
        }
    }
}
