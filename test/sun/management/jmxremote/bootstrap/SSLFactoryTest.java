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
 * @bug 6557093
 * @summary Test SSLContextRMIServerSocketFactory equals() and hashCode().
 * @author Luis-Miguel Alventosa
 * @run clean SSLFactoryTest
 * @run build SSLFactoryTest
 * @run main SSLFactoryTest
 */

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.net.ssl.SSLContext;
import sun.management.jmxremote.SSLContextRMIServerSocketFactory;

public class SSLFactoryTest {
    
    public static final String[] ciphersuite =
            new String[] { "SSL_RSA_WITH_NULL_MD5" };
    
    public static final String[] protocol =
            new String[] { "TLSv1" };
    
    public static class MySSLContextRMIServerSocketFactory
            extends SSLContextRMIServerSocketFactory {
        public MySSLContextRMIServerSocketFactory(SSLContext context) {
            super(context);
        }
        public MySSLContextRMIServerSocketFactory(
                SSLContext context,
                String[] ciphers,
                String[] protocols,
                boolean need) {
            super(context, ciphers, protocols, need);
        }
    }
    
    public static Object serializeAndClone(Object o) throws Exception {
        System.out.println("Serializing object: " + o);
        final ByteArrayOutputStream obytes =
                new ByteArrayOutputStream();
        final ObjectOutputStream ostr =
                new ObjectOutputStream(obytes);
        ostr.writeObject(o);
        ostr.flush();
        
        System.out.println("Deserializing object");
        final ByteArrayInputStream ibytes =
                new ByteArrayInputStream(obytes.toByteArray());
        final ObjectInputStream istr =
                new ObjectInputStream(ibytes);
        return istr.readObject();
    }
    
    public static void testEquals(Object a, Object b, boolean expected) {
        final boolean found = a.equals(b);
        if (found != expected)
            throw new RuntimeException("testEquals failed: objects are " +
                    ((found)?"equals":"not equals"));
        if (found && a.hashCode()!=b.hashCode())
            throw new RuntimeException("testEquals failed: objects are " +
                    "equals but their hashcode differ");
    }
    
