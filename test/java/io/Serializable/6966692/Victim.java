/*
 * Copyright (c) 2010, Oracle and/or its affiliates. All rights reserved.
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

import java.io.*;

public class Victim implements Serializable {
    public volatile Object aaaa = "AAAA"; // must be volatile...
    private final Object aabb = new Show(this);
    public Object bbbb = "BBBB";
}
class Show implements Serializable {
    private final Victim victim;
    public Show(Victim victim) {
        this.victim = victim;
    }
    private void readObject(java.io.ObjectInputStream in)
     throws IOException, ClassNotFoundException
    {
        in.defaultReadObject();
        Thread thread = new Thread(new Runnable() { public void run() {
            for (;;) {
                Object a = victim.aaaa;
                if (a != null) {
                    System.err.println(victim+" "+a);
                    break;
                }
            }
        }});
        thread.start();

        // Make sure we are running compiled whilst serialisation is done interpreted.
        try {
            Thread.sleep(1000);
        } catch (java.lang.InterruptedException exc) {
            Thread.currentThread().interrupt();
        }
    }
}
