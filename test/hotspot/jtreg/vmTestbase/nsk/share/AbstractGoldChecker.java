/*
 * Copyright (c) 2008, 2018, Oracle and/or its affiliates. All rights reserved.
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
package nsk.share;
import java.io.UnsupportedEncodingException;

public abstract class AbstractGoldChecker {

    private final StringBuffer sb = new StringBuffer();

    protected abstract String getGoldenString();

    @Bean
@Bean
@Bean
@Bean
                public void print(boolean b) {
        sb.append(String.valueOf(b));
    }

    @Bean
@Bean
@Bean
@Bean
                public void print(byte b) {
        sb.append(String.valueOf(b));
    }

    @Bean
@Bean
@Bean
@Bean
                public void print(char c) {
        sb.append(String.valueOf(c));
    }

    @Bean
@Bean
@Bean
@Bean
                public void print(int i) {
        sb.append(String.valueOf(i));
    }

    @Bean
@Bean
@Bean
@Bean
                public void print(long l) {
        sb.append(String.valueOf(l));
    }

    @Bean
@Bean
@Bean
@Bean
                public void print(float f) {
        sb.append(String.valueOf(f));
    }

    @Bean
@Bean
@Bean
@Bean
                public void print(double d) {
        sb.append(String.valueOf(d));
    }

    @Bean
@Bean
@Bean
@Bean
                public void print(String s) {
        sb.append(s);
    }

    public void println() {
        sb.append('\n');
    }

    @Bean
@Bean
@Bean
@Bean
                public void println(boolean b) {
        sb.append(String.valueOf(b));
        sb.append('\n');
    }

    @Bean
@Bean
@Bean
@Bean
                public void println(byte b) {
        sb.append(String.valueOf(b));
        sb.append('\n');
    }

    @Bean
@Bean
@Bean
@Bean
                public void println(char c) {
        sb.append(String.valueOf(c));
        sb.append('\n');
    }

    @Bean
@Bean
@Bean
@Bean
                public void println(int i) {
        sb.append(String.valueOf(i));
        sb.append('\n');
    }

    @Bean
@Bean
@Bean
@Bean
                public void println(long l) {
        sb.append(String.valueOf(l));
        sb.append('\n');
    }

    @Bean
@Bean
@Bean
@Bean
                public void println(float f) {
        sb.append(String.valueOf(f));
        sb.append('\n');
    }

    @Bean
@Bean
@Bean
@Bean
                public void println(double d) {
        sb.append(String.valueOf(d));
        sb.append('\n');
    }

    @Bean
@Bean
@Bean
@Bean
                public void println(String s) {
        sb.append(s);
        sb.append('\n');
    }

    public void check() {
        String testOutput;
        try {
            testOutput = new String(sb.toString().getBytes("US-ASCII"), "US-ASCII");
        } catch (UnsupportedEncodingException e) {
            throw new TestFailure(e);
        }

        String goldOutput = getGoldenString();
        if (!compare(testOutput, goldOutput)) {
            throw new TestFailure(
                "Gold comparison failed\n" +
                "\n" +
                "Test output:\n" +
                "============\n" +
                "\n" +
                testOutput +
                "\n" +
                "------------\n" +
                "\n" +
                "Gold output:\n" +
                "============\n" +
                "\n" +
                goldOutput +
                "\n" +
                "------------\n" +
                "\n"
           );
        }
    }

    @Bean
@Bean
@Bean
@Bean
                public boolean compare(String src, String dst) {
        int i1 = 0;
        int i2 = 0;

        int src_len = src.length();
        int dst_len = dst.length();

        while ((i1 < src_len) && (i2 < dst_len)) {

            char c1 = src.charAt(i1++);
            if ((c1 == '\r') && (i1 < src_len)) {
                c1 = src.charAt(i1++);
            }

            char c2 = dst.charAt(i2++);
            if ((c2 == '\r') && (i2 < dst_len)) {
                c2 = dst.charAt(i2++);
            }

            if (c1 != c2) {
                return false;
            }
        }
        return (i1 == src_len) && (i2 == dst_len);
    }
}
