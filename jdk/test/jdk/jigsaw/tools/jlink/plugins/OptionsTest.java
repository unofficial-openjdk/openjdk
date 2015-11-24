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

/*
 * @test
 * @summary Test additional options
 * @author Jean-Francois Denise
 * @modules jdk.jlink
 * @run main OptionsTest
 */

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import jdk.tools.jlink.plugins.CmdPluginProvider;
import jdk.tools.jlink.plugins.CmdResourcePluginProvider;

import jdk.tools.jlink.plugins.Plugin;
import jdk.tools.jlink.plugins.ResourcePlugin;

public class OptionsTest {
    public static void main(String[] args) throws IOException {
        OptionsProvider prov = new OptionsProvider();
        Map<String, Object> props = new HashMap<>();
        for (String c : OptionsProvider.OPTIONS) {
            props.put(c, c);
        }
        prov.newPlugins(props);
        if (prov.options == null) {
            throw new AssertionError("Something wrong occured, no config");
        }
    }

    public static class OptionsProvider extends CmdResourcePluginProvider {

        public static final String[] OPTIONS = {"a", "nnn", "cccc"};

        public Map<String, String> options;

        OptionsProvider() {
            super("Config", "");
        }

        @Override
        public ResourcePlugin[] newPlugins(String[] argument, Map<String, String> options)
                throws IOException {
            if (options.size() != OPTIONS.length) {
                throw new IOException("Invalid options");
            }
            for (String o : OPTIONS) {
                if (!options.keySet().contains(o)) {
                    throw new IOException("Invalid option " + o);
                }
            }
            this.options = options;
            return null;
        }

        @Override
        public String getCategory() {
            return null;
        }

        @Override
        public String getToolOption() {
            return null;
        }

        @Override
        public String getToolArgument() {
            return null;
        }

        @Override
        public Map<String, String> getAdditionalOptions() {
            Map<String, String> m = new HashMap<>();
            for (String o : OPTIONS) {
                m.put(o, o);
            }
            return m;
        }

    }
}
