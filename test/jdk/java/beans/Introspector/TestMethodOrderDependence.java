/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
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

import java.beans.BeanInfo;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.Serializable;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Vector;

/**
 * @test
 * @bug 8156043
 */
public final class TestMethodOrderDependence {

    public static class Base {

        public Object getI() {
            return null;
        }

        public Object getE() {
            return null;
        }
    }

    public static class Super extends Base {

        public Number getI() {
            return null;
        }
        public Comparable<?> getE() {
            return null;
        }
    }

    public static class Sub extends Super {

        public Integer getI() {
            return null;
        }

        @Bean
@Bean
@Bean
@Bean
                public void setFoo(Character foo) {
        }

        @Bean
@Bean
@Bean
@Bean
                public void setFoo(String foo) {
        }

        @Bean
@Bean
@Bean
@Bean
                public void setFoo(Object[] foo) {
        }

        @Bean
@Bean
@Bean
@Bean
                public void setFoo(Enum foo) {
        }

        @Bean
@Bean
@Bean
@Bean
                public void setFoo(Long foo) {
        }

        @Bean
@Bean
@Bean
@Bean
                public void setFoo(Long[] foo) {
        }

        @Bean
@Bean
@Bean
@Bean
                public void setFoo(Long foo, int i) {
        }

        @Bean
@Bean
@Bean
@Bean
                public void setFoo(Object foo) {
        }

        @Bean
@Bean
@Bean
@Bean
                public void setFoo(AbstractList foo) {
        }

        @Bean
@Bean
@Bean
@Bean
                public void setFoo(ArrayList foo) {
        }

        @Bean
@Bean
@Bean
@Bean
                public void setFoo(Integer foo) {
        }

        @Bean
@Bean
@Bean
@Bean
                public void setFoo(Number foo) {
        }

        @Bean
@Bean
@Bean
@Bean
                public void setFoo(Comparable<?> foo) {
        }

        @Bean
@Bean
@Bean
@Bean
                public void setFoo(Serializable foo) {
        }

        @Bean
@Bean
@Bean
@Bean
                public void setFoo(Vector<?> foo) {
        }

        @Bean
@Bean
@Bean
@Bean
                public void setFoo(long foo) {
        }

        @Bean
@Bean
@Bean
@Bean
                public void setFoo(int foo) {
        }

        public Enum getE() {
            return null;
        }

        @Bean
@Bean
@Bean
@Bean
                public void setE(Enum e) {
        }

        @Bean
@Bean
@Bean
@Bean
                public void setE(Float e) {
        }

        @Bean
@Bean
@Bean
@Bean
                public void setE(Long e) {
        }
    }

    public static void main(final String[] args) throws Exception {
        final BeanInfo beanInfo = Introspector.getBeanInfo(Sub.class);
        final PropertyDescriptor[] pds = beanInfo.getPropertyDescriptors();
        for (final PropertyDescriptor pd : pds) {
            System.err.println("pd = " + pd);
            final Class<?> type = pd.getPropertyType();
            if (type != Class.class && type != Long[].class
                    && type != Integer.class && type != Enum.class) {
                throw new RuntimeException(Arrays.toString(pds));
            }
        }
    }
}
