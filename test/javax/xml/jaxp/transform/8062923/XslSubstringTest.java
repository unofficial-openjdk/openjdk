/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

/**
 * @test
 * @bug 8062923 8062924 8074297 8076290
 * @run testng XslSubstringTest
 * @summary Test xsl substring function with negative, Inf and
 * NaN length and few other use cases. Also test proper
 * processing of supplementary characters by substring function.
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
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

public class XslSubstringTest {

    final String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><test></test>";
    final String xslPre = "<xsl:stylesheet version='1.0'"
            + " xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>"
            + "<xsl:output method='xml' indent='yes' omit-xml-declaration='yes'/>"
            + "<xsl:template match='/'><t>";
    final String xslPost = "</t></xsl:template></xsl:stylesheet>";

    @DataProvider(name = "GeneralTestsData")
    private Object[][] xmls() {
        return new Object[][] {
            { "|<xsl:value-of select=\"substring('asdf',2, 1)\"/>|", "<t>|s|</t>"},
            { "|<xsl:value-of select=\"substring('asdf',2, 1 div 0)\"/>|", "<t>|sdf|</t>"},
            { "|<xsl:value-of select=\"substring('asdf',2, -0 div 0)\"/>|", "<t>||</t>" },
            { "|<xsl:value-of select=\"substring('asdf',2, 1 div 0)\"/>|", "<t>|sdf|</t>" },
            // 8076290 bug test case
            { "|<xsl:value-of select=\"substring('123', 0, 3)\"/>|", "<t>|12|</t>"},
        };
    }

    @DataProvider(name = "SupplementaryCharactersTestData")
    private Object[][] dataSupplementaryCharacters() {
        return new Object[][] {
            // 8074297 bug test cases
            { "|<xsl:value-of select=\"substring('&#131083;ABC', 3)\"/>|",    "<t>|BC|</t>"},
            { "|<xsl:value-of select=\"substring('&#131083;ABC', 3, 1)\"/>|", "<t>|B|</t>" },
            { "|<xsl:value-of select=\"substring('&#131083;ABC', 2, 2)\"/>|", "<t>|AB|</t>"},
            { "|<xsl:value-of select=\"substring('&#131083;ABC', 3, 2)\"/>|", "<t>|BC|</t>"},
            { "|<xsl:value-of select=\"substring('&#131083;ABC', 3, 4)\"/>|", "<t>|BC|</t>"},
            { "|<xsl:value-of select=\"substring('&#131083;ABC', 1, 1)\"/>|", "<t>|&#131083;|</t>"},
            { "|<xsl:value-of select=\"substring('&#131083;ABC', 2, 1)\"/>|", "<t>|A|</t>"},
            { "|<xsl:value-of select=\"substring('&#131083;ABC', 1, 1 div 0)\"/>|", "<t>|&#131083;ABC|</t>"},
            { "|<xsl:value-of select=\"substring('&#131083;ABC', -10, 1 div 0)\"/>|", "<t>|&#131083;ABC|</t>"},
            // 8076290 bug test case
            { "|<xsl:value-of select=\"substring('&#131083;ABC', 0, 2)\"/>|", "<t>|&#131083;|</t>"},
        };
    }

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

    @Test(dataProvider = "GeneralTestsData")
    public void testGeneralAll(String xsl, String result) throws Exception {
        assertEquals(testTransform(xsl), result);
    }

    @Test(dataProvider = "SupplementaryCharactersTestData")
    public void testSupplementCharacters(String xsl, String result) throws Exception {
        assertEquals(testTransform(xsl), result);
    }

}
