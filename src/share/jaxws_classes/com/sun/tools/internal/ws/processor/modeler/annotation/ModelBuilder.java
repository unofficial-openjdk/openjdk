/*
 * Copyright (c) 1997, 2010, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.tools.internal.ws.processor.modeler.annotation;

import com.sun.mirror.apt.AnnotationProcessorEnvironment;
import com.sun.mirror.declaration.TypeDeclaration;
import com.sun.mirror.type.TypeMirror;
import com.sun.mirror.util.SourcePosition;
import com.sun.tools.internal.ws.processor.model.Port;
import com.sun.tools.internal.ws.processor.model.Service;
import com.sun.tools.internal.ws.processor.modeler.ModelerException;
import com.sun.tools.internal.ws.wscompile.WsgenOptions;
import com.sun.xml.internal.ws.util.localization.Localizable;

import java.io.File;

/**
 *
 * @author WS Development Team
 */
public interface ModelBuilder {
    public AnnotationProcessorEnvironment getAPEnv();
    public void setService(Service service);
    public void setPort(Port port);
    public String getOperationName(String methodName);
    public String getResponseName(String operationName);
    public TypeMirror getHolderValueType(TypeMirror type);
    public boolean checkAndSetProcessed(TypeDeclaration typeDecl);
    public boolean isRemoteException(TypeDeclaration typeDecl);

    /**
     * Checks if the TypeDeclaration is a service specific exception
     * @param typeDecl
     * @return true if is not a service specifc excpetion as defined by JAX-WS specification
     */
    public boolean isServiceException(TypeDeclaration typeDecl);

    public boolean isRemote(TypeDeclaration typeDecl);
    public boolean canOverWriteClass(String className);
    public void setWrapperGenerated(boolean wrapperGenerated);
    public TypeDeclaration getTypeDeclaration(String typeName);
    public String getSourceVersion();
    public WsgenOptions getOptions();
    public File getSourceDir();
    public String getXMLName(String javaName);
    public void log(String msg);

    public void onError(String s);
    public void onError(SourcePosition pos, Localizable msg) throws ModelerException;
}
