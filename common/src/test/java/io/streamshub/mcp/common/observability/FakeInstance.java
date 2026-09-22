/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.observability;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.stream.Stream;

/**
 * Minimal {@link Instance} fake for unit tests: either resolves to a single value
 * or reports itself unsatisfied.
 *
 * @param <T> the bean type
 */
class FakeInstance<T> implements Instance<T> {

    private final T value;

    private FakeInstance(final T value) {
        this.value = value;
    }

    static <T> FakeInstance<T> of(final T value) {
        return new FakeInstance<>(value);
    }

    static <T> FakeInstance<T> unresolvable() {
        return new FakeInstance<>(null);
    }

    @Override
    public T get() {
        if (value == null) {
            throw new UnsupportedOperationException("unresolvable");
        }
        return value;
    }

    @Override
    public boolean isResolvable() {
        return value != null;
    }

    @Override
    public boolean isAmbiguous() {
        return false;
    }

    @Override
    public boolean isUnsatisfied() {
        return value == null;
    }

    @Override
    public void destroy(final T instance) {
    }

    @Override
    public Handle<T> getHandle() {
        return null;
    }

    @Override
    public Iterable<? extends Handle<T>> handles() {
        return null;
    }

    @Override
    public <U extends T> Instance<U> select(final Class<U> subtype, final Annotation... qualifiers) {
        return null;
    }

    @Override
    public <U extends T> Instance<U> select(final TypeLiteral<U> subtype, final Annotation... qualifiers) {
        return null;
    }

    @Override
    public Instance<T> select(final Annotation... qualifiers) {
        return null;
    }

    @Override
    public Iterator<T> iterator() {
        return null;
    }

    @Override
    public Stream<T> stream() {
        return null;
    }
}
