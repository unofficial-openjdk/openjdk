/*
 * Copyright (c) 2012, Oracle and/or its affiliates. All rights reserved.
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
 *
 */

/**
 * @test
 * @bug 7158807
 * @summary Revise stack management with volatile call sites
 * @author Marc Schonefeld
 *
 * @run main/othervm -XX:CompileThreshold=100 -Xbatch -Xss248k Test7158807
 */

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.invoke.VolatileCallSite;

public class Test7158807 {
    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws Throwable {
        for (int i = 0; i < 25600; i++) {
            MethodType mt = MethodType.methodType(java.lang.String.class);
            System.out.println(mt);
            MethodType mt3 = null;
            try {
              mt3 = MethodType.genericMethodType(i);
            } catch (IllegalArgumentException e) {
              System.out.println("Passed");
              System.exit(95);
            }
            System.out.println(i+":");
            try {
                VolatileCallSite vcs = new VolatileCallSite(mt3);
                System.out.println(vcs);
                MethodHandle mh = vcs.dynamicInvoker();
                vcs.setTarget(mh);
                // System.out.println(mh);
                mh.invoke(mt, mh);
            } catch (Throwable e) {
            }
        }
    }
}

