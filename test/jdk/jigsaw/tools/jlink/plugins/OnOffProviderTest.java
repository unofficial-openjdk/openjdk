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
 * @summary Test on/off provider
 * @author Andrei Eremeev
 * @modules java.base/jdk.internal.jimage.decompressor
 *          jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jlink.internal.plugins
 * @run main OnOffProviderTest
 */

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import jdk.tools.jlink.internal.ImagePluginConfiguration;
import jdk.tools.jlink.internal.plugins.OnOffProvider;
import jdk.tools.jlink.plugins.CmdPluginProvider;
import jdk.tools.jlink.plugins.Plugin;
import jdk.tools.jlink.plugins.PluginProvider;
import jdk.tools.jlink.plugins.ResourcePlugin;

public class OnOffProviderTest {

    private final static String OPTION = "option";
    private final static String VALUE = "value";

    public static void main(String[] args) throws IOException {
        new OnOffProviderTest().test();
    }

    public void test() throws IOException {
        {
            Properties config = new Properties();
            CustomProvider customProvider = new CustomProvider();
            config.setProperty(CmdPluginProvider.TOOL_ARGUMENT_PROPERTY, ImagePluginConfiguration.OFF_ARGUMENT);
            Plugin[] plugins = customProvider.newPlugins(config);
            if (plugins.length != 0) {
                throw new AssertionError("Expected empty list of plugins");
            }
        }
        {
            Properties config = new Properties();
            CustomProvider customProvider = new CustomProvider();
            config.setProperty(CmdPluginProvider.TOOL_ARGUMENT_PROPERTY, ImagePluginConfiguration.ON_ARGUMENT);
            config.setProperty(OPTION, VALUE);
            customProvider.newPlugins(config);
            if (!customProvider.isNewPluginsCalled()) {
                throw new AssertionError("newPlugins() was not called");
            }
        }
        {
            Properties config = new Properties();
            config.setProperty(CmdPluginProvider.TOOL_ARGUMENT_PROPERTY,
                    ImagePluginConfiguration.ON_ARGUMENT + "," + ImagePluginConfiguration.OFF_ARGUMENT);
            CustomProvider customProvider = new CustomProvider();
            try {
                customProvider.newPlugins(config);
                throw new AssertionError("IOException expected");
            } catch (IOException e) {
                assertException(e, "Invalid number of arguments expecting on|off");
            }
        }
        {
            Properties config = new Properties();
            config.setProperty(CmdPluginProvider.TOOL_ARGUMENT_PROPERTY, "INVALID");
            CustomProvider customProvider = new CustomProvider();
            try {
                customProvider.newPlugins(config);
                throw new AssertionError("IOException expected");
            } catch (IOException e) {
                assertException(e, "Invalid argument INVALID, expecting on or off");
            }
        }
    }

    private void assertException(IOException e, String expectedMessage) {
        String message = e.getMessage();
        if (!Objects.equals(message, expectedMessage)) {
            throw new AssertionError("Expected: " + expectedMessage + ", got: " + message);
        }
    }

    private static class CustomProvider extends OnOffProvider {

        private final static Map<String, String> additionalOptions;

        private boolean isNewPluginsCalled = false;

        static {
            additionalOptions = new HashMap<>();
            additionalOptions.put(OPTION, VALUE);
        }

        public CustomProvider() {
            super("custom-on-off-provider", "custom-on-off-provider");
        }

        public boolean isNewPluginsCalled() {
            return isNewPluginsCalled;
        }

        @Override
        public ResourcePlugin[] createPlugins(Map<String, String> otherOptions) throws IOException {
            if (!additionalOptions.equals(otherOptions)) {
                throw new AssertionError("Additional options: expected: " +
                        additionalOptions + ", got: " + otherOptions);
            }
            isNewPluginsCalled = true;
            return null;
        }

        @Override
        public String getCategory() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getToolOption() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, String> getAdditionalOptions() {
            return additionalOptions;
        }
    }
}
