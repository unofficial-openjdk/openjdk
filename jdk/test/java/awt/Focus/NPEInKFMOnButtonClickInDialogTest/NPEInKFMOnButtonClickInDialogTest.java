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
   @bug 8061954
   @summary Button does not get the focus on a mouse click, and NPE is thrown in KFM
   @author Anton Litvinov
*/

import java.awt.Container;
import java.awt.FlowLayout;
import java.awt.Frame;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;

import sun.awt.OSInfo;
import sun.awt.SunToolkit;

public class NPEInKFMOnButtonClickInDialogTest {
    private static Frame frame = null;
    private static JDialog dialog = null;
    private static JButton cancelBtn = null;
    private static Point clickPoint = null;
    private static volatile Boolean cancelBtnIsFocused = null;

    public static void main(String[] args) {
        OSInfo.OSType osType = OSInfo.getOSType();
        if ((osType != OSInfo.OSType.LINUX) && (osType != OSInfo.OSType.SOLARIS)) {
            System.out.println("This test is only for Linux OS and Solaris OS.");
            return;
        }

        ThreadGroup mainThreadGroup = Thread.currentThread().getThreadGroup();
        Thread t = new Thread(new ThreadGroup(mainThreadGroup, "TestThreadGroup"), new Runnable() {
            public void run() {
                try {
                    SunToolkit.createNewAppContext();
                    doTest();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        t.start();

        try {
            t.join();
        } catch (InterruptedException ie) {
            ie.printStackTrace();
        }

        if (cancelBtnIsFocused == null) {
            throw new RuntimeException("Test failed for an unknown reason, look at error log.");
        } else if (cancelBtnIsFocused.booleanValue() == false) {
            throw new RuntimeException("'Cancel' button did not become a focus owner.");
        }
    }

    private static void doTest() throws Exception {
        final SunToolkit toolkit = (SunToolkit)Toolkit.getDefaultToolkit();
        final Robot robot = new Robot();
        robot.setAutoDelay(50);

        try {
            frame = new Frame("Frame of NPEInKFMOnButtonClickInDialogTest");
            frame.setSize(100, 100);
            frame.setVisible(true);
            toolkit.realSync();

            SwingUtilities.invokeAndWait(new Runnable() {
                public void run() {
                    dialog = new JDialog(frame,
                        "Dialog of NPEInKFMOnButtonClickInDialogTest", false);
                    Container content = dialog.getContentPane();
                    content.setLayout(new FlowLayout());
                    content.add(new JButton("Run"));
                    content.add(cancelBtn = new JButton("Cancel"));
                    dialog.pack();
                    dialog.setVisible(true);
                }
            });
            toolkit.realSync();

            SwingUtilities.invokeAndWait(new Runnable() {
                public void run() {
                    Point p = cancelBtn.getLocationOnScreen();
                    clickPoint = new Point(p.x + cancelBtn.getWidth() / 2,
                        p.y + cancelBtn.getHeight() / 2);
                }
            });
            robot.mouseMove(clickPoint.x, clickPoint.y);
            robot.mousePress(InputEvent.BUTTON1_MASK);
            robot.mouseRelease(InputEvent.BUTTON1_MASK);
            toolkit.realSync();

            SwingUtilities.invokeAndWait(new Runnable() {
                public void run() {
                    cancelBtnIsFocused = cancelBtn.isFocusOwner();
                }
            });
        } finally {
            if (dialog != null) {
                dialog.dispose();
            }
            if (frame != null) {
                frame.dispose();
            }
        }
    }
}
