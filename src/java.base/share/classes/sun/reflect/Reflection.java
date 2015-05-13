/*
 * Copyright (c) 2001, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package sun.reflect;

import sun.misc.VM;

import java.lang.reflect.*;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.Map;

/** Common utility routines used by both java.lang and
    java.lang.reflect */

public class Reflection {

    /** Used to filter out fields and methods from certain classes from public
        view, where they are sensitive or they may contain VM-internal objects.
        These Maps are updated very rarely. Rather than synchronize on
        each access, we use copy-on-write */
    private static volatile Map<Class<?>,String[]> fieldFilterMap;
    private static volatile Map<Class<?>,String[]> methodFilterMap;

    static {
        Map<Class<?>,String[]> map = new HashMap<Class<?>,String[]>();
        map.put(Reflection.class,
            new String[] {"fieldFilterMap", "methodFilterMap"});
        map.put(System.class, new String[] {"security"});
        map.put(Class.class, new String[] {"classLoader"});
        fieldFilterMap = map;

        methodFilterMap = new HashMap<>();
    }

    /** Returns the class of the caller of the method calling this method,
        ignoring frames associated with java.lang.reflect.Method.invoke()
        and its implementation. */
    @CallerSensitive
    public static native Class<?> getCallerClass();

    /**
     * @deprecated This method will be removed in JDK 9.
     * This method is a private JDK API and retained temporarily for
     * existing code to run until a replacement API is defined.
     */
    @Deprecated
    public static native Class<?> getCallerClass(int depth);

    /** Retrieves the access flags written to the class file. For
        inner classes these flags may differ from those returned by
        Class.getModifiers(), which searches the InnerClasses
        attribute to find the source-level access flags. This is used
        instead of Class.getModifiers() for run-time access checks due
        to compatibility reasons; see 4471811. Only the values of the
        low 13 bits (i.e., a mask of 0x1FFF) are guaranteed to be
        valid. */
    public static native int getClassAccessFlags(Class<?> c);


    public static void ensureMemberAccess(Class<?> currentClass,
                                          Class<?> memberClass,
                                          Object target,
                                          int modifiers)
        throws IllegalAccessException
    {
        if (currentClass == null || memberClass == null) {
            throw new InternalError();
        }

        if (!verifyMemberAccess(currentClass, memberClass, target, modifiers)) {
            String currentSuffix = "";
            String memberSuffix = "";
            Module m1 = currentClass.getModule();
            if (m1.isNamed())
                currentSuffix = " (" + m1 + ")";
            Module m2 = memberClass.getModule();
            if (m2.isNamed())
                memberSuffix = " (" + m2 + ")";

            String msg = "Class " + currentClass.getName() +
                    currentSuffix +
                    " can not access a member of class " +
                    memberClass.getName() + memberSuffix +
                    " with modifiers \"" +
                    Modifier.toString(modifiers) + "\"";

            // Expand the message to help troubleshooting
            if (!m1.canRead(m2)) {
                msg += ", " + m1;
                if (!m1.canRead(null))
                    msg += " (strict module) ";
                msg += " does not read " + m2;
            }
            String pkg = packageName(memberClass);
            if (!m2.isExported(pkg, m1)) {
                msg += ", " + m2 + " does not export " + pkg;
                if (m2.isNamed())
                    msg += " to " + m1;
            }

            throwIAE(msg);
        }
    }

    public static boolean verifyMemberAccess(Class<?> currentClass,
                                             // Declaring class of field
                                             // or method
                                             Class<?> memberClass,
                                             // May be NULL in case of statics
                                             Object   target,
                                             int      modifiers)
    {
        // Verify that currentClass can access a field, method, or
        // constructor of memberClass, where that member's access bits are
        // "modifiers".

        boolean gotIsSameClassPackage = false;
        boolean isSameClassPackage = false;

        if (currentClass == memberClass) {
            // Always succeeds
            return true;
        }

        if (!verifyModuleAccess(currentClass, memberClass)) {
            return false;
        }

        if (!Modifier.isPublic(getClassAccessFlags(memberClass))) {
            isSameClassPackage = isSameClassPackage(currentClass, memberClass);
            gotIsSameClassPackage = true;
            if (!isSameClassPackage) {
                return false;
            }
        }

        // At this point we know that currentClass can access memberClass.

        if (Modifier.isPublic(modifiers)) {
            return true;
        }

        boolean successSoFar = false;

        if (Modifier.isProtected(modifiers)) {
            // See if currentClass is a subclass of memberClass
            if (isSubclassOf(currentClass, memberClass)) {
                successSoFar = true;
            }
        }

        if (!successSoFar && !Modifier.isPrivate(modifiers)) {
            if (!gotIsSameClassPackage) {
                isSameClassPackage = isSameClassPackage(currentClass,
                                                        memberClass);
                gotIsSameClassPackage = true;
            }

            if (isSameClassPackage) {
                successSoFar = true;
            }
        }

        if (!successSoFar) {
            return false;
        }

        if (Modifier.isProtected(modifiers)) {
            // Additional test for protected members: JLS 6.6.2
            Class<?> targetClass = (target == null ? memberClass : target.getClass());
            if (targetClass != currentClass) {
                if (!gotIsSameClassPackage) {
                    isSameClassPackage = isSameClassPackage(currentClass, memberClass);
                    gotIsSameClassPackage = true;
                }
                if (!isSameClassPackage) {
                    if (!isSubclassOf(targetClass, currentClass)) {
                        return false;
                    }
                }
            }
        }

        return true;
    }

