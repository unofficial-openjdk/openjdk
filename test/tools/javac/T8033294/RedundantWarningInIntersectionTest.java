/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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
 * @bug 8033294
 * @summary javac, spurious warning for instanceof operator
 * @compile -Werror -Xlint:unchecked RedundantWarningInIntersectionTest.java
 */

import java.math.BigDecimal;

public class RedundantWarningInIntersectionTest {

    class A<S extends A<S, T>, T> {

        protected T p;

        A(T p) {}

        public S m(T parameter) {
            @SuppressWarnings("unchecked")
            S self = (S) new A<>(parameter);
            return self;
        }
    }

    class B<K extends Number & Comparable<? super K>> extends A<B<K>, K> {

        B(K parameter) {
            super(parameter);
        }

        public boolean m2() {
            return (p instanceof BigDecimal);
        }
    }
}
