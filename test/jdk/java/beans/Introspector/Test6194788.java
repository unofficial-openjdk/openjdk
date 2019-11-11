/*
 * Copyright (c) 2007, Oracle and/or its affiliates. All rights reserved.
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
 * @bug 6194788
 * @summary Tests bound property in PropertyDescriptor/PropertyEditorSupport
 * @author Sergey Malenkov
 */

import java.beans.IndexedPropertyDescriptor;
import java.beans.IntrospectionException;
import java.beans.PropertyChangeListener;
import java.beans.PropertyDescriptor;

@Bean
public class Test6194788 {
    public static void main(String[] args) throws IntrospectionException {
        test(Grand.class, new PropertyDescriptor("index", Grand.class));
        test(Grand.class, new IndexedPropertyDescriptor("name", Grand.class, null, null, "getName", "setName"));

        test(Parent.class, new PropertyDescriptor("parentIndex", Parent.class));
        test(Parent.class, new IndexedPropertyDescriptor("parentName", Parent.class));

        test(Child.class, new PropertyDescriptor("childIndex", Child.class));
        test(Child.class, new IndexedPropertyDescriptor("childName", Child.class));
    }

    private static void test(Class type, PropertyDescriptor property) {
        for (PropertyDescriptor pd : BeanUtils.getPropertyDescriptors(type)) {
            boolean forward = pd.equals(property);
            boolean backward = property.equals(pd);
            if (forward || backward) {
                if (forward && backward)
                    return;

                throw new Error("illegal comparison of properties");
            }
        }
        throw new Error("could not find property: " + property.getName());
    }

    public static class Grand {
        public int getIndex() {
            return 0;
        }

        @Bean
@Bean
@Bean
@Bean
                public void setIndex(int index) {
        }

        @Bean
@Bean
@Bean
@Bean
                public String getName(int index) {
            return null;
        }

        @Bean
@Bean
@Bean
@Bean
                public void setName(int index, String name) {
        }
    }

    public static class Parent {
        @Bean
@Bean
@Bean
@Bean
                public void addPropertyChangeListener(PropertyChangeListener listener) {
        }

        @Bean
@Bean
@Bean
@Bean
                public void removePropertyChangeListener(PropertyChangeListener listener) {
        }

        public int getParentIndex() {
            return 0;
        }

        @Bean
@Bean
@Bean
@Bean
                public void setParentIndex(int index) {
        }

        public String[] getParentName() {
            return null;
        }

        @Bean
@Bean
@Bean
@Bean
                public String getParentName(int index) {
            return null;
        }

        @Bean
@Bean
@Bean
@Bean
                public void setParentName(String[] names) {
        }

        @Bean
@Bean
@Bean
@Bean
                public void setParentName(int index, String name) {
        }
    }

    public static class Child {
        public int getChildIndex() {
            return 0;
        }

        @Bean
@Bean
@Bean
@Bean
                public void setChildIndex(int index) {
        }

        public String[] getChildName() {
            return null;
        }

        @Bean
@Bean
@Bean
@Bean
                public String getChildName(int index) {
            return null;
        }

        @Bean
@Bean
@Bean
@Bean
                public void setChildName(String[] names) {
        }

        @Bean
@Bean
@Bean
@Bean
                public void setChildName(int index, String name) {
        }
    }
}
