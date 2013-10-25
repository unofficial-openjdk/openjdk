/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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

import com.sun.tools.classfile.*;
import static com.sun.tools.classfile.ConstantPool.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

/*
 * @test
 * @bug 8010117
 * @summary Verify if CallerSensitive methods are annotated with
 *          sun.reflect.CallerSensitive annotation
 * @build CallerSensitiveFinder MethodFinder ClassFileReader
 * @run main/othervm/timeout=900 -mx800m CallerSensitiveFinder
 */
public class CallerSensitiveFinder extends MethodFinder {
    private static int numThreads = 3;
    private static boolean verbose = false;
    public static void main(String[] args) throws Exception {
        List<File> classes = new ArrayList<File>();
        String testclasses = System.getProperty("test.classes", ".");
        int i = 0;
        while (i < args.length) {
            String arg = args[i++];
            if (arg.equals("-v")) {
                verbose = true;
            } else {
                File f = new File(testclasses, arg);
                if (!f.exists()) {
                    throw new IllegalArgumentException(arg + " does not exist");
                }
                classes.add(f);
            }
        }
        if (classes.isEmpty()) {
            classes.addAll(PlatformClassPath.getJREClasses());
        }
        final String method = "sun/reflect/Reflection.getCallerClass";
        CallerSensitiveFinder csfinder = new CallerSensitiveFinder(method);

        List<String> errors = csfinder.run(classes);
        if (!errors.isEmpty()) {
            throw new RuntimeException(errors.size() +
                    " caller-sensitive methods are missing @CallerSensitive annotation");
        }
    }

    private final List<String> csMethodsMissingAnnotation = new ArrayList<String>();
    private final java.lang.reflect.Method mhnCallerSensitiveMethod;
    public CallerSensitiveFinder(String... methods) throws Exception {
        super(methods);
        this.mhnCallerSensitiveMethod = getIsCallerSensitiveMethod();
    }

    static java.lang.reflect.Method getIsCallerSensitiveMethod()
            throws ClassNotFoundException, NoSuchMethodException
    {
        Class<?> cls = CallerSensitiveFinder.class;
        java.lang.reflect.Method m = cls.getDeclaredMethod("isCallerSensitiveMethod", Class.class, String.class);
        m.setAccessible(true);
        return m;
    }

