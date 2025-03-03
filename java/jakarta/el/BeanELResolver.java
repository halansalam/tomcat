/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jakarta.el;

import java.beans.BeanInfo;
import java.beans.FeatureDescriptor;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

public class BeanELResolver extends ELResolver {

    private static final int CACHE_SIZE;
    private static final String CACHE_SIZE_PROP = "org.apache.el.BeanELResolver.CACHE_SIZE";

    static {
        String cacheSizeStr;
        if (System.getSecurityManager() == null) {
            cacheSizeStr = System.getProperty(CACHE_SIZE_PROP, "1000");
        } else {
            cacheSizeStr = AccessController.doPrivileged(
                    (PrivilegedAction<String>) () -> System.getProperty(CACHE_SIZE_PROP, "1000"));
        }
        CACHE_SIZE = Integer.parseInt(cacheSizeStr);
    }

    private final boolean readOnly;

    private final ConcurrentCache<String, BeanProperties> cache = new ConcurrentCache<>(CACHE_SIZE);

    public BeanELResolver() {
        this.readOnly = false;
    }

    public BeanELResolver(boolean readOnly) {
        this.readOnly = readOnly;
    }

    @Override
    public Class<?> getType(ELContext context, Object base, Object property) {
        Objects.requireNonNull(context);
        if (base == null || property == null) {
            return null;
        }

        context.setPropertyResolved(base, property);
        return this.property(context, base, property).getPropertyType();
    }

    @Override
    public Object getValue(ELContext context, Object base, Object property) {
        Objects.requireNonNull(context);
        if (base == null || property == null) {
            return null;
        }

        context.setPropertyResolved(base, property);
        Method m = this.property(context, base, property).read(context, base);
        try {
            return m.invoke(base, (Object[]) null);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            Util.handleThrowable(cause);
            throw new ELException(Util.message(context, "propertyReadError",
                    base.getClass().getName(), property.toString()), cause);
        } catch (Exception e) {
            throw new ELException(e);
        }
    }

    @Override
    public void setValue(ELContext context, Object base, Object property,
            Object value) {
        Objects.requireNonNull(context);
        if (base == null || property == null) {
            return;
        }

        context.setPropertyResolved(base, property);

        if (this.readOnly) {
            throw new PropertyNotWritableException(Util.message(context,
                    "resolverNotWriteable", base.getClass().getName()));
        }

        Method m = this.property(context, base, property).write(context, base);
        try {
            m.invoke(base, value);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            Util.handleThrowable(cause);
            throw new ELException(Util.message(context, "propertyWriteError",
                    base.getClass().getName(), property.toString()), cause);
        } catch (Exception e) {
            throw new ELException(e);
        }
    }

