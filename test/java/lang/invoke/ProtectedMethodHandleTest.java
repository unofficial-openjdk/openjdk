/*
 * Copyright 2015 Google, Inc.  All Rights Reserved.
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
 * @summary check that a MethodHandle can not be used to call a protected interface method
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.security.AllPermission;
import java.security.Permissions;
import java.security.ProtectionDomain;
import java.util.Arrays;
import java.util.HashMap;

/**
 * Tests that a MethodHandle for an interface method (which is necessarily public) cannot be used
 * to access a protected method with the same signature. The specific exploit works like this.
 * We make a subclass of ClassLoader that implements an interface with a public method matching
 * the protected {@link ClassLoader#defineClass(String, byte[], int, int, ProtectionDomain)} method.
 * We make a MethodHandle for the interface method, and invoke {@code ClassLoader.defineClass}
 * through it. That should not work, of course, but when this test was written it did.
 *
 * <p>A fair amount of trickery is needed for this test to work. Our subclass of
 * ClassLoader cannot be written to implement an interface with the {@code defineClass} method.
 * The compiler will reject that, because the method is public and abstract in the interface, but
 * its implementation, inherited from {@code ClassLoader}, is protected and final. So we write
 * the interface with a method called {@code defineClazz}, and we use a second ClassLoader which
 * rewrites the method name to be {@code defineClass}.
 *
 * @author emcmanus@google.com (Eamonn McManus)
 */
public class ProtectedMethodHandleTest {
    /**
     * The interface that redefines the {@code defineClass} method from ClassLoader. As explained
     * above, this will be rewritten during class loading so that the spelling is indeed
     * defineClass and not defineClazz.
     */
    public interface DefineClass {
        Class<?> defineClazz(
            String name, byte[] b, int off, int len, ProtectionDomain protectionDomain);
    }

    /**
     * If the exploit works, this class will be loaded with a ProtectionDomain
     * that has AllPermission.
     */
    public static class VanillaClass {
    }

    /**
     * Loads the class-file bytes of the given class using the standard getResourceAsStream trick.
     */
    static byte[] loadClassBytes(Class<?> c) {
        String resourceName = c.getName().replace('.', '/') + ".class";
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                InputStream inputStream = ProtectedMethodHandleTest.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
                if (inputStream == null) {
                    throw new RuntimeException("No such resource: " + resourceName);
                }
                int b;
                while ((b = inputStream.read()) >= 0) {
                    baos.write(b);
                }
                baos.flush();
                return baos.toByteArray();
            } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The bytes of every class that we are interested in loading explicitly. This is public because
     * it will be accessed from classes loaded by a different loader.
     */
    public static final HashMap<String, byte[]> CLASS_BYTES = new HashMap<>();

    /**
     * The loader that launches the exploit code. We use {@link TweakLoader} below to make a copy
     * of this class where the DefineClass interface contains a method called {@code defineClass}
     * with the same signature as a method from {@code ClassLoader}.
     */
    public static class Loader extends ClassLoader implements Runnable, DefineClass {
        @Override
        public void run() {
            try {
                doHack();
                throw new RuntimeException("Should have got an exception, but did not.");
            } catch (IllegalAccessError expected) {
                // Catch and silently swallow the desired IllegalAccessError.
            } catch (NullPointerException npe) {
                // An otherwise unpatched JDK7 may instead throw an NPE at the
                // invoke in doHack below. Though the behavior is confusing, and not
                // the preferred exception, it is indicative that the hole is closed.
            } catch (Throwable t) {
                // Don't catch anything else
                throw new RuntimeException(t);
            }
        }

        private void doHack() throws Throwable {
            MethodType methodType = MethodType.methodType(
                Class.class, String.class, byte[].class, int.class, int.class, ProtectionDomain.class);
            MethodHandle defineClass = MethodHandles.lookup()
                    .findVirtual(DefineClass.class, "defineClass", methodType)
                    .bindTo(this);
            Permissions allPermissions = new Permissions();
            allPermissions.add(new AllPermission());
            ProtectionDomain protectionDomain = new ProtectionDomain(null, allPermissions);
            byte[] vanillaClassBytes = CLASS_BYTES.get(VanillaClass.class.getName());
            Class<?> vanillaClassClass = (Class<?>) defineClass.invoke(
                VanillaClass.class.getName(),
                vanillaClassBytes,
                0,
                vanillaClassBytes.length,
                protectionDomain);
            // If we have reached this point then the exploit has succeeded: we have called defineClass
            // with a ProtectionDomain, and got back a Class that has AllPermission.
        }

        // This method is only defined so that this class will compile. It is never called.
        @Override
        public Class<?> defineClazz(String name, byte[] b, int off, int len,
                ProtectionDomain protectionDomain) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * A ClassLoader that loads the classes in {@link #CLASS_BYTES} using the bytes there, and
     * delegates all other class loading to its parent. Additionally, it rewrites the method
     * {@link DefineClass#defineClazz} so it is called {@code defineClass}.
     */
    public static class TweakLoader extends ClassLoader {
        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            byte[] bytes = CLASS_BYTES.get(name);
            if (bytes == null) {
                return Class.forName(name, resolve, getParent());
            }
            if (name.equals(DefineClass.class.getName())) {
                byte[] defineClazz = "defineClazz".getBytes(StandardCharsets.UTF_8);
                int defineClazzLength = defineClazz.length;
                for (int i = 0; i < bytes.length - defineClazzLength; i++) {
                    if (Arrays.equals(defineClazz, Arrays.copyOfRange(bytes, i, i + defineClazzLength))) {
                        bytes[i + defineClazzLength - 1] = 's';
                        bytes[i + defineClazzLength - 2] = 's';
                    }
                }
            }
            Class<?> defineClassClass = defineClass(name, bytes, 0, bytes.length);
            return defineClassClass;
        }
    }

    public static void main(String[] args) throws Exception {
        CLASS_BYTES.put(VanillaClass.class.getName(), loadClassBytes(VanillaClass.class));
        CLASS_BYTES.put(DefineClass.class.getName(), loadClassBytes(DefineClass.class));
        CLASS_BYTES.put(Loader.class.getName(), loadClassBytes(Loader.class));

        // Get a version of Loader where the DefineClass interface has a method defineClass.
        Class<?> loaderClass = Class.forName(Loader.class.getName(), false, new TweakLoader());

        Runnable loader = (Runnable) loaderClass.newInstance();
        loader.run();
    }
}
