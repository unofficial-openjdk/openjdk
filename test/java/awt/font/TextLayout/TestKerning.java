/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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
 * @summary Test Kerning is working.
 * @bug 8009530
 */

import java.applet.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.font.*;
import java.util.Map;
import java.util.HashMap;
import java.util.Locale;

public class TestKerning extends Applet {
    private Panel panel;

    static public void main(String[] args) {
System.out.println(System.getProperty("os.name"));

        Applet test = new TestKerning();
        test.init();
        test.start();

        Frame f = new Frame("Test Kerning");
        f.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                System.exit(0);
            }
        });
        f.add("Center", test);
        f.pack();
        f.setVisible(true);
    }

    public Dimension getPreferredSize() {
        return new Dimension(500, 200);
    }

    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    private static final String testString = "To WAVA 1,45 office glyph.";

    public void paint(Graphics g) {
        Graphics2D g2d = (Graphics2D)g;
        Font f = new Font("Arial", Font.PLAIN, 36);
        // testing Arial on Solaris.
        if (!("SunOS".equals(System.getProperty("os.name")))) {
           return;
        }
        if (!("Arial".equals(f.getFamily(Locale.ENGLISH)))) {
            return;
        }
        Map m = new HashMap();
        m.put(TextAttribute.KERNING, TextAttribute.KERNING_ON);
        Font kf = f.deriveFont(m);
        g.setFont(f);
        FontMetrics fm1 = g.getFontMetrics();
        int sw1 = fm1.stringWidth(testString);
        g.drawString(testString, 10, 50);
        g.setFont(kf);
        FontMetrics fm2 = g.getFontMetrics();
        int sw2 = fm2.stringWidth(testString);
        g.drawString(testString, 10, 90);
        if (sw1 == sw2) {
            System.out.println(sw1+" " + sw2);
            throw new RuntimeException("No kerning");
        }
    }
}
