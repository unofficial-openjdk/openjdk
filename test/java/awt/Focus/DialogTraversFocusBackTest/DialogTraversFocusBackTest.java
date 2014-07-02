/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

/* @test
    @bug 8031075
    @summary Regression: focus disappears with shift+tab on dialogue having a focus component
    @author mcherkas
    @run main DialogTraversFocusBackTest
*/

import sun.awt.SunToolkit;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;

public class DialogTraversFocusBackTest {

    private static Robot robot;
    private volatile static JButton button;
    private static Component currentFocusOwner;

    public static void main(String[] args) throws Exception {
        initUI();
        sync();
        initRobot();
        runScript();
        sync();
        validate();
    }

    public static void sync() {
        SunToolkit toolkit = (SunToolkit) Toolkit.getDefaultToolkit();
        toolkit.realSync();
    }

    private static void validate() throws Exception {
        currentFocusOwner = FocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if(currentFocusOwner
            != button) {
             throw new Exception("Test failed! Wrong focus owner: " +
                     String.valueOf(currentFocusOwner) + "\n but must be: " +
                    button);
        }
    }

    private static void runScript() {
        robot.keyPress(KeyEvent.VK_SHIFT);
        robot.keyPress(KeyEvent.VK_TAB);
        robot.keyRelease(KeyEvent.VK_TAB);
        robot.keyRelease(KeyEvent.VK_SHIFT);

    }

    private static void initRobot() throws AWTException {
        robot = new Robot();
        robot.setAutoDelay(100);

    }

    private static void initUI() throws Exception {
        SwingUtilities.invokeAndWait( new Runnable() {
            @Override
            public void run() {
                JDialog dialog = new JDialog((Frame)null, "Test Dialog");
                button = new JButton("Button 1");
                dialog.add(button);
                dialog.pack();
                dialog.setVisible(true);
            }
        });

    }
}
