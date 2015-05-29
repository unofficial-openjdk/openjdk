/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
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

module java.base {
    exports java.io;
    exports java.lang;
    exports java.lang.annotation;
    exports java.lang.invoke;
    exports java.lang.module;
    exports java.lang.ref;
    exports java.lang.reflect;
    exports java.math;
    exports java.net;
    exports java.net.spi;
    exports java.nio;
    exports java.nio.channels;
    exports java.nio.channels.spi;
    exports java.nio.charset;
    exports java.nio.charset.spi;
    exports java.nio.file;
    exports java.nio.file.attribute;
    exports java.nio.file.spi;
    exports java.security;
    exports java.security.acl;
    exports java.security.cert;
    exports java.security.interfaces;
    exports java.security.spec;
    exports java.text;
    exports java.text.spi;
    exports java.time;
    exports java.time.chrono;
    exports java.time.format;
    exports java.time.temporal;
    exports java.time.zone;
    exports java.util;
    exports java.util.concurrent;
    exports java.util.concurrent.atomic;
    exports java.util.concurrent.locks;
    exports java.util.function;
    exports java.util.jar;
    exports java.util.regex;
    exports java.util.spi;
    exports java.util.stream;
    exports java.util.zip;
    exports javax.crypto;
    exports javax.crypto.interfaces;
    exports javax.crypto.spec;
    exports javax.net;
    exports javax.net.ssl;
    exports javax.security.auth;
    exports javax.security.auth.callback;
    exports javax.security.auth.login;
    exports javax.security.auth.spi;
    exports javax.security.auth.x500;
    exports javax.security.cert;
    exports jdk;
    exports jdk.net;
    // additional qualified exports may be inserted at build time
    // see make/gensrc/GenModuleInfo.gmk
    exports com.sun.security.ntlm to
        java.security.sasl;
    exports jdk.internal.jimage to
        jdk.jlink;
    exports jdk.internal.jimage.decompressor to
        jdk.jlink;
    exports jdk.internal.org.objectweb.asm to
        java.instrument,
        jdk.jlink,
        jdk.scripting.nashorn;
    exports jdk.internal.org.objectweb.asm.commons to
        java.instrument,
        jdk.scripting.nashorn;
    exports jdk.internal.org.objectweb.asm.signature to
        jdk.scripting.nashorn;
    exports jdk.internal.org.objectweb.asm.util to
        jdk.scripting.nashorn;
    exports jdk.internal.module to
        jdk.jlink;
    exports sun.misc to
        java.corba,
        java.desktop,
        java.logging,
        java.management,
        java.naming,
        java.prefs,
        java.rmi,
        java.security.jgss,
        java.security.sasl,
        java.sql,
        java.xml,
        java.xml.ws,
        jdk.charsets,
        jdk.crypto.pkcs11,
        jdk.httpserver,
        jdk.jartool,
        jdk.jconsole,
        jdk.jvmstat,
        jdk.scripting.nashorn,
        jdk.security.auth,
        jdk.security.jgss;
    exports sun.net.dns to
        java.security.jgss,
        jdk.naming.dns;
    exports sun.net.spi.nameservice to
        jdk.naming.dns;
    exports sun.net.util to
        jdk.jconsole,
        jdk.naming.dns;
    exports sun.net.www to
        java.desktop,
        jdk.compiler,
        jdk.jartool;
    exports sun.net.www.protocol.http to
        java.security.jgss;
    exports sun.nio.ch to
        java.management,
        jdk.crypto.pkcs11,
        jdk.sctp;
    exports sun.nio.cs to
        java.desktop,
        jdk.charsets;
    exports sun.reflect to
        java.corba,
        java.logging,
        java.sql,
        java.sql.rowset,
        jdk.scripting.nashorn;
    exports sun.reflect.annotation to
        jdk.compiler;
    exports sun.reflect.generics.reflectiveObjects to
        java.desktop;
    exports sun.reflect.misc to
        java.corba,
        java.desktop,
        java.datatransfer,
        java.management,
        java.rmi,
        java.sql.rowset,
        java.xml,
        java.xml.ws;
    exports sun.security.action to
        java.desktop,
        java.security.jgss,
        jdk.crypto.ec,
        jdk.crypto.pkcs11;
    exports sun.security.internal.interfaces to
        jdk.crypto.pkcs11;
    exports sun.security.internal.spec to
        jdk.crypto.pkcs11;
    exports sun.security.jca to
        java.smartcardio,
        java.xml.crypto,
        jdk.crypto.ec,
        jdk.crypto.pkcs11,
        jdk.naming.dns;
    exports sun.security.pkcs to
        jdk.crypto.ec,
        jdk.jartool;
    exports sun.security.provider to
        java.rmi,
        java.security.jgss,
        jdk.crypto.pkcs11,
        jdk.jartool,
        jdk.policytool,
        jdk.security.auth;
    exports sun.security.provider.certpath to
        java.naming;
    exports sun.security.rsa to
        jdk.crypto.pkcs11;
    exports sun.security.ssl to
        java.security.jgss;
    exports sun.security.tools to
        jdk.jartool;
    exports sun.security.util to
        java.desktop,
        java.naming,
        java.rmi,
        java.security.jgss,
        java.smartcardio,
        jdk.crypto.ec,
        jdk.crypto.pkcs11,
        jdk.jartool,
        jdk.policytool,
        jdk.security.auth;
    exports sun.security.x509 to
        java.naming,
        jdk.crypto.ec,
        jdk.crypto.pkcs11,
        jdk.jartool,
        jdk.security.auth;
    exports sun.text to
        java.desktop;
    exports sun.text.resources to
        jdk.localedata;
    exports sun.util to
        java.desktop;
    exports sun.util.locale.provider to
        java.desktop,
        jdk.localedata;
    exports sun.util.logging to
        java.desktop,
        java.logging,
        java.management,
        java.prefs;
    exports sun.util.resources to
        jdk.localedata;
    uses java.net.spi.URLStreamHandlerProvider;
    uses java.nio.channels.spi.AsynchronousChannelProvider;
    uses java.nio.channels.spi.SelectorProvider;
    uses java.nio.charset.spi.CharsetProvider;
    uses java.nio.file.spi.FileSystemProvider;
    uses java.nio.file.spi.FileTypeDetector;
    uses java.security.Provider;
    uses java.text.spi.BreakIteratorProvider;
    uses java.text.spi.CollatorProvider;
    uses java.text.spi.DateFormatProvider;
    uses java.text.spi.DateFormatSymbolsProvider;
    uses java.text.spi.DecimalFormatSymbolsProvider;
    uses java.text.spi.NumberFormatProvider;
    uses java.time.chrono.AbstractChronology;
    uses java.time.chrono.Chronology;
    uses java.time.zone.ZoneRulesProvider;
    uses java.util.spi.CalendarDataProvider;
    uses java.util.spi.CalendarNameProvider;
    uses java.util.spi.CurrencyNameProvider;
    uses java.util.spi.LocaleNameProvider;
    uses java.util.spi.ResourceBundleControlProvider;
    uses java.util.spi.ResourceBundleProvider;
    uses java.util.spi.TimeZoneNameProvider;
    uses javax.security.auth.spi.LoginModule;
    uses sun.net.spi.nameservice.NameServiceDescriptor;
    uses sun.text.resources.BreakIteratorInfoProvider;
    uses sun.text.resources.BreakIteratorRulesProvider;
    uses sun.text.resources.FormatDataProvider;
    uses sun.text.resources.CollationDataProvider;
    uses sun.text.resources.JavaTimeSupplementaryProvider;
    uses sun.util.resources.LocaleNamesProvider;
    uses sun.util.resources.TimeZoneNamesProvider;
    uses sun.util.resources.CalendarDataProvider;
    uses sun.util.resources.CurrencyNamesProvider;
    uses sun.util.locale.provider.LocaleDataMetaInfo;
    uses sun.util.spi.CalendarProvider;
    provides java.nio.file.spi.FileSystemProvider with jdk.internal.jrtfs.JrtFileSystemProvider;
    provides java.security.Provider with sun.security.provider.Sun;
    provides java.security.Provider with sun.security.rsa.SunRsaSign;
    provides java.security.Provider with com.sun.crypto.provider.SunJCE;
    provides java.security.Provider with com.sun.net.ssl.internal.ssl.Provider;
}

