/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.lang.module.ModuleDescriptor;
import static java.lang.module.ModuleDescriptor.Builder;

/**
 * Jigsaw utility methods are part of this class. It exposes methods to generate
 * module descriptor object and create service descriptor inside META-INF folder
 * etc.
 */
public class JigsawSecurityUtils {

    /**
     * Enum represents all supported module types in JDK9.
     */
    public enum MODULE_TYPE {

        EXPLICIT, AUTO, UNNAMED;
    }

    public static final String SPACE = " ";

    /**
     * Constructs a Java Command line string based on modular structure followed
     * by modular client and service.
     */
    public static String[] getJavaCommand(Path modulePath,
            StringBuilder classPath, String clientModuleName,
            String mainClass, Map<String, String> vmArgs) throws IOException {

        final StringBuilder command = new StringBuilder();
        vmArgs.forEach((key, value) -> command.append(key + value + SPACE));
        if (modulePath != null) {
            command.append("-mp" + SPACE + modulePath.toRealPath() + SPACE);
        }
        if (classPath != null && classPath.length() > 0) {
            command.append("-cp" + SPACE + classPath + SPACE);
        }
        if (clientModuleName != null && clientModuleName.length() > 0) {
            command.append("-m" + SPACE + clientModuleName + "/");
        }
        command.append(mainClass);
        return command.toString().trim().split("[\\s]+");
    }

    /**
     * Generate ModuleDescriptor object for explicit/auto based client/service
     * modules.
     */
    public static ModuleDescriptor generateModuleDescriptor(
            boolean service, MODULE_TYPE moduleType, String moduleName,
            String pkg, String serviceInterface,
            String serviceImpl, String serviceModuleName,
            List<String> requiredModules, boolean depends) {
        final Builder builder;
        if (moduleType == MODULE_TYPE.EXPLICIT) {
            System.out.println("Generating ModuleDescriptor object");
            builder = new Builder(moduleName).exports(pkg);
            if (service) {
                builder.provides(serviceInterface, serviceImpl);
            } else {
                builder.uses(serviceInterface);
                if (depends) {
                    builder.requires(serviceModuleName);
                }
            }
        } else {
            System.out.println("ModuleDescriptor object not required.");
            return null;
        }
        requiredModules.stream().forEach(reqMod -> builder.requires(reqMod));

        return builder.build();
    }

    /**
     * Generates service descriptor inside META-INF folder.
     */
    public static boolean createMetaInfServiceDescriptor(
            Path serviceDescriptorFile, String serviceImpl) {
        boolean created = true;
        System.out.println(String.format("Creating META-INF service descriptor"
                + " for '%s' at path '%s'", serviceImpl,
                serviceDescriptorFile));
        try {
            Path parent = serviceDescriptorFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(serviceDescriptorFile, serviceImpl.getBytes("UTF-8"));
            System.out.println(String.format(
                    "META-INF service descriptor generated successfully"));
        } catch (IOException e) {
            e.printStackTrace(System.out);
            created = false;
        }
        return created;
    }

}
