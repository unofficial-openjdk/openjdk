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

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import jdk.tools.jlink.internal.ImagePluginProviderRepository;
import jdk.tools.jlink.plugins.ImageFilePlugin;
import jdk.tools.jlink.plugins.OnOffImageFilePluginProvider;
import jdk.tools.jlink.plugins.OnOffResourcePluginProvider;
import jdk.tools.jlink.plugins.PluginProvider;
import jdk.tools.jlink.plugins.ResourcePlugin;
import tests.Helper;

/*
 * @test
 * @summary Test plugins enabled by default
 * @author Jean-Francois Denise
 * @library ../lib
 * @modules java.base/jdk.internal.jimage
 *          jdk.jdeps/com.sun.tools.classfile
 *          jdk.jlink/jdk.tools.jlink
 *          jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jmod
 *          jdk.jlink/jdk.tools.jimage
 * @build tests.*
 * @run main/othervm DefaultProviderTest
 */

public class DefaultProviderTest {

    private final static Map<String, String> additionalOptions;

    static {
        additionalOptions = new HashMap<>();
        additionalOptions.put("option1", "value1");
        additionalOptions.put("option2", "value2");
    }

    private static class CustomProvider extends OnOffResourcePluginProvider {

        public CustomProvider() {
            super(NAME, NAME);
        }

        @Override
        public ResourcePlugin[] createPlugins(Map<String, String> otherOptions) throws IOException {
            DefaultProviderTest.isNewPluginsCalled = true;
            DefaultProviderTest.otherOptions = otherOptions;
            return null;
        }

        @Override
        public boolean isEnabledByDefault() {
            return true;
        }

        @Override
        public String getCategory() {
            return PluginProvider.TRANSFORMER;
        }

        @Override
        public String getToolOption() {
            return NAME;
        }

        @Override
        public Map<String, String> getAdditionalOptions() {
            return additionalOptions;
        }
    }

    private static class CustomProvider2 extends OnOffImageFilePluginProvider {
        public CustomProvider2() {
            super(NAME, NAME);
        }

        @Override
        public ImageFilePlugin[] createPlugins(Map<String, String> otherOptions) throws IOException {
            DefaultProviderTest.isNewPluginsCalled = true;
            DefaultProviderTest.otherOptions = otherOptions;
            return null;
        }

        @Override
        public boolean isEnabledByDefault() {
            return true;
        }

        @Override
        public String getCategory() {
            return PluginProvider.TRANSFORMER;
        }

        @Override
        public String getToolOption() {
            return NAME;
        }

        @Override
        public Map<String, String> getAdditionalOptions() {
            return additionalOptions;
        }
    }
    private static final String NAME = "toto";

    private static boolean isNewPluginsCalled;
    private static Map<String, String> otherOptions;

    private static void reset() {
        isNewPluginsCalled = false;
        otherOptions = null;
    }

    public static void main(String[] args) throws Exception {
        Helper helper = Helper.newHelper();
        if (helper == null) {
            System.err.println("Test not run");
            return;
        }
        helper.generateDefaultModules();
        test(helper, new CustomProvider());
        test(helper, new CustomProvider2());
    }

    private static void test(Helper helper, PluginProvider provider) throws Exception {
        ImagePluginProviderRepository.registerPluginProvider(provider);

        {
            String[] userOptions = {};
            Path imageDir = helper.generateDefaultImage(userOptions, "composite2").assertSuccess();
            helper.checkImage(imageDir, "composite2", null, null);
            if (!isNewPluginsCalled) {
                throw new Exception("Should have been called");
            }
            reset();
        }

        {
            String[] userOptions = {"--option1", "value1", "--option2", "value2"};
            Path imageDir = helper.generateDefaultImage(userOptions, "composite2").assertSuccess();
            helper.checkImage(imageDir, "composite2", null, null);
            if (!isNewPluginsCalled) {
                throw new Exception("Should have been called");
            }
            if (!otherOptions.equals(additionalOptions)) {
                throw new Exception("Optional options are not expected one "
                        + otherOptions);
            }
            System.err.println("OPTIONS " + otherOptions);
            reset();
        }

        {
            String[] userOptions = {"--toto", "off", "--option1", "value1"};
            Path imageDir = helper.generateDefaultImage(userOptions, "composite2").assertSuccess();
            helper.checkImage(imageDir, "composite2", null, null);
            if (isNewPluginsCalled) {
                throw new Exception("Should not have been called");
            }
            if (otherOptions != null) {
                throw new Exception("Optional options are not expected");
            }
            reset();
        }

    }
}
