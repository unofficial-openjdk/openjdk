/*
 * Copyright (c) 2009, Oracle and/or its affiliates. All rights reserved.
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

/*
 * This file is available under and governed by the GNU General Public
 * License version 2 only, as published by the Free Software Foundation.
 * However, the following notice accompanied the original version of this
 * file:
 *
 * Copyright (c) 2004-2009 Paul R. Holser, Jr.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package jdk.joptsimple;

import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import jdk.joptsimple.internal.ColumnarData;
import static jdk.joptsimple.ParserRules.*;
import static jdk.joptsimple.internal.Classes.*;
import static jdk.joptsimple.internal.Strings.*;

/**
 * @author <a href="mailto:pholser@alumni.rice.edu">Paul Holser</a>
 * @version $Id: HelpFormatter.java,v 1.11 2008/12/19 00:11:00 pholser Exp $
 */
class HelpFormatter implements OptionSpecVisitor {
    private final ColumnarData grid;

    HelpFormatter() {
        grid = new ColumnarData( "Option", "Description" );
    }

    String format( Map<String, AbstractOptionSpec<?>> options ) {
        if ( options.isEmpty() )
            return "No options specified";

        grid.clear();

        Comparator<AbstractOptionSpec<?>> comparator =
            new Comparator<AbstractOptionSpec<?>>() {
                public int compare( AbstractOptionSpec<?> first,
                    AbstractOptionSpec<?> second ) {

                    return first.options().iterator().next().compareTo(
                        second.options().iterator().next() );
                }
            };

        Set<AbstractOptionSpec<?>> sorted =
            new TreeSet<AbstractOptionSpec<?>>( comparator );
        sorted.addAll( options.values() );

        for ( AbstractOptionSpec<?> each : sorted )
            each.accept( this );

        return grid.format();
    }

    void addHelpLineFor( AbstractOptionSpec<?> spec, String additionalInfo ) {
        grid.addRow(
            createOptionDisplay( spec ) + additionalInfo,
            spec.description() );
    }

    public void visit( NoArgumentOptionSpec spec ) {
        addHelpLineFor( spec, "" );
    }

    public void visit( RequiredArgumentOptionSpec<?> spec ) {
        visit( spec, '<', '>' );
    }

    public void visit( OptionalArgumentOptionSpec<?> spec ) {
        visit( spec, '[', ']' );
    }

    private void visit( ArgumentAcceptingOptionSpec<?> spec, char begin, char end ) {
        String argDescription = spec.argumentDescription();
        String argType = nameOfArgumentType( spec );
        StringBuilder collector = new StringBuilder();

        if ( argType.length() > 0 ) {
            collector.append( argType );

            if ( argDescription.length() > 0 )
                collector.append( ": " ).append( argDescription );
        }
        else if ( argDescription.length() > 0 )
            collector.append( argDescription );

        String helpLine = collector.length() == 0
            ? ""
            : ' ' + surround( collector.toString(), begin, end );
        addHelpLineFor( spec, helpLine );
    }

    public void visit( AlternativeLongOptionSpec spec ) {
        addHelpLineFor( spec, ' ' + surround( spec.argumentDescription(), '<', '>' ) );
    }

    private String createOptionDisplay( AbstractOptionSpec<?> spec ) {
        StringBuilder buffer = new StringBuilder();

        for ( Iterator<String> iter = spec.options().iterator(); iter.hasNext(); ) {
            String option = iter.next();
            buffer.append( option.length() > 1 ? DOUBLE_HYPHEN : HYPHEN );
            buffer.append( option );

            if ( iter.hasNext() )
                buffer.append( ", " );
        }

        return buffer.toString();
    }

    private static String nameOfArgumentType( ArgumentAcceptingOptionSpec<?> spec ) {
        Class<?> argType = spec.argumentType();
        return String.class.equals( argType ) ? "" : shortNameOf( argType );
    }
}