    // Needs to match method in 7's java.lang.invoke.MethodHandleNatives
    private static boolean isCallerSensitiveMethod(Class<?> defc, String method) {
        if ("doPrivileged".equals(method) ||
            "doPrivilegedWithCombiner".equals(method))
            return defc == java.security.AccessController.class;
        else if ("checkMemberAccess".equals(method))
            return defc == java.lang.SecurityManager.class;
        else if ("getUnsafe".equals(method))
            return defc == sun.misc.Unsafe.class;
        else if ("invoke".equals(method))
            return defc == java.lang.reflect.Method.class;
        else if ("get".equals(method) ||
                 "getBoolean".equals(method) ||
                 "getByte".equals(method) ||
                 "getChar".equals(method) ||
                 "getShort".equals(method) ||
                 "getInt".equals(method) ||
                 "getLong".equals(method) ||
                 "getFloat".equals(method) ||
                 "getDouble".equals(method) ||
                 "set".equals(method) ||
                 "setBoolean".equals(method) ||
                 "setByte".equals(method) ||
                 "setChar".equals(method) ||
                 "setShort".equals(method) ||
                 "setInt".equals(method) ||
                 "setLong".equals(method) ||
                 "setFloat".equals(method) ||
                 "setDouble".equals(method))
            return defc == java.lang.reflect.Field.class;
        else if ("newInstance".equals(method)) {
            if (defc == java.lang.reflect.Constructor.class)  return true;
            if (defc == java.lang.Class.class)  return true;
        } else if ("getFields".equals(method))
            return defc == java.lang.Class.class ||
                   defc == javax.sql.rowset.serial.SerialJavaObject.class;
        else if ("forName".equals(method) ||
                 "getClassLoader".equals(method) ||
                 "getClasses".equals(method) ||
                 "getMethods".equals(method) ||
                 "getConstructors".equals(method) ||
                 "getDeclaredClasses".equals(method) ||
                 "getDeclaredFields".equals(method) ||
                 "getDeclaredMethods".equals(method) ||
                 "getDeclaredConstructors".equals(method) ||
                 "getField".equals(method) ||
                 "getMethod".equals(method) ||
                 "getConstructor".equals(method) ||
                 "getDeclaredField".equals(method) ||
                 "getDeclaredMethod".equals(method) ||
                 "getDeclaredConstructor".equals(method) ||
                 "getDeclaringClass".equals(method) ||
                 "getEnclosingClass".equals(method) ||
                 "getEnclosingMethod".equals(method) ||
                 "getEnclosingConstructor".equals(method))
            return defc == java.lang.Class.class;
        else if ("getConnection".equals(method) ||
                 "getDriver".equals(method) ||
                 "getDrivers".equals(method) ||
                 "deregisterDriver".equals(method))
                 return defc == java.sql.DriverManager.class;
        else if ("newUpdater".equals(method)) {
            if (defc == java.util.concurrent.atomic.AtomicIntegerFieldUpdater.class)  return true;
            if (defc == java.util.concurrent.atomic.AtomicLongFieldUpdater.class)  return true;
            if (defc == java.util.concurrent.atomic.AtomicReferenceFieldUpdater.class)  return true;
        } else if ("getContextClassLoader".equals(method))
            return defc == java.lang.Thread.class;
        else if ("getPackage".equals(method) ||
                 "getPackages".equals(method))
            return defc == java.lang.Package.class;
        else if ("getParent".equals(method) ||
                 "getSystemClassLoader".equals(method))
            return defc == java.lang.ClassLoader.class;
        else if ("load".equals(method) ||
                 "loadLibrary".equals(method)) {
            if (defc == java.lang.Runtime.class)  return true;
            if (defc == java.lang.System.class)  return true;
        } else if ("getCallerClass".equals(method)) {
            if (defc == sun.reflect.Reflection.class)  return true;
            if (defc == java.lang.System.class)  return true;
        } else if ("getCallerClassLoader".equals(method))
            return defc == java.lang.ClassLoader.class;
        else if ("registerAsParallelCapable".equals(method))
            return defc == java.lang.ClassLoader.class;
        else if ("getInvocationHandler".equals(method) ||
                 "getProxyClass".equals(method) ||
                 "newProxyInstance".equals(method))
            return defc == java.lang.reflect.Proxy.class;
        else if ("getBundle".equals(method) ||
                 "clearCache".equals(method))
            return defc == java.util.ResourceBundle.class;
        else if ("getType".equals(method))
            return defc == java.io.ObjectStreamField.class;
        else if ("forClass".equals(method))
            return defc == java.io.ObjectStreamClass.class;
        else if ("getLogger".equals(method))
            return defc == java.util.logging.Logger.class;
        else if ("getAnonymousLogger".equals(method))
            return defc == java.util.logging.Logger.class;
        return false;
    }

