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
import java.util.HashMap;
import java.util.Map;
import jdk.tools.jlink.internal.ImagePluginProviderRepository;
import jdk.tools.jlink.plugins.ResourcePlugin;
import jdk.tools.jlink.plugins.ResourcePluginProvider;

/*
 * @test
 * @summary Test jlink options
 * @author Jean-Francois Denise
 * @library /lib/testlibrary/jlink
 * @modules java.base/jdk.internal.jimage
 *          jdk.compiler/com.sun.tools.classfile
 *          jdk.jlink/jdk.tools.jlink
 *          jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jmod
 *          jdk.jlink/jdk.tools.jimage
 * @build Helper tests.JImageGenerator tests.JImageValidator
 * @run main JLinkOptionsTest
 */
public class JLinkOptionsTest {

    private static class TestProvider extends ResourcePluginProvider {

        private final String option;
        private final Map<String, String> options;

        private TestProvider(String name, String option,
                Map<String, String> options) {
            super(name, "");
            this.option = option;
            this.options = options;
        }

        @Override
        public ResourcePlugin[] newPlugins(String[] arguments,
                Map<String, String> otherOptions) throws IOException {
            return null;
        }

        @Override
        public String getCategory() {
            return null;
        }

        @Override
        public String getToolArgument() {
            return null;
        }

        @Override
        public String getToolOption() {
            return option;

        }

        @Override
        public Map<String, String> getAdditionalOptions() {
            return options;
        }
    }

    public static void main(String[] args) throws Exception {
        Helper helper = Helper.newHelper();
        if (helper == null) {
            System.err.println("Test not run");
            return;
        }
        {
            // multiple plugins with same option
            String option = "test1";
            ImagePluginProviderRepository.
                    registerPluginProvider(new TestProvider("test1", option, null));
            ImagePluginProviderRepository.
                    registerPluginProvider(new TestProvider("test2", option, null));
            boolean failed = false;
            try {
                helper.checkImage("composite2", null, null, null);
                failed = true;
            } catch (Exception ex) {
                System.err.println("OK, Got expected exception " + ex);
                // XXX OK expected
            }
            if (failed) {
                throw new Exception("Image creation should have failed");
            }
            ImagePluginProviderRepository.unregisterPluginProvider("test1");
            ImagePluginProviderRepository.unregisterPluginProvider("test2");
        }

        {
            // option and optional options collision
            Map<String, String> options = new HashMap<>();
            options.put("test1", "");
            ImagePluginProviderRepository.
                    registerPluginProvider(new TestProvider("test1", "test1", null));
            ImagePluginProviderRepository.
                    registerPluginProvider(new TestProvider("test2", "test2", options));
            boolean failed = false;
            try {
                helper.checkImage("composite2", null, null, null);
                failed = true;
            } catch (Exception ex) {
                System.err.println("OK, Got expected exception " + ex);
                // XXX OK expected
            }
            if (failed) {
                throw new Exception("Image creation should have failed");
            }
            ImagePluginProviderRepository.unregisterPluginProvider("test1");
            ImagePluginProviderRepository.unregisterPluginProvider("test2");

        }
    }
}
