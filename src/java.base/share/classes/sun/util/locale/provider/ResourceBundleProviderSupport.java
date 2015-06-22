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
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

/**
 * Support class for ResourceBundleProvider implementation
 */
public class ResourceBundleProviderSupport {
    /**
     * Load a {@code ResourceBundle} of the given bundleName local to the given module
     *
     * @apiNote Perhaps no need to take baseName and locale parameters
     *
     * @param module the module from which the {@code ResourceBundle} is loaded
     * @param baseName the base name of the resource bundle, a full qualified class name
     * @param locale the locale for which a resource bundle should be loaded
     * @param bundleName bundle name
     * @return the resource bundle
     */
    public static ResourceBundle loadResourceBundle(Module module, String baseName,
                                                    Locale locale, String bundleName)
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
     * local to the given module
     *
     * @apiNote Perhaps no need to take baseName and locale parameters
     *
     * @param module the module from which the {@code ResourceBundle} is loaded
     * @param baseName the base name of the resource bundle, a full qualified class name
     * @param locale the locale for which a resource bundle should be loaded
     * @param bundleName bundle name
     * @return the resource bundle
     */
    public static ResourceBundle loadPropertyResourceBundle(Module module, String baseName,
                                                            Locale locale, String bundleName)
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
                    ClassLoader ld = module.getClassLoader();
                    in = ld != null
                            ? ld.getResourceAsStream(resourceName)
                            : ClassLoader.getSystemResourceAsStream(resourceName);
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
        StringBuilder sb = new StringBuilder(bundleName.length() + 1 + suffix.length());
        sb.append(bundleName.replace('.', '/')).append('.').append(suffix);
        return sb.toString();
    }
}
