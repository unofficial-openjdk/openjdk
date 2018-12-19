/*
* Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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

/**
* @test
* @summary Basic tests for java.lang.Continuation
* @run testng Basic
* @run testng/othervm -Xint Basic
* @run testng/othervm -XX:+UnlockDiagnosticVMOptions -Xint -XX:+UseNewCode Basic
*
* @summary Basic tests for java.lang.Continuation
*/

// * @run testng/othervm -XX:+UseParallelGC -XX:-TieredCompilation -Xcomp Basic
// * @run testng/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UseParallelGC -XX:-TieredCompilation -Xcomp -XX:+UseNewCode Basic
// * @run testng/othervm -XX:+UseParallelGC -XX:TieredStopAtLevel=3 -Xcomp Basic
// * @run testng/othervm -XX:+UnlockDiagnosticVMOptions -XX:+UseParallelGC -XX:TieredStopAtLevel=3 -Xcomp -XX:+UseNewCode Basic

// * @run testng/othervm -XX:+UnlockExperimentalVMOptions -XX:+UseParallelGC -XX:-TieredCompilation -XX:+UseJVMCICompiler -Xcomp Basic
// * @run testng/othervm -XX:+UnlockExperimentalVMOptions -XX:+UnlockDiagnosticVMOptions -XX:+UseParallelGC -XX:-TieredCompilation -XX:+UseJVMCICompiler -Xcomp -XX:+UseNewCode Basic


import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;
import static org.testng.Assert.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Test
public class Basic {
    static final ContinuationScope FOO = new ContinuationScope() {};
    
    public void test1() {
        System.out.println("test1");
        final AtomicInteger res = new AtomicInteger(0);
        Continuation cont = new Continuation(FOO, ()-> {
            double r = 0;
            for (int k = 1; k < 20; k++) {
                int x = 3;
                String s = "abc";
                r += foo(k);
            }
            res.set((int)r);
        });
        
        int i = 0;
        while (!cont.isDone()) {
            cont.run();
            System.gc();

            List<String> frames = cont.stackWalker().walk(fs -> fs.map(StackWalker.StackFrame::getMethodName).collect(Collectors.toList()));
            assertEquals(frames, Arrays.asList("yield0", "yield", "bar", "foo", "lambda$test1$0", "enter0"));
        }
        assertEquals(res.get(), 247);
    }
    
    static double foo(int a) {
        long x = 8;
        String s = "yyy";
        String r = bar(a + 1);
        return Integer.parseInt(r)+1;
    }
    
    static String bar(long b) {
        double x = 9.99;
        String s = "zzz";
        Continuation.yield(FOO);

        StackWalker walker = StackWalker.getInstance();
        List<String> frames = walker.walk(fs -> fs.map(StackWalker.StackFrame::getMethodName).collect(Collectors.toList()));

        assertEquals(frames.subList(0, 7), Arrays.asList("bar", "foo", "lambda$test1$0", "enter0", "enter", "run", "test1"));

        walker = StackWalker.getInstance(FOO);
        frames = walker.walk(fs -> fs.map(StackWalker.StackFrame::getMethodName).collect(Collectors.toList()));

        assertEquals(frames, Arrays.asList("bar", "foo", "lambda$test1$0", "enter0"));

        long r = b+1;
        return "" + r;
    }

    static String bar2(long b) {
        double x = 9.99;
        String s = "zzz";
        Continuation.yield(FOO);

        long r = b+1;
        return "" + r;
    }

    static class LoomException extends RuntimeException {
        public LoomException(String message) {
            super(message);
        }

        @Override
        public Throwable fillInStackTrace() {
            return fillInStackTrace(FOO);
        }
    }

    static double fooThrow(int a) {
        long x = 8;
        String s = "yyy";
        String r = barThrow(a + 1);
        return Integer.parseInt(r)+1;
    }

    static String barThrow(long b) {
        double x = 9.99;
        String s = "zzz";
        Continuation.yield(FOO);

        long r = b+1;

        if (true)
            throw new LoomException("Loom exception!");
        return "" + r;
    }
    
    public void testException1() {
        System.out.println("testException1");
        Continuation cont = new Continuation(FOO, ()-> {
            double r = 0;
            for (int k = 1; k < 20; k++) {
                int x = 3;
                String s = "abc";
                r += fooThrow(k);
            }
        });
        
        cont.run();
        try {
            cont.run();
            fail("Exception not thrown.");
        } catch (LoomException e) {
            assertEquals(e.getMessage(), "Loom exception!");

            StackTraceElement[] stes = e.getStackTrace();
            // System.out.println(Arrays.toString(stes));
            assertEquals(stes[0].getMethodName(), "barThrow");
            assertEquals(stes[stes.length - 1].getClassName(), "java.lang.Continuation");
            assertEquals(stes[stes.length - 1].getMethodName(), "enter0");
        }
    }

