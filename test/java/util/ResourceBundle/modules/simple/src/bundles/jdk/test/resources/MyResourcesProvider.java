/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

package jdk.test.resources;

import java.io.InputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.spi.ResourceBundleProvider;

public class MyResourcesProvider implements ResourceBundleProvider {
    @Override
    public ResourceBundle getBundle(String baseName, Locale locale) {
        StringBuilder sb = new StringBuilder(baseName);
        String lang = locale.getLanguage();
        if (!lang.isEmpty()) {
            sb.append('_').append(lang);
            String country = locale.getCountry();
            if (!country.isEmpty()) {
                sb.append('_').append(country);
            }
        }
        String bundleName = sb.toString();
        ClassLoader loader = MyResourcesProvider.class.getClassLoader();
        ResourceBundle bundle = null;
        try {
            @SuppressWarnings("unchecked")
            Class<? extends ResourceBundle> cl = (Class<? extends ResourceBundle>)loader.loadClass(bundleName);
            bundle = cl.newInstance();
        } catch (IllegalAccessException | InstantiationException e) {
            System.out.println(e);
            return null;
        } catch (ClassNotFoundException cnf) {
        }

        String resourceName = "/" + sb.toString().replace('.', '/') + ".properties";
        try (InputStream stream = MyResourcesProvider.class.getResourceAsStream(resourceName)) {
            if (stream != null) {
                bundle = new PropertyResourceBundle(stream);
            }
        } catch (IOException e) {
            System.out.println(e);
        }
        return bundle;

        /* Use ResourceBundle.Control as a utility class
        ResourceBundle.Control control = new ResourceBundle.Control();
        ResourceBundle bundle = null;
        try {
            for (String format : ResourceBundle.Control.FORMAT_DEFAULT) {
                bundle = control.newBundle(baseName, locale, format, loader, false);
                if (bundle != null) {
                    break;
                }
            }
        } catch (IllegalAccessException | InstantiationException | IOException e) {
            System.out.println(e);
        }
        return bundle;
        */
    }
}
