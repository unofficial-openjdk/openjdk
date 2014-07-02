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
 */

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/*
 * @test
 * @bug 8021368
 * @summary SecurityManager.checkMemberAccess call should not resolve
 *          and load other classes
 * @run main/othervm/policy=test.policy CheckMemberAccess
 */

public class CheckMemberAccess {
    private static int count = 0;
    public static void main(String[] args) throws Exception {
        String testClasses = System.getProperty("test.classes", ".");
        // remove Foo class
        // the test will verify SecurityManager.checkMemberAccess should not
        // cause any class loading of implementation classes
        Path p = Paths.get(testClasses, "CheckMemberAccess$Foo.class");
        if (Files.exists(p)) {
            // Foo already deleted in rerun
            Files.delete(p);
        }
        // patch the checkMemberAcces_ method name
        patch(Paths.get(testClasses, "CheckMemberAccess$PrivateCheckMemberAccess.class"));
        patch(Paths.get(testClasses, "CheckMemberAccess$StaticCheckMemberAccess.class"));

        test(new OverriddedCheckMemberAccess(), count+1);
        test(new NoOverriddedCheckMemberAccess(), count+1);
        test(new PrivateCheckMemberAccess(), count);
        test(new StaticCheckMemberAccess(), count);
    }

    private static void patch(Path p) throws IOException {
        // s/checkMemberAcces_/checkMemberAccess
        byte[] bytes = Files.readAllBytes(p);
        int len = "Acces_".length();
        for (int i=0; i < bytes.length-len; i++) {
            if (bytes[i] == 'A' &&
                bytes[i+1] == 'c' &&
                bytes[i+2] == 'c' &&
                bytes[i+3] == 'e' &&
                bytes[i+4] == 's' &&
                bytes[i+5] == '_') {
                bytes[i+5] = 's';
                break;
            }
        }
        Files.write(p, bytes);
    }

    public void findMe() {};
    public static void test(SecurityManager smgr, int expected) throws Exception {
        System.setSecurityManager(smgr);
        // this will trigger SecurityManager.checkMemberAccess to be called
        Method m = CheckMemberAccess.class.getMethod("findMe", new Class<?>[0]);
        if (count != expected) {
            throw new RuntimeException(smgr.getClass() + ": " + count + " != " + expected);
        }
    }

    static class OverriddedCheckMemberAccess extends SecurityManager {
        @Override
        public void checkMemberAccess(Class<?> clazz, int which) {
            System.out.println("OverriddedCheckMemberAccess.checkMemberAccess called");
            count++;
        }
        // implementation-specific class should not be loaded when
        // this.checkMemberAccess is called
        public Foo foo() {
            return null;
        }
    }
    static class NoOverriddedCheckMemberAccess extends OverriddedCheckMemberAccess {
    }
    static class PrivateCheckMemberAccess extends SecurityManager {
        private void checkMemberAcces_(Class<?> clazz, int which) {
            throw new RuntimeException("should not reach here");
        }
        // implementation-specific class should not be loaded when
        // this.checkMemberAccess is called
        public Foo foo() {
            return null;
        }
    }
    static class StaticCheckMemberAccess extends SecurityManager {
        public static void checkMemberAcces_(Class<?> clazz, int which) {
            throw new RuntimeException("should not reach here");
        }
        // implementation-specific class should not be loaded when
        // this.checkMemberAccess is called
        public Foo foo() {
            return null;
        }
    }
    static class Foo {}
}
