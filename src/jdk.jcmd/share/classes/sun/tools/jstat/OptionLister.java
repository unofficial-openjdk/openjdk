/*
 * Copyright (c) 2004, 2010, Oracle and/or its affiliates. All rights reserved.
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

package sun.tools.jstat;

import java.util.*;
import java.net.*;
import java.io.*;
import java.util.stream.Collectors;

/**
 * A class for listing the available options in the jstat_options file.
 *
 * @author Brian Doherty
 * @since 1.5
 */
public class OptionLister {
    private static final boolean debug = false;
    private final List<Parser> optionParsers;

    private Parser newParser(InputStream in) {
        Reader r = new BufferedReader(new InputStreamReader(in));
        return new Parser(r);
    }

    public OptionLister(List<InputStream> sources) {
        this.optionParsers = sources.stream()
            .map(in -> newParser(in)).collect(Collectors.toList());
    }

    public void print(PrintStream ps) {
        Comparator<OptionFormat> c = new Comparator<OptionFormat>() {
               public int compare(OptionFormat o1, OptionFormat o2) {
                   OptionFormat of1 = o1;
                   OptionFormat of2 = o2;
                   return (of1.getName().compareTo(of2.getName()));
               }
        };

        Set<OptionFormat> options = new TreeSet<OptionFormat>(c);

        for (Parser parser : optionParsers) {
            try {
                Set<OptionFormat> s = parser.parseOptions();
                options.addAll(s);
            } catch (IOException | ParserException e) {
                if (debug) e.printStackTrace();
            }
        }

        for (OptionFormat of : options) {
            if (of.getName().compareTo("timestamp") == 0) {
                // ignore the special timestamp OptionFormat.
                continue;
            }
            ps.println("-" + of.getName());
        }
    }
}
