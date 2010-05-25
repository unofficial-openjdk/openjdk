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
 * @summary Test SSL parameters in new SSLContextRMIServerSocketFactory.
 * @author Luis-Miguel Alventosa
 * @run main/othervm SSLParametersTest 1
 * @run main/othervm SSLParametersTest 2
 * @run main/othervm SSLParametersTest 3
 * @run main/othervm SSLParametersTest 4
 * @run main/othervm SSLParametersTest 5
 * @run main/othervm SSLParametersTest 6
 * @run main/othervm SSLParametersTest 7
 */

import java.io.*;
import java.net.*;
import java.rmi.*;
import java.rmi.server.*;
import java.security.*;
import javax.net.ssl.*;
import javax.rmi.ssl.*;
import sun.management.jmxremote.SSLContextRMIServerSocketFactory;

public class SSLParametersTest implements Serializable {
    
    public interface Hello extends Remote {
        public String sayHello() throws RemoteException;
    }
    
    public class HelloImpl extends UnicastRemoteObject implements Hello {
        
        public HelloImpl(int port,
                RMIClientSocketFactory csf,
                RMIServerSocketFactory ssf)
                throws RemoteException {
            super(port, csf, ssf);
        }
        
        public String sayHello() {
            return "Hello World!";
        }
        
        public Remote runServer() throws IOException {
            System.out.println("Inside HelloImpl::runServer");
            // Get a remote stub for this RMI object
            //
            Remote stub = toStub(this);
            System.out.println("Stub = " + stub);
            return stub;
        }
    }
    
    public class HelloClient {
        
        public void runClient(Remote stub) throws IOException {
            System.out.println("Inside HelloClient::runClient");
            // "obj" is the identifier that we'll use to refer
            // to the remote object that implements the "Hello"
            // interface
            Hello obj = (Hello) stub;
            String message = obj.sayHello();
            System.out.println(message);
        }
    }
    
    public class ClientFactory extends SslRMIClientSocketFactory {
        
        public ClientFactory() {
            super();
        }
        
        public Socket createSocket(String host, int port) throws IOException {
            System.out.println("ClientFactory::Calling createSocket(" +
                    host + "," + port + ")");
            return super.createSocket(host, port);
        }
    }
    
    public class ServerFactory extends SSLContextRMIServerSocketFactory {
        
        public ServerFactory(SSLContext context) {
            super(context);
        }
        
        public ServerFactory(
                SSLContext context,
                String[] ciphers,
                String[] protocols,
                boolean need) {
            super(context, ciphers, protocols, need);
        }
        
        public ServerSocket createServerSocket(int port) throws IOException {
            System.out.println("ServerFactory::Calling createServerSocket(" +
                    port + ")");
            return super.createServerSocket(port);
        }
    }
    
