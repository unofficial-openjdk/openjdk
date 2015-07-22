/*
 * Copyright (c) 2008, Oracle and/or its affiliates. All rights reserved.
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

import java.util.Properties;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedAction;

/**
 * Provides access to the value of properties in the test environment.
 * Properties are typically host names used by the networking or NIO tests.
 *
 * If the environment variable JTREG_TESTENV is set then its value is taken
 * to be the path to a java properties file defining some or all of the
 * properties required by the tests. Environment variables are passed to
 * jtreg with the -e option, eg:
 *   jtreg -e JTREG_TESTENV=/config/testenv.properties ...
 *
 * Where the environment variable is not set or the properties file doesn't
 * define all the required properties then the properties are loaded from the
 * properties file ${user.home}/.jtreg.testenv where ${user.home} is the value
 * of the system property "user.home".
 *
 * Finally, this class hard-codes a number of properties for when the properties
 * are not defined in the user's home directory or by the environment variable.
 *
 * Note to test developers: You can invoke this class from shell scripts to
 * get the value of properties using the -get option, eg:
 *     VALUE=`java TestEnv -get host`
 * will set VALUE to the value of the "host" property.
 */

public class TestEnv {
    // environment variable to configure location of properties file
    private static final String CONFIG_PROPERTY = "JTREG_TESTENV";

    // properties file in home directory
    private static final String RC_FILE = ".jtreg.testenv";

    // hard-coded defaults
    private static final String defaultProps[][] = {

        // Reachable host with the following services running:
        // - echo service (port 7)
        // - day time port (port 13)
        { "host", "javaweb.sfbay.sun.com"  },

        // Reachable host that refuses connections to port 80
        { "refusing_host", "jano1.sfbay.sun.com" },

        // Reachable host that is of sufficient hops away that a connection
        // takes a while to be established (connect doesn't complete immediatly)
        { "far_host", "irejano.ireland.sun.com" },

        // Hostname that cannot be resolved by named service
        { "unresovable_host", "blah-blah.blah-blah.blah" },
    };

    private static Properties props = loadProperties();

    /**
     * Returns the value of a property in the test environment or {@code null}
     * if the property is not defined.
     */
    public static String getProperty(String key) {
        return props.getProperty(key);
    }

    /**
     * Prints the value of a property, or "unknown" to standard output
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            props.list(System.out);
            System.exit(0);
        }
        if (args.length < 2 || !args[0].equals("-get")) {
            System.err.println("Usage: java TestEnv [-get prop]");
            System.exit(-1);
        }
        String value = props.getProperty(args[1]);
        if (value == null)
            value = "unknown";
        System.out.println(value);
    }

    /**
     * Loads properties. The properties are loaded in the following order:
     *
     * 1. Default (hard-coded) properties
     * 2. Properties file in home directory (overrides defaults)
     * 3. Properties file configured by environment variable (overrides 1 & 2)
     */
    private static Properties loadProperties() {
        // default properties
        final Properties p = new Properties();
        for (int i=0; i<defaultProps.length; i++) {
            p.put(defaultProps[i][0], defaultProps[i][1]);
        }

        AccessController.doPrivileged(new PrivilegedAction<Void>() {
            public Void run() {
                // load from ~/<config-file>
                String rcfile = System.getProperty("user.home") +
                    File.separator + RC_FILE;
                loadPropertiesFromFile(rcfile, p);


                // load from file set by environment variable
                String config = System.getenv(CONFIG_PROPERTY);
                if (config != null) {
                    loadPropertiesFromFile(config, p);
                }
                return null;
            }
        });

        return p;
    }

    private static void loadPropertiesFromFile(String file, Properties p) {
        try {
            FileReader reader = new FileReader(file);
            try {
                p.load(reader);
            } finally {
                reader.close();
            }
        } catch (IOException ignore) { }
    }
}
