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
 * @summary Test exclude plugin
 * @author Jean-Francois Denise
 * @modules jdk.jlink/jdk.tools.jlink.internal
 *          jdk.jlink/jdk.tools.jlink.internal.plugins
 * @run main ExcludePluginTest
 */

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import jdk.tools.jlink.internal.ResourcePoolImpl;
import jdk.tools.jlink.internal.plugins.ExcludeProvider;
import jdk.tools.jlink.plugins.CmdPluginProvider;
import jdk.tools.jlink.plugins.ResourcePlugin;
import jdk.tools.jlink.plugins.ResourcePool;
import jdk.tools.jlink.plugins.StringTable;

public class ExcludePluginTest {

    public static void main(String[] args) throws Exception {
        new ExcludePluginTest().test();
    }

    public void test() throws Exception {
        check("*.jcov", "/num/toto.jcov", true);
        check("*.jcov", "//toto.jcov", true);
        check("*.jcov", "/toto.jcov/tutu/tata", false);
        check("/java.base/*.jcov", "/java.base/toto.jcov", true);
        check("/java.base/toto.jcov", "t/java.base/iti.jcov", false);
        check("/java.base/*/toto.jcov", "/java.base/toto.jcov", false);
        check("/java.base/*/toto.jcov", "/java.base/tutu/toto.jcov", true);
        check("*/java.base/*/toto.jcov", "/tutu/java.base/tutu/toto.jcov", true);
        check("*/META-INF/*", "/META-INF/services/  MyProvider ", false);
        check("*/META-INF/*", "/META-INF/services/MyProvider", false);
        check("*/META-INF", " /META-INF/services/MyProvider", false);
        check("*/META-INF/*", "/java.base//META-INF/services/MyProvider", true);
        check("/java.base/*/Toto$Titi.class", "/java.base/tutu/Toto$Titi.class", true);
        check("/*$*.class", "/java.base/tutu/Toto$Titi.class", true);
        check("*$*.class", "/java.base/tutu/Toto$Titi.class", true);

        // Excluded resource list in a file
        File order = new File("resources.exc");
        order.createNewFile();
        Files.write(order.toPath(), "*.jcov".getBytes());
        check(order.getAbsolutePath(), "/num/toto.jcov", true);
    }

    public void check(String s, String sample, boolean exclude) throws Exception {
        Map<String, Object> p = new HashMap<>();
        p.put(CmdPluginProvider.TOOL_ARGUMENT_PROPERTY, s);
        ExcludeProvider provider = new ExcludeProvider();
        ResourcePlugin excludePlugin = (ResourcePlugin) provider.newPlugins(p)[0];
        ResourcePool resources = new ResourcePoolImpl(ByteOrder.nativeOrder());
        ResourcePool.Resource resource = new ResourcePool.Resource(sample, ByteBuffer.allocate(0));
        resources.addResource(resource);
        ResourcePool result = new ResourcePoolImpl(ByteOrder.nativeOrder());
        excludePlugin.visit(resources, result, new StringTable() {
            @Override
            public int addString(String str) {
                return -1;
            }

            @Override
            public String getString(int id) {
                throw new UnsupportedOperationException("Not supported yet.");
            }
        });
        if (exclude) {
            if (result.getResources().contains(resource)) {
                throw new AssertionError(sample + " should be excluded by " + s);
            }
        } else {
            if (!result.getResources().contains(resource)) {
                throw new AssertionError(sample + " shouldn't be excluded by " + s);
            }
        }
    }
}
