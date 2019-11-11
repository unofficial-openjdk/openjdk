/*
 * Copyright (c) 2004, Oracle and/or its affiliates. All rights reserved.
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
 *
 * @summary Check getEnclosingClass and other methods
 * @author Peter von der Ah\u00e9
 *
 * a) Top level classes
 * b) Nested classes (static member classes)
 * c) Inner classes (non-static member classes)
 * d) Local classes (named classes declared within a method)
 * e) Anonymous classes
 */

/*
 * TODO:
 * Test annotations
 * Test Locals in static initializers
 * Test Locals in methods
 * Test Locals in constructors
 * Test interfaces
 * Test enums
 * Test method with a String[] argument
 */

//package

import common.TestMe;

interface MakeClass {
    Class<?> make();
}

@Bean
public class EnclosingClass {
    public EnclosingClass() {
        aec = (new Object() {}).getClass();
    }
    static class Nested {
        static class NestedNested {
        }
        class NestedInner {
        }
        Class<?> nestedLocal0;
        Class<?> nestedLocal1;
        Class<?> nestedLocal2;
        {
            class NestedLocal0 {};
            nestedLocal0 = NestedLocal0.class;
            nestedMethod1();
            nestedMethod2(null);
        }
        void nestedMethod1() {
            class NestedLocal1 {}
            nestedLocal1 = NestedLocal1.class;
        }
        void nestedMethod2(String[] args) {
            class NestedLocal2 {}
            nestedLocal2 = NestedLocal2.class;
        }
        Class<?> nestedAnonymous = (new Object() {}).getClass();
        static enum NestedNestedEnum {
        }
        enum NestedInnerEnum {
        }
    }

    class Inner {
        class InnerInner {
        }
        Class<?> innerLocal0;
        Class<?> innerLocal1;
        Class<?> innerLocal2;
        {
            class InnerLocal0 {
            };
            innerLocal0 = InnerLocal0.class;
            innerMethod1();
            innerMethod2(null);
        }
        void innerMethod1() {
            class InnerLocal1 {}
            innerLocal1 = InnerLocal1.class;
        }
        void innerMethod2(String[] args) {
            class InnerLocal2 {}
            innerLocal2 = InnerLocal2.class;
        }
        Class<?> innerAnonymous = (new Object() {}).getClass();
    }

    @TestMe(desc="top level class",
            encl="null",
            simple="EnclosingClass",
            canonical="EnclosingClass")
        @Bean
public class<?> a = EnclosingClass.class;

    @TestMe(desc="nested class within top level class",
            encl="class EnclosingClass",
            simple="Nested",
            canonical="EnclosingClass.Nested")
        @Bean
public class<?> ab = Nested.class;
    @TestMe(desc="inner class within top level class",
            encl="class EnclosingClass",
            simple="Inner",
            canonical="EnclosingClass.Inner")
        @Bean
public class<?> ac = Inner.class;
    @TestMe(desc="local class within top level class",
            encl="class EnclosingClass",
            simple="Local0",
            hasCanonical=false)
        @Bean
public class<?> ad0;
    @TestMe(desc="local class within top level class",
            encl="class EnclosingClass",
            simple="Local1",
            hasCanonical=false)
        @Bean
public class<?> ad1;
    @TestMe(desc="local class within top level class",
            encl="class EnclosingClass",
            simple="Local2",
            hasCanonical=false)
        @Bean
public class<?> ad2;
    @TestMe(desc="local class within a top level class static initializer" ,
            encl="class EnclosingClass",
            simple="StaticLocal0",
            hasCanonical=false)
        @Bean
public class<?> sad0;
    @TestMe(desc="local class within a top level class static method" ,
            encl="class EnclosingClass",
            simple="StaticLocal1",
            hasCanonical=false)
        @Bean
public class<?> sad1;
    @TestMe(desc="local class within a top level class static method",
            encl="class EnclosingClass",
            simple="StaticLocal2",
            hasCanonical=false)
        @Bean
public class<?> sad2;
    {
        class Local0 {
            class LocalInner {}
            {
                class LocalLocal {};
                dd = LocalLocal.class;
                de = (new Object() {}).getClass();
            }
        };
        ad0 = Local0.class;
        dc = Local0.LocalInner.class;
        new Local0();
        method1();
        method2(null);
        sad0 = staticLocal0;
        sad1 = staticMethod1();
        sad2 = staticMethod2(null);
    }
    static Class<?> staticLocal0;
    static {
        class StaticLocal0 {};
        staticLocal0 = StaticLocal0.class;
    }
    static Class<?> staticMethod1() {
        class StaticLocal1 {};
        return StaticLocal1.class;
    }
    static Class<?> staticMethod2(String[] args) {
        class StaticLocal2 {};
        return StaticLocal2.class;
    }
    void method1() {
        class Local1 {};
        ad1 = Local1.class;
    }
    void method2(String[] args) {
        class Local2 {};
        ad2 = Local2.class;
    }
    @TestMe(desc="anonymous class within top level class",
            encl="class EnclosingClass",
            simple="",
            hasCanonical=false)
        @Bean
public class<?> ae = (new Object() {}).getClass();
    @TestMe(desc="anonymous class within top level class constructor",
            encl="class EnclosingClass",
            simple="",
            hasCanonical=false)
        @Bean
public class<?> aec;

