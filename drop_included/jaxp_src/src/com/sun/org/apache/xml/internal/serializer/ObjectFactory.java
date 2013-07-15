/*
 * reserved comment block
 * DO NOT REMOVE OR ALTER!
 */
/*
 * Copyright 2001-2004 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * $Id: ObjectFactory.java,v 1.2.4.1 2005/09/15 08:15:20 suresh_emailid Exp $
 */

package com.sun.org.apache.xml.internal.serializer;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Properties;

/**
 * This class is duplicated for each JAXP subpackage so keep it in sync.
 * It is package private and therefore is not exposed as part of the JAXP
 * API.
 * <p>
 * This code is designed to implement the JAXP 1.1 spec pluggability
 * feature and is designed to run on JDK version 1.1 and
 * later, and to compile on JDK 1.2 and onward.  
 * The code also runs both as part of an unbundled jar file and
 * when bundled as part of the JDK.
 * <p>
 * This class was moved from the <code>javax.xml.parsers.ObjectFactory</code>
 * class and modified to be used as a general utility for creating objects 
 * dynamically.
 *
 * @xsl.usage internal
 */
class ObjectFactory {

    //
    // Constants
    //

    // name of default properties file to look for in JDK's jre/lib directory
    private static final String DEFAULT_PROPERTIES_FILENAME =
                                                     "xalan.properties";

    private static final String SERVICES_PATH = "META-INF/services/";

    /** Set to true for debugging */
    private static final boolean DEBUG = false;

    /** cache the contents of the xalan.properties file.
     *  Until an attempt has been made to read this file, this will
     * be null; if the file does not exist or we encounter some other error
     * during the read, this will be empty.
     */
    private static Properties fXalanProperties = null;

    /***
     * Cache the time stamp of the xalan.properties file so
     * that we know if it's been modified and can invalidate
     * the cache when necessary.
     */
    private static long fLastModified = -1;

    /**
     * Create an instance of a class using the specified ClassLoader
     */ 
    static Object newInstance(String className, ClassLoader cl,
                                      boolean doFallback)
        throws ConfigurationError
    {
        // assert(className != null);
        try{
            Class providerClass = findProviderClass(className, cl, doFallback);
            Object instance = providerClass.newInstance();
            return instance;
        } catch (ClassNotFoundException x) {
            throw new ConfigurationError(
                "Provider " + className + " not found", x);
        } catch (Exception x) {
            throw new ConfigurationError(
                "Provider " + className + " could not be instantiated: " + x,
                x);
        }
    }

    /**
     * Find a Class using the specified ClassLoader
     */ 
    static Class findProviderClass(String className, ClassLoader cl,
                                           boolean doFallback)
        throws ClassNotFoundException, ConfigurationError
    {   
        //throw security exception if the calling thread is not allowed to access the
        //class. Restrict the access to the package classes as specified in java.security policy.
        SecurityManager security = System.getSecurityManager();
        try{
                if (security != null){
                    final int lastDot = className.lastIndexOf(".");
                    String packageName = className;
                    if (lastDot != -1) packageName = className.substring(0, lastDot);
                    security.checkPackageAccess(packageName);
                 }   
        }catch(SecurityException e){
            throw e;
        }
        
        Class providerClass;
        if (cl == null) {
            // XXX Use the bootstrap ClassLoader.  There is no way to
            // load a class using the bootstrap ClassLoader that works
            // in both JDK 1.1 and Java 2.  However, this should still
            // work b/c the following should be true:
            //
            // (cl == null) iff current ClassLoader == null
            //
            // Thus Class.forName(String) will use the current
            // ClassLoader which will be the bootstrap ClassLoader.
            providerClass = Class.forName(className);
        } else {
            try {
                providerClass = cl.loadClass(className);
            } catch (ClassNotFoundException x) {
                if (doFallback) {
                    // Fall back to current classloader
                    ClassLoader current = ObjectFactory.class.getClassLoader();
                    if (current == null) {
                        providerClass = Class.forName(className);
                    } else if (cl != current) {
                        cl = current;
                        providerClass = cl.loadClass(className);
                    } else {
                        throw x;
                    }
                } else {
                    throw x;
                }
            }
        }

        return providerClass;
    }

    //
    // Classes
    //

    /**
     * A configuration error.
     */
    static class ConfigurationError 
        extends Error {
                static final long serialVersionUID = 8859254254255146542L;
        //
        // Data
        //

        /** Exception. */
        private Exception exception;

        //
        // Constructors
        //

        /**
         * Construct a new instance with the specified detail string and
         * exception.
         */
        ConfigurationError(String msg, Exception x) {
            super(msg);
            this.exception = x;
        } // <init>(String,Exception)

        //
        // Public methods
        //

        /** Returns the exception associated to this error. */
        Exception getException() {
            return exception;
        } // getException():Exception

    } // class ConfigurationError

} // class ObjectFactory
