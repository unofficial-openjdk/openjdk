/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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
 * @bug 8042235
 * @summary redefining method used by multiple MethodHandles crashes VM
 * @compile -XDignore.symbol.file RedefineMethodUsedByMultipleMethodHandlesNoASM.java
 * @run main/othervm -XX:+UnlockDiagnosticVMOptions -XX:+EnableInvokeDynamic RedefineMethodUsedByMultipleMethodHandlesNoASM
 */

import java.io.*;
import java.lang.instrument.*;
import java.lang.invoke.*;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.management.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.*;
import java.util.jar.*;

import javax.tools.*;

public class RedefineMethodUsedByMultipleMethodHandlesNoASM {

    static class Foo {
        public static Object getName() {
            int fooInt = 1;
            if (true) {
                // We "just know" that this creates bytecodes:
                // bipush 0x7     0x10 0x7
                // ishl           0x78
                fooInt <<= 0x7;
            }
            return "foo" + fooInt;
        }
    }

    public static void main(String[] args) throws Throwable {

        Lookup lookup = MethodHandles.lookup();
        Method fooMethod = Foo.class.getDeclaredMethod("getName");

        // fooMH2 displaces fooMH1 from the MemberNamesTable
        MethodHandle fooMH1 = lookup.unreflect(fooMethod);
        MethodHandle fooMH2 = lookup.unreflect(fooMethod);

        System.out.println("Foo.getName() = " + Foo.getName());
        System.out.println("fooMH1.invoke = " + fooMH1.invokeExact());
        System.out.println("fooMH2.invoke = " + fooMH2.invokeExact());

        // Redefining Foo.getName() causes vmtarget to be updated
        // in fooMH2 but not fooMH1
        redefineFoo();

        // Full GC causes fooMH1.vmtarget to be deallocated
        System.gc();

        // Calling fooMH1.vmtarget crashes the VM on JDK8, on JDK7 we see
        // the wrong method invoked, we execute the old code.
        Object newResult = fooMH1.invokeExact();
        System.out.println("fooMH1.invoke = " + fooMH1.invokeExact());
        if (!((String) newResult).equals("foo32")) {
            throw new RuntimeException("failed, fooMH1 invoke gets '" + newResult + "'");
        }
    }

    /**
     * Adds the class file bytes for {@code c} to {@code jar}.
     */
    static void add(JarOutputStream jar, Class<?> c) throws IOException {
        String classAsPath = c.getName().replace('.', '/') + ".class";
        jar.putNextEntry(new JarEntry(classAsPath));
        InputStream stream = c.getClassLoader().getResourceAsStream(classAsPath);

        int b;
        while ((b = stream.read()) != -1) {
            jar.write(b);
        }
    }

    static void redefineFoo() throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        Attributes mainAttrs = manifest.getMainAttributes();
        mainAttrs.putValue("Agent-Class", FooAgent.class.getName());
        mainAttrs.putValue("Can-Redefine-Classes", "true");
        mainAttrs.putValue("Can-Retransform-Classes", "true");

        Path jar = Files.createTempFile("myagent", ".jar");
        try {
            JarOutputStream jarStream = new JarOutputStream(new FileOutputStream(jar.toFile()), manifest);
            add(jarStream, FooAgent.class);
            add(jarStream, FooTransformer.class);
            jarStream.close();
            runAgent(jar);
        } finally {
            Files.deleteIfExists(jar);
        }
    }

    public static void runAgent(Path agent) throws Exception {
        String vmName = ManagementFactory.getRuntimeMXBean().getName();
        int p = vmName.indexOf('@');
        assert p != -1 : "VM name not in <pid>@<host> format: " + vmName;
        String pid = vmName.substring(0, p);
        ClassLoader cl = ToolProvider.getSystemToolClassLoader();
        Class<?> c = Class.forName("com.sun.tools.attach.VirtualMachine", true, cl);
        Method attach = c.getDeclaredMethod("attach", String.class);
        Method loadAgent = c.getDeclaredMethod("loadAgent", String.class);
        Method detach = c.getDeclaredMethod("detach");
        Object vm = attach.invoke(null, pid);
        loadAgent.invoke(vm, agent.toString());
        detach.invoke(vm);
    }

    public static class FooAgent {

        public static void agentmain(@SuppressWarnings("unused") String args, Instrumentation inst) throws Exception {
            assert inst.isRedefineClassesSupported();
            assert inst.isRetransformClassesSupported();
            inst.addTransformer(new FooTransformer(), true);
            Class<?>[] classes = inst.getAllLoadedClasses();
            for (int i = 0; i < classes.length; i++) {
                Class<?> c = classes[i];
                if (c == Foo.class) {
                    inst.retransformClasses(new Class[]{c});
                }
            }
        }
    }


    /**
      * This method will only be called on the class Foo, above, and that class
      * only has the method getName().
      * Avoid using the objectweb ASM library as we do in jdk8, by
      * looking for a specific bytecode pattern (which this method does not really
      * understand).
      */
    static class FooTransformer implements ClassFileTransformer {

        @Override
        public byte[] transform(ClassLoader cl, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain,
                                byte[] classfileBuffer) throws IllegalClassFormatException {


            if (Foo.class.equals(classBeingRedefined)) {

                try {
                    System.out.println("redefining " + classBeingRedefined);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    InputStream is = new ByteArrayInputStream(classfileBuffer);
                    copyWithSubstitution(is, new byte[] {(byte)0x10,(byte)0x07,(byte)0x78},
                                         new byte[] {(byte)0x10,(byte)0x05,(byte)0x78},
                                         baos);
                    return baos.toByteArray();
                } catch(Exception e) {
                     e.printStackTrace();
                }
            }
            return classfileBuffer;
        }

        /**
          * Copy bytes from a Reader to an OutputStream.  If a sequence of bytes
          * matches the given oldBytes byte array, write the newBytes array instead.
          */
        public void copyWithSubstitution(InputStream is, byte[] oldBytes, byte [] newBytes, OutputStream out) throws Exception {

            byte[] buffer = new byte[oldBytes.length];

            while (is.available() > 0) {
                int i = 0xff & is.read();
                if (i != oldBytes[0]) {
                    out.write(i);
                    continue;
                }
                int pos = 0;
                while (pos < oldBytes.length && oldBytes[pos] == (byte) i) {
                    buffer[pos] = (byte) i;
                    pos++;
                    i = is.read();
                }
                // We have read as much as matches oldBytes, plus one byte (now in i).
                // Write out:
                // buffer it if did not match fully
                // new bytes if it was a full match
                if (pos > 0) {
                if (pos == oldBytes.length) {
                    System.out.println("copyWithSubstitution: replacing: ");
                    printBytesOn(System.out, buffer);
                    System.out.println("copyWithSubstitution: with:");
                    printBytesOn(System.out, newBytes);
                    out.write(newBytes, 0, newBytes.length);
                } else {
                    out.write(buffer, 0, pos);
                }
                }
                // Does not handle two sequential occurrences of oldBytes.
                out.write(i);
            }
            out.close();
        }


    public static void printBytesOn(PrintStream out, byte[] bytes) {
        int numColumns =  16;
        int column = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (column == 0) {
                out.print(i);
                out.print("\t");
            }
            out.print("0x" + Integer.toHexString(255 & bytes[i])
                    /* + " (" + (char) bytes[i] + */ + "\t");
            column++;
            if (column == numColumns) {
                out.println();
                column = 0;
            }
        }
        out.println();
    }
    }
}
