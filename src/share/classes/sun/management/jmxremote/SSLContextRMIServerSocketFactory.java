/*
 * Copyright (c) 2007, Oracle and/or its affiliates. All rights reserved.
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

package sun.management.jmxremote;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.rmi.ssl.SslRMIServerSocketFactory;

/**
 * This class represents a specialized version of the
 * <code>SslRMIServerSocketFactory</code> class that
 * allows to supply an <code>SSLContext</code>.
 *
 * @see javax.rmi.ssl.SslRMIServerSocketFactory
 */
public class SSLContextRMIServerSocketFactory extends SslRMIServerSocketFactory {
    
    /**
     * <p>Creates a new <code>SSLContextRMIServerSocketFactory</code> with
     * SSL sockets created from the <code>SSLSocketFactory</code> returned
     * by the given <code>SSLContext</code> and configured with the default
     * SSL parameters.
     *
     * <p>SSL connections accepted by server sockets created by this
     * factory have the default cipher suites and protocol versions
     * enabled and do not require client authentication.</p>
     *
     * @param context the SSL context to be used for creating SSL sockets.
     * Calling this constructor with a null <code>context</code> is equivalent
     * to calling <code>SslRMIServerSocketFactory()</code>.
     */
    public SSLContextRMIServerSocketFactory(SSLContext context) {
        this(context, null, null, false);
    }
    
    /**
     * <p>Creates a new <code>SSLContextRMIServerSocketFactory</code> with
     * SSL sockets created from the <code>SSLSocketFactory</code> returned
     * by the given <code>SSLContext</code> and configured with the supplied
     * SSL parameters.
     *
     * @param context the SSL context to be used for creating SSL sockets.
     * Calling this constructor with a null <code>context</code> is equivalent
     * to calling <code>SslRMIServerSocketFactory(enabledCipherSuites,
     * enabledProtocols, needClientAuth)</code>.
     *
     * @param enabledCipherSuites names of all the cipher suites to
     * enable on SSL connections accepted by server sockets created by
     * this factory, or <code>null</code> to use the cipher suites
     * that are enabled by default
     *
     * @param enabledProtocols names of all the protocol versions to
     * enable on SSL connections accepted by server sockets created by
     * this factory, or <code>null</code> to use the protocol versions
     * that are enabled by default
     *
     * @param needClientAuth <code>true</code> to require client
     * authentication on SSL connections accepted by server sockets
     * created by this factory; <code>false</code> to not require
     * client authentication
     *
     * @exception IllegalArgumentException when one or more of the cipher
     * suites named by the <code>enabledCipherSuites</code> parameter is
     * not supported, when one or more of the protocols named by the
     * <code>enabledProtocols</code> parameter is not supported or when
     * a problem is encountered while trying to check if the supplied
     * cipher suites and protocols to be enabled are supported.
     *
     * @see SSLSocket#setEnabledCipherSuites
     * @see SSLSocket#setEnabledProtocols
     * @see SSLSocket#setNeedClientAuth
     */
    public SSLContextRMIServerSocketFactory(
            SSLContext context,
            String[] enabledCipherSuites,
            String[] enabledProtocols,
            boolean needClientAuth)
            throws IllegalArgumentException {
        super(enabledCipherSuites, enabledProtocols, needClientAuth);
        this.context = context;
        // NOTE: We should check the availability of the enabledCipherSuites
        // and enabledProtocols in the socket factory returned by the call
        // context.getSocketFactory() because it could differ from the one
        // returned by SSLSocketFactory.getDefault(), which is already
        // checked in the parent's class constructor, but we don't do it
        // because we know that although the factory the out-of-the-box
        // management agent uses might be different, they are of the same
        // type and use the same underlying SSLSocket implementation.
    }
    
    /**
     * <p>Creates a server socket that accepts SSL connections configured
     * according to this factory's SSL socket configuration parameters.
     * If a null <code>SSLContext</code> was supplied in the constructor
     * this method just calls <code>super.createServerSocket(port)</code>.
     * Otherwise, the <code>SSLSocketFactory</code> returned by the call to
     * <code>SSLContext.getSocketFactory()</code> will be used to create the
     * SSL sockets.</p>
     */
    @Override
    public ServerSocket createServerSocket(int port) throws IOException {
        if (context == null) {
            return super.createServerSocket(port);
        } else {
            final SSLSocketFactory sslSocketFactory = context.getSocketFactory();
            return new ServerSocket(port) {
                public Socket accept() throws IOException {
                    Socket socket = super.accept();
                    SSLSocket sslSocket = (SSLSocket)
                    sslSocketFactory.createSocket(
                            socket, socket.getInetAddress().getHostName(),
                            socket.getPort(), true);
                    sslSocket.setUseClientMode(false);
                    if (getEnabledCipherSuites() != null) {
                        sslSocket.setEnabledCipherSuites(getEnabledCipherSuites());
                    }
                    if (getEnabledProtocols() != null) {
                        sslSocket.setEnabledProtocols(getEnabledProtocols());
                    }
                    sslSocket.setNeedClientAuth(getNeedClientAuth());
                    return sslSocket;
                }
            };
        }
    }
    
    /**
     * <p>Indicates whether some other object is "equal to" this one.</p>
     *
     * <p>Two <code>SSLContextRMIServerSocketFactory</code> objects are
     * equal if they have been constructed with the same SSL context and
     * SSL socket configuration parameters.</p>
     *
     * <p>A subclass should override this method (as well as
     * {@link #hashCode()}) if it adds instance state that affects
     * equality.</p>
     */
    @Override
    public boolean equals(Object obj) {
        if (!super.equals(obj))
            return false;
        SSLContextRMIServerSocketFactory that = (SSLContextRMIServerSocketFactory) obj;
        return context == null ? that.context == null : context.equals(that.context);
    }
    
    /**
     * <p>Returns a hash code value for this
     * <code>SSLContextRMIServerSocketFactory</code>.</p>
     *
     * @return a hash code value for this
     * <code>SSLContextRMIServerSocketFactory</code>.
     */
    @Override
    public int hashCode() {
        return super.hashCode() + (context == null ? 0 : context.hashCode());
    }
    
    private SSLContext context;
}
