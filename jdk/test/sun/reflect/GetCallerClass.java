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

/*
 * @test
 * @bug 8016814 8014925 8021946
 * @summary Test sun.reflect.Reflection.getCallerClass(int) disabled by default
 * @compile -XDignore.symbol.file GetCallerClass.java
 * @run main/othervm GetCallerClass
 * @run main/othervm -Djdk.reflect.allowGetCallerClass GetCallerClass
 * @run main/othervm -Djdk.reflect.allowGetCallerClass=true GetCallerClass
 * @run main/othervm -Djdk.reflect.allowGetCallerClass=false GetCallerClass
 */

public class GetCallerClass {
    public static void main(String[] args) throws Exception {
        String s = System.getProperty("jdk.reflect.allowGetCallerClass");
        boolean allowed;
        if (s == null || s.equals("") || s.equals("true")) {
            allowed = true;
        } else if (s.equals("false")) {
            allowed = false;
        } else {
            throw new RuntimeException("Unsupported test setting");
        }

        try {
            Class<?> c = Test.test();
            if (!allowed) {
                throw new RuntimeException("Reflection.getCallerClass should not be allowed");
            }
            Class<?> caller = Test.caller();
            if (c != GetCallerClass.class || caller != c) {
                throw new RuntimeException("Incorrect caller: " + c);
            }
            Test.selfTest();
        } catch (UnsupportedOperationException e) {
            if (allowed) throw e;
        }
    }

    @sun.reflect.CallerSensitive
    public Class<?> getCallerClass() {
        // 0: Reflection 1: getCallerClass 2: Test.test 3: main
        return sun.reflect.Reflection.getCallerClass(3);
    }

    static class Test {
        // Returns the caller of this method
        public static Class<?> test() {
            return new GetCallerClass().getCallerClass();
        }
        @sun.reflect.CallerSensitive
        public static Class<?> caller() {
            return sun.reflect.Reflection.getCallerClass();
        }
        @sun.reflect.CallerSensitive
        public static void selfTest() {
            // 0: Reflection 1: Test.selfTest
            Class<?> c = sun.reflect.Reflection.getCallerClass(1);
            if (c != Test.class || caller() != c) {
                throw new RuntimeException("Incorrect caller: " + c);
            }
            Inner1.deep();
        }

        static class Inner1 {
            static void deep() {
                 deeper();
            }
            static void deeper() {
                 Inner2.deepest();
            }
            static class Inner2 {
                static void deepest() {
                    // 0: Reflection 1: deepest 2: deeper 3: deep 4: Test.selfTest
                    Class<?> c = sun.reflect.Reflection.getCallerClass(4);
                    if (c != Test.class) {
                        throw new RuntimeException("Incorrect caller: " + c);
                    }
                }
            }
        }
    }
}
