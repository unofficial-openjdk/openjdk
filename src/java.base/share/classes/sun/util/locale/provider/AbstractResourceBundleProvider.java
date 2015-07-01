/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.util.locale.provider;

import sun.misc.BootLoader;
import sun.misc.Unsafe;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Module;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.spi.ResourceBundleProvider;

/**
 * Support class for ResourceBundleProvider implementation
 */
public abstract class AbstractResourceBundleProvider implements ResourceBundleProvider {
    private final List<String> formats;

    protected AbstractResourceBundleProvider() {
        formats = ResourceBundle.Control.FORMAT_DEFAULT;
    }

    protected AbstractResourceBundleProvider(List<String> formats) {
        Objects.requireNonNull(formats);
        this.formats = formats;
    }

    protected List<String> getFormats() {
        return formats;
    }

    protected abstract String toBundleName(String baseName, Locale locale);

    @Override
    public ResourceBundle getBundle(String baseName, Locale locale) {
        String bundleName = toBundleName(baseName, locale);
        ResourceBundle bundle = null;
        for (String format : getFormats()) {
            try {
                bundle = loadResourceBundle(format, this.getClass().getModule(), bundleName);
                if (bundle != null) {
                    break;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return bundle;
    }

    public static ResourceBundle loadResourceBundle(String format,
                                                    Module module,
                                                    String bundleName)
                                                throws IOException {
        switch (format) {
        case "java.class":
            return loadResourceBundle(module, bundleName);
        case "java.properties":
            return loadPropertyResourceBundle(module, bundleName);
        }
        throw new IllegalArgumentException("Unsupported format: " + format);
    }

    /**
     * Load a {@code ResourceBundle} of the given bundleName local to the given module.
     *
     * @param module the module from which the {@code ResourceBundle} is loaded
     * @param bundleName bundle name
     * @return the {@code ResourceBundle}, or null if no {@code ResourceBundle} is found
     */
    public static ResourceBundle loadResourceBundle(Module module, String bundleName)
    {
        PrivilegedAction<Class<?>> pa = () -> Class.forName(module, bundleName);
        Class<?> c = AccessController.doPrivileged(pa);
        if (c != null && ResourceBundle.class.isAssignableFrom(c)) {
            try {
                @SuppressWarnings("unchecked")
                Class<ResourceBundle> bundleClass = (Class<ResourceBundle>) c;
                Constructor<ResourceBundle> ctor = bundleClass.getConstructor();
                if (!Modifier.isPublic(ctor.getModifiers())) {
                    return null;
                }

                // java.base may not be able to read the bundleClass's module.
                PrivilegedAction<Void> pa1 = () -> { ctor.setAccessible(true); return null; };
                AccessController.doPrivileged(pa1);
                try {
                    return ctor.newInstance((Object[]) null);
                } catch (InvocationTargetException e) {
                    Unsafe.getUnsafe().throwException(e.getTargetException());
                } catch (InstantiationException | IllegalAccessException e) {
                    throw new InternalError(e);
                }
            } catch (NoSuchMethodException e) {
            }
        }
        return null;
    }

    /**
     * Load a properties {@code ResourceBundle} of the given bundleName
     * local to the given module.
     *
     * @param module the module from which the {@code ResourceBundle} is loaded
     * @param bundleName bundle name
     * @return the java.util.ResourceBundle
     */
    public static ResourceBundle loadPropertyResourceBundle(Module module, String bundleName)
            throws IOException
    {
        String resourceName = toResourceName(bundleName, "properties");
        if (resourceName == null) {
            return null;
        }
        PrivilegedAction<InputStream> pa = () -> {
            try {
                InputStream in = module.getResourceAsStream(resourceName);
                if (in == null) {
                    // for migration, find .properties bundle from unnamed module
                    Module unnamed = module.getClassLoader() != null
                            ? module.getClassLoader().getUnnamedModule()
                            : BootLoader.getUnnamedModule();
                    return unnamed.getResourceAsStream(resourceName);
                }
                return in;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        try (InputStream stream = AccessController.doPrivileged(pa)) {
            if (stream != null) {
                return new PropertyResourceBundle(stream);
            } else {
                return null;
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static String toResourceName(String bundleName, String suffix) {
        if (bundleName.contains("://")) {
            return null;
        }
        StringBuilder sb = new StringBuilder(bundleName.length() + 1 + suffix.length());
        sb.append(bundleName.replace('.', '/')).append('.').append(suffix);
        return sb.toString();
    }
}
