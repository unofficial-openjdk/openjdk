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
 * @summary Sanity test for ModuleClassLoader
 * @run main/othervm Basic
 * @run main/othervm Basic access
 * @run main/othervm Basic denied
 */

import java.io.FilePermission;
import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.net.URL;
import java.security.AccessControlException;
import java.security.Permission;
import java.security.Policy;
import java.security.ProtectionDomain;
import java.util.HashSet;
import java.util.Set;

public class Basic {
    private static final Permission PERM = new RuntimePermission("createClassLoader");

    public static void main(String... args) throws Exception {
        // get the class loader before setting security manager
        ClassLoader parent = Basic.class.getClassLoader();
        boolean succeed = true;
        if (args.length > 0) {
            System.out.println("Test with security manager permission " + args[0]);
            switch (args[0]) {
                case "access":
                    URL url = Basic.class.getResource("Foo.class");
                    FilePermission fm = new FilePermission(url.getPath(), "read");
                    Policy.setPolicy(new TestPolicy(PERM, fm));
                    succeed = true;
                    break;
                case "denied":
                    succeed = false;
                    break;
                default:
                    throw new IllegalArgumentException(args[0]);
            }
            System.setSecurityManager(new SecurityManager());
        }

        test(parent, succeed);
    }

    static void test(ClassLoader parent, boolean succeed) throws ClassNotFoundException {

        Configuration cf = Configuration.resolve(ModuleFinder.empty(),
                                                 Configuration.empty(),
                                                 ModuleFinder.empty());
        try {
            ModuleClassLoader loader = new ModuleClassLoader(parent, cf);
            if (!succeed) {
                throw new RuntimeException("Expected SecurityException not thrown");
            }
            if (loader.getParent() != parent) {
                throw new RuntimeException(loader.getParent().toString());
            }

            // Test if it delegates to the parent class loader
            loader.loadClass("Foo");

            // Find resource from parent class loader
            URL url = loader.getResource("Foo.class");
            if (url == null) {
                throw new RuntimeException("resource not found");
            }

            checkPackageAccess(loader);
        } catch (SecurityException e) {
            if (succeed)
                throw e;

            AccessControlException ace = (AccessControlException)e;
            if (!ace.getPermission().equals(PERM)) {
                throw new RuntimeException("Unexpected exception:", e);
            }
        }
    }

    static void checkPackageAccess(ModuleClassLoader loader) throws ClassNotFoundException {
        boolean deny = System.getSecurityManager() != null;
        try {
            Class<?> c = Class.forName("sun.misc.Unsafe", false, loader);
            if (deny) {
                throw new RuntimeException("Expected SecurityException not thrown");
            }
        } catch (SecurityException e) {
            if (!deny)
                throw e;
        }

    }
    static class TestPolicy extends Policy {
        private final Set<Permission> perms = new HashSet<>();
        TestPolicy(Permission... permissions) {
            for (Permission p: permissions) {
                perms.add(p);
            }
        }
        public boolean implies(ProtectionDomain domain,
                               Permission permission) {
            if (perms.contains(permission)) {
                return true;
            }
            return super.implies(domain, permission);
        }
    }
}

class Foo {}
