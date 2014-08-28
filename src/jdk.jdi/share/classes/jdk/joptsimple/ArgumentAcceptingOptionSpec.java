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

import java.util.Collection;
import java.util.StringTokenizer;

import jdk.joptsimple.internal.ReflectionException;
import jdk.joptsimple.internal.ValueConverter;
import static jdk.joptsimple.internal.Reflection.*;
import static jdk.joptsimple.internal.Strings.*;

/**
 * <p>Specification of an option that accepts an argument.</p>
 *
 * <p>Instances are returned from {@link OptionSpecBuilder} methods to allow the formation
 * of parser directives as sentences in a "fluent interface" language.  For example:</p>
 *
 * <pre>
 *   <code>
 *   OptionParser parser = new OptionParser();
 *   parser.accepts( "c" ).withRequiredArg().<strong>ofType( Integer.class )</strong>;
 *   </code>
 * </pre>
 *
 * <p>If no methods are invoked on an instance of this class, then that instance's option
 * will treat its argument as a {@link String}.</p>
 *
 * @param <V> represents the type of the arguments this option accepts
 * @author <a href="mailto:pholser@alumni.rice.edu">Paul Holser</a>
 * @version $Id: ArgumentAcceptingOptionSpec.java,v 1.29 2008/12/27 23:24:03 pholser Exp $
 */
public abstract class ArgumentAcceptingOptionSpec<V> extends AbstractOptionSpec<V> {
    private static final char NIL_VALUE_SEPARATOR = '\u0000';

    private final boolean argumentRequired;
    private ValueConverter<V> converter;
    private String argumentDescription = "";
    private String valueSeparator = String.valueOf( NIL_VALUE_SEPARATOR );

    ArgumentAcceptingOptionSpec( String option, boolean argumentRequired ) {
        super( option );

        this.argumentRequired = argumentRequired;
    }

    ArgumentAcceptingOptionSpec( Collection<String> options, boolean argumentRequired,
        String description ) {

        super( options, description );

        this.argumentRequired = argumentRequired;
    }

    /**
     * <p>Specifies a type to which arguments of this spec's option are to be
     * converted.</p>
     *
     * <p>JOpt Simple accepts types that have either:</p>
     *
     * <ol>
     *   <li>a public static method called {@code valueOf} which accepts a single
     *   argument of type {@link String} and whose return type is the same as the class
     *   on which the method is declared.  The {@code java.lang} primitive wrapper
     *   classes have such methods.</li>
     *
     *   <li>a public constructor which accepts a single argument of type
     *   {@link String}.</li>
     * </ol>
     *
     * <p>This class converts arguments using those methods in that order; that is,
     * {@code valueOf} would be invoked before a one-{@link String}-arg constructor
     * would.</p>
     *
     * @param <T> represents the runtime class of the desired option argument type
     * @param argumentType desired type of arguments to this spec's option
     * @return self, so that the caller can add clauses to the fluent interface sentence
     * @throws NullPointerException if the type is {@code null}
     * @throws IllegalArgumentException if the type does not have the standard conversion
     * methods
     */
    @SuppressWarnings( "unchecked" )
    public <T> ArgumentAcceptingOptionSpec<T> ofType( Class<T> argumentType ) {
        converter = (ValueConverter<V>) findConverter( argumentType );
        return (ArgumentAcceptingOptionSpec<T>) this;
    }

    /**
     * <p>Specifies a description for the argument of the option that this spec
     * represents.  This description is used when generating help information about
     * the parser.</p>
     *
     * @param description describes the nature of the argument of this spec's
     * option
     * @return self, so that the caller can add clauses to the fluent interface sentence
     */
    public final ArgumentAcceptingOptionSpec<V> describedAs( String description ) {
        argumentDescription = description;

        return this;
    }

    /**
     * <p>Specifies a value separator for the argument of the option that this spec
     * represents.  This allows a single option argument to represent multiple values
     * for the option.  For example:</p>
     *
     * <pre>
     *   <code>
     *   parser.accepts( "z" ).withRequiredArg()
     *       .<strong>withValuesSeparatedBy( ',' )</strong>;
     *   OptionSet options = parser.parse( new String[] { "-z", "foo,bar,baz", "-z",
     *       "fizz", "-z", "buzz" } );
     *   </code>
     * </pre>
     *
     * <p>Then {@code options.valuesOf( "z" )} would yield the list {@code [foo, bar,
     * baz, fizz, buzz]}.</p>
     *
     * <p>You cannot use Unicode U+0000 as the separator.</p>
     *
     * @param separator a character separator
     * @return self, so that the caller can add clauses to the fluent interface sentence
     * @throws IllegalArgumentException if the separator is Unicode U+0000
     */
    public final ArgumentAcceptingOptionSpec<V> withValuesSeparatedBy( char separator ) {
        if ( separator == NIL_VALUE_SEPARATOR )
            throw new IllegalArgumentException( "cannot use U+0000 as separator" );

        valueSeparator = String.valueOf( separator );
        return this;
    }

    @Override
    final void handleOption( OptionParser parser, ArgumentList arguments,
        OptionSet detectedOptions, String detectedArgument ) {

        if ( isNullOrEmpty( detectedArgument ) )
            detectOptionArgument( parser, arguments, detectedOptions );
        else
            addArguments( detectedOptions, detectedArgument );
    }

    protected void addArguments( OptionSet detectedOptions, String detectedArgument ) {
        StringTokenizer lexer = new StringTokenizer( detectedArgument, valueSeparator );
        while ( lexer.hasMoreTokens() )
            detectedOptions.addWithArgument( this, lexer.nextToken() );
    }

    protected abstract void detectOptionArgument( OptionParser parser,
        ArgumentList arguments, OptionSet detectedOptions );

    @SuppressWarnings( "unchecked" )
    @Override
    protected final V convert( String argument ) {
        if ( converter == null )
            return (V) argument;

        try {
            return converter.convert( argument );
        }
        catch ( ReflectionException ex ) {
            throw new OptionArgumentConversionException(
                options(), argument, converter.valueType(), ex );
        }
    }

    protected boolean canConvertArgument( String argument ) {
        StringTokenizer lexer = new StringTokenizer( argument, valueSeparator );

        try {
            while ( lexer.hasMoreTokens() )
                convert( lexer.nextToken() );
            return true;
        }
        catch ( OptionException ignored ) {
            return false;
        }
    }

    protected boolean isArgumentOfNumberType() {
        return converter != null
            && Number.class.isAssignableFrom( converter.valueType() );
    }

    @Override
    boolean acceptsArguments() {
        return true;
    }

    @Override
    boolean requiresArgument() {
        return argumentRequired;
    }

    String argumentDescription() {
        return argumentDescription;
    }

    Class<?> argumentType() {
        return converter == null ? String.class : converter.valueType();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean equals( Object that ) {
        if ( !super.equals( that ) )
            return false;

        ArgumentAcceptingOptionSpec<?> other = (ArgumentAcceptingOptionSpec<?>) that;
        return requiresArgument() == other.requiresArgument();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        return super.hashCode() ^ ( argumentRequired ? 0 : 1 );
    }
}