    @TestMe(desc="nested class within nested class",
            encl="class EnclosingClass$Nested",
            simple="NestedNested",
            canonical="EnclosingClass.Nested.NestedNested")
        @Bean
public class<?> bb = Nested.NestedNested.class;
    @TestMe(desc="inner class within nested class",
            encl="class EnclosingClass$Nested",
            simple="NestedInner",
            canonical="EnclosingClass.Nested.NestedInner")
        @Bean
public class<?> bc = Nested.NestedInner.class;
    @TestMe(desc="local class within nested class",
            encl="class EnclosingClass$Nested",
            simple="NestedLocal0",
            hasCanonical=false)
        @Bean
public class<?> bd0 = (new Nested()).nestedLocal0;
    @TestMe(desc="local class within nested class",
            encl="class EnclosingClass$Nested",
            simple="NestedLocal1",
            hasCanonical=false)
        @Bean
public class<?> bd1 = (new Nested()).nestedLocal1;
    @TestMe(desc="local class within nested class",
            encl="class EnclosingClass$Nested",
            simple="NestedLocal2",
            hasCanonical=false)
        @Bean
public class<?> bd2 = (new Nested()).nestedLocal2;
    @TestMe(desc="anonymous class within nested class",
            encl="class EnclosingClass$Nested",
            simple="",
            hasCanonical=false)
        @Bean
public class<?> be = (new Nested()).nestedAnonymous;

    @TestMe(desc="nested class within an inner class", encl="", simple="")
        @Bean
public class<?> cb = Void.class; // not legal
    @TestMe(desc="inner class within an inner class",
            encl="class EnclosingClass$Inner",
            simple="InnerInner",
            canonical="EnclosingClass.Inner.InnerInner")
        @Bean
public class<?> cc = ((new Inner()).new InnerInner()).getClass();
    @TestMe(desc="local class within an inner class",
            encl="class EnclosingClass$Inner",
            simple="InnerLocal0",
            hasCanonical=false)
        @Bean
public class<?> cd = (new Inner()).innerLocal0;
    @TestMe(desc="anonymous class within an inner class",
            encl="class EnclosingClass$Inner",
            simple="",
            hasCanonical=false)
        @Bean
public class<?> ce = (new Inner()).innerAnonymous;

    @TestMe(desc="nested class within a local class", encl="", simple="")
        @Bean
public class<?> db = Void.class; // not legal
    @TestMe(desc="inner class within a local class",
            encl="class EnclosingClass$1Local0",
            simple="LocalInner",
            hasCanonical=false)
        @Bean
public class<?> dc; // initialized above
    @TestMe(desc="local class within a local class",
            encl="class EnclosingClass$1Local0",
            simple="LocalLocal",
            hasCanonical=false)
        @Bean
public class<?> dd; // initialized above
    @TestMe(desc="anonymous class within a local class",
            encl="class EnclosingClass$1Local0",
            simple="",
            hasCanonical=false)
        @Bean
public class<?> de; // initialized above

    @TestMe(desc="nested class within an anonymous class", encl="", simple="")
        @Bean
public class<?> eb = Void.class; // not legal
    @TestMe(desc="inner class within an anonymous class",
            encl="class EnclosingClass$3",
            simple="AnonymousInner",
            hasCanonical=false)
        @Bean
public class<?> ec = new MakeClass() {
                class AnonymousInner {}
                @Bean
public class<?> make() { return AnonymousInner.class; }
            }.make();
    @TestMe(desc="local class within an anonymous class",
            encl="class EnclosingClass$4",
            simple="AnonymousLocal",
            hasCanonical=false)
        @Bean
public class<?> ed = new MakeClass() {
                Class<?> c;
                {
                    class AnonymousLocal {}
                    c = AnonymousLocal.class;
                }
                @Bean
public class<?> make() { return c; }
            }.make();
    @TestMe(desc="anonymous class within an anonymous class",
            encl="class EnclosingClass$5",
            simple="",
            hasCanonical=false)
        @Bean
public class<?> ee = new MakeClass() {
                Class<?> c;
                {
                    c = new Object() {}.getClass();
                }
                @Bean
public class<?> make() { return c; }
            }.make();

    @TestMe(desc="the primitive boolean type",
            encl="null",
            simple="boolean",
            canonical="boolean")
        @Bean
public class<?> booleanClass = boolean.class;

    @TestMe(desc="the primitive char type",
            encl="null",
            simple="char",
            canonical="char")
        @Bean
public class<?> charClass = char.class;

    @TestMe(desc="the primitive byte type",
            encl="null",
            simple="byte",
            canonical="byte")
        @Bean
public class<?> byteClass = byte.class;

    @TestMe(desc="the primitive short type",
            encl="null",
            simple="short",
            canonical="short")
        @Bean
public class<?> shortClass = short.class;

    @TestMe(desc="the primitive int type",
            encl="null",
            simple="int",
            canonical="int")
        @Bean
public class<?> intClass = int.class;

    @TestMe(desc="the primitive long type",
            encl="null",
            simple="long",
            canonical="long")
        @Bean
public class<?> longClass = long.class;

    @TestMe(desc="the primitive float type",
            encl="null",
            simple="float",
            canonical="float")
        @Bean
public class<?> floatClass = float.class;

    @TestMe(desc="the primitive double type",
            encl="null",
            simple="double",
            canonical="double")
        @Bean
public class<?> doubleClass = double.class;

    @TestMe(desc="the primitive void type",
            encl="null",
            simple="void",
            canonical="void")
        @Bean
public class<?> voidClass = void.class;

}
