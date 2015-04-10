/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

/**
 * @test
 * @bug 8062923 8062924
 * @run testng XslSubstringTest
 * @summary Test xsl substring function with negative, Inf and
 * NaN length and few other use cases
 */

import java.io.StringReader;
import java.io.StringWriter;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import static org.testng.Assert.assertEquals;
import org.testng.annotations.Test;

public class XslSubstringTest {

    final String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><test></test>";
    final String xslPre = "<xsl:stylesheet version='1.0'"
            + " xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
            + "<xsl:output method='xml' indent='yes' omit-xml-declaration='yes'/>"
            + "<xsl:template match='/'><t>";
    final String xslPost = "</t></xsl:template></xsl:stylesheet>";

    private String testTransform(String xsl) throws Exception {
        //Prepare sources for transormation
        Source src = new StreamSource(new StringReader(xml));
        Source xslsrc = new StreamSource(new StringReader(xslPre + xsl + xslPost));
        //Create factory, template and transformer
        TransformerFactory tf = TransformerFactory.newInstance();
        Templates tmpl = tf.newTemplates(xslsrc);
        Transformer t = tmpl.newTransformer();
        //Prepare output stream
        StringWriter xmlResultString = new StringWriter();
        StreamResult xmlResultStream = new StreamResult(xmlResultString);
        //Transform
        t.transform(src, xmlResultStream);
        return xmlResultString.toString().trim();
    }

    @Test
    public void test8062923() throws Exception {
        assertEquals(testTransform("|<xsl:value-of select=\"substring('asdf',2,-1)\"/>|"),
                "<t>||</t>");
    }

    @Test
    public void test8062924() throws Exception {
        assertEquals(testTransform("|<xsl:value-of select=\"substring('asdf',2,-1 div 0)\"/>|"),
                "<t>||</t>");
    }

    @Test
    public void testGeneral1() throws Exception {
        assertEquals(testTransform("|<xsl:value-of select=\"substring('asdf',2, 1)\"/>|"),
                "<t>|s|</t>");
    }

    @Test
    public void testGeneral2() throws Exception {
        assertEquals(testTransform("|<xsl:value-of select=\"substring('asdf',2, 1 div 0)\"/>|"),
                "<t>|sdf|</t>");
    }

    @Test
    public void testGeneral3() throws Exception {
        assertEquals(testTransform("|<xsl:value-of select=\"substring('asdf',2, -0 div 0)\"/>|"),
                "<t>||</t>");
    }

    @Test
    public void testGeneral4() throws Exception {
        assertEquals(testTransform("|<xsl:value-of select=\"substring('asdf',2, 0 div 0)\"/>|"),
                "<t>||</t>");
    }
}
