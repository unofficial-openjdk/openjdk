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

import com.sun.tools.javac.code.Directive.PermitsDirective;
import com.sun.tools.javac.code.Directive.RequiresDirective;
import com.sun.tools.javac.code.Directive.RequiresFlag;
import com.sun.tools.javac.code.Scope;
import com.sun.tools.javac.code.Scope.WriteableScope;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.ModuleSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCModuleDecl;
import com.sun.tools.javac.tree.JCTree.JCPermits;
import com.sun.tools.javac.tree.JCTree.JCRequires;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import java.util.EnumSet;
import java.util.Set;
import javax.tools.JavaFileObject;

public class Modules extends JCTree.Visitor {
    Log log;
    Names names;
    Symtab syms;

    Env<ModuleContext> env;

    public static Modules instance(Context context) {
        Modules instance = context.get(Modules.class);
        if (instance == null)
            instance = new Modules(context);
        return instance;
    }

    protected Modules(Context context) {
        context.put(Modules.class, this);
        log = Log.instance(context);
        names = Names.instance(context);
        syms = Symtab.instance(context);
    }

    <T extends JCTree> void acceptAll(List<T> trees) {
        for (List<T> l = trees; l.nonEmpty(); l = l.tail)
            l.head.accept(this);
    }

    @Override
    public void visitModuleDef(JCModuleDecl tree) {
        ModuleSymbol sym = syms.enterModule(env.toplevel.locn);
        if (sym.name != null) {
            log.error(tree.pos(), "mdl.already.defined", sym.module_info.sourcefile);
            sym = new ModuleSymbol(TreeInfo.fullName(tree.qualId), syms.rootModule);
        } else {
            sym.name = sym.fullname = TreeInfo.fullName(tree.qualId);
            sym.module_info.fullname = ClassSymbol.formFullName(sym.module_info.name, sym);
            sym.module_info.flatname = ClassSymbol.formFlatName(sym.module_info.name, sym);
            sym.module_info.sourcefile = env.toplevel.sourcefile;
            sym.module_info.members_field = WriteableScope.create(sym.module_info);
            sym.completer = null;
        }

        sym.location = env.toplevel.locn;
        tree.sym = sym;
        env.toplevel.modle = sym;

        acceptAll(tree.directives);
    }

    @Override
    public void visitTopLevel(JCCompilationUnit tree) {
        env = new Env<>(tree, null);
        env.toplevel = tree;
        JavaFileObject prev = log.useSource(tree.sourcefile);
        try {
            if (TreeInfo.isModuleInfo(tree))
                acceptAll(tree.defs);
        } finally {
            log.useSource(prev);
        }
    }

    @Override
    public void visitPermits(JCPermits tree) {
        Name name = TreeInfo.fullName(tree.moduleName);
        tree.directive = new PermitsDirective(name);
    }

    @Override
    public void visitRequires(JCRequires tree) {
        Name name = TreeInfo.fullName(tree.moduleName);
        Set<RequiresFlag> flags = EnumSet.noneOf(RequiresFlag.class);
        if (tree.isPublic)
            flags.add(RequiresFlag.PUBLIC);
        tree.directive = new RequiresDirective(name, flags);
    }

    @Override
    public void visitTree(JCTree tree) { }

    boolean enter(List<JCTree.JCCompilationUnit> trees) {
        acceptAll(trees);
        return true; // for now
    }
}