    public static void main(String[] args) {
        try {
            System.out.println("SocketFactoryTest START.");
            
            SSLContext context = SSLContext.getInstance("SSL");
            context.init(null, null, null);
            
            final SSLContextRMIServerSocketFactory server1 =
                    new SSLContextRMIServerSocketFactory(null);
            final SSLContextRMIServerSocketFactory server2 =
                    new SSLContextRMIServerSocketFactory(null, null, null, false);
            final SSLContextRMIServerSocketFactory server3 =
                    new SSLContextRMIServerSocketFactory(null, ciphersuite, null, false);
            final SSLContextRMIServerSocketFactory server4 =
                    new SSLContextRMIServerSocketFactory(null, null, protocol, false);
            final SSLContextRMIServerSocketFactory server5 =
                    new SSLContextRMIServerSocketFactory(null, null, null, true);
            final SSLContextRMIServerSocketFactory server6 =
                    new SSLContextRMIServerSocketFactory(context);
            final SSLContextRMIServerSocketFactory server7 =
                    new SSLContextRMIServerSocketFactory(context, null, null, false);
            final SSLContextRMIServerSocketFactory server8 =
                    new SSLContextRMIServerSocketFactory(context, ciphersuite, null, false);
            final SSLContextRMIServerSocketFactory server9 =
                    new SSLContextRMIServerSocketFactory(context, null, protocol, false);
            final SSLContextRMIServerSocketFactory server10 =
                    new SSLContextRMIServerSocketFactory(context, null, null, true);
            final MySSLContextRMIServerSocketFactory subserver1 =
                    new MySSLContextRMIServerSocketFactory(null);
            final MySSLContextRMIServerSocketFactory subserver2 =
                    new MySSLContextRMIServerSocketFactory(null, null, null, false);
            final MySSLContextRMIServerSocketFactory subserver3 =
                    new MySSLContextRMIServerSocketFactory(null, ciphersuite, null, false);
            final MySSLContextRMIServerSocketFactory subserver4 =
                    new MySSLContextRMIServerSocketFactory(null, null, protocol, false);
            final MySSLContextRMIServerSocketFactory subserver5 =
                    new MySSLContextRMIServerSocketFactory(null, null, null, true);
            final MySSLContextRMIServerSocketFactory subserver6 =
                    new MySSLContextRMIServerSocketFactory(context);
            final MySSLContextRMIServerSocketFactory subserver7 =
                    new MySSLContextRMIServerSocketFactory(context, null, null, false);
            final MySSLContextRMIServerSocketFactory subserver8 =
                    new MySSLContextRMIServerSocketFactory(context, ciphersuite, null, false);
            final MySSLContextRMIServerSocketFactory subserver9 =
                    new MySSLContextRMIServerSocketFactory(context, null, protocol, false);
            final MySSLContextRMIServerSocketFactory subserver10 =
                    new MySSLContextRMIServerSocketFactory(context, null, null, true);
            
            // servers
            System.out.println("testEquals(server1,server1,true)");
            testEquals(server1,server1,true);
            System.out.println("testEquals(server2,server2,true)");
            testEquals(server2,server2,true);
            System.out.println("testEquals(server3,server3,true)");
            testEquals(server3,server3,true);
            System.out.println("testEquals(server4,server4,true)");
            testEquals(server4,server4,true);
            System.out.println("testEquals(server5,server5,true)");
            testEquals(server5,server5,true);
            System.out.println("testEquals(server6,server6,true)");
            testEquals(server6,server6,true);
            System.out.println("testEquals(server7,server7,true)");
            testEquals(server7,server7,true);
            System.out.println("testEquals(server8,server8,true)");
            testEquals(server8,server8,true);
            System.out.println("testEquals(server9,server9,true)");
            testEquals(server9,server9,true);
            System.out.println("testEquals(server10,server10,true)");
            testEquals(server10,server10,true);
            
            System.out.println("testEquals(server1,server2,true)");
            testEquals(server1,server2,true);
            System.out.println("testEquals(server1,server3,false)");
            testEquals(server1,server3,false);
            System.out.println("testEquals(server2,server3,false)");
            testEquals(server2,server3,false);
            System.out.println("testEquals(server3,server4,false)");
            testEquals(server3,server4,false);
            System.out.println("testEquals(server4,server5,false)");
            testEquals(server4,server5,false);
            System.out.println("testEquals(server6,server7,true)");
            testEquals(server6,server7,true);
            System.out.println("testEquals(server6,server8,false)");
            testEquals(server6,server8,false);
            System.out.println("testEquals(server7,server8,false)");
            testEquals(server7,server8,false);
            System.out.println("testEquals(server8,server9,false)");
            testEquals(server8,server9,false);
            System.out.println("testEquals(server9,server10,false)");
            testEquals(server9,server10,false);
            
            System.out.println("testEquals(server1,server6,false)");
            testEquals(server1,server6,false);
            System.out.println("testEquals(server2,server7,false)");
            testEquals(server2,server7,false);
            System.out.println("testEquals(server3,server8,false)");
            testEquals(server3,server8,false);
            System.out.println("testEquals(server4,server9,false)");
            testEquals(server4,server9,false);
            System.out.println("testEquals(server5,server10,false)");
            testEquals(server5,server10,false);
            
            System.out.println("testEquals(server1,null,false)");
            testEquals(server1,null,false);
            System.out.println("testEquals(server2,null,false)");
            testEquals(server2,null,false);
            System.out.println("testEquals(server3,null,false)");
            testEquals(server3,null,false);
            System.out.println("testEquals(server1,new Object(),false)");
            testEquals(server1,new Object(),false);
            
            // server subclass
            System.out.println("testEquals(subserver1,subserver1,true)");
            testEquals(subserver1,subserver1,true);
            System.out.println("testEquals(subserver2,subserver2,true)");
            testEquals(subserver2,subserver2,true);
            System.out.println("testEquals(subserver3,subserver3,true)");
            testEquals(subserver3,subserver3,true);
            System.out.println("testEquals(subserver4,subserver4,true)");
            testEquals(subserver4,subserver4,true);
            System.out.println("testEquals(subserver5,subserver5,true)");
            testEquals(subserver5,subserver5,true);
            System.out.println("testEquals(subserver6,subserver6,true)");
            testEquals(subserver6,subserver6,true);
            System.out.println("testEquals(subserver7,subserver7,true)");
            testEquals(subserver7,subserver7,true);
            System.out.println("testEquals(subserver8,subserver8,true)");
            testEquals(subserver8,subserver8,true);
            System.out.println("testEquals(subserver9,subserver9,true)");
            testEquals(subserver9,subserver9,true);
            System.out.println("testEquals(subserver10,subserver10,true)");
            testEquals(subserver10,subserver10,true);
            
            System.out.println("testEquals(subserver1,subserver2,true)");
            testEquals(subserver1,subserver2,true);
            System.out.println("testEquals(subserver1,subserver3,false)");
            testEquals(subserver1,subserver3,false);
            System.out.println("testEquals(subserver2,subserver3,false)");
            testEquals(subserver2,subserver3,false);
            System.out.println("testEquals(subserver3,subserver4,false)");
            testEquals(subserver3,subserver4,false);
            System.out.println("testEquals(subserver4,subserver5,false)");
            testEquals(subserver4,subserver5,false);
            System.out.println("testEquals(subserver6,subserver7,true)");
            testEquals(subserver6,subserver7,true);
            System.out.println("testEquals(subserver6,subserver8,false)");
            testEquals(subserver6,subserver8,false);
            System.out.println("testEquals(subserver7,subserver8,false)");
            testEquals(subserver7,subserver8,false);
            System.out.println("testEquals(subserver8,subserver9,false)");
            testEquals(subserver8,subserver9,false);
            System.out.println("testEquals(subserver9,subserver10,false)");
            testEquals(subserver9,subserver10,false);
            
            System.out.println("testEquals(subserver1,subserver6,false)");
            testEquals(subserver1,subserver6,false);
            System.out.println("testEquals(subserver2,subserver7,false)");
            testEquals(subserver2,subserver7,false);
            System.out.println("testEquals(subserver3,subserver8,false)");
            testEquals(subserver3,subserver8,false);
            System.out.println("testEquals(subserver4,subserver9,false)");
            testEquals(subserver4,subserver9,false);
            System.out.println("testEquals(subserver5,subserver10,false)");
            testEquals(subserver5,subserver10,false);
            
            System.out.println("testEquals(subserver1,server1,false)");
            testEquals(subserver1,server1,false);
            System.out.println("testEquals(server1,subserver1,false)");
            testEquals(server1,subserver1,false);
            System.out.println("testEquals(subserver2,server2,false)");
            testEquals(subserver2,server2,false);
            System.out.println("testEquals(server2,subserver2,false)");
            testEquals(server2,subserver2,false);
            System.out.println("testEquals(subserver3,server3,false)");
            testEquals(subserver3,server3,false);
            System.out.println("testEquals(server3,subserver3,false)");
            testEquals(server3,subserver3,false);
            System.out.println("testEquals(subserver4,server4,false)");
            testEquals(subserver4,server4,false);
            System.out.println("testEquals(server4,subserver4,false)");
            testEquals(server4,subserver4,false);
            System.out.println("testEquals(subserver5,server5,false)");
            testEquals(subserver5,server5,false);
            System.out.println("testEquals(server5,subserver5,false)");
            testEquals(server5,subserver5,false);
            System.out.println("testEquals(subserver6,server6,false)");
            testEquals(subserver6,server6,false);
            System.out.println("testEquals(server6,subserver6,false)");
            testEquals(server6,subserver6,false);
            System.out.println("testEquals(subserver7,server7,false)");
            testEquals(subserver7,server7,false);
            System.out.println("testEquals(server7,subserver7,false)");
            testEquals(server7,subserver7,false);
            System.out.println("testEquals(subserver8,server8,false)");
            testEquals(subserver8,server8,false);
            System.out.println("testEquals(server8,subserver8,false)");
            testEquals(server8,subserver8,false);
            System.out.println("testEquals(subserver9,server9,false)");
            testEquals(subserver9,server9,false);
            System.out.println("testEquals(server9,subserver9,false)");
            testEquals(server9,subserver9,false);
            System.out.println("testEquals(subserver10,server10,false)");
            testEquals(subserver10,server10,false);
            System.out.println("testEquals(server10,subserver10,false)");
            testEquals(server10,subserver10,false);
            
            System.out.println("testEquals(subserver1,null,false)");
            testEquals(subserver1,null,false);
            System.out.println("testEquals(subserver1,new Object(),false)");
            testEquals(subserver1,new Object(),false);
            
            System.out.println("SocketFactoryTest PASSED.");
        } catch (Exception x) {
            System.out.println("SocketFactoryTest FAILED: " + x);
            x.printStackTrace();
            System.exit(1);
        }
    }
}
