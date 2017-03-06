/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
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

package jdk.internal.module;

import java.lang.reflect.Module;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Supports tracking and reporting of API packages that are exported or opened
 * via backdoor mechanisms to code in unnamed modules.
 */

public final class InternalUseReporter {

    // true to print a full stack trace
    private static final boolean PRINT_FULL_STACK_TRACE = false;

    // the maximum number of frames to print when not printing full stack trace
    private static final int MAX_STACK_FRAMES = 32;

    // stack walker for use when not printing full stack trace
    private static final StackWalker STACK_WALKER
        = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    // lock to avoid interference when printing stack traces
    private static final Object OUTPUT_LOCK = new Object();



    private final Map<Module, Set<String>> exported;
    private final Map<Module, Set<String>> opened;


    private InternalUseReporter(Map<Module, Set<String>> exported,
                                Map<Module, Set<String>> opened) {
        this.exported = deepCopy(exported);
        this.opened = deepCopy(opened);
    }

    /**
     * Returns that a Builder that is seeded with the packages known
     * to this reporter.
     */
    public Builder toBuilder() {
        return new Builder(exported, opened);
    }

    /**
     * Returns {@code true} if a module exports a package to a given unnamed caller
     * module because of a backdoor option. This method always reports {@code false}
     * if the caller is a named module.
     */
    public boolean isExportedByBackdoor(Module module, String pn, Module caller) {
        if (!caller.isNamed()) {
            Set<String> packages = exported.get(module);
            if (packages != null && packages.contains(pn)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if a module opens a package to a given unnamed caller
     * module because of a backdoor option. This method always reports {@code false}
     * if the caller is a named module.
     */
    public boolean isOpenByBackdoor(Module module, String pn, Module caller) {
        if (!caller.isNamed()) {
            Set<String> packages = opened.get(module);
            if (packages != null && packages.contains(pn)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Prints stack trace that identifies "caller" as the code that is making use
     * of backdoor options to get at internal APIs.
     */
    public void printStack(Class<?> caller, String suffix) {
        String msg = caller + " " + suffix;
        if (PRINT_FULL_STACK_TRACE) {
            synchronized (OUTPUT_LOCK) {
                new Exception(msg).printStackTrace(System.err);
            }
        } else {
            // stack traces that doesn't have the top-most frames in java.base
            List<StackWalker.StackFrame> stack = STACK_WALKER.walk(s ->
                s.dropWhile(this::isJavaBase)
                 .limit(MAX_STACK_FRAMES)
                 .collect(Collectors.toList())
            );
            synchronized (OUTPUT_LOCK) {
                System.err.println("WARNING: " + msg);
                stack.forEach(f -> System.err.println("\tat " + f));
            }
        }
    }

    /**
     * Returns true if the stack frame is for a class in java.base.
     */
    private boolean isJavaBase(StackWalker.StackFrame frame) {
        Module caller = frame.getDeclaringClass().getModule();
        return "java.base".equals(caller.getName());
    }


    // VM-wide/reporter reporter
    private static volatile InternalUseReporter reporter;

    /**
     * Sets the VM-wide/global reporter.
     */
    public static void setInternalUseReporter(InternalUseReporter r) {
        if (r.exported.isEmpty() && r.opened.isEmpty()) {
            reporter = null;
        } else {
            reporter = r;
        }
    }

    /**
     * Returns the VM-wide/global reporter or {@code null} if there is no
     * reporter.
     */
    public static InternalUseReporter internalUseReporter() {
        return reporter;
    }

    /**
     * A builder for InternalUseReporter objects.
     */
    public static class Builder {
        private Map<Module, Set<String>> exported;
        private Map<Module, Set<String>> opened;

        public Builder() { }

        public Builder(Map<Module, Set<String>> exported,
                       Map<Module, Set<String>> opened) {
            this.exported = deepCopy(exported);
            this.opened = deepCopy(opened);
        }

        public void addExports(Module m, String pn) {
            if (!m.isExported(pn)) {
                if (exported == null)
                    exported = new HashMap<>();
                exported.computeIfAbsent(m, k -> new HashSet<>()).add(pn);
            }
        }

        public void addOpens(Module m, String pn) {
            // opens implies exported at run-time.
            addExports(m, pn);

            if (!m.isOpen(pn)) {
                if (opened == null)
                    opened = new HashMap<>();
                opened.computeIfAbsent(m, k -> new HashSet<>()).add(pn);
            }
        }

        /**
         * Builds the reporter.
         */
        public InternalUseReporter build() {
            return new InternalUseReporter(exported, opened);
        }
    }


    static Map<Module, Set<String>> deepCopy(Map<Module, Set<String>> map) {
        if (map == null || map.isEmpty()) {
            return new HashMap<>();
        } else {
            Map<Module, Set<String>> newMap = new HashMap<>();
            for (Map.Entry<Module, Set<String>> e : map.entrySet()) {
                newMap.put(e.getKey(), new HashSet<>(e.getValue()));
            }
            return newMap;
        }
    }

}
