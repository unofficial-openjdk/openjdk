/*
 * Copyright (c) 2007, Oracle and/or its affiliates. All rights reserved.
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

/*
 * @test
 * @bug 6685178
 * @summary Sanity check for local only option. In order to fully test this
 *          new local only option two different machines would be required.
 * @author Luis-Miguel Alventosa
 * @run main/othervm LocalOnlyTest
 * @run main/othervm -Dcom.sun.management.jmxremote.local.only=true LocalOnlyTest
 * @run main/othervm -Dcom.sun.management.jmxremote.local.only=false LocalOnlyTest
 * @run main/othervm -Dcom.sun.management.jmxremote LocalOnlyTest
 * @run main/othervm -Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.local.only=true LocalOnlyTest
 * @run main/othervm -Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.local.only=false LocalOnlyTest
 * @run main/othervm -Dcom.sun.management.jmxremote.port=0 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false LocalOnlyTest
 * @run main/othervm -Dcom.sun.management.jmxremote.port=0 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false -Dcom.sun.management.jmxremote.local.only=true LocalOnlyTest
 * @run main/othervm -Dcom.sun.management.jmxremote.port=0 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false -Dcom.sun.management.jmxremote.local.only=false LocalOnlyTest
 */

import java.io.*;
import java.lang.management.*;
import java.util.*;
import javax.management.*;
import javax.management.remote.*;
import com.sun.tools.attach.*;

public class LocalOnlyTest {

    public static void main(String args[]) throws Exception {
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        String name = rt.getName();
        System.out.println("name = " + name);
        String vmid = name.substring(0, name.indexOf("@"));
        System.out.println("vmid = " + vmid);
        VirtualMachine vm = VirtualMachine.attach(vmid);
        String addr = vm.getAgentProperties().getProperty(
                "com.sun.management.jmxremote.localConnectorAddress");
        System.out.println("connectorAddress = " + addr);
        if (addr == null) {
            // Normally in ${java.home}/jre/lib/management-agent.jar
            // but might be in ${java.home}/lib in build environments.
            String javaHome = System.getProperty("java.home");
            String agent = javaHome + File.separator + "jre" + File.separator +
                    "lib" + File.separator + "management-agent.jar";
            File f = new File(agent);
            if (!f.exists()) {
                agent = javaHome + File.separator + "lib" + File.separator +
                        "management-agent.jar";
                f = new File(agent);
                if (!f.exists()) {
                    throw new IOException("Management agent not found");
                }
            }
            agent = f.getCanonicalPath();
            try {
                vm.loadAgent(agent, "com.sun.management.jmxremote");
            } catch (AgentLoadException x) {
                IOException ioe = new IOException(x.getMessage());
                ioe.initCause(x);
                throw ioe;
            } catch (AgentInitializationException x) {
                IOException ioe = new IOException(x.getMessage());
                ioe.initCause(x);
                throw ioe;
            }
            addr = vm.getAgentProperties().getProperty(
                    "com.sun.management.jmxremote.localConnectorAddress");
            System.out.println("connectorAddress (after loading agent) = " + addr);
        }
        vm.detach();
        JMXServiceURL url = new JMXServiceURL(addr);
        JMXConnector c = JMXConnectorFactory.connect(url);
        System.out.println("connectionId  = " + c.getConnectionId());
        System.out.println("Bye! Bye!");
    }
}