    boolean inMethodHandlesList(String classname, String method)  {
       Class<?> cls;
        try {
            cls = Class.forName(classname.replace('/', '.'),
                                false,
                                ClassLoader.getSystemClassLoader());
            return (Boolean) mhnCallerSensitiveMethod.invoke(null, cls, method);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        } catch (InvocationTargetException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    public List<String> run(List<File> classes) throws IOException, InterruptedException,
            ExecutionException, ConstantPoolException
    {
        ExecutorService pool = Executors.newFixedThreadPool(numThreads);
        for (File path : classes) {
            ClassFileReader reader = ClassFileReader.newInstance(path);
            for (ClassFile cf : reader.getClassFiles()) {
                String classFileName = cf.getName();
                // for each ClassFile
                //    parse constant pool to find matching method refs
                //      parse each method (caller)
                //      - visit and find method references matching the given method name
                pool.submit(getTask(cf));
            }
        }
        waitForCompletion();
        pool.shutdown();
        return csMethodsMissingAnnotation;
    }

    private static final String CALLER_SENSITIVE_ANNOTATION = "Lsun/reflect/CallerSensitive;";
    private static boolean isCallerSensitive(Method m, ConstantPool cp)
            throws ConstantPoolException
    {
        RuntimeAnnotations_attribute attr =
            (RuntimeAnnotations_attribute)m.attributes.get(Attribute.RuntimeVisibleAnnotations);
        int index = 0;
        if (attr != null) {
            for (int i = 0; i < attr.annotations.length; i++) {
                Annotation ann = attr.annotations[i];
                String annType = cp.getUTF8Value(ann.type_index);
                if (CALLER_SENSITIVE_ANNOTATION.equals(annType)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void referenceFound(ClassFile cf, Method m, Set<Integer> refs)
            throws ConstantPoolException
    {
        String name = String.format("%s#%s %s", cf.getName(),
                                    m.getName(cf.constant_pool),
                                    m.descriptor.getValue(cf.constant_pool));
        if (!CallerSensitiveFinder.isCallerSensitive(m, cf.constant_pool)) {
            csMethodsMissingAnnotation.add(name);
            System.err.println("   Missing @CallerSensitive: " + name);
        } else if (verbose) {
            System.out.format("Caller found: %s%n", name);
        }
        if (m.access_flags.is(AccessFlags.ACC_PUBLIC)) {
            if (!inMethodHandlesList(cf.getName(), m.getName(cf.constant_pool))) {
                csMethodsMissingAnnotation.add(name);
                System.err.println("   Missing in MethodHandleNatives list: " + name);
            } else if (verbose) {
                System.out.format("Caller found in MethodHandleNatives list: %s%n", name);

            }
        }
    }

    private final List<FutureTask<String>> tasks = new ArrayList<FutureTask<String>>();
    private FutureTask<String> getTask(final ClassFile cf) {
        FutureTask<String> task = new FutureTask<String>(new Callable<String>() {
            public String call() throws Exception {
                return parse(cf);
            }
        });
        tasks.add(task);
        return task;
    }

    private void waitForCompletion() throws InterruptedException, ExecutionException {
        for (FutureTask<String> t : tasks) {
            String s = t.get();
        }
        System.out.println("Parsed " + tasks.size() + " classfiles");
    }

    static class PlatformClassPath {
        static List<File> getJREClasses() throws IOException {
            List<File> result = new ArrayList<File>();
            File home = new File(System.getProperty("java.home"));

            if (home.toString().endsWith("jre")) {
                // jar files in <javahome>/jre/lib
                // skip <javahome>/lib
                result.addAll(addJarFiles(new File(home, "lib")));
            } else if (new File(home, "lib").exists()) {
                // either a JRE or a jdk build image
                File classes = new File(home, "classes");
                if (classes.exists() && classes.isDirectory()) {
                    // jdk build outputdir
                    result.add(classes);
                }
                // add other JAR files
                result.addAll(addJarFiles(new File(home, "lib")));
            } else {
                throw new RuntimeException("\"" + home + "\" not a JDK home");
            }
            return result;
        }

        static List<File> addJarFiles(final File root) throws IOException {
            final List<File> result = new ArrayList<File>();
            final File ext = new File(root, "ext");
            final List<File> files = new ArrayList<File>();
            for (String s : root.list())
                files.add(new File(root, s));
            for (String s : ext.list())
                files.add(new File(ext, s));
            for (File f : files) {
                if (f.isFile()) {
                    String fn = f.getName();
                    // parse alt-rt.jar as well
                    if (fn.endsWith(".jar") && !fn.equals("jfxrt.jar")) {
                        result.add(f);
                    }
                }
            }
            return result;
        }
    }
}
