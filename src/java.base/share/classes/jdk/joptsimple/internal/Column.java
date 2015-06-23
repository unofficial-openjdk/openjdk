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

package jdk.joptsimple.internal;

import java.text.BreakIterator;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

import static jdk.joptsimple.internal.Strings.*;

/**
 * @author <a href="mailto:pholser@alumni.rice.edu">Paul Holser</a>
 * @version $Id: Column.java,v 1.10 2008/12/19 00:11:00 pholser Exp $
 */
public class Column {
    static final Comparator<Column> BY_HEIGHT = new Comparator<Column>() {
        public int compare( Column first, Column second ) {
            if ( first.height() < second.height() )
                return -1;
            return first.height() == second.height() ? 0 : 1;
        }
    };

    private final String header;
    private final List<String> data;
    private int width;
    private int height;

    Column( String header, int width ) {
        this.header = header;
        this.data = new LinkedList<String>();
        this.width = Math.max( width, header.length() );
        this.height = 0;
    }

    int addCells( Object cellCandidate ) {
        int originalHeight = height;

        String source = String.valueOf( cellCandidate ).trim();
        BreakIterator words = BreakIterator.getLineInstance( Locale.US );
        words.setText( source );

        StringBuilder nextCell = new StringBuilder();

        for ( int start = words.first(), end = words.next();
            end != BreakIterator.DONE;
            start = end, end = words.next() ) {

            nextCell = processNextWord( source, nextCell, start, end );
        }

        if ( nextCell.length() > 0 )
            addCell( nextCell.toString() );

        return height - originalHeight;
    }

    private StringBuilder processNextWord( String source, StringBuilder nextCell,
        int start, int end ) {

        StringBuilder augmented = nextCell;

        String word = source.substring( start, end );
        if ( augmented.length() + word.length() > width ) {
            addCell( augmented.toString() );
            augmented = new StringBuilder( "  " ).append( word );
        }
        else
            augmented.append( word );

        return augmented;
    }

    void addCell( String newCell ) {
        data.add( newCell );
        ++height;
    }

    void writeHeaderOn( StringBuilder buffer, boolean appendSpace ) {
        buffer.append( header ).append( repeat( ' ', width - header.length() ) );

        if ( appendSpace )
            buffer.append( ' ' );
    }

    void writeSeparatorOn( StringBuilder buffer, boolean appendSpace ) {
        buffer.append( repeat( '-', header.length() ) )
            .append( repeat( ' ', width - header.length() ) );
        if ( appendSpace )
            buffer.append( ' ' );
    }

    void writeCellOn( int index, StringBuilder buffer, boolean appendSpace ) {
        if ( index < data.size() ) {
            String item = data.get( index );

            buffer.append( item ).append( repeat( ' ', width - item.length() ) );
            if ( appendSpace )
                buffer.append( ' ' );
        }
    }

    int height() {
        return height;
    }
}
