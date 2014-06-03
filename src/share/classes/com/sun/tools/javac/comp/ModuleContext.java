/*
 * Copyright (c) 2009, 2014, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.tools.javac.comp;

//import java.util.ArrayList;
//import java.util.Collection;
//import java.util.Collections;
//import java.util.HashMap;
//import java.util.LinkedHashSet;
//import java.util.List;
//import java.util.Map;
//import java.util.Set;

//import com.sun.tools.javac.code.Directive;
//import com.sun.tools.javac.tree.JCTree.JCDirective;
//import com.sun.tools.javac.util.ListBuffer;
//import com.sun.tools.javac.util.Name;

/**
 *  Contains information specific to the modules/enter/attr
 *  passes, to be used in place of the generic field in environments.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class ModuleContext {

    ModuleContext() {
        requiresBaseModule = true;
//        directives = new ListBuffer<Directive>();
//        directiveForTree = new HashMap<JCModuleDirective, Directive>();
//        directiveIndex = new HashMap<Name, Set<Directive>>();
    }

    ModuleContext dup() {
        return new ModuleContext();
    }

//    void addDirective(Directive d, JCModuleDirective tree, Name name) {
//        directives.add(d);
//        directiveForTree.put(tree, d);
//        Set<Directive> set = directiveIndex.get(name);
//        if (set == null)
//            directiveIndex.put(name, (set = new LinkedHashSet<Directive>()));
//        set.add(d);
//    }
//
//    Collection<Directive> getDirectives(Name name) {
//        Set<Directive> set = directiveIndex.get(name);
//        return (set != null) ? set : Collections.<Directive>emptySet();
//    }

//    Collection<Directive> getDirectives(Directive.Kind kind, Name name) {
//        List<Directive> list = null;
//        Set<Directive> set = directiveIndex.get(name);
//        if (set != null) {
//            for (Directive d: set) {
//                if (d.getKind() == kind) {
//                    if (list == null)
//                        list = new ArrayList<Directive>();
//                    list.add(d);
//                }
//            }
//        }
//        return (list == null) ? Collections.<Directive>emptySet() : list;
//    }

//    final ListBuffer<Directive> directives;
//    final Map<JCModuleDirective, Directive> directiveForTree;
//    final Map<Name, Set<Directive>> directiveIndex;

    boolean requiresBaseModule;
    boolean isPlatformModule;
}

