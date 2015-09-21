/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A factory class encapsulating processing the {@linkplain Parser} definitions.
 *
 * @author Jaroslav Bachorik
 * @since 1.9
 */
class OptionParserFactory {

    /**
     * Returns a configured {@linkplain Parser} instance based on the given
     * configuration file.
     *
     * @param filename The configuration file name
     * @return A configured {@linkplain Parser} instance based on the given
     * configuration file
     */
    static Parser newParser(String filename) {
        InputStream in = OptionParserFactory.class.getResourceAsStream(filename);
        assert in != null;
        Reader r = new BufferedReader(new InputStreamReader(in));
        return new Parser(r);
    }

    /**
     * Returns a list of {@linkplain Parser} instances created out of the
     * provided {@linkplain InputStream}s.
     * <p>
     * ! Parsers can not be restarted !
     * Therefore we need to create new instances to parse each option.
     * </p>
     * @param optionSources list of the option sources file names
     * @return list of {@linkplain Parser} instances created out of the
     * provided {@linkplain InputStream}s
     */
    static List<Parser> getOptionParsers(List<String> optionsSources) {
        return optionsSources.stream()
                .map(OptionParserFactory::newParser).collect(Collectors.toList());
    }
}
