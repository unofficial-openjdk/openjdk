/*
 * Copyright (c) 2009, 2013, Oracle and/or its affiliates. All rights reserved.
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
 * <p> A module definition </p>
 */

@SuppressWarnings("serial")             // serialVersionUID intentionally omitted
public final class Module
    implements Comparable<Module>, Serializable
{

    private final View mainView;
    private final Set<View> views;
    private final Set<ViewDependence> viewDependences;
    private final Set<ServiceDependence> serviceDependences;
    private final Set<String> packages;

    // Every exported package must be included in this module
    //
    private void checkExportedPackages(Set<String> packages) {
        for (View v : views) {
            for (String p : v.exports()) {
                if (!packages.contains(p)) {
                    Set<String> ps = new HashSet<>(v.exports());
                    ps.removeAll(packages);
                    String msg = String.format("Package%s %s exported by view %s"
                                               + " but not included in module %s",
                                               ps.size() > 1 ? "s" : "", ps,
                                               v.id(), this.id());
                    throw new IllegalArgumentException(msg);
                }
            }
        }
    }

    private Module(View mainView,
                   Set<View> views,
                   Set<ViewDependence> viewDeps,
                   Set<ServiceDependence> serviceDeps,
                   Set<String> packages)
    {
        this.mainView = requireNonNull(mainView);
        if (!views.contains(mainView))
            throw new IllegalArgumentException("views");
        this.views = Collections.unmodifiableSet(views);
        this.viewDependences = Collections.unmodifiableSet(viewDeps);
        this.serviceDependences = Collections.unmodifiableSet(serviceDeps);
        checkExportedPackages(packages);
        this.packages = Collections.unmodifiableSet(packages);
    }

    /**
     * This module's identifier
     */
    public ViewId id() {
        return mainView.id();
    }

    /**
     * The names of the packages included in this module, not all of which are
     * necessarily {@linkplain View#exports() exported}.
     *
     * @return  A possibly-empty unmodifiable set of package names
     */
    public Set<String> packages() {
        return packages;
    }

    /**
     * <p> The view dependences of this module </p>
     *
     * @return  A possibly-empty unmodifiable set of {@link ViewDependence}s
     */
    public Set<ViewDependence> viewDependences() {
        return viewDependences;
    }

    /**
     * <p> The service dependences of this module </p>
     *
     * @return  A possibly-empty unmodifiable set of
     *          {@link ServiceDependence}s
     */
    public Set<ServiceDependence> serviceDependences() {
        return serviceDependences;
    }

    /**
     * <p> The main view of this module.</p>
     *
     * <p> Each module has a main view whose {@linkplain View#id() identifier}
     * is taken also to be the identifier of the module itself. </p>
     *
     * @return  A {@link View}
     */
    public View mainView() {
        return mainView;
    }

    /**
     * <p> The views of this module.</p>
     *
     * @return  An unmodifiable set of {@link View Views}
     *          which includes the {@linkplain #mainView() main view}
     */
    public Set<View> views() {
        return views;
    }

    public final static class Builder {

        private View mainView;
        private final Set<View> views = new HashSet<>();
        private final Set<ViewDependence> viewDeps = new HashSet<>();
        private final Set<ServiceDependence> serviceDeps = new HashSet<>();
        private final Set<String> packages = new HashSet<>();

        public Builder() { }

        public Builder main(View v) {
            if (mainView != null)
                throw new IllegalStateException("mainView");
            mainView = v;
            views.add(v);
            return this;
        }

        public Builder view(View v) {
            views.add(v);
            return this;
        }

        public Builder include(String p) {
            packages.add(p);
            return this;
        }

        public Builder requires(ViewDependence vd) {
            viewDeps.add(requireNonNull(vd));
            return this;
        }

        public Builder requires(ServiceDependence sd) {
            serviceDeps.add(requireNonNull(sd));
            return this;
        }

        public Module build() {
            Module m = new Module(mainView, views,
                                  viewDeps, serviceDeps, packages);
            views.forEach(v -> { v.module(m); });
            return m;
        }

    }

    @Override
    public int compareTo(Module that) {
        return this.mainView.compareTo(that.mainView);
    }

    @Override
    public boolean equals(Object ob) {
        if (!(ob instanceof Module))
            return false;
        Module that = (Module)ob;
        return (mainView.equals(that.mainView)
                && views.equals(that.views)
                && viewDependences.equals(that.viewDependences)
                && serviceDependences.equals(that.serviceDependences)
                && packages.equals(that.packages));
    }

    @Override
    public int hashCode() {
        int hc = mainView.hashCode();
        hc = hc * 43 + views.hashCode();
        hc = hc * 43 + viewDependences.hashCode();
        hc = hc * 43 + serviceDependences.hashCode();
        hc = hc * 43 + packages.hashCode();
        return hc;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Module { id: ").append(id());
        if (!views.isEmpty()) sb.append(", views: ").append(views);
        if (!viewDependences.isEmpty())
            sb.append(", viewDeps: ").append(viewDependences);
        if (!serviceDependences.isEmpty())
            sb.append(", svcDeps: ").append(serviceDependences);
        if (!packages.isEmpty())
            sb.append(", packages: ").append(packages);
        sb.append(" }");
        return sb.toString();
    }

}
