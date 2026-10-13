/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.compose;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.CustomResource;
import io.github.green4j.kjucon.status.StandardStatus;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/** Finds - or creates - the {@link StandardStatus} of a custom resource. */
final class StandardStatuses {
    private StandardStatuses() {
    }

    /**
     * @param resource whose status to find
     * @return its standard status, created if the resource has none yet; null if its status is of another type
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static StandardStatus of(final HasMetadata resource) {
        if (!(resource instanceof CustomResource)) {
            return null;
        }
        final CustomResource custom = (CustomResource) resource;
        final Object status = custom.getStatus();
        if (status instanceof StandardStatus) {
            return (StandardStatus) status;
        }
        if (status != null) {
            return null;
        }
        final Class<?> type = statusType(resource.getClass());
        if (type == null || !StandardStatus.class.isAssignableFrom(type)) {
            return null;
        }
        try {
            final StandardStatus created = (StandardStatus) type.getDeclaredConstructor().newInstance();
            custom.setStatus(created);
            return created;
        } catch (final InstantiationException | IllegalAccessException | InvocationTargetException
                       | NoSuchMethodException e) {
            throw new IllegalStateException("Cannot create the status " + type.getName(), e);
        }
    }

    /**
     * @param resourceType of a resource
     * @return true if it is a {@code CustomResource} declaring a {@link StandardStatus} - one a kjucon
     *         controller reports on
     */
    static boolean isDeclaredBy(final Class<?> resourceType) {
        final Class<?> type = statusType(resourceType);
        return type != null && StandardStatus.class.isAssignableFrom(type);
    }

    private static Class<?> statusType(final Class<?> resourceType) {
        Class<?> current = resourceType;
        while (current != null && current != Object.class) {
            final Type superType = current.getGenericSuperclass();
            if (superType instanceof ParameterizedType
                    && ((ParameterizedType) superType).getRawType() == CustomResource.class) {
                final Type status = ((ParameterizedType) superType).getActualTypeArguments()[1];
                return status instanceof Class ? (Class<?>) status : null;
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
