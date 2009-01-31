/*
 * Copyright 2008 Sun Microsystems, Inc.  All Rights Reserved.
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
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */

/*
 * @test
 * @bug 6456395
 * @summary Special case byte[] and String in writeTo
 * @author Peter Williams
 * @run main bug6456395
 */

import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import javax.activation.CommandInfo;
import javax.activation.CommandMap;
import javax.activation.DataContentHandler;
import javax.activation.DataHandler;

public class bug6456395 {

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws ClassNotFoundException, UnsupportedFlavorException, IOException {
        testDataHandlerWriteTo();
        System.out.println("Test completed.");
    }

    private static void testDataHandlerWriteTo() throws ClassNotFoundException, UnsupportedFlavorException, IOException {
        // test String data
        String textMimeType = "text/plain";
        String stringData = "The quick brown fox jumped over the lazy dogs.\n";
        DataHandler dh = new DataHandler(stringData, textMimeType);
        dh.setCommandMap(createDummyCommandMap());
        dh.writeTo(System.out);
        
        // test byte [] data
        String byteMimeType = "text/ascii";
        byte [] byteData = stringData.getBytes();
        dh = new DataHandler(byteData, byteMimeType);
        dh.setCommandMap(createDummyCommandMap());
        dh.writeTo(System.out);
    }
    
    // Use empty command map to avoid reading .mailcap if it exists on test system.
    private static CommandMap createDummyCommandMap() {
        return new CommandMap() {
            public CommandInfo[] getPreferredCommands(String mimeType) {
                return new CommandInfo [0];
            }
            public CommandInfo[] getAllCommands(String mimeType) {
                return new CommandInfo [0];
            }
            public CommandInfo getCommand(String mimeType, String cmdName) {
                return null;
            }
            public DataContentHandler createDataContentHandler(String mimeType) {
                return null;
            }
        };
    }
    
}