    public int runTest(SSLContext ctx, String[] args) {
        
        int test = Integer.parseInt(args[0]);
        
        String msg1 = "Running SSLParametersTest [" + test + "]";
        String msg2 = "SSLParametersTest [" + test + "] PASSED!";
        String msg3 = "SSLParametersTest [" + test + "] FAILED!";
        
        switch (test) {
            case 1: /* default constructor - default config */
                System.out.println(msg1);
                try {
                    HelloImpl server = new HelloImpl(
                            0,
                            new ClientFactory(),
                            new ServerFactory(ctx));
                    Remote stub = server.runServer();
                    HelloClient client = new HelloClient();
                    client.runClient(stub);
                    System.out.println(msg2);
                    return 0;
                } catch (Exception e) {
                    System.out.println(msg3 + " Exception: " + e.toString());
                    e.printStackTrace(System.out);
                    return 1;
                }
            case 2: /* non-default constructor - default config */
                System.out.println(msg1);
                try {
                    HelloImpl server = new HelloImpl(
                            0,
                            new ClientFactory(),
                            new ServerFactory(ctx, null, null, false));
                    Remote stub = server.runServer();
                    HelloClient client = new HelloClient();
                    client.runClient(stub);
                    System.out.println(msg2);
                    return 0;
                } catch (Exception e) {
                    System.out.println(msg3 + " Exception: " + e.toString());
                    e.printStackTrace(System.out);
                    return 1;
                }
            case 3: /* needClientAuth=true */
                System.out.println(msg1);
                try {
                    HelloImpl server = new HelloImpl(
                            0,
                            new ClientFactory(),
                            new ServerFactory(ctx, null, null, true));
                    Remote stub = server.runServer();
                    HelloClient client = new HelloClient();
                    client.runClient(stub);
                    System.out.println(msg2);
                    return 0;
                } catch (Exception e) {
                    System.out.println(msg3 + " Exception: " + e.toString());
                    e.printStackTrace(System.out);
                    return 1;
                }
            case 4: /* server side dummy_ciphersuite */
                System.out.println(msg1);
                try {
                    HelloImpl server = new HelloImpl(
                            0,
                            new ClientFactory(),
                            new ServerFactory(
                            ctx, new String[] {"dummy_ciphersuite"}, null, false));
                    Remote stub = server.runServer();
                    HelloClient client = new HelloClient();
                    client.runClient(stub);
                    System.out.println(msg3);
                    return 1;
                } catch (Exception e) {
                    System.out.println(msg2 + " Exception: " + e.toString());
                    return 0;
                }
            case 5: /* server side dummy_protocol */
                System.out.println(msg1);
                try {
                    HelloImpl server = new HelloImpl(
                            0,
                            new ClientFactory(),
                            new ServerFactory(
                            ctx, null, new String[] {"dummy_protocol"}, false));
                    Remote stub = server.runServer();
                    HelloClient client = new HelloClient();
                    client.runClient(stub);
                    System.out.println(msg3);
                    return 1;
                } catch (Exception e) {
                    System.out.println(msg2 + " Exception: " + e.toString());
                    return 0;
                }
            case 6: /* client side dummy_ciphersuite */
                System.out.println(msg1);
                try {
                    System.setProperty("javax.rmi.ssl.client.enabledCipherSuites",
                            "dummy_ciphersuite");
                    HelloImpl server = new HelloImpl(
                            0,
                            new ClientFactory(),
                            new ServerFactory(ctx));
                    Remote stub = server.runServer();
                    HelloClient client = new HelloClient();
                    client.runClient(stub);
                    System.out.println(msg3);
                    return 1;
                } catch (Exception e) {
                    System.out.println(msg2 + " Exception: " + e.toString());
                    return 0;
                }
            case 7: /* client side dummy_protocol */
                System.out.println(msg1);
                try {
                    System.setProperty("javax.rmi.ssl.client.enabledProtocols",
                            "dummy_protocol");
                    HelloImpl server = new HelloImpl(
                            0,
                            new ClientFactory(),
                            new ServerFactory(ctx));
                    Remote stub = server.runServer();
                    HelloClient client = new HelloClient();
                    client.runClient(stub);
                    System.out.println(msg3);
                    return 1;
                } catch (Exception e) {
                    System.out.println(msg2 + " Exception: " + e.toString());
                    return 0;
                }
            default:
                throw new IllegalArgumentException("invalid test number");
        }
    }
    
    public static void main(String[] args) throws Exception {
        // KeyStore and TrustStore locations
        //
        final String keyStore = System.getProperty("test.src") +
                File.separator + "ssl" + File.separator + "keystore";
        final String keyStorePassword = "password";
        System.out.println("KeyStore = " + keyStore);
        final String trustStore = System.getProperty("test.src") +
                File.separator + "ssl" + File.separator + "truststore";
        final String trustStorePassword = "trustword";
        System.out.println("TrustStore = " + trustStore);
        
        // Set keystore/truststore properties (server-side)
        //
        char[] keyStorePasswd = keyStorePassword.toCharArray();
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(new FileInputStream(keyStore), keyStorePasswd);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, keyStorePasswd);
        char[] trustStorePasswd = trustStorePassword.toCharArray();
        KeyStore ts = KeyStore.getInstance(KeyStore.getDefaultType());
        ts.load(new FileInputStream(trustStore), trustStorePasswd);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) ts);
        SSLContext ctx = SSLContext.getInstance("SSL");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        
        // Set keystore/truststore properties (client-side)
        //
        System.setProperty("javax.net.ssl.keyStore", keyStore);
        System.setProperty("javax.net.ssl.keyStorePassword", keyStorePassword);
        System.setProperty("javax.net.ssl.trustStore", trustStore);
        System.setProperty("javax.net.ssl.trustStorePassword", trustStorePassword);
        
        // Run test
        //
        SSLParametersTest test = new SSLParametersTest();
        int error = test.runTest(ctx, args);
        if (error > 0) {
            throw new IllegalArgumentException("Test failed!");
        }
    }
}
