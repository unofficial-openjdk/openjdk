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

package jdk.test.resources.asia;

import java.io.InputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import jdk.test.resources.MyResourcesProvider;

public class MyResourcesAsia implements MyResourcesProvider {
    @Override
    public ResourceBundle getBundle(String baseName, Locale locale) {
        if (locale.equals(Locale.JAPANESE)
                || locale.equals(Locale.CHINESE) || locale.equals(Locale.TAIWAN)) {
            return getBundleImpl(baseName, locale);
        }
        return null;
    }

    private ResourceBundle getBundleImpl(String baseName, Locale locale) {
        // Convert baseName to its properties resource name for the given locale
        // e.g., jdk.test.resources.MyResources -> jdk/test/resources/asia/MyResources_zh_TW.properties
        StringBuilder sb = new StringBuilder();
        int index = baseName.lastIndexOf('.');
        sb.append(baseName.substring(0, index))
            .append(".asia")
            .append(baseName.substring(index));
        String lang = locale.getLanguage();
        if (!lang.isEmpty()) {
            sb.append('_').append(lang);
            String country = locale.getCountry();
            if (!country.isEmpty()) {
                sb.append('_').append(country);
            }
        }
        String resourceName = "/" + sb.toString().replace('.', '/') + ".properties";
        ResourceBundle bundle = null;
        try (InputStream stream = MyResourcesAsia.class.getResourceAsStream(resourceName)) {
            if (stream != null) {
                bundle = new PropertyResourceBundle(stream);
            }
        } catch (IOException e) {
            System.out.println(e);
        }
        return bundle;
    }
}