    /**
     * @since EL 2.2
     */
    @Override
    public Object invoke(ELContext context, Object base, Object method,
            Class<?>[] paramTypes, Object[] params) {
        Objects.requireNonNull(context);
        if (base == null || method == null) {
            return null;
        }

        ExpressionFactory factory = ELManager.getExpressionFactory();

        String methodName = factory.coerceToType(method, String.class);

        // Find the matching method
        Method matchingMethod =
                Util.findMethod(base.getClass(), base, methodName, paramTypes, params);

        Object[] parameters = Util.buildParameters(
                matchingMethod.getParameterTypes(), matchingMethod.isVarArgs(),
                params);

        Object result = null;
        try {
            result = matchingMethod.invoke(base, parameters);
        } catch (IllegalArgumentException | IllegalAccessException e) {
            throw new ELException(e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            Util.handleThrowable(cause);
            throw new ELException(cause);
        }

        context.setPropertyResolved(base, method);
        return result;
    }

    @Override
    public boolean isReadOnly(ELContext context, Object base, Object property) {
        Objects.requireNonNull(context);
        if (base == null || property == null) {
            return false;
        }

        context.setPropertyResolved(base, property);
        return this.readOnly || this.property(context, base, property).isReadOnly(base);
    }

    @Deprecated(forRemoval = true, since = "EL 5.0")
    @Override
    public Iterator<FeatureDescriptor> getFeatureDescriptors(ELContext context, Object base) {
        if (base == null) {
            return null;
        }

        try {
            BeanInfo info = Introspector.getBeanInfo(base.getClass());
            PropertyDescriptor[] pds = info.getPropertyDescriptors();
            for (PropertyDescriptor pd : pds) {
                pd.setValue(RESOLVABLE_AT_DESIGN_TIME, Boolean.TRUE);
                pd.setValue(TYPE, pd.getPropertyType());
            }
            return Arrays.asList((FeatureDescriptor[]) pds).iterator();
        } catch (IntrospectionException e) {
            //
        }

        return null;
    }

    @Override
    public Class<?> getCommonPropertyType(ELContext context, Object base) {
        if (base != null) {
            return Object.class;
        }

        return null;
    }

    static final class BeanProperties {
        private final Map<String, BeanProperty> properties;

        private final Class<?> type;

        public BeanProperties(Class<?> type) throws ELException {
            this.type = type;
            this.properties = new HashMap<>();
            try {
                BeanInfo info = Introspector.getBeanInfo(this.type);
                PropertyDescriptor[] pds = info.getPropertyDescriptors();
                for (PropertyDescriptor pd: pds) {
                    this.properties.put(pd.getName(), new BeanProperty(type, pd));
                }
                /**
                 * Populating from any interfaces solves two distinct problems:
                 * 1. When running under a security manager, classes may be
                 *    unaccessible but have accessible interfaces.
                 * 2. It causes default methods to be included.
                 */
                populateFromInterfaces(type);
            } catch (IntrospectionException ie) {
                throw new ELException(ie);
            }
        }

        private void populateFromInterfaces(Class<?> aClass) throws IntrospectionException {
            Class<?> interfaces[] = aClass.getInterfaces();
            if (interfaces.length > 0) {
                for (Class<?> ifs : interfaces) {
                    BeanInfo info = Introspector.getBeanInfo(ifs);
                    PropertyDescriptor[] pds = info.getPropertyDescriptors();
                    for (PropertyDescriptor pd : pds) {
                        if (!this.properties.containsKey(pd.getName())) {
                            this.properties.put(pd.getName(), new BeanProperty(
                                    this.type, pd));
                        }
                    }
                    populateFromInterfaces(ifs);
                }
            }
            Class<?> superclass = aClass.getSuperclass();
            if (superclass != null) {
                populateFromInterfaces(superclass);
            }
        }

        private BeanProperty get(ELContext ctx, String name) {
            BeanProperty property = this.properties.get(name);
            if (property == null) {
                throw new PropertyNotFoundException(Util.message(ctx,
                        "propertyNotFound", type.getName(), name));
            }
            return property;
        }

        private Class<?> getType() {
            return type;
        }
    }

    static final class BeanProperty {
        private final Class<?> type;

        private final Class<?> owner;

        private final PropertyDescriptor descriptor;

        private Method read;

        private Method write;

        public BeanProperty(Class<?> owner, PropertyDescriptor descriptor) {
            this.owner = owner;
            this.descriptor = descriptor;
            this.type = descriptor.getPropertyType();
        }

        public Class<?> getPropertyType() {
            return this.type;
        }

        public boolean isReadOnly(Object base) {
            return this.write == null &&
                    (null == (this.write = Util.getMethod(this.owner, base, descriptor.getWriteMethod())));
        }

        private Method write(ELContext ctx, Object base) {
            if (this.write == null) {
                this.write = Util.getMethod(this.owner, base, descriptor.getWriteMethod());
                if (this.write == null) {
                    throw new PropertyNotWritableException(Util.message(ctx,
                            "propertyNotWritable", new Object[] {
                                    owner.getName(), descriptor.getName() }));
                }
            }
            return this.write;
        }

        private Method read(ELContext ctx, Object base) {
            if (this.read == null) {
                this.read = Util.getMethod(this.owner, base, descriptor.getReadMethod());
                if (this.read == null) {
                    throw new PropertyNotFoundException(Util.message(ctx,
                            "propertyNotReadable", new Object[] {
                                    owner.getName(), descriptor.getName() }));
                }
            }
            return this.read;
        }
    }

    private final BeanProperty property(ELContext ctx, Object base,
            Object property) {
        Class<?> type = base.getClass();
        String prop = property.toString();

        BeanProperties props = this.cache.get(type.getName());
        if (props == null || type != props.getType()) {
            props = new BeanProperties(type);
            this.cache.put(type.getName(), props);
        }

        return props.get(ctx, prop);
    }

    private static final class ConcurrentCache<K,V> {

        private final int size;
        private final Map<K,V> eden;
        private final Map<K,V> longterm;

        public ConcurrentCache(int size) {
            this.size = size;
            this.eden = new ConcurrentHashMap<>(size);
            this.longterm = new WeakHashMap<>(size);
        }

        public V get(K key) {
            V value = this.eden.get(key);
            if (value == null) {
                synchronized (longterm) {
                    value = this.longterm.get(key);
                }
                if (value != null) {
                    this.eden.put(key, value);
                }
            }
            return value;
        }

        public void put(K key, V value) {
            if (this.eden.size() >= this.size) {
                synchronized (longterm) {
                    this.longterm.putAll(this.eden);
                }
                this.eden.clear();
            }
            this.eden.put(key, value);
        }

    }
}
