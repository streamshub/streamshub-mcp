/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Locale;

/**
 * Shared completion helper for MCP auto-completions.
 *
 * <p>Provides generic Kubernetes completions (namespaces) and utility methods
 * for prefix-based filtering that can be reused by module-specific completion services.</p>
 */
@ApplicationScoped
public class CompletionHelper {

    private static final Logger LOG = Logger.getLogger(CompletionHelper.class);

    /** Maximum number of completion values returned per request. */
    public static final int MAX_COMPLETIONS = 50;

    @Inject
    KubernetesClient kubernetesClient;

    CompletionHelper() {
    }

    /**
     * Complete namespace names matching a partial input.
     *
     * @param partial the partial namespace input (may be null or empty)
     * @return list of matching namespace names, up to {@value MAX_COMPLETIONS}
     */
    public List<String> completeNamespace(final String partial) {
        try {
            List<Namespace> namespaces = kubernetesClient.namespaces().list().getItems();
            return filterByPrefix(
                namespaces.stream()
                    .map(ns -> ns.getMetadata().getName())
                    .toList(),
                partial
            );
        } catch (Exception e) {
            LOG.debugf("Error completing namespaces: %s", e.getMessage());
            return List.of();
        }
    }

    /**
     * Filter a list of names by case-insensitive prefix match.
     *
     * @param names   the full list of candidate names
     * @param partial the partial input to match (may be null or empty)
     * @return filtered and limited list of matching names
     */
    public List<String> filterByPrefix(final List<String> names, final String partial) {
        String prefix = partial == null ? "" : partial.toLowerCase(Locale.ROOT);
        return names.stream()
            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
            .limit(MAX_COMPLETIONS)
            .toList();
    }
}
