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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import static java.util.Collections.*;

/**
 * <p>Representation of a group of detected command line options, their arguments, and
 * non-option arguments.</p>
 *
 * @author <a href="mailto:pholser@alumni.rice.edu">Paul Holser</a>
 * @version $Id: OptionSet.java,v 1.17 2008/12/27 16:32:48 pholser Exp $
 */
public class OptionSet {
    private final Map<String, AbstractOptionSpec<?>> detectedOptions;
    private final Map<AbstractOptionSpec<?>, List<String>> optionsToArguments;
    private final List<String> nonOptionArguments;

    /**
     * Package-private because clients don't create these.
     */
    OptionSet() {
        detectedOptions = new HashMap<String, AbstractOptionSpec<?>>();
        optionsToArguments = new IdentityHashMap<AbstractOptionSpec<?>, List<String>>();
        nonOptionArguments = new ArrayList<String>();
    }

    /**
     * <p>Tells whether the given option was detected.</p>
     *
     * @param option the option to search for
     * @return {@code true} if the option was detected
     * @see #has(OptionSpec)
     */
    public boolean has( String option ) {
        return detectedOptions.containsKey( option );
    }

    /**
     * <p>Tells whether the given option was detected.</p>
     *
     * <p>This method recognizes only instances of options returned from the fluent
     * interface methods.</p>
     *
     * @param option the option to search for
     * @return {@code true} if the option was detected
     * @see #has(String)
     */
    public boolean has( OptionSpec<?> option ) {
        return optionsToArguments.containsKey( option );
    }

    /**
     * <p>Tells whether there are any arguments associated with the given option.</p>
     *
     * @param option the option to search for
     * @return {@code true} if the option was detected and at least one argument was
     * detected for the option
     */
    public boolean hasArgument( String option ) {
        return !valuesOf( option ).isEmpty();
    }

    /**
     * <p>Tells whether there are any arguments associated with the given option.</p>
     *
     * <p>This method recognizes only instances of options returned from the fluent
     * interface methods.</p>
     *
     * @param option the option to search for
     * @return {@code true} if the option was detected and at least one argument was
     * detected for the option
     */
    public boolean hasArgument( OptionSpec<?> option ) {
        return !valuesOf( option ).isEmpty();
    }

    /**
     * <p>Gives the argument associated with the given option.  If the option was given
     * an argument type, the argument will take on that type; otherwise, it will be a
     * {@link String}.</p>
     *
     * @param option the option to search for
     * @return the argument of the given option; {@code null} if no argument is
     * present, or that option was not detected
     * @throws OptionException if more than one argument was detected for the option
     */
    public Object valueOf( String option ) {
        return valueOf( detectedOptions.get( option ) );
    }

    /**
     * <p>Gives the argument associated with the given option.</p>
     *
     * <p>This method recognizes only instances of options returned from the fluent
     * interface methods.</p>
     *
     * @param <V> represents the type of the arguments the given option accepts
     * @param option the option to search for
     * @return the argument of the given option; {@code null} if no argument is
     * present, or that option was not detected
     * @throws OptionException if more than one argument was detected for the option
     * @throws ClassCastException if the arguments of this option are not of the
     * expected type
     */
    public <V> V valueOf( OptionSpec<V> option ) {
        if ( option == null )
            return null;

        List<V> values = valuesOf( option );
        switch ( values.size() ) {
            case 0:
                return null;
            case 1:
                return values.get( 0 );
            default:
                throw new MultipleArgumentsForOptionException( option.options() );
        }
    }

    /**
     * <p>Gives any arguments associated with the given option.  If the option was given
     * an argument type, the arguments will take on that type; otherwise, they will be
     * {@link String}s.</p>
     *
     * @param option the option to search for
     * @return the arguments associated with the option, as a list of objects of the
     * type given to the arguments; an empty list if no such arguments are present, or if
     * the option was not detected
     */
    public List<?> valuesOf( String option ) {
        return valuesOf( detectedOptions.get( option ) );
    }

    /**
     * <p>Gives any arguments associated with the given option.  If the option was given
     * an argument type, the arguments will take on that type; otherwise, they will be
     * {@link String}s.</p>
     *
     * <p>This method recognizes only instances of options returned from the fluent
     * interface methods.</p>
     *
     * @param <V> represents the type of the arguments the given option accepts
     * @param option the option to search for
     * @return the arguments associated with the option; an empty list if no such
     * arguments are present, or if the option was not detected
     * @throws OptionException if there is a problem converting the option's arguments to
     * the desired type; for example, if the type does not implement a correct conversion
     * constructor or method
     */
    public <V> List<V> valuesOf( OptionSpec<V> option ) {
        List<String> values = optionsToArguments.get( option );
        if ( values == null )
            return emptyList();

        AbstractOptionSpec<V> spec = (AbstractOptionSpec<V>) option;
        List<V> convertedValues = new ArrayList<V>();
        for ( String each : values )
            convertedValues.add( spec.convert( each ) );

        return unmodifiableList( convertedValues );
    }

    /**
     * @return the detected non-option arguments
     */
    public List<String> nonOptionArguments() {
        return unmodifiableList( nonOptionArguments );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals( Object that ) {
        if ( this == that )
            return true;

        if ( that == null || !getClass().equals( that.getClass() ) )
            return false;

        OptionSet other = (OptionSet) that;
        Map<AbstractOptionSpec<?>, List<String>> thisOptionsToArguments =
            new HashMap<AbstractOptionSpec<?>, List<String>>( optionsToArguments );
        Map<AbstractOptionSpec<?>, List<String>> otherOptionsToArguments =
            new HashMap<AbstractOptionSpec<?>, List<String>>( other.optionsToArguments );
        return detectedOptions.equals( other.detectedOptions )
            && thisOptionsToArguments.equals( otherOptionsToArguments )
            && nonOptionArguments.equals( other.nonOptionArguments() );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        Map<AbstractOptionSpec<?>, List<String>> thisOptionsToArguments =
            new HashMap<AbstractOptionSpec<?>, List<String>>( optionsToArguments );
        return detectedOptions.hashCode()
            ^ thisOptionsToArguments.hashCode()
            ^ nonOptionArguments.hashCode();
    }

    void add( AbstractOptionSpec<?> option ) {
        addWithArgument( option, null );
    }

    void addWithArgument( AbstractOptionSpec<?> option, String argument ) {
        for ( String each : option.options() )
            detectedOptions.put( each, option );

        List<String> optionArguments = optionsToArguments.get( option );

        if ( optionArguments == null ) {
            optionArguments = new ArrayList<String>();
            optionsToArguments.put( option, optionArguments );
        }

        if ( argument != null )
            optionArguments.add( argument );
    }

    void addNonOptionArgument( String argument ) {
        nonOptionArguments.add( argument );
    }
}
