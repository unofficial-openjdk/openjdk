/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.xml.internal.ws.api.server;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility class to invoke sun.reflect.misc.MethodUtil.invoke() if available. If not (other then Oracle JDK) fallbacks
 * to java.lang,reflect.Method.invoke()
 *
 * Be careful, copy of this class exists in several packages, iny modification must be done to other copies too!
 */
class MethodUtil {

    private static final Logger LOGGER = Logger.getLogger(MethodUtil.class.getName());
    private static final Method INVOKE_METHOD;

    static {
        Method method;
        try {
            Class<?> clazz = Class.forName("sun.reflect.misc.MethodUtil");
            method = clazz.getMethod("invoke", Method.class, Object.class, Object[].class);
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "Class sun.reflect.misc.MethodUtil found; it will be used to invoke methods.");
            }
        } catch (Throwable t) {
            method = null;
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "Class sun.reflect.misc.MethodUtil not found, probably non-Oracle JVM");
            }
        }
        INVOKE_METHOD = method;
    }

    static Object invoke(Object target, Method method, Object[] args) throws IllegalAccessException, InvocationTargetException {
        if (INVOKE_METHOD != null) {
            // sun.reflect.misc.MethodUtil.invoke(method, owner, args)
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "Invoking method using sun.reflect.misc.MethodUtil");
            }
            try {
                return INVOKE_METHOD.invoke(null, method, target, args);
            } catch (InvocationTargetException ite) {
                // unwrap invocation exception added by reflection code ...
                throw unwrapException(ite);
            }
        } else {
            // other then Oracle JDK ...
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "Invoking method directly, probably non-Oracle JVM");
            }
            return method.invoke(target, args);
        }
    }

    private static InvocationTargetException unwrapException(InvocationTargetException ite) {
        Throwable targetException = ite.getTargetException();
        if (targetException != null && targetException instanceof InvocationTargetException) {
            if (LOGGER.isLoggable(Level.FINE)) {
                LOGGER.log(Level.FINE, "Unwrapping invocation target exception");
            }
            return (InvocationTargetException) targetException;
        } else {
            return ite;
        }
    }

}
