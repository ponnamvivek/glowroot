/*
 * Copyright 2012-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.dynamicadvice;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.MapMaker;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public class Beans {

    private static final Logger logger = LoggerFactory.getLogger(Beans.class);

    // sentinel method is used to represent null value in the weak valued ConcurrentMap below
    // using guava's Optional would make the weakness on the Optional instance instead of on the
    // Method instance which would cause unnecessary clearing of the map values
    private static final Method SENTINEL_METHOD;

    static {
        try {
            SENTINEL_METHOD = Beans.class.getDeclaredMethod("sentinelMethod");
        } catch (NoSuchMethodException e) {
            // unrecoverable error
            throw new AssertionError(e);
        } catch (SecurityException e) {
            // unrecoverable error
            throw new AssertionError(e);
        }
    }

    // note, not using nested loading cache since the nested loading cache maintains a strong
    // reference to the class loader
    //
    // weak keys in loading cache to prevent Class retention
    private static final LoadingCache<Class<?>, ConcurrentMap<String, AccessibleObject>> getters =
            CacheBuilder.newBuilder().weakKeys()
                    .build(new CacheLoader<Class<?>, ConcurrentMap<String, AccessibleObject>>() {
                        @Override
                        public ConcurrentMap<String, AccessibleObject> load(Class<?> type) {
                            // weak values since Method has a strong reference to its Class which
                            // is used as the key in the outer loading cache
                            return new MapMaker().weakValues().makeMap();
                        }
                    });

    private Beans() {}

    @Nullable
    public static Object value(@Nullable Object obj, String[] path) {
        return value(obj, path, 0);
    }

    @Nullable
    public static Object value(@Nullable Object obj, String[] path, int currIndex) {
        if (obj == null) {
            return null;
        }
        if (currIndex == path.length) {
            return obj;
        }
        String curr = path[currIndex];
        if (obj instanceof Map) {
            return value(((Map<?, ?>) obj).get(curr), path, currIndex + 1);
        }
        try {
            AccessibleObject accessor = getAccessor(obj.getClass(), curr);
            if (accessor.equals(SENTINEL_METHOD)) {
                // no appropriate method found, dynamic paths that may or may not resolve
                // correctly are ok, just return null
                return null;
            }
            Object currItem;
            if (accessor instanceof Method) {
                currItem = ((Method) accessor).invoke(obj);
            } else {
                currItem = ((Field) accessor).get(obj);
            }
            return value(currItem, path, currIndex + 1);
        } catch (IllegalAccessException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            return "<could not access>";
        } catch (IllegalArgumentException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            return "<could not access>";
        } catch (InvocationTargetException e) {
            // log exception at debug level
            logger.debug(e.getMessage(), e);
            return "<could not access>";
        }
    }

    private static AccessibleObject getAccessor(Class<?> type, String name) {
        ConcurrentMap<String, AccessibleObject> accessorsForType = getters.getUnchecked(type);
        AccessibleObject accessor = accessorsForType.get(name);
        if (accessor == null) {
            accessor = findAccessor(type, name);
            if (accessor == null) {
                accessor = SENTINEL_METHOD;
            }
            accessor.setAccessible(true);
            accessorsForType.put(name, accessor);
        }
        return accessor;
    }

    @Nullable
    public static AccessibleObject findAccessor(Class<?> type, String name) {
        String capitalizedName = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        try {
            return getMethod(type, "get" + capitalizedName);
        } catch (ReflectiveException e) {
            // log exception at trace level
            logger.trace(e.getMessage(), e);
            // fall back to "is" prefix
            try {
                return getMethod(type, "is" + capitalizedName);
            } catch (ReflectiveException f) {
                // log exception at trace level
                logger.trace(f.getMessage(), f);
                // fall back to no prefix
                try {
                    return getMethod(type, name);
                } catch (ReflectiveException g) {
                    // log exception at trace level
                    logger.trace(g.getMessage(), g);
                    // fall back to field access
                    try {
                        return getField(type, name);
                    } catch (ReflectiveException h) {
                        // log exception at trace level
                        logger.trace(h.getMessage(), h);
                        // log general failure message at debug level
                        logger.debug("no accessor found for {} in class {}", name, type.getName());
                        return null;
                    }
                }
            }
        }
    }

    private static Method getMethod(Class<?> type, String methodName) throws ReflectiveException {
        try {
            return type.getMethod(methodName);
        } catch (NoSuchMethodException e) {
            try {
                return type.getDeclaredMethod(methodName);
            } catch (SecurityException f) {
                // re-throw new exception (f)
                throw new ReflectiveException(f);
            } catch (NoSuchMethodException f) {
                // re-throw original exception (e)
                throw new ReflectiveException(e);
            }
        } catch (SecurityException e) {
            throw new ReflectiveException(e);
        }
    }

    private static Field getField(Class<?> type, String fieldName) throws ReflectiveException {
        try {
            return type.getField(fieldName);
        } catch (NoSuchFieldException e) {
            try {
                return type.getDeclaredField(fieldName);
            } catch (SecurityException f) {
                // re-throw new exception (f)
                throw new ReflectiveException(f);
            } catch (NoSuchFieldException f) {
                // re-throw original exception (e)
                throw new ReflectiveException(e);
            }
        } catch (SecurityException e) {
            throw new ReflectiveException(e);
        }
    }

    // this unused private method is required for use as SENTINEL_METHOD above
    @SuppressWarnings("unused")
    private static void sentinelMethod() {}

    @SuppressWarnings("serial")
    private static class ReflectiveException extends Exception {
        private ReflectiveException(Exception cause) {
            super(cause);
        }
    }
}