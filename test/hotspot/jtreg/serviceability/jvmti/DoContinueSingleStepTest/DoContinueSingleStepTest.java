/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Verifies that single stepping into Continuation.doContinue() properly completes in yield0().
 * @compile DoContinueSingleStepTest.java
 * @run main/othervm/native -XX:+UseContinuationLazyCopy -agentlib:DoContinueSingleStepTest -Djdk.defaultScheduler.parallelism=1 DoContinueSingleStepTest 150
 * @run main/othervm/native -XX:+UseContinuationLazyCopy -agentlib:DoContinueSingleStepTest -Djdk.defaultScheduler.parallelism=1 DoContinueSingleStepTest 500
 * @run main/othervm/native -XX:+UseContinuationLazyCopy -agentlib:DoContinueSingleStepTest DoContinueSingleStepTest 150
 * @run main/othervm/native -XX:+UseContinuationLazyCopy -agentlib:DoContinueSingleStepTest DoContinueSingleStepTest 500
 */

import java.util.concurrent.*;

public class DoContinueSingleStepTest {
    private static final String agentLib = "DoContinueSingleStepTest";

    static native void enableEvents(Thread thread, Class contClass);
    static native boolean check();

    static int MSG_COUNT; // Passed as an argument
    static final SynchronousQueue<String> QUEUE = new SynchronousQueue<>();

    static final Runnable PRODUCER = () -> {
        try {
            for (int i = 0; i < MSG_COUNT; i++) {
                QUEUE.put("msg"+i);
                QUEUE.put("msg"+i);
                if (i == MSG_COUNT - 10) {
                    // Once we have warmed up, enable the first breakpoint which eventually will
                    // lead to enabling single stepping.
                    enableEvents(Thread.currentThread(), java.lang.Continuation.class);
                }
            }
        } catch (InterruptedException e) { }
    };

    static final Runnable CONSUMER = () -> {
        try {
            for (int i = 0; i < MSG_COUNT; i++) {
                String s = QUEUE.take();
            }
        } catch (InterruptedException e) { }
    };

    public static void test1() throws Exception {
        try (var scope = FiberScope.open()) {
            scope.schedule(PRODUCER);
            scope.schedule(CONSUMER);
            scope.schedule(CONSUMER);
        }
    }

    void runTest() throws Exception {
        test1();
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new RuntimeException("Invalid # of arguments.");
        } else {
            MSG_COUNT = Integer.valueOf(args[0]);
        }

        try {
            System.out.println("loading " + agentLib + " lib");
            System.loadLibrary(agentLib);
        } catch (UnsatisfiedLinkError ex) {
            System.err.println("Failed to load " + agentLib + " lib");
            System.err.println("java.library.path: " + System.getProperty("java.library.path"));
            throw ex;
        }

        DoContinueSingleStepTest obj = new DoContinueSingleStepTest();
        obj.runTest();
        if (!check()) {
            throw new RuntimeException("DoContinueSingleStepTest failed!");
        }
        System.out.println("DoContinueSingleStepTest passed\n");
        System.out.println("\n#####   main: finished  #####\n");
    }
}