    /**
     * Returns {@code true} if memberClass's module is readable by currentClass's
     * module and memberClass's's module exports memberClass's package to
     * currentClass's module.
     */
    public static boolean verifyModuleAccess(Class<?> currentClass,
                                             Class<?> memberClass) {
        return verifyModuleAccess(currentClass.getModule(), memberClass);
    }

    public static boolean verifyModuleAccess(Module currentModule, Class<?> memberClass) {
        Module memberModule = memberClass.getModule();

        // module may be null during startup (initLevel 0)
        if (currentModule == memberModule)
           return true;  // same module (named or unnamed)

        // check readability
        if (!currentModule.canRead(memberModule))
            return false;

        // check that memberModule exports the package to currentModule
        return memberModule.isExported(packageName(memberClass), currentModule);
    }

    private static String packageName(Class<?> c) {
        if (c.isArray()) {
            return packageName(c.getComponentType());
        } else {
            String name = c.getName();
            int dot = name.lastIndexOf('.');
            if (dot == -1) return "";
            return name.substring(0, dot);
        }
    }

    /**
     * A holder class for a property value to avoid System.getProperty
     * early in the startup before java.lang.System is initialized.
     * Also defines the {@code canAccess} method to use the VM to test
     * module access.
     */
    private static class VMAccessCheck {
        static final boolean USE_VM_ACCESS_CHECK;
        static {
            PrivilegedAction<Boolean> pa =
                () -> Boolean.getBoolean("sun.reflect.useHotSpotAccessCheck");
            USE_VM_ACCESS_CHECK = AccessController.doPrivileged(pa);
        }

        /**
         * Returns true if m1 reads m2 and memberClass is in a package in m2
         * that is exported to m1.
         */
        static boolean canAccess(Module m1, Module m2, Class<?> memberClass) {
            if (m1 != null) {
                // named module trying to access member in unnamed module
                if (m2 == null)
                    return true;

                // named module trying to access member in another named module
                if (!jvmCanReadModule(m1, m2))
                    return false;
            }

            // check that m2 exports the package to m1
            String pkg = packageName(memberClass).replace('.', '/');
            return jvmIsExportedToModule(m2, pkg, m1);
        }

    }

    // JVM_CanReadModule
    private static native boolean jvmCanReadModule(Module from, Module to);

    // JVM_IsExportedToModule
    private static native boolean jvmIsExportedToModule(Module from, String pkg,
                                                        Module to);


    private static boolean isSameClassPackage(Class<?> c1, Class<?> c2) {
        return isSameClassPackage(c1.getClassLoader(), c1.getName(),
                c2.getClassLoader(), c2.getName());
    }

    /** Returns true if two classes are in the same package; classloader
        and classname information is enough to determine a class's package */
    private static boolean isSameClassPackage(ClassLoader loader1, String name1,
                                              ClassLoader loader2, String name2)
    {
        if (loader1 != loader2) {
            return false;
        } else {
            int lastDot1 = name1.lastIndexOf('.');
            int lastDot2 = name2.lastIndexOf('.');
            if ((lastDot1 == -1) || (lastDot2 == -1)) {
                // One of the two doesn't have a package.  Only return true
                // if the other one also doesn't have a package.
                return (lastDot1 == lastDot2);
            } else {
                int idx1 = 0;
                int idx2 = 0;

                // Skip over '['s
                if (name1.charAt(idx1) == '[') {
                    do {
                        idx1++;
                    } while (name1.charAt(idx1) == '[');
                    if (name1.charAt(idx1) != 'L') {
                        // Something is terribly wrong.  Shouldn't be here.
                        throw new InternalError("Illegal class name " + name1);
                    }
                }
                if (name2.charAt(idx2) == '[') {
                    do {
                        idx2++;
                    } while (name2.charAt(idx2) == '[');
                    if (name2.charAt(idx2) != 'L') {
                        // Something is terribly wrong.  Shouldn't be here.
                        throw new InternalError("Illegal class name " + name2);
                    }
                }

                // Check that package part is identical
                int length1 = lastDot1 - idx1;
                int length2 = lastDot2 - idx2;

                if (length1 != length2) {
                    return false;
                }
                return name1.regionMatches(false, idx1, name2, idx2, length1);
            }
        }
    }

