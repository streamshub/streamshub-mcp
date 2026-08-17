/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.ToolCallException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link DiagnosticHelper} cancellation callback registration.
 */
class DiagnosticHelperTest {

    DiagnosticHelperTest() {
    }

    @Test
    void registerCancellationCallback_withNullCancellation_doesNotThrow() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        assertDoesNotThrow(() -> DiagnosticHelper.registerCancellationCallback(null, cancelled));
        assertFalse(cancelled.get(), "Flag should remain false when cancellation is null");
    }

    @Test
    @SuppressWarnings("unchecked")
    void registerCancellationCallback_withValidCancellation_setsFlag() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Cancellation cancellation = mock(Cancellation.class);

        ArgumentCaptor<Consumer<Optional<String>>> captor =
            ArgumentCaptor.forClass(Consumer.class);

        DiagnosticHelper.registerCancellationCallback(cancellation, cancelled);

        verify(cancellation).onCancelled(captor.capture());
        assertFalse(cancelled.get(), "Flag should be false before cancellation fires");

        captor.getValue().accept(Optional.empty());

        assertTrue(cancelled.get(), "Flag should be true after cancellation fires");
    }

    @Test
    @SuppressWarnings("unchecked")
    void registerCancellationCallback_withCancellationReason_setsFlag() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Cancellation cancellation = mock(Cancellation.class);

        ArgumentCaptor<Consumer<Optional<String>>> captor =
            ArgumentCaptor.forClass(Consumer.class);

        DiagnosticHelper.registerCancellationCallback(cancellation, cancelled);

        verify(cancellation).onCancelled(captor.capture());

        captor.getValue().accept(Optional.of("User cancelled operation"));

        assertTrue(cancelled.get(), "Flag should be true after cancellation with reason");
    }

    @Test
    void checkAsyncCancellation_withNullFlag_doesNotThrow() {
        assertDoesNotThrow(() -> DiagnosticHelper.checkAsyncCancellation(null));
    }

    @Test
    void checkAsyncCancellation_withFalseFlag_doesNotThrow() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        assertDoesNotThrow(() -> DiagnosticHelper.checkAsyncCancellation(cancelled));
    }

    @Test
    void checkAsyncCancellation_withTrueFlag_throws() {
        AtomicBoolean cancelled = new AtomicBoolean(true);
        Exception exception = assertThrows(
            ToolCallException.class,
            () -> DiagnosticHelper.checkAsyncCancellation(cancelled)
        );
        assertEquals("Operation cancelled", exception.getMessage());
    }
}
