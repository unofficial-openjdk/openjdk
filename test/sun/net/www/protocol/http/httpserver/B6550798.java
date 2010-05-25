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

/**
 * @test
 * @bug 6550798
 * @run main/othervm B6550798
 * @summary  Using InputStream.skip with ResponseCache will cause partial data to be cached
 */

import java.io.*;
import java.util.*;
import java.net.*;

import com.sun.net.httpserver.*;

public class B6550798 {

    static final String RESP = "This is the response";

    public static byte[] read (InputStream i) throws IOException {
        byte[]b = new byte [1024];
        int c, j = 0;
        while ((c=i.read()) != -1) {
            b[j++] = (byte)c;
        }
        i.close();
        byte[] b1 = new byte [j];
        System.arraycopy (b, 0, b1, 0, j);
        return b1;
    }

    static int invocations = 0;

    /**
         * @param args
         */
    public static void main(String[] args) {
        class MyHandler implements HttpHandler {
            public void handle(HttpExchange t) throws IOException {
                invocations ++;
                InputStream is = t.getRequestBody();
                read(is);
                // .. read the request body
                t.sendResponseHeaders(200, RESP.length());
                OutputStream os = t.getResponseBody();
                os.write(RESP.getBytes("ISO8859_1"));
                os.close();
            }
        }


        HttpServer server = null;
        try {
            server = HttpServer.create(new InetSocketAddress(0), 10);

            server.createContext("/apps", new MyHandler());
            server.setExecutor(null);
            ResponseCache.setDefault (new ResponseCache() {

                HashMap<URI,String> cache = new HashMap<URI,String>();

                public CacheResponse get (URI uri, String meth,
                                          Map<String,List<String>> hdrs) {
                    final String s = cache.get(uri);
                    if (s == null) {
                        return null;
                    }
                    CacheResponse cr = new CacheResponse () {
                        public Map<String,List<String>> getHeaders() {
                            return null;
                        }
                        public InputStream getBody () {
                            InputStream i = null;
                            try {
                                i = new ByteArrayInputStream (s.getBytes(
                                        "ISO8859_1"
                                ));
                            } catch (Exception e) {}
                            return i;
                        }
                    };
                    return cr;
                }
                public CacheRequest put (URI uri, URLConnection conn)
                                throws IOException {
                    final URI u = uri;

                    return new CacheRequest () {
                        public void abort () {}
                        public OutputStream getBody () {
                            return new ByteArrayOutputStream () {
                                public void close () throws IOException {
                                    super.close ();
                                    String s = new String (buf, 0, count, "ISO8859_1");
                                    cache.put (u, s);
                                }
                            };
                        }
                    };
                }
            });

            // creates a default executor
            server.start();
            int port = server.getAddress().getPort();
            String s = "http://localhost:"+port+"/apps/foo";
            URL url = new URL (s);
            InputStream is = url.openStream();
            is.read ();
            is.read ();
            if (is.skip(3) != 3) {
                throw new RuntimeException ("error 1");
            }
            read (is);
            /* request resource a second time */
            is = url.openStream();
            byte [] b = read (is);
            String s1 = new String (b, "ISO8859_1");
            if (!s1.equals (RESP)) {
                throw new RuntimeException ("error 2 :"+s1);
            }
            if (invocations != 1) {
                throw new RuntimeException ("error 3 :");
            }
            System.out.println ("OK");
        } catch (IOException e) {
            // TODO Auto-generated catch block
                e.printStackTrace();
        } finally {
            server.stop (1);
        }
    }
}
