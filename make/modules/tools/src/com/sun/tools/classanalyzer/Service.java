/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
 *
 */
package com.sun.tools.classanalyzer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 *
 */
public class Service {
    final Klass service;
    public Service(Klass s) {
        this.service = s;
    }

    public String toString() {
        return "service " + service.getClassName();
    }

    public static Resource loadProviderConfig(String fname, InputStream in, long size) {
        return new ProviderConfigFile(fname, in, size);
    }

    static final String METAINF_SERVICES = "META-INF/services";
    static final String METAINF_SERVICES_PATH =
            METAINF_SERVICES.replace('/', File.separatorChar);

    public static class ProviderConfigFile extends Resource {
        final List<String> providers = new ArrayList<>();
        final String service;

        ProviderConfigFile(String fname, InputStream in) {
            this(fname, in, 0);
        }

        ProviderConfigFile(String fname, InputStream in, long size) {
            super(fname, size);
            readServiceConfiguration(in, providers);
            this.service = name.substring(METAINF_SERVICES.length() + 1, name.length());
        }

        @Override
        boolean isService() {
            return true;
        }

        String getName() {
            return service;
        }

        @Override
        public boolean equals(Object o) {
            if (o instanceof ProviderConfigFile) {
                ProviderConfigFile sp = (ProviderConfigFile) o;
                if (service.equals(sp.service) && providers.size() == sp.providers.size()) {
                    List<String> tmp = new ArrayList<String>(providers);
                    if (tmp.removeAll(sp.providers)) {
                        return tmp.size() == 0;
                    }
                }
            }
            return false;
        }

        public int hashCode() {
            int hash = 7;
            hash = 73 * hash + (this.providers != null ? this.providers.hashCode() : 0);
            hash = 73 * hash + (this.service != null ? this.service.hashCode() : 0);
            return hash;
        }

        @Override
        public int compareTo(Resource o) {
            if (this.equals(o)) {
                return 0;
            } else {
                if (getName().compareTo(o.getName()) < 0) {
                    return -1;
                } else {
                    return 1;
                }
            }
        }

        @SuppressWarnings("empty-statement")
        void readServiceConfiguration(InputStream in, List<String> names) {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(in, "utf-8"))) {
                int lc = 1;
                while ((lc  = parseLine(br, lc, names)) >= 0);
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }

        // Parse a single line from the given configuration file, adding the name
        // on the line to the names list.
        //
        private int parseLine(BufferedReader r, int lc, List<String> names) throws IOException {
            String ln = r.readLine();
            if (ln == null) {
                return -1;
            }
            int ci = ln.indexOf('#');
            if (ci >= 0) {
                ln = ln.substring(0, ci);
            }
            ln = ln.trim();
            int n = ln.length();
            if (n != 0) {
                if ((ln.indexOf(' ') >= 0) || (ln.indexOf('\t') >= 0)) {
                    throw new RuntimeException("Illegal configuration-file syntax");
                }
                int cp = ln.codePointAt(0);
                if (!Character.isJavaIdentifierStart(cp)) {
                    throw new RuntimeException("Illegal provider-class name: " + ln);
                }
                for (int i = Character.charCount(cp); i < n; i += Character.charCount(cp)) {
                    cp = ln.codePointAt(i);
                    if (!Character.isJavaIdentifierPart(cp) && (cp != '.')) {
                        throw new RuntimeException("Illegal provider-class name: " + ln);
                    }
                }
                if (!names.contains(ln)) {
                    names.add(ln);
                }
            }
            return lc + 1;
        }
    }
}