    static boolean isSubclassOf(Class<?> queryClass,
                                Class<?> ofClass)
    {
        while (queryClass != null) {
            if (queryClass == ofClass) {
                return true;
            }
            queryClass = queryClass.getSuperclass();
        }
        return false;
    }

    // fieldNames must contain only interned Strings
    public static synchronized void registerFieldsToFilter(Class<?> containingClass,
                                              String ... fieldNames) {
        fieldFilterMap =
            registerFilter(fieldFilterMap, containingClass, fieldNames);
    }

    // methodNames must contain only interned Strings
    public static synchronized void registerMethodsToFilter(Class<?> containingClass,
                                              String ... methodNames) {
        methodFilterMap =
            registerFilter(methodFilterMap, containingClass, methodNames);
    }

    private static Map<Class<?>,String[]> registerFilter(Map<Class<?>,String[]> map,
            Class<?> containingClass, String ... names) {
        if (map.get(containingClass) != null) {
            throw new IllegalArgumentException
                            ("Filter already registered: " + containingClass);
        }
        map = new HashMap<Class<?>,String[]>(map);
        map.put(containingClass, names);
        return map;
    }

    public static Field[] filterFields(Class<?> containingClass,
                                       Field[] fields) {
        if (fieldFilterMap == null) {
            // Bootstrapping
            return fields;
        }
        return (Field[])filter(fields, fieldFilterMap.get(containingClass));
    }

    public static Method[] filterMethods(Class<?> containingClass, Method[] methods) {
        if (methodFilterMap == null) {
            // Bootstrapping
            return methods;
        }
        return (Method[])filter(methods, methodFilterMap.get(containingClass));
    }

    private static Member[] filter(Member[] members, String[] filteredNames) {
        if ((filteredNames == null) || (members.length == 0)) {
            return members;
        }
        int numNewMembers = 0;
        for (Member member : members) {
            boolean shouldSkip = false;
            for (String filteredName : filteredNames) {
                if (member.getName() == filteredName) {
                    shouldSkip = true;
                    break;
                }
            }
            if (!shouldSkip) {
                ++numNewMembers;
            }
        }
        Member[] newMembers =
            (Member[])Array.newInstance(members[0].getClass(), numNewMembers);
        int destIdx = 0;
        for (Member member : members) {
            boolean shouldSkip = false;
            for (String filteredName : filteredNames) {
                if (member.getName() == filteredName) {
                    shouldSkip = true;
                    break;
                }
            }
            if (!shouldSkip) {
                newMembers[destIdx++] = member;
            }
        }
        return newMembers;
    }

    /**
     * Tests if the given method is caller-sensitive and the declaring class
     * is defined by either the bootstrap class loader or extension class loader.
     */
    public static boolean isCallerSensitive(Method m) {
        final ClassLoader loader = m.getDeclaringClass().getClassLoader();
        if (sun.misc.VM.isSystemDomainLoader(loader) || isExtClassLoader(loader))  {
            return m.isAnnotationPresent(CallerSensitive.class);
        }
        return false;
    }

    private static boolean isExtClassLoader(ClassLoader loader) {
        ClassLoader cl = ClassLoader.getSystemClassLoader();
        while (cl != null) {
            if (cl.getParent() == null && cl == loader) {
                return true;
            }
            cl = cl.getParent();
        }
        return false;
    }


    // true to print a stack trace when IAE is thrown
    private static volatile boolean printStackWhenAccessFails;

    // true if printStackWhenAccessFails has been initialized
    private static volatile boolean printStackWhenAccessFailsSet;

    /**
     * Throws IllegalAccessException with the given exception message.
     */
    private static void throwIAE(String msg) throws IllegalAccessException {
        IllegalAccessException iae = new IllegalAccessException(msg);
        if (!printStackWhenAccessFailsSet && VM.initLevel() >= 1) {
            // can't use method reference here, might be too early in startup
            PrivilegedAction<String> pa = new PrivilegedAction<String>() {
                public String run() {
                    // legacy property name, it cannot be used to disable checks
                    return System.getProperty("sun.reflect.enableModuleChecks");
                }
            };
            String s = AccessController.doPrivileged(pa);
            printStackWhenAccessFails = "debug".equals(s);
            printStackWhenAccessFailsSet = true;
        }
        if (printStackWhenAccessFails) {
            iae.printStackTrace();
        }
        throw iae;
    }

}
