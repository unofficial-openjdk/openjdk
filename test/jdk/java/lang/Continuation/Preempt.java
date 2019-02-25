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
* @summary Tests for java.lang.Continuation preemption
*
* @run testng/othervm -Xint Preempt
* @run testng/othervm -XX:+UnlockDiagnosticVMOptions -Xint -XX:+UseNewCode Preempt
* @run testng/othervm -XX:-TieredCompilation -Xcomp Preempt
* @run testng/othervm -XX:+UnlockDiagnosticVMOptions -XX:-TieredCompilation -Xcomp -XX:+UseNewCode Preempt
* @run testng/othervm -XX:TieredStopAtLevel=3 -Xcomp Preempt
* @run testng/othervm -XX:+UnlockDiagnosticVMOptions -XX:TieredStopAtLevel=3 -Xcomp -XX:+UseNewCode Preempt
*/

// * @run testng/othervm -XX:+UnlockExperimentalVMOptions -XX:-TieredCompilation -XX:+UseJVMCICompiler -Xcomp Preempt
// * @run testng/othervm -XX:+UnlockExperimentalVMOptions -XX:+UnlockDiagnosticVMOptions -XX:-TieredCompilation -XX:+UseJVMCICompiler -Xcomp -XX:+UseNewCode Preempt

// TODO:
// - Add tests for failed preemptions
// - Add tests for additional safepoint types
// - Add tests with -XX:-ThreadLocalHandshakes

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;
import static org.testng.Assert.*;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Test
public class Preempt {
    static final ContinuationScope FOO = new ContinuationScope() {};
    volatile boolean run;
	volatile int x;
    
    public void test1() throws Exception {
        System.out.println("test1");

		final Continuation cont = new Continuation(FOO, ()-> { 
				loop();
            });
        
        final Thread t0 = Thread.currentThread();
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(1000);
                {
                    var res = cont.tryPreempt(t0);
                    assertEquals(res, Continuation.PreemptStatus.SUCCESS);
                }
    
                Thread.sleep(1000);
                {
                    var res = cont.tryPreempt(t0);
                    assertEquals(res, Continuation.PreemptStatus.SUCCESS);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        t.start();

        run = true;

        cont.run();
        assertEquals(cont.isDone(), false);
        assertEquals(cont.isPreempted(), true);

        List<String> frames = cont.stackWalker().walk(fs -> fs.map(StackWalker.StackFrame::getMethodName).collect(Collectors.toList()));
        assertEquals(frames, Arrays.asList("loop", "lambda$test1$0", "enter0", "enter"));

        cont.run();
        assertEquals(cont.isDone(), false);
        assertEquals(cont.isPreempted(), true);

        t.join();
    }

    private void loop() {
		while (run)
		   x++;
    }
}
