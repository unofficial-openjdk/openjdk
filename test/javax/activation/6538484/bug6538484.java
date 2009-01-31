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
 * @bug 6538484
 * @summary Check for proper handling of String.toLowerCase in TURKISH locale
 * @author Peter Williams
 * @run main bug6538484
 */

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringBufferInputStream;
import java.util.Locale;
import javax.activation.CommandInfo;
import javax.activation.DataContentHandler;
import javax.activation.DataSource;
import javax.activation.MailcapCommandMap;
import javax.activation.MimeType;
import javax.activation.MimeTypeParameterList;
import javax.activation.MimeTypeParseException;

import static java.util.Locale.ENGLISH;

public class bug6538484 {

    private static final Locale TURKISH = new Locale("tr");

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) throws MimeTypeParseException {
        testMailcapCommandMap(ENGLISH);
        testMailcapCommandMap(TURKISH);
        testMimeType(ENGLISH);
        testMimeType(TURKISH);
        testMimeTypeParameterList(ENGLISH);
        testMimeTypeParameterList(TURKISH);
        System.out.println("Test completed.");
    }

    private static void testMailcapCommandMap(Locale locale) {
        String cmdMapString = "# Java Web Start\n"
                + "application/x-java-jnlp-file; /jre/bin/javaws %s\n"
                + "# Image command\n"
                + "image/gif; /usr/sfw/bin/gimp %s;\\\n"
                + "    x-java-view=com.foo.FancyFooViewer;\\\n"
                + "    x-java-content-handler=" + 
                        DummyContentHandler.class.getName() + "\n"
                + "# Text command\n"
                + "text/plain; /usr/bin/less %s;\\\n"
                + "    x-java-fallback-entry=true;\\\n"
                + "    x-java-view=com.sun.TextViewer\n"
                ;
        
        String preferredMt1 = "image/gif";
        String preferredMt2 = "IMAGE/GIF";
        String fallbackMt1 = "text/plain";
        String fallbackMt2 = "TEXT/PLAIN";
        String nativeMt1 = "application/x-java-jnlp-file";
        String nativeMt2 = "APPLICATION/X-JAVA-JNLP-FILE";

        MailcapCommandMap cmdMap = new MailcapCommandMap(new StringBufferInputStream(cmdMapString));
        
        testMailcapCommandMapGetPreferredCommands(locale, cmdMap, preferredMt1, 2);
        testMailcapCommandMapGetPreferredCommands(locale, cmdMap, preferredMt2, 2);

        testMailcapCommandMapGetAllCommands(locale, cmdMap, fallbackMt1, 3);
        testMailcapCommandMapGetAllCommands(locale, cmdMap, fallbackMt2, 3);

        testMailcapCommandMapGetCommand(locale, cmdMap, preferredMt1, "view");
        testMailcapCommandMapGetCommand(locale, cmdMap, preferredMt2, "view");

        testMailcapCommandMapCreateDataContentHandler(locale, cmdMap, preferredMt1);
        testMailcapCommandMapCreateDataContentHandler(locale, cmdMap, preferredMt2);
                
        testMailcapCommandMapGetNativeCommands(locale, cmdMap, nativeMt1, 1);
        testMailcapCommandMapGetNativeCommands(locale, cmdMap, nativeMt2, 1);
    }
    
    private static void testMailcapCommandMapGetPreferredCommands(Locale locale,
            MailcapCommandMap cmdMap, String mimetype, int expectedCount) {
        Locale.setDefault(locale);
        
        CommandInfo [] result = cmdMap.getPreferredCommands(mimetype);
        
        if(result == null || result.length != expectedCount) {
            throw new RuntimeException("MailcapCommandMap.getPreferredCommands() failed for " + mimetype);
        }
    }
    
    private static void testMailcapCommandMapGetAllCommands(Locale locale,
            MailcapCommandMap cmdMap, String mimetype, int expectedCount) {
        Locale.setDefault(locale);
        
        CommandInfo [] result = cmdMap.getAllCommands(mimetype);
        
        if(result == null || result.length != expectedCount) {
            throw new RuntimeException("MailcapCommandMap.getAllCommands() failed for " + mimetype);
        }
    }
    
    private static void testMailcapCommandMapGetCommand(Locale locale,
            MailcapCommandMap cmdMap, String mimetype, String command) {
        Locale.setDefault(locale);
        
        CommandInfo result = cmdMap.getCommand(mimetype, command);

        if(result == null) {
            throw new RuntimeException("MailcapCommandMap.getCommand() failed for " + mimetype);
        }
    }
    
    private static void testMailcapCommandMapCreateDataContentHandler(Locale locale,
            MailcapCommandMap cmdMap, String mimetype) {
        Locale.setDefault(locale);
        
        DataContentHandler handler = cmdMap.createDataContentHandler(mimetype);

        if(handler == null) {
            throw new RuntimeException("MailcapCommandMap.createDataContentHandler() failed for " + mimetype);
        }
    }
    
    private static void testMailcapCommandMapGetNativeCommands(Locale locale,
            MailcapCommandMap cmdMap, String mimetype, int expectedCount) {
        Locale.setDefault(locale);
        
        String [] result = cmdMap.getNativeCommands(mimetype);

        if(result == null || result.length != expectedCount) {
            throw new RuntimeException("MailcapCommandMap.getNativeCommands() failed for " + mimetype);
        }
    }
    
    public static class DummyContentHandler implements DataContentHandler {

        public DataFlavor[] getTransferDataFlavors() {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        public Object getTransferData(DataFlavor arg0, DataSource arg1) throws UnsupportedFlavorException, IOException {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        public Object getContent(DataSource arg0) throws IOException {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        public void writeTo(Object arg0, String arg1, OutputStream arg2) throws IOException {
            throw new UnsupportedOperationException("Not supported yet.");
        }
        
    }
    
    private static void testMimeType(Locale locale) throws MimeTypeParseException {
        String t1 = "image";
        String t2 = "IMAGE";
        String s1 = "plain";
        String s2 = "PLAIN";
        
        String expectedPrimaryType = t1;
        String expectedSubType = s1;
        String expectedBaseType = expectedPrimaryType + "/" + expectedSubType;

        // MimeType(expr)
        testMimeTypeCtor(locale, t1 + "/" + s1, expectedBaseType);
        testMimeTypeCtor(locale, t1 + "/" + s2, expectedBaseType);
        testMimeTypeCtor(locale, t2 + "/" + s1, expectedBaseType);
        testMimeTypeCtor(locale, t2 + "/" + s2, expectedBaseType);
        
        // MimeType(type, subtype)
        testMimeTypeCtor(locale, t1, s1, expectedBaseType);
        testMimeTypeCtor(locale, t1, s2, expectedBaseType);
        testMimeTypeCtor(locale, t2, s1, expectedBaseType);
        testMimeTypeCtor(locale, t2, s2, expectedBaseType);
        
        // MimeType.setPrimaryType()
        MimeType mt = new MimeType(expectedBaseType);
        testMimeTypeSetPrimaryType(locale, mt, t1, expectedPrimaryType);
        testMimeTypeSetPrimaryType(locale, mt, t2, expectedPrimaryType);
        
        // MimeType.setSubType()
        mt = new MimeType(expectedBaseType);
        testMimeTypeSetSubType(locale, mt, s1, expectedSubType);
        testMimeTypeSetSubType(locale, mt, s2, expectedSubType);
    }

    private static void testMimeTypeCtor(Locale locale, String parse, String expectedBaseType) throws MimeTypeParseException {
        Locale.setDefault(locale);
        MimeType mt = new MimeType(parse);
        
        if(!mt.getBaseType().equals(expectedBaseType)) {
            throw new RuntimeException("Mimetype case conversion failed for " + mt.getBaseType());
        }
    }

    private static void testMimeTypeCtor(Locale locale, String type, String subtype, String expectedBaseType) throws MimeTypeParseException {
        Locale.setDefault(locale);
        MimeType mt = new MimeType(type, subtype);
        
        if(!mt.getBaseType().equals(expectedBaseType)) {
            throw new RuntimeException("Mimetype case conversion failed for " + mt.getBaseType());
        }
    }

    private static void testMimeTypeSetPrimaryType(Locale locale, MimeType mt, String type, String expectedType) throws MimeTypeParseException {
        Locale.setDefault(locale);
        
        mt.setPrimaryType(type);
        
        if(!mt.getPrimaryType().equals(expectedType)) {
            throw new RuntimeException("MimeType.setPrimaryType() failed for " + mt.getPrimaryType());
        }
    }

    private static void testMimeTypeSetSubType(Locale locale, MimeType mt, String subtype, String expectedSubType) throws MimeTypeParseException {
        Locale.setDefault(locale);
        
        mt.setSubType(subtype);
        
        if(!mt.getSubType().equals(expectedSubType)) {
            throw new RuntimeException("MimeType.getSubType() failed for " + mt.getSubType());
        }
    }

    private static void testMimeTypeParameterList(Locale locale) throws MimeTypeParseException {
        String expectedValue = "foo";
        String n1 = "field";
        String n2 = "FIELD";
        String p1 = ";" + n1 + "=" + expectedValue;
        String p2 = ";" + n2 + "=" + expectedValue;

        String expectedName = n1;
        String expectedParams = ";" + expectedName + "=" + expectedValue;
        
        // MimeTypeParameterList(expr);
        testMimeTypeParameterListCtor(locale, p1, expectedName);
        testMimeTypeParameterListCtor(locale, p2, expectedName);
        
        // MimeTypeParameterList.get(name)
        testMimeTypeParameterListGet(locale, n1, expectedName, expectedParams);
        testMimeTypeParameterListGet(locale, n2, expectedName, expectedParams);
        
        // MimeTypeParameterList.set(name)
        testMimeTypeParameterListSet(locale, n1, expectedName, expectedParams);
        testMimeTypeParameterListSet(locale, n2, expectedName, expectedParams);
        
        // MimeTypeParameterList.remove(name)
        testMimeTypeParameterListRemove(locale, n1, expectedName, expectedParams);
        testMimeTypeParameterListRemove(locale, n2, expectedName, expectedParams);
    }
    
    private static void testMimeTypeParameterListCtor(Locale locale, String params, String expectedName) throws MimeTypeParseException {
        Locale.setDefault(locale);
        
        MimeTypeParameterList mtpl = new MimeTypeParameterList(params);
        
        if(mtpl.get(expectedName) == null) {
            throw new RuntimeException("MimeTypeParameterList case conversion failed for " + mtpl.toString());
        }
    }

    private static void testMimeTypeParameterListGet(Locale locale, String name, String properName, String properParams) throws MimeTypeParseException {
        Locale.setDefault(locale);

        MimeTypeParameterList mtpl = new MimeTypeParameterList(properParams);
        String v1 = mtpl.get(name);
        String v2 = mtpl.get(properName);

        if(v1 == null) {
            if(v2 != null) {
                throw new RuntimeException("MimeTypeParameterList.get() failed for " + name);
            }
        } else if(!v1.equals(v2)) {
            throw new RuntimeException("MimeTypeParameterList.get() failed for " + mtpl.toString());
        }
    }
    
    private static void testMimeTypeParameterListSet(Locale locale, String name, String properName, String properParams) throws MimeTypeParseException {
        Locale.setDefault(locale);

        MimeTypeParameterList mtpl = new MimeTypeParameterList(properParams);
        String expectedValue = "bar";
        mtpl.set(name, expectedValue);
        String newValue = mtpl.get(properName);
        
        if(!expectedValue.equals(newValue)) {
            throw new RuntimeException("MimeTypeParameterList.set() failed to change " + name + " in " + mtpl.toString());
        }
    }
    
    private static void testMimeTypeParameterListRemove(Locale locale, String name, String properName, String properParams) throws MimeTypeParseException {
        Locale.setDefault(locale);

        MimeTypeParameterList mtpl = new MimeTypeParameterList(properParams);
        String oldvalue = mtpl.get(properName);
        mtpl.remove(name);
        String newvalue = mtpl.get(properName);

        if(oldvalue == null) {
            throw new NullPointerException("Broken test for MimeTypeParameterList.remove()" + mtpl.toString());
        } else if(oldvalue.equals(newvalue)) {
            throw new RuntimeException("MimeTypeParameterList.remove() failed for " + name + " in " + mtpl);
        }
    }
    
}
