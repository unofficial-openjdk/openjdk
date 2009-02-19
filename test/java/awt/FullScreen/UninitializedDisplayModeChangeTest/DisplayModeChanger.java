/*
 * Copyright 2006-2008 Sun Microsystems, Inc.  All rights reserved.
 * SUN PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 *
 */

import java.awt.DisplayMode;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.InvocationTargetException;

/**
 * Used by the UninitializedDisplayModeChangeTest to change the
 * display mode.
 */
public class DisplayModeChanger {

    public static void main(String[] args)
        throws InterruptedException, InvocationTargetException
    {
        final GraphicsDevice gd =
            GraphicsEnvironment.getLocalGraphicsEnvironment().
                getDefaultScreenDevice();

        EventQueue.invokeAndWait(new Runnable() {
            public void run() {
                Frame f = null;
                if (gd.isFullScreenSupported()) {
                    try {
                        f = new Frame("DisplayChanger Frame");
                        gd.setFullScreenWindow(f);
                        if (gd.isDisplayChangeSupported()) {
                            DisplayMode dm = findDisplayMode(gd);
                            if (gd != null) {
                                gd.setDisplayMode(dm);
                            }
                        }
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ex) {
                            ex.printStackTrace();
                        }
                        gd.setFullScreenWindow(null);
                    } finally {
                        if (f != null) {
                            f.dispose();
                        }
                    }
                }
            }
        });
    }

    /**
     * Finds a display mode that is different from the current display
     * mode and is likely to cause a display change event.
     */
    private static DisplayMode findDisplayMode(GraphicsDevice gd) {
        DisplayMode dms[] = gd.getDisplayModes();
        DisplayMode currentDM = gd.getDisplayMode();
        for (DisplayMode dm : dms) {
            if (!dm.equals(currentDM) &&
                 dm.getRefreshRate() == currentDM.getRefreshRate())
            {
                // different from the current dm and refresh rate is the same
                // means that something else is different => more likely to
                // cause a DM change event
                return dm;
            }
        }
        return null;
    }

}
