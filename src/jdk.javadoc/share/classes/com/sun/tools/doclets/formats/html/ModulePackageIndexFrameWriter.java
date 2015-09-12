/*
 * Copyright (c) 2013, 2015, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.tools.doclets.formats.html;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sun.javadoc.*;
import com.sun.tools.doclets.formats.html.markup.*;
import com.sun.tools.doclets.internal.toolkit.*;
import com.sun.tools.doclets.internal.toolkit.util.*;

/**
 * Generate the module package index for the left-hand frame in the generated output.
 * A click on the package name in this frame will update the page in the bottom
 * left hand frame with the listing of contents of the clicked module package.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 *
 * @author Bhavesh Patel
 */
public class ModulePackageIndexFrameWriter extends AbstractModuleIndexWriter {

    /**
     * Construct the ModulePackageIndexFrameWriter object.
     *
     * @param configuration the configuration object
     * @param filename Name of the package index file to be generated.
     */
    public ModulePackageIndexFrameWriter(ConfigurationImpl configuration,
                                   DocPath filename) throws IOException {
        super(configuration, filename);
    }

    /**
     * Generate the module package index file.
     * @throws DocletAbortException
     * @param configuration the configuration object
     * @param moduleName the name of the module being documented
     */
    public static void generate(ConfigurationImpl configuration, String moduleName) {
        ModulePackageIndexFrameWriter modpackgen;
        DocPath filename = DocPaths.moduleFrame(moduleName);
        try {
            modpackgen = new ModulePackageIndexFrameWriter(configuration, filename);
            modpackgen.buildModulePackagesIndexFile("doclet.Window_Overview", false, moduleName);
            modpackgen.close();
        } catch (IOException exc) {
            configuration.standardmessage.error(
                        "doclet.exception_encountered",
                        exc.toString(), filename);
            throw new DocletAbortException(exc);
        }
    }

    /**
     * {@inheritDoc}
     */
    protected void addModulePackagesList(Map<String, Set<PackageDoc>> modules, String text,
            String tableSummary, Content body, String moduleName) {
        Content profNameContent = new StringContent(moduleName);
        Content heading = HtmlTree.HEADING(HtmlConstants.PACKAGE_HEADING, true,
                getTargetModuleLink("classFrame", profNameContent, moduleName));
        heading.addContent(getSpace());
        heading.addContent(packagesLabel);
        HtmlTree htmlTree = (configuration.allowTag(HtmlTag.MAIN))
                ? HtmlTree.MAIN(HtmlStyle.indexContainer, heading)
                : HtmlTree.DIV(HtmlStyle.indexContainer, heading);
        HtmlTree ul = new HtmlTree(HtmlTag.UL);
        ul.setTitle(packagesLabel);
        List<PackageDoc> packages = new ArrayList<>(modules.get(moduleName));
        for (PackageDoc packageDoc : packages) {
            if ((!(configuration.nodeprecated && utils.isDeprecated(packageDoc)))) {
                ul.addContent(getPackage(packageDoc, moduleName));
            }
        }
        htmlTree.addContent(ul);
        body.addContent(htmlTree);
    }

    /**
     * {@inheritDoc}
     */
    protected void addModulePackagesList(Set<String> modules, String text,
            String tableSummary, Content body, String moduleName) {
        Content moduleNameContent = new StringContent(moduleName);
        Content heading = HtmlTree.HEADING(HtmlConstants.PACKAGE_HEADING, true,
                getTargetModuleLink("classFrame", moduleNameContent, moduleName));
        heading.addContent(getSpace());
        heading.addContent(packagesLabel);
        HtmlTree htmlTree = (configuration.allowTag(HtmlTag.MAIN))
                ? HtmlTree.MAIN(HtmlStyle.indexContainer, heading)
                : HtmlTree.DIV(HtmlStyle.indexContainer, heading);
        HtmlTree ul = new HtmlTree(HtmlTag.UL);
        ul.setTitle(packagesLabel);
        Set<PackageDoc> modulePackages = configuration.modulePackages.get(moduleName);
        for (PackageDoc packageDoc: modulePackages) {
            if ((!(configuration.nodeprecated && utils.isDeprecated(packageDoc)))) {
                ul.addContent(getPackage(packageDoc, moduleName));
            }
        }
        htmlTree.addContent(ul);
        body.addContent(htmlTree);
    }

    /**
     * Returns each package name as a separate link.
     *
     * @param pd PackageDoc
     * @param moduleName the name of the module being documented
     * @return content for the package link
     */
    protected Content getPackage(PackageDoc pd, String moduleName) {
        Content packageLinkContent;
        Content pkgLabel;
        if (pd.name().length() > 0) {
            pkgLabel = getPackageLabel(pd.name());
            packageLinkContent = getHyperLink(pathString(pd,
                     DocPaths.PACKAGE_FRAME), pkgLabel, "",
                    "packageFrame");
        } else {
            pkgLabel = new StringContent("<unnamed package>");
            packageLinkContent = getHyperLink(DocPaths.PACKAGE_FRAME,
                    pkgLabel, "", "packageFrame");
        }
        Content li = HtmlTree.LI(packageLinkContent);
        return li;
    }

    /**
     * {@inheritDoc}
     */
    protected void addNavigationBarHeader(Content body) {
        Content headerContent;
        if (configuration.packagesheader.length() > 0) {
            headerContent = new RawHtml(replaceDocRootDir(configuration.packagesheader));
        } else {
            headerContent = new RawHtml(replaceDocRootDir(configuration.header));
        }
        Content heading = HtmlTree.HEADING(HtmlConstants.TITLE_HEADING, true,
                HtmlStyle.bar, headerContent);
        body.addContent(heading);
    }

    /**
     * Do nothing as there is no overview information in this page.
     */
    protected void addOverviewHeader(Content body) {
    }

    protected void addModulesList(Map<String,Set<PackageDoc>> modules, String text,
            String tableSummary, Content body) {
    }

    /**
     * Adds "All Classes" link for the top of the left-hand frame page to the
     * documentation tree.
     *
     * @param ul the Content object to which the all classes link should be added
     */
    protected void addAllClassesLink(Content ul) {
        Content linkContent = getHyperLink(DocPaths.ALLCLASSES_FRAME,
                allclassesLabel, "", "packageFrame");
        Content li = HtmlTree.LI(linkContent);
        ul.addContent(li);
    }

    /**
     * Adds "All Packages" link for the top of the left-hand frame page to the
     * documentation tree.
     *
     * @param ul the Content object to which the all packages link should be added
     */
    protected void addAllPackagesLink(Content ul) {
        Content linkContent = getHyperLink(DocPaths.OVERVIEW_FRAME,
                allpackagesLabel, "", "packageListFrame");
        Content li = HtmlTree.LI(linkContent);
        ul.addContent(li);
    }

    /**
     * Adds "All Modules" link for the top of the left-hand frame page to the
     * documentation tree.
     *
     * @param ul the Content object to which the all modules link should be added
     */
    protected void addAllModulesLink(Content ul) {
        Content linkContent = getHyperLink(DocPaths.MODULE_OVERVIEW_FRAME,
                allmodulesLabel, "", "packageListFrame");
        Content li = HtmlTree.LI(linkContent);
        ul.addContent(li);
    }

    /**
     * {@inheritDoc}
     */
    protected void addNavigationBarFooter(Content body) {
        Content p = HtmlTree.P(getSpace());
        body.addContent(p);
    }
}
