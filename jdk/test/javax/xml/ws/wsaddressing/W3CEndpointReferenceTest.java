/*
 * Copyright (c) 2005, 2008, Oracle and/or its affiliates. All rights reserved.
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
 * @bug 6672868
 * @summary Package javax.xml.ws.wsaddressing not included in make/docs/CORE_PKGS.gmk
 * @compile W3CEndpointReferenceTest.java
 */

import java.io.StringReader;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Source;
import javax.xml.transform.dom.DOMSource;
import javax.xml.ws.wsaddressing.W3CEndpointReference;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

public class W3CEndpointReferenceTest {
    
    private static final String EPR200508 =
    "<wsa:EndpointReference xmlns:axis2=\"http://ws.apache.org/namespaces/axis2\" xmlns:wsa=\"http://www.w3.org/2005/08/addressing\" axis2:AttrExt=\"123456789\">"+
        "<wsa:Address>http://www.w3.org/2005/08/addressing/anonymous</wsa:Address>"+
        "<wsa:ReferenceParameters xmlns:fabrikam=\"http://example.com/fabrikam\">"+
            "<fabrikam:CustomerKey>123456789</fabrikam:CustomerKey>"+
            "<fabrikam:ShoppingCart>ABCDEFG</fabrikam:ShoppingCart>"+
        "</wsa:ReferenceParameters>"+
        "<wsa:Metadata>"+
            "<axis2:MetaExt axis2:AttrExt=\"123456789\">123456789</axis2:MetaExt>"+
        "</wsa:Metadata>"+
        "<axis2:EPRExt axis2:AttrExt=\"123456789\">123456789</axis2:EPRExt>"+
    "</wsa:EndpointReference>";

    public static void main(String args[]) throws Exception {
        DocumentBuilderFactory dbfac = DocumentBuilderFactory.newInstance();
        dbfac.setNamespaceAware(true);
        DocumentBuilder docBuilder = dbfac.newDocumentBuilder();
        Document jaxwsDoc = docBuilder.parse(new InputSource(new StringReader(EPR200508)));
        Source source = new DOMSource(jaxwsDoc);
        
        W3CEndpointReference jaxwsEPR = new W3CEndpointReference(source);
        System.out.print(jaxwsEPR.toString());

    }
}
