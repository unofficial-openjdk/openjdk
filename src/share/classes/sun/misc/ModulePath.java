/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

package sun.misc;

import java.net.URL;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import jdk.jigsaw.module.Module;
import jdk.jigsaw.module.View;
import jdk.jigsaw.module.ViewDependence;
import jdk.jigsaw.module.ViewIdQuery;

/**
 * Represents a module path, essentially a PATH of directories containing
 * exploded modules or jmod files.
 *
 * @apiNote URL used in this API because the system class laoder is
 * a URLClassLoader.
 */

public class ModulePath {
    private static final String MODULE_INFO = "module-info.class";

    // the list of URLs to the candidate modules on the module path
    private final List<URL> urls;

    // the list of Modules for the actual modules found, creted lazily
    private List<Module> modules;

    private ModulePath(List<URL> urls) {
        this.urls = urls;
    }

    /**
     * Scans each of the directories in the given PATH string looking for
     * candidate modules (jmod or {@code <dir>/module-info.class} and returns
     * a {@code ModulePath} to represent the resulting module path.
     */
    public static ModulePath scan(String path) {
        List<URL> urls = new ArrayList<>();
        if (path != null) {
            String[] dirs = path.split(File.pathSeparator);
            for (String dir: dirs) {
                Path dirPath = Paths.get(dir);
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(dirPath)) {
                    for (Path entry: stream) {
                        BasicFileAttributes attrs =
                            Files.readAttributes(entry, BasicFileAttributes.class);
                        if (attrs.isRegularFile() && entry.toString().endsWith(".jmod")) {
                            String s = entry.toUri().toURL().toString();
                            URL url = new URL("jmod" + s.substring(4));
                            urls.add(url);
                        } else if (attrs.isDirectory()) {
                            Path mi = entry.resolve(MODULE_INFO);
                            if (Files.exists(mi)) {
                                URL url = entry.toUri().toURL();
                                urls.add(url);
                            }
                        }
                    }
                } catch (IOException ioe) {
                    // ignore for now, similar to legacy classpath behavior
                }
            }
        }
        return new ModulePath(urls);
    }

    /**
     * Returns a list of URLs with the candidate modules on the module path.
     */
    public List<URL> urls() {
        return new ArrayList<>(urls);  // return copy
    }

    /**
     * Returns a list of the Modules found on this module  path (in module
     * path order).
     */
    public synchronized List<Module> modules() {
        if (modules == null) {
            List<Module> result = new ArrayList<>();
            for (URL url: urls) {
                String protocol = url.getProtocol();
                Module m;
                try {
                    switch (protocol) {
                        case "jmod" :
                            m = readJModModule(url);
                            break;
                        case "file" :
                            Path top = Paths.get(url.toURI());
                            m = readExplodedModule(top);
                            break;
                        default     : m = null;
                    }
                } catch (IOException | URISyntaxException e) {
                    // bail for now
                    throw new RuntimeException(e);
                }
                if (m != null) {
                    result.add(m);
                }
            }
            modules = result;
        }
        return modules;
    }

    /**
     * Returns a list of the names of the modules found on this module
     * path (in module path order).
     */
    public List<String> moduleNames() {
        List<Module> mods = modules();
        List<String> names = new ArrayList<>(mods.size());
        mods.forEach(m -> names.add(m.mainView().id().name()));
        return names;
    }

    /**
     * For testing purposes only, will be replaced.
     */
    private static class ModuleInfo {
        private final Properties props;
        private ModuleInfo(Properties props) {
            this.props = props;
        }
        static ModuleInfo read(InputStream in) throws IOException {
            Properties props = new Properties();
            props.load(in);
            return new ModuleInfo(props);
        }
        String name() {
            return props.getProperty("name");
        }
        Set<String> requires() {
            return split("requires");
        }
        Set<String> permits() {
            return split("permits");
        }
        Set<String> exports() {
            return split("exports");
        }
        Set<String> split(String prop) {
            String values = props.getProperty(prop);
            if (values == null) {
                return Collections.emptySet();
            } else {
                Set<String> result = new HashSet<>();
                for (String value: values.split(",")) {
                    result.add(value.trim());
                }
                return result;
            }
        }
    }

    /**
     * Read the jmod at the given URL and returns a {@code Module}
     * corresponding to its module-info.class and jmod contents.
     */
    private Module readJModModule(URL url) throws IOException {
        ZipFile zf = JModCache.get(url);

        // package list
        List<String> packages =
            zf.stream()
              .filter(entry -> entry.getName().startsWith("classes/") &&
                               entry.getName().endsWith(".class"))
              .map(entry -> toPackageName(entry))
              .filter(pkg -> pkg.length() > 0)   // module-info
              .distinct()
              .collect(Collectors.toList());

        // module-info
        ZipEntry entry = zf.getEntry("classes/" + MODULE_INFO);
        if (entry == null)
            throw new IOException(MODULE_INFO + " not found in " + url);

        try (InputStream in = zf.getInputStream(entry)) {
            ModuleInfo mi = ModuleInfo.read(in);
            return makeModule(mi, packages);
        }
    }

    /**
     * Returns a {@code Module} corresponding to the exploded module
     * at the given location.
     */
    private Module readExplodedModule(Path top) throws IOException {
        //  package list
        List<String> packages =
            Files.find(top, Integer.MAX_VALUE,
                     ((path, attrs) -> attrs.isRegularFile() &&
                                       path.toString().endsWith(".class")))
                 .map(path -> toPackageName(top.relativize(path)))
                 .filter(pkg -> pkg.length() > 0)   // module-info
                 .distinct()
                 .collect(Collectors.toList());

        // module-info
        Path file = top.resolve(MODULE_INFO);
        try (InputStream in = Files.newInputStream(file)) {
            ModuleInfo mi = ModuleInfo.read(in);
            // check that the module name is the same as the directory name?
            return makeModule(mi, packages);
        }
    }

    /**
     * Creates a {@code Module} from the given {@code ModuleInfo} and
     * package list.
     */
    private Module makeModule(ModuleInfo mi, List<String> packages) {
        String name = mi.name();
        if (name == null)
            throw new RuntimeException("No module name");

        View.Builder viewBuilder = new View.Builder().id(name);
        mi.permits().forEach(permit -> viewBuilder.permit(permit));
        mi.exports().forEach(export -> viewBuilder.export(export));

        View mainView = viewBuilder.build();
        Module.Builder builder = new Module.Builder().main(mainView);

        // contents
        packages.forEach(pkg -> builder.include(pkg));

        // requires, default to java.base if missing
        Set<String> requires = mi.requires();
        if (requires == null) {
            builder.requires(new ViewDependence(null, ViewIdQuery.parse("java.base")));
        } else {
            for (String require: requires) {
                ViewDependence vd = new ViewDependence(null, ViewIdQuery.parse(require));
                builder.requires(vd);
            }
        }

        // TBD, need qualified exports and more

        return builder.build();
    }

    private String toPackageName(ZipEntry entry) {
        String name = entry.getName();
        assert name.startsWith("classes/") && name.endsWith(".class");
        int index = name.lastIndexOf("/");
        if (index > 7) {
            return name.substring(8, index).replace('/', '.');
        } else {
            return "";
        }
    }

    private String toPackageName(Path path) {
        String name = path.toString();
        assert name.endsWith(".class");
        int index = name.lastIndexOf("/");
        if (index != -1) {
            return name.substring(0, index).replace(File.separatorChar, '.');
        } else {
            return "";
        }
    }
}
