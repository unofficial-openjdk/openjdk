/*
 * Copyright (c) 2009, 2015, Oracle and/or its affiliates. All rights reserved.
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

import java.io.IOException;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import javax.tools.JavaFileManager;
import javax.tools.JavaFileManager.Location;
import javax.tools.JavaFileObject;
import javax.tools.JavaFileObject.Kind;
import javax.tools.StandardLocation;

import com.sun.tools.javac.code.Directive;
import com.sun.tools.javac.code.Directive.ExportsDirective;
import com.sun.tools.javac.code.Directive.RequiresDirective;
import com.sun.tools.javac.code.Directive.RequiresFlag;
import com.sun.tools.javac.code.ModuleFinder;
import com.sun.tools.javac.code.Source;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.Completer;
import com.sun.tools.javac.code.Symbol.CompletionFailure;
import com.sun.tools.javac.code.Symbol.ModuleSymbol;
import com.sun.tools.javac.code.Symbol.PackageSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.jvm.ClassWriter;
import com.sun.tools.javac.jvm.JNIWriter;
import com.sun.tools.javac.main.Option;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.JCCompilationUnit;
import com.sun.tools.javac.tree.JCTree.JCExports;
import com.sun.tools.javac.tree.JCTree.JCExpression;
import com.sun.tools.javac.tree.JCTree.JCModuleDecl;
import com.sun.tools.javac.tree.JCTree.JCPackageDecl;
import com.sun.tools.javac.tree.JCTree.JCProvides;
import com.sun.tools.javac.tree.JCTree.JCRequires;
import com.sun.tools.javac.tree.JCTree.JCUses;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.util.Assert;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import com.sun.tools.javac.util.Options;

import static com.sun.tools.javac.code.Flags.UNATTRIBUTED;
import static com.sun.tools.javac.code.Kinds.Kind.MDL;
import static com.sun.tools.javac.tree.JCTree.Tag.MODULEDEF;

/**
 *  TODO: fill in
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class Modules extends JCTree.Visitor {
    private final Log log;
    private final Names names;
    private final Symtab syms;
    private final JavaFileManager fileManager;
    private final ModuleFinder moduleFinder;
    private final boolean multiModuleMode;
    private final boolean allowModules;

    private final boolean noModules;

    ModuleSymbol defaultModule;

    private final String addModuleExportsOpt;
    private Map<ModuleSymbol, Set<ExportsDirective>> addModuleExports;

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
        moduleFinder = ModuleFinder.instance(context);
        fileManager = context.get(JavaFileManager.class);
        allowModules = Source.instance(context).allowModules();
        Options options = Options.instance(context);

        // The following is required, for now, to support building
        // Swing beaninfo via javadoc.
        noModules = options.isSet("noModules");

        multiModuleMode = fileManager.hasLocation(StandardLocation.MODULE_SOURCE_PATH);
        ClassWriter classWriter = ClassWriter.instance(context);
        classWriter.multiModuleMode = multiModuleMode;
        JNIWriter jniWriter = JNIWriter.instance(context);
        jniWriter.multiModuleMode = multiModuleMode;

        addModuleExportsOpt = options.get(Option.XXADDMODULEEXPORTS);
    }

    int depth = -1;
    private void dprintln(String msg) {
        for (int i = 0; i < depth; i++)
            System.err.print("  ");
        System.err.println(msg);
    }

    public boolean enter(List<JCCompilationUnit> trees, ClassSymbol c) {
        if (!allowModules || noModules) {
            for (JCCompilationUnit tree: trees) {
                tree.modle = syms.noModule;
            }
            return true;
        }

        int startErrors = log.nerrors;

        depth++;
        try {
            // scan trees for module defs
            Set<ModuleSymbol> rootModules = enterModules(trees, c);

            setCompilationUnitModules(trees, rootModules);

            for (ModuleSymbol msym: rootModules) {
                msym.complete();
            }
        } finally {
            depth--;
        }

        return (log.nerrors == startErrors);
    }

    public Completer getCompleter() {
        return mainCompleter;
    }


    private Set<ModuleSymbol> enterModules(List<JCCompilationUnit> trees, ClassSymbol c) {
        Set<ModuleSymbol> modules = new LinkedHashSet<>();
        for (JCCompilationUnit tree : trees) {
            JavaFileObject prev = log.useSource(tree.sourcefile);
            try {
                enterModule(tree, c, modules);
            } finally {
                log.useSource(prev);
            }
        }
        return modules;
    }


    private void enterModule(JCCompilationUnit toplevel, ClassSymbol c, Set<ModuleSymbol> modules) {
        boolean isModuleInfo = toplevel.sourcefile.isNameCompatible("module-info", Kind.SOURCE);
        boolean isModuleDecl = toplevel.defs.nonEmpty() && toplevel.defs.head.hasTag(MODULEDEF);
        if (isModuleInfo && isModuleDecl) {
            JCModuleDecl decl = (JCModuleDecl) toplevel.defs.head;
            Name name = TreeInfo.fullName(decl.qualId);
            ModuleSymbol sym;
            if (c != null) {
               sym = (ModuleSymbol) c.owner;
               if (sym.name == null) {
                   syms.enterModule(sym, name);
               } else {
                   // TODO: validate name
               }
            } else {
                sym = syms.enterModule(name);
                if (sym.module_info.sourcefile != null) {
                    log.error(decl.pos(), "duplicate.module", name);
                    return;
                }
            }
            sym.completer = getSourceCompleter(toplevel);
            sym.module_info.sourcefile = toplevel.sourcefile;
            decl.sym = sym;

            if (multiModuleMode || modules.isEmpty()) {
                modules.add(sym);
            } else {
                log.error(toplevel.pos(), "too.many.modules");
            }

        } else if (isModuleInfo) {
            if (multiModuleMode) {
                JCTree tree = toplevel.defs.isEmpty() ? toplevel : toplevel.defs.head;
                log.error(tree.pos(), "expected.module");
            }
        } else if (isModuleDecl) {
            JCTree tree = toplevel.defs.head;
            log.error(tree.pos(), "module.decl.sb.in.module-info.java");
        }
    }

    private void setCompilationUnitModules(List<JCCompilationUnit> trees, Set<ModuleSymbol> rootModules) {
        // update the module for each compilation unit
        if (multiModuleMode) {
            for (JCCompilationUnit tree: trees) {
                if (tree.defs.isEmpty())
                    continue;

                JavaFileObject prev = log.useSource(tree.sourcefile);
                try {
                    Location locn = getModuleLocation(tree);
                    if (locn != null) {
                        Name name = names.fromString(fileManager.inferModuleName(locn));
                        ModuleSymbol msym = syms.enterModule(name);
                        if (msym.sourceLocation == null) {
                            msym.sourceLocation = locn;
                            if (fileManager.hasLocation(StandardLocation.CLASS_OUTPUT)) {
                                msym.classLocation = fileManager.getModuleLocation(
                                        StandardLocation.CLASS_OUTPUT, msym.name.toString());
                            }
                        }
                        tree.modle = msym;
                        rootModules.add(msym);
                    } else {
                        log.error(tree.pos(), "cant.determine.module");
                        tree.modle = syms.errModule;
                    }
                } catch (IOException e) {
                    throw new Error(e); // FIXME
                } finally {
                    log.useSource(prev);
                }
            }
        } else {
            if (defaultModule == null) {
                switch (rootModules.size()) {
                    case 0:
                        defaultModule = moduleFinder.findSingleModule();
                        if (defaultModule == syms.unnamedModule) {
                            // Question: why not do findAllModules and initVisiblePackages here?
                            // i.e. body of unnamedModuleCompleter
                            defaultModule.completer = getUnnamedModuleCompleter();
                        } else {
                            // Question: why not do completeModule here?
                            defaultModule.completer = new Completer() {
                                @Override
                                public void complete(Symbol sym) throws CompletionFailure {
                                    completeModule((ModuleSymbol) sym);
                                }
                            };
                        }
                        rootModules.add(defaultModule);
                        break;
                    case 1:
                        defaultModule = rootModules.iterator().next();
                        break;
                    default:
                        Assert.error("too many modules");
                }
                defaultModule.sourceLocation = StandardLocation.SOURCE_PATH;
                defaultModule.classLocation = StandardLocation.CLASS_PATH;
            } else {
                Assert.check(rootModules.isEmpty());
            }

            for (JCCompilationUnit tree: trees) {
                tree.modle = defaultModule;
            }
        }
    }

    private Location getModuleLocation(JCCompilationUnit tree) throws IOException {
        switch (tree.defs.head.getTag()) {
            case MODULEDEF:
                return getModuleLocation(tree.sourcefile, null);

            case PACKAGEDEF:
                JCPackageDecl pkg = (JCPackageDecl) tree.defs.head;
                return getModuleLocation(tree.sourcefile, TreeInfo.fullName(pkg.pid));

            default:
                // code in unnamed module
                return null;
        }
    }

    private Location getModuleLocation(JavaFileObject fo, Name pkgName) throws IOException {
        // For now, just check module source path.
        // We may want to check source path as well.
        return fileManager.getModuleLocation(StandardLocation.MODULE_SOURCE_PATH,
                fo, (pkgName == null) ? null : pkgName.toString());
    }

    private final Completer mainCompleter = new Completer() {
        @Override
        public void complete(Symbol sym) throws CompletionFailure {
            ModuleSymbol msym = (ModuleSymbol) sym;
            moduleFinder.findModule(msym).module_info.complete();

            // If module-info comes from a .java file, the underlying
            // call of classFinder.fillIn will have called through the
            // source completer, to Enter, and then to Modules.enter,
            // which will call completeModule.
            // But, if module-info comes from a .class file, the underlying
            // call of classFinder.fillIn will just call ClassReader to read
            // the .class file, and so we call completeModule here.
            if (msym.module_info.classfile.getKind() == Kind.CLASS) {
                completeModule(msym);
            }
        }

        @Override
        public String toString() {
            return "mainCompleter";
        }
    };

    private Completer getSourceCompleter(JCCompilationUnit tree) {
        return new Completer() {
            @Override
            public void complete(Symbol sym) throws CompletionFailure {
                ModuleSymbol msym = (ModuleSymbol) sym;
                msym.flags_field |= UNATTRIBUTED;
                ModuleVisitor v = new ModuleVisitor();
                JavaFileObject prev = log.useSource(tree.sourcefile);
                try {
                    tree.defs.head.accept(v);
                    completeModule(msym);
                } finally {
                    log.useSource(prev);
                    msym.flags_field &= ~UNATTRIBUTED;
                }
            }

            @Override
            public String toString() {
                return "SourceCompleter: " + tree.sourcefile.getName();
            }

        };
    }

    class ModuleVisitor extends JCTree.Visitor {
        private ModuleSymbol sym;
        private final Set<ModuleSymbol> allRequires = new HashSet<>();
        private final Set<PackageSymbol> allExports = new HashSet<>();

        private <T extends JCTree> void acceptAll(List<T> trees) {
            for (List<T> l = trees; l.nonEmpty(); l = l.tail)
                l.head.accept(this);
        }

        @Override
        public void visitModuleDef(JCModuleDecl tree) {
            sym = Assert.checkNonNull(tree.sym);
            allRequires.clear();
            allExports.clear();

            sym.requires = List.nil();
            sym.exports = List.nil();
            acceptAll(tree.directives);
            sym.requires = sym.requires.reverse();
            sym.exports = sym.exports.reverse();
            ensureJavaBase();
        }

        @Override
        public void visitRequires(JCRequires tree) {
            ModuleSymbol msym = lookupModule(tree.moduleName);
            if (msym.kind != MDL) {
                log.error(tree.moduleName.pos(), "module.not.found", msym);
            } else if ((msym.flags_field & UNATTRIBUTED) != 0) {
                log.error(tree.moduleName.pos(), "cyclic.requires", msym);
            } else if (allRequires.contains(msym)) {
                log.error(tree.moduleName.pos(), "duplicate.requires", msym);
            } else {
                allRequires.add(msym);
                Set<RequiresFlag> flags = EnumSet.noneOf(RequiresFlag.class);
                if (tree.isPublic)
                    flags.add(RequiresFlag.PUBLIC);
                RequiresDirective d = new RequiresDirective(msym, flags);
                tree.directive = d;
                sym.requires = sym.requires.prepend(d);
            }
        }

        @Override
        public void visitExports(JCExports tree) {
            Name name = TreeInfo.fullName(tree.qualid);
            PackageSymbol packge = syms.enterPackage(name);
            if (!allExports.add(packge)) {
                log.error(tree.qualid.pos(), "duplicate.exports", packge);
            }

            List<ModuleSymbol> toModules = null;
            if (tree.moduleNames != null) {
                Set<ModuleSymbol> to = new HashSet<>();
                for (JCExpression n: tree.moduleNames) {
                    ModuleSymbol msym = lookupModule(n);
                    if (msym.kind != MDL) {
                        log.error(n.pos(), "module.not.found", msym);
                    } else if (!to.add(msym)) {
                        log.error(n.pos(), "duplicate.exports", msym);
                    }
                }
                toModules = List.from(to);
            }

            if (toModules == null || !toModules.isEmpty()) {
                ExportsDirective d = new ExportsDirective(packge, toModules);
                tree.directive = d;
                sym.exports = sym.exports.prepend(d);
            }
        }

        @Override
        public void visitProvides(JCProvides tree) { }

        @Override
        public void visitUses(JCUses tree) { }

        private void ensureJavaBase() {
            if (sym.name == names.java_base)
                return;

            for (RequiresDirective d: sym.requires) {
                if (d.module.name == names.java_base)
                    return;
            }

            ModuleSymbol java_base = syms.enterModule(names.java_base);
            Directive.RequiresDirective d =
                    new Directive.RequiresDirective(java_base,
                            EnumSet.of(Directive.RequiresFlag.MANDATED));
            sym.requires = sym.requires.prepend(d);
        }

        private ModuleSymbol lookupModule(JCExpression moduleName) {
            try {
            Name name = TreeInfo.fullName(moduleName);
            return moduleFinder.findModule(name);
            } catch (Throwable t) {
                System.err.println("Module " + sym + "; lookup export " + moduleName);
                throw t;
            }
        }
    }

    private Completer getUnnamedModuleCompleter() {
        List<ModuleSymbol> allModules = moduleFinder.findAllModules();
        return new Symbol.Completer() {
            @Override
            public void complete(Symbol sym) throws CompletionFailure {
                ModuleSymbol msym = (ModuleSymbol) sym;
                for (ModuleSymbol m : allModules) {
                    m.complete();
                }
                initVisiblePackages(msym, allModules);
            }

            @Override
            public String toString() {
                return "unnamedModule Completer";
            }
        };
    }

    private final Map<ModuleSymbol, Set<ModuleSymbol>> requiresPublicCache = new HashMap<>();

    private void completeModule(ModuleSymbol msym) {
        Assert.checkNonNull(msym.requires);
        Set<ModuleSymbol> readable = new HashSet<>();
        Set<ModuleSymbol> requiresPublic = new HashSet<>();
        for (RequiresDirective d: msym.requires) {
            d.module.complete();
            readable.add(d.module);
            Set<ModuleSymbol> s = requiresPublicCache.get(d.module);
            if (s == null) {
                System.err.println("no entry in cache for " + d.module);
            }
            readable.addAll(s);
            if (d.flags.contains(RequiresFlag.PUBLIC)) {
                requiresPublic.add(d.module);
                requiresPublic.addAll(s);
            }
        }
        requiresPublicCache.put(msym, requiresPublic);
        initVisiblePackages(msym, readable);
        for (ExportsDirective d: msym.exports) {
            d.packge.modle = msym;
        }

    }

    private void initVisiblePackages(ModuleSymbol msym, Collection<ModuleSymbol> readable) {
        initAddModuleExports();

        msym.visiblePackages = new LinkedHashSet<>();
        msym.visiblePackages.add(syms.rootPackage);

        for (ModuleSymbol rm : readable) {
            addVisiblePackages(msym, rm.exports);
            Set<ExportsDirective> extra = addModuleExports.get(rm);
            if (extra != null)
                addVisiblePackages(msym, extra);
        }
    }

    private void addVisiblePackages(ModuleSymbol msym, Collection<ExportsDirective> directives) {
        for (ExportsDirective d: directives) {
            if (d.modules == null || d.modules.contains(msym))
                msym.visiblePackages.add(d.packge);
        }
    }

    private void initAddModuleExports() {
        if (addModuleExports != null)
            return;

        addModuleExports = new LinkedHashMap<>();

        if (addModuleExportsOpt == null)
            return;

        for (String s: addModuleExportsOpt.split("[ ,]+")) {
            if (s.isEmpty())
                continue;
            int slash = s.indexOf('/');
            String moduleName = s.substring(0, slash);
            ModuleSymbol msym = syms.enterModule(names.fromString(moduleName));
            int equals = s.indexOf('=', slash + 1);
            ExportsDirective d;
            if (equals == -1) {
                String packageName = s.substring(slash + 1);
                PackageSymbol p = syms.enterPackage(msym, names.fromString(packageName));
                p.modle = msym;
                d = new ExportsDirective(p, null);
            } else {
                String packageName = s.substring(slash + 1, equals);
                String toModule = s.substring(equals + 1);
                PackageSymbol p = syms.enterPackage(msym, names.fromString(packageName));
                p.modle = msym;
                ModuleSymbol m = syms.enterModule(names.fromString(toModule));
                d = new ExportsDirective(p, List.of(m));
            }

            Set<ExportsDirective> extra = addModuleExports.get(msym);
            if (extra == null) {
                addModuleExports.put(msym, extra = new LinkedHashSet<>());
            }
            extra.add(d);
        }
    }

    // DEBUG
    private String toString(ModuleSymbol msym) {
        return msym.name + "["
                + "kind:" + msym.kind + ";"
                + "locn:" + toString(msym.sourceLocation) + "," + toString(msym.classLocation) + ";"
                + "info:" + toString(msym.module_info.sourcefile) + ","
                            + toString(msym.module_info.classfile) + ","
                            + msym.module_info.completer
                + "]";
    }

    // DEBUG
    String toString(Location locn) {
        return (locn == null) ? "--" : locn.getName();
    }

    // DEBUG
    String toString(JavaFileObject fo) {
        return (fo == null) ? "--" : fo.getName();
    }
}
