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
 * @run testng Basic
 * @run testng/othervm -Xint Scoped
 * @run testng/othervm -Xint -XX:+UnlockDiagnosticVMOptions -XX:+UseNewCode Scoped
 *  
 * @summary Nested continuations test
 */

// * @run testng/othervm -Xcomp Scoped
// * @run testng/othervm -Xcomp -XX:+UnlockDiagnosticVMOptions -XX:+UseNewCode Scoped

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;
import static org.testng.Assert.*;

import java.util.concurrent.atomic.AtomicInteger;

@Test
public class Scoped {
        static final ContinuationScope A = new ContinuationScope() {};
        static final ContinuationScope B = new ContinuationScope() {};
        static final ContinuationScope C = new ContinuationScope() {};

    public void test1() {
                final AtomicInteger res = new AtomicInteger(0);
                Continuation cont = new Continuation(A, ()-> {
                        double r = 0;
                        for (int k = 1; k < 2; k++) {
                                int x = 3;
                                String s = "abc";
                                r += foo(k);
            }
            res.set((int)r);
                });

                while (!cont.isDone()) {
                        cont.run();
                        System.gc();
                }
                assertEquals(res.get(), 2);
        }

    static double foo(int a) {
                long x = 8;
                String s = "yyy";
                String r = null;
                Continuation cont = new Continuation(B, ()-> {
                        bar(a + 1);
                });
                cont.run();
                return 2; // Integer.parseInt(r)+1;
        }

        static String bar(long b) {
                double x = 9.99;
                String s = "zzz";
                // Thread.dumpStack();
                Continuation cont = new Continuation(C, ()-> {
                        Continuation.yield(A);

                        StackWalker walker = StackWalker.getInstance();
                        List<String> frames = walker.walk(fs -> fs.map(StackWalker.StackFrame::getMethodName).collect(Collectors.toList()));
                
                        assertEquals(frames.subList(0, 15), Arrays.asList("lambda$bar$6", "enter0", "enter", "run", "bar", "lambda$foo$1", "enter0", "enter", "run", "foo", "lambda$test1$0", "enter0", "enter", "run", "test1"));
                
                        walker = StackWalker.getInstance(C);
                        frames = walker.walk(fs -> fs.map(StackWalker.StackFrame::getMethodName).collect(Collectors.toList()));
                
                        assertEquals(frames, Arrays.asList("lambda$bar$6", "enter0"));

                        walker = StackWalker.getInstance(B);
                        frames = walker.walk(fs -> fs.map(StackWalker.StackFrame::getMethodName).collect(Collectors.toList()));
                
                        assertEquals(frames, Arrays.asList("lambda$bar$6", "enter0", "enter", "run", "bar", "lambda$foo$1", "enter0"));

                        walker = StackWalker.getInstance(A);
                        frames = walker.walk(fs -> fs.map(StackWalker.StackFrame::getMethodName).collect(Collectors.toList()));
                
                        assertEquals(frames, Arrays.asList("lambda$bar$6", "enter0", "enter", "run", "bar", "lambda$foo$1", "enter0", "enter", "run", "foo", "lambda$test1$0", "enter0"));


                        long r = b+1;
                });
                cont.run();
                return "" + 3;
        }
}
