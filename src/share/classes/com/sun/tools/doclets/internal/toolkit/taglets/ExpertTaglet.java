/*
 * Copyright (c) 2006, 2012 Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

package com.sun.tools.doclets.internal.toolkit.taglets;

import java.util.Map;
import com.sun.tools.doclets.Taglet;
import com.sun.javadoc.Tag;

public class ExpertTaglet implements Taglet {

    private static final String NAME = "expert";
    private static final String START_TAG = "<sub id=\"expert\">";
    private static final String END_TAG = "</sub>";

    public boolean inField() {
        return true;
    }

    public boolean inConstructor() {
        return true;
    }

    public boolean inMethod() {
        return true;
    }

    public boolean inOverview() {
        return true;
    }

    public boolean inPackage() {
        return true;
    }

    public boolean inType() {
        return true;
    }

    public boolean isInlineTag() {
        return false;
    }

    public String getName() {
        return NAME;
    }

    public static void register(Map<String, Taglet> map) {
        map.remove(NAME);
        map.put(NAME, new ExpertTaglet());
    }

    public String toString(Tag tag) {
        return (tag.text() == null || tag.text().length() == 0) ? null :
            START_TAG + LiteralTaglet.textToString(tag.text()) + END_TAG;
    }


    public String toString(Tag[] tags) {
        if (tags == null || tags.length == 0) return null;

        StringBuffer sb = new StringBuffer(START_TAG);

        for(Tag t:tags) {
            sb.append(LiteralTaglet.textToString(t.text()));
        }
        sb.append(END_TAG);
        return sb.toString();
    }

}
