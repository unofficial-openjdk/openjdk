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

package MyPackage;

/**
 * @test
 * @summary Verifies JVMTI support for Fibers.
 * @compile FiberTest.java
 * @run main/othervm/native -agentlib:FiberTest MyPackage.FiberTest
 */

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.stream.Stream;

/*
    public static void main(String[] args) {
        final Script script = createScript("sleep(1000);", new HashMap<String, Object>());
        final AtomicInteger counter = new AtomicInteger(0);
        Fiber fiber = new Fiber(scheduler, new SuspendableRunnable() {
            @Override
            public void run() throws SuspendExecution, InterruptedException {
                counter.incrementAndGet();
                script.run();
                counter.incrementAndGet();
            }
        });
        fiber.start();
        fiber.join();
        // Assert.assertEquals(counter.intValue(), 2);
    }
}
*/

public class FiberTest {

    static final Runnable DO_NOTHING = () -> { };
    private static final String agentLib = "FiberTest";

    // -- basic tests ---

    public void testExecute1() {
        var executed = new AtomicBoolean();
        var f = Fiber.execute(() -> executed.set(true));
        f.await();
    }

    // throw uncaught exception
    public void testUncaughtException1() {
        var executed = new AtomicBoolean();
        Fiber f = Fiber.execute(() -> {
            executed.set(true);
            throw new RuntimeException();
        });
        f.await();
    }

    // throw uncaught error
    public void testUncaughtException2() {
        var executed = new AtomicBoolean();
        Fiber f = Fiber.execute(() -> {
            executed.set(true);
            throw new Error();
        });
        f.await();
    }


    // -- park/parkNanos/unpark --

    // fiber parks, unparked by thread
    public void testPark1() throws Exception {
        var completed = new AtomicBoolean();
        Fiber f = Fiber.execute(() -> {
            Fiber.park();
            completed.set(true);
        });
        Thread.sleep(1000); // give time for fiber to park
        f.unpark();
        f.await();
    }

    // fiber parks, unparked by another fiber
    public void testPark2() throws Exception {
        var completed = new AtomicInteger();
        Fiber f1 = Fiber.execute(() -> {
            Fiber.park();
            completed.incrementAndGet();
        });
        Thread.sleep(1000); // give time for fiber to park
        Fiber f2 = Fiber.execute(() -> {
            f1.unpark();
            completed.incrementAndGet();
        });
        f1.await();
        f2.await();
    }

    // park while holding monitor
    public void testPark3() throws Exception {
        var completed = new AtomicBoolean();
        Fiber f = Fiber.execute(() -> {
            var lock = new Object();
            synchronized (lock) {
                Fiber.park();
            }
            completed.set(true);
        });
        Thread.sleep(1000); // give time for fiber to park
        f.unpark();
        f.await();
    }

    void runTest() throws Exception {
        testExecute1();
        // testUncaughtException1();
        // testUncaughtException2();
        testPark1();
        testPark2();
        testPark3();
    }

    public static void main(String[] args) throws Exception {
        try {
            System.loadLibrary(agentLib);
        } catch (UnsatisfiedLinkError ex) {
            System.err.println("Failed to load " + agentLib + " lib");
            System.err.println("java.library.path: " + System.getProperty("java.library.path"));
            throw ex;
        }

        FiberTest obj = new FiberTest();
        obj.runTest();
    }
}
