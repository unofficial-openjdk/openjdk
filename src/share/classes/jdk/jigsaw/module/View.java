/*
 * Copyright (c) 2011, 2013, Oracle and/or its affiliates. All rights reserved.
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

package jdk.jigsaw.module;

import java.io.*;
import java.util.*;
import static java.util.Objects.*;


/**
 * <p> A view definition </p>
 */

@SuppressWarnings("serial")             // serialVersionUID intentionally omitted
public final class View
    implements Comparable<View>, Serializable
{

    private Module module;
    private final ViewId id;
    private final Set<ViewId> aliases;
    private final Set<String> permits;
    private final Set<String> exports;
    private final Map<String,Set<String>> services;
    private final String mainClass;

    private View(ViewId id,
                 Set<ViewId> aliases,
                 Set<String> permits,
                 Set<String> exports,
                 Map<String,Set<String>> services,
                 String mainClass)
    {
        this.id = requireNonNull(id);
        this.aliases = Collections.unmodifiableSet(aliases);
        this.permits = Collections.unmodifiableSet(permits);
        this.exports = Collections.unmodifiableSet(exports);
        // ## Make values unmodifiable also:
        this.services = Collections.unmodifiableMap(services);
        this.mainClass = mainClass;
    }

    // Invoked by Module.Builder when adding a View to a Module
    //
    /* package */ void module(Module m) {
        this.module = requireNonNull(m);
    }

    /**
     * <p> This view's containing module </p>
     */
    public Module module() {
        return module;
    }

    /**
     * <p> This view's identifier </p>
     */
    public ViewId id() {
        return id;
    }

    /**
     * <p> The names of this view's aliases </p>
     *
     * @return  A possibly-empty unmodifiable set of {@link ViewId}s
     */
    public Set<ViewId> aliases() {
        return aliases;
    }

    /**
     * <p> The names of the modules that are permitted to require this view
     * </p>
     *
     * @return  A possibly-empty unmodifiable set of module names
     */
    public Set<String> permits() {
        return permits;
    }

    /**
     * <p> The exported packages of this view </p>
     *
     * @return  A possibly-empty unmodifiable set of exported packages
     */
    public Set<String> exports() {
        return exports;
    }

    /**
     * <p> The services that this view provides </p>
     *
     * @return  A possibly-empty unmodifiable map of a fully-qualified
     *          name of a service type to the class names of its providers
     *          provided by this view.
     */
    public Map<String,Set<String>> services() {
        return services;
    }

    /**
     * <p> The fully qualified name of the main class of this view </p>
     *
     * @return  The fully qualified name of the main class of this module, or
     *          {@code null} if this module does not have a main class
     */
    public String mainClass() {
        return mainClass;
    }

    public static final class Builder {

        private ViewId id;
        private final Set<ViewId> aliases = new HashSet<>();
        private final Set<String> permits = new HashSet<>();
        private final Set<String> exports = new HashSet<>();
        private final Map<String,Set<String>> services = new HashMap<>();
        private String mainClass = null;

        public Builder() { }

        public Builder id(String i) {
            if (id != null) throw new IllegalStateException("id");
            id = ViewId.parse(i);
            return this;
        }

        public Builder alias(String i) {
            aliases.add(ViewId.parse(i));
            return this;
        }

        public Builder permit(String i) {
            permits.add(i);
            return this;
        }

        public Builder export(String p) {
            exports.add(p);
            return this;
        }

        public Builder service(String s, String p) {
            Set<String> ps = services.get(s);
            if (ps == null) {
                ps = new HashSet<>();
                services.put(s, ps);
            }
            ps.add(p);
            return this;
        }

        public Builder mainClass(String c) {
            if (mainClass != null)
                throw new IllegalStateException("mainClass");
            mainClass = c;
            return this;
        }

        // Returns a View whose module is not yet set
        //
        public View build() {
            return new View(id, aliases, permits,
                            exports, services, mainClass);
        }

    }

    @Override
    public int compareTo(View that) {
        return this.id().compareTo(that.id());
    }

    @Override
    public boolean equals(Object ob) {
        if (!(ob instanceof View))
            return false;
        View that = (View)ob;
        return (module.id().equals(that.module.id())
                && id.equals(that.id)
                && aliases.equals(that.aliases)
                && permits.equals(that.permits)
                && exports.equals(that.exports)
                && services.equals(that.services)
                && Objects.equals(mainClass, that.mainClass));
    }

    @Override
    public int hashCode() {
        int hc = id.hashCode();
        hc = hc * 43 + aliases.hashCode();
        hc = hc * 43 + permits.hashCode();
        hc = hc * 43 + exports.hashCode();
        hc = hc * 43 + services.hashCode();
        hc = hc * 43 + Objects.hashCode(mainClass);
        return hc;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("View { id: ").append(id);
        if (!aliases.isEmpty()) sb.append(", aliases: ").append(aliases);
        if (!permits.isEmpty()) sb.append(", permits: ").append(permits);
        if (!exports.isEmpty()) sb.append(", exports: ").append(exports);
        if (!services.isEmpty()) sb.append(", services: ").append(services);
        if (mainClass != null) sb.append(", mainClass: ").append(mainClass);
        if (module != null) sb.append(", module: ").append(module.id());
        sb.append(" }");
        return sb.toString();
    }

}
