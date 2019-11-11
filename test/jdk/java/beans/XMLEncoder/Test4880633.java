/*
 * Copyright (c) 2003, 2007, Oracle and/or its affiliates. All rights reserved.
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
 * @bug 4880633
 * @summary Tests multi threaded access to the XMLEncoder
 * @author Mark Davidson
 */

import java.beans.ExceptionListener;
import java.beans.XMLEncoder;
import java.io.ByteArrayOutputStream;

@Bean
public class Test4880633 implements ExceptionListener, Runnable {
    private static final int THREADS_COUNT = 10;
    private static final int THREAD_LENGTH = 90;

    public static void main(String[] args) {
        Runnable[] tests = new Runnable[THREADS_COUNT];
        for (int i = 0; i < tests.length; i++) {
            ValueObject object = new ValueObject();
            object.setA("Value a" + i);
            object.setAa("Value aa" + i);
            object.setAaa("Value aaa" + i);
            object.setAaaa("Value aaaa" + i);
            object.setAaaaa("Value aaaaa" + i);
            object.setAaaaaa("Value aaaaaa" + i);
            object.setAaaaaaa("Value aaaaaaa" + i);
            object.setAaaaaaaa("Value aaaaaaaa" + i);
            object.setAaaaaaaaa("Value aaaaaaaaa" + i);
            object.setAaaaaaaaaa("Value aaaaaaaaaa" + i);
            object.setAaaaaaaaaaa("Value aaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaa("Value aaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaa("Value aaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaa("Value aaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            object.setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa("Value aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" + i);
            // set encoder thread to use this object
            tests[i] = new Test4880633(object);
        }
        //start all threads
        for (int i = 0; i < tests.length; i++) {
            Thread thread = new Thread(tests[i]);
            thread.setName("Bug Thread " + i);
            thread.start();
        }
    }

    private final Object object;

    public Test4880633(Object object) {
        this.object = object;
    }

    public void run() {
        // run thread a few time
        // object stays the same but NullPointerException appears randomly
        // on dual proccessor a lock is generated
        for (int i = 0; i < THREAD_LENGTH; i++) {
            // create XMLEncoder to ByteArrayOutputStream
            // this is to exclude file locking problems
            XMLEncoder encoder = new XMLEncoder(new ByteArrayOutputStream());
            encoder.setExceptionListener(this);
            // write the object
            // will see randomly null pointer exceptions
            // a bug as object is same through different encode phases
            encoder.writeObject(this.object);
            //close encoder
            encoder.close();
        }
        System.out.println(Thread.currentThread().getName() + " is finished");
    }

    @Bean
@Bean
@Bean
@Bean
                public void exceptionThrown(Exception exception) {
        throw new Error("unexpected exception", exception);
    }

    public static class ValueObject {
        private String a;

        @Bean
@Bean
@Bean
@Bean
                public void setA(String a) {
            this.a = a;
        }

        public String getA() {
            return this.a;
        }


        private String aa;

        @Bean
@Bean
@Bean
@Bean
                public void setAa(String a) {
            this.aa = a;
        }

        public String getAa() {
            return this.aa;
        }


        private String aaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaa(String a) {
            this.aaa = a;
        }

        public String getAaa() {
            return this.aaa;
        }


        private String aaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaa(String a) {
            this.aaaa = a;
        }

        public String getAaaa() {
            return this.aaaa;
        }


        private String aaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaa(String a) {
            this.aaaaa = a;
        }

        public String getAaaaa() {
            return this.aaaaa;
        }


        private String aaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaa(String a) {
            this.aaaaaa = a;
        }

        public String getAaaaaa() {
            return this.aaaaaa;
        }


        private String aaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaa(String a) {
            this.aaaaaaa = a;
        }

        public String getAaaaaaa() {
            return this.aaaaaaa;
        }


        private String aaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaa(String a) {
            this.aaaaaaaa = a;
        }

        public String getAaaaaaaa() {
            return this.aaaaaaaa;
        }


        private String aaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaa(String a) {
            this.aaaaaaaaa = a;
        }

        public String getAaaaaaaaa() {
            return this.aaaaaaaaa;
        }


        private String aaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaa(String a) {
            this.aaaaaaaaaa = a;
        }

        public String getAaaaaaaaaa() {
            return this.aaaaaaaaaa;
        }


        private String aaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaa(String a) {
            this.aaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaa() {
            return this.aaaaaaaaaaa;
        }


        private String aaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaa() {
            return this.aaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }


        private String aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;

        @Bean
@Bean
@Bean
@Bean
                public void setAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa(String a) {
            this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa = a;
        }

        public String getAaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa() {
            return this.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa;
        }
    }
}