    public void testManyArgs() {
        System.out.println("testManyArgs");
        final AtomicInteger res = new AtomicInteger(0);
        Continuation cont = new Continuation(FOO, ()-> {
            double r = 0;
            for (int k = 1; k < 20; k++) {
                int x = 3;
                String s = "abc";
                r += fooMany(k,
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
                1.0, 2.0, 3.0, 4.0, 5.0, 6.0f, 7.0f, 8.0, 9.0, 10.0, 11.0, 12.0, 13.0, 14.0f, 15.0f, 16.0f, 17.0, 18.0, 19.0, 20.0,
                1001, 1002, 1003, 1004, 1005, 1006, 1007, 1008, 1009, 1010, 1011, 1012, 1013, 1014, 1015, 1016, 1017, 1018, 1019, 1020);
            }
            res.set((int)r);
        });
        
        int i = 0;
        while (!cont.isDone()) {
            cont.run();
            System.gc();
        }
        assertEquals(res.get(), 247);
    }
    
    static double fooMany(int a,
    int x1, int x2, int x3, int x4, int x5, int x6, int x7, int x8, int x9, int x10, int x11, int x12,
    int x13, int x14, int x15, int x16, int x17, int x18, int x19, int x20,
    double f1, double f2, double f3, double f4, double f5, float f6, float f7, double f8, double f9, double f10,
    double f11, double f12, double f13, float f14, float f15, float f16, double f17, double f18, double f19, double f20,
    Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10,
    Object o11, Object o12, Object o13, Object o14, Object o15, Object o16, Object o17, Object o18, Object o19, Object o20) {
        long x = 8;
        String s = "yyy";
        String r = barMany(a + 1,
        x1, x2, x3, x4, x5, x6, x7, x8, x9, x10, x11, x12, x13, x14, x15, x16, x17, x18, x19, x20,
        f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14, f15, f16, f17, f18, f19, f20,
        o1, o2, o3, o4, o5, o6, o7, o8, o9, o10, o11, o12, o13, o14, o15, o16, o17, o18, o19, o20);
        return Integer.parseInt(r)+1;
    }
    
    static String barMany(long b,
    int x1, int x2, int x3, int x4, int x5, int x6, int x7, int x8, int x9, int x10, int x11, int x12,
    int x13, int x14, int x15, int x16, int x17, int x18, int x19, int x20,
    double f1, double f2, double f3, double f4, double f5, float f6, float f7, double f8, double f9, double f10,
    double f11, double f12, double f13, float f14, float f15, float f16, double f17, double f18, double f19, double f20,
    Object o1, Object o2, Object o3, Object o4, Object o5, Object o6, Object o7, Object o8, Object o9, Object o10,
    Object o11, Object o12, Object o13, Object o14, Object o15, Object o16, Object o17, Object o18, Object o19, Object o20) {
        double x = 9.99;
        String s = "zzz";
        Continuation.yield(FOO);
        long r = b+1;
        return "" + r;
    }
    
    public void testPinnedMonitor() {
        System.out.println("testPinnedMonitor");
        final AtomicReference<Continuation.Pinned> res = new AtomicReference<>();
        
        Continuation cont = new Continuation(FOO, ()-> {
            syncFoo(1);
        }) {
            @Override
            protected void onPinned(Continuation.Pinned reason) {
                res.set(reason);
            }
        };
        
        cont.run();
        assertEquals(res.get(), Continuation.Pinned.MONITOR);
    }
    
    static double syncFoo(int a) {
        long x = 8;
        String s = "yyy";
        String r;
        synchronized(FOO) {
            r = bar2(a + 1);
        }
        return Integer.parseInt(r)+1;
    }
    
    public void testPinnedNative() {
        System.out.println("testPinnedNative");
        final AtomicReference<Continuation.Pinned> res = new AtomicReference<>();
        
        Continuation cont = new Continuation(FOO, ()-> {
            nativeFoo(1);
        }) {
            @Override
            protected void onPinned(Continuation.Pinned reason) {
                res.set(reason);
            }
        };
        
        cont.run();
        assertEquals(res.get(), Continuation.Pinned.NATIVE);
    }
    
    static double nativeFoo(int a) {
        try {
            long x = 8;
            String s = "yyy";
            String r = (String)Basic.class.getDeclaredMethod("bar2", long.class).invoke(null, 1L);
            return Integer.parseInt(r)+1;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
