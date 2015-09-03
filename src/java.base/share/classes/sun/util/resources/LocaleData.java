/*
 * Copyright (c) 1996, 2014, Oracle and/or its affiliates. All rights reserved.
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
 * (C) Copyright Taligent, Inc. 1996, 1997 - All Rights Reserved
 * (C) Copyright IBM Corp. 1996 - 1998 - All Rights Reserved
 *
 * The original version of this source code and documentation
 * is copyrighted and owned by Taligent, Inc., a wholly-owned
 * subsidiary of IBM. These materials are provided under terms
 * of a License Agreement between Taligent and Sun. This technology
 * is protected by multiple US and International patents.
 *
 * This notice and attribution to Taligent may not be removed.
 * Taligent is a registered trademark of Taligent, Inc.
 *
 */

package sun.util.resources;

import java.lang.reflect.Module;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.spi.AbstractResourceBundleProvider;
import sun.text.resources.JavaTimeSupplementaryProvider;
import sun.util.locale.provider.ResourceBundleProviderSupport;
import sun.util.locale.provider.LocaleProviderAdapter;
import static sun.util.locale.provider.LocaleProviderAdapter.Type.JRE;

/**
 * Provides information about and access to resource bundles in the
 * sun.text.resources and sun.util.resources packages or in their corresponding
 * packages for CLDR.
 *
 * @author Asmus Freytag
 * @author Mark Davis
 */

public class LocaleData {
    private static final ResourceBundle.Control defaultControl
        = ResourceBundle.Control.getControl(ResourceBundle.Control.FORMAT_DEFAULT);
    private final LocaleProviderAdapter.Type type;

    public LocaleData(LocaleProviderAdapter.Type type) {
        this.type = type;
    }

    /**
     * Gets a calendar data resource bundle, using privileges
     * to allow accessing a sun.* package.
     */
    public ResourceBundle getCalendarData(Locale locale) {
        return getBundle(type.getUtilResourcesPackage() + ".CalendarData", locale);
    }

    /**
     * Gets a currency names resource bundle, using privileges
     * to allow accessing a sun.* package.
     */
    public OpenListResourceBundle getCurrencyNames(Locale locale) {
        return (OpenListResourceBundle) getBundle(type.getUtilResourcesPackage() + ".CurrencyNames", locale);
    }

    /**
     * Gets a locale names resource bundle, using privileges
     * to allow accessing a sun.* package.
     */
    public OpenListResourceBundle getLocaleNames(Locale locale) {
        return (OpenListResourceBundle) getBundle(type.getUtilResourcesPackage() + ".LocaleNames", locale);
    }

    /**
     * Gets a time zone names resource bundle, using privileges
     * to allow accessing a sun.* package.
     */
    public TimeZoneNamesBundle getTimeZoneNames(Locale locale) {
        return (TimeZoneNamesBundle) getBundle(type.getUtilResourcesPackage() + ".TimeZoneNames", locale);
    }

    /**
     * Gets a break iterator info resource bundle, using privileges
     * to allow accessing a sun.* package.
     */
    public ResourceBundle getBreakIteratorInfo(Locale locale) {
        return getBundle(type.getTextResourcesPackage() + ".BreakIteratorInfo", locale);
    }

    /**
     * Gets a collation data resource bundle, using privileges
     * to allow accessing a sun.* package.
     */
    public ResourceBundle getCollationData(Locale locale) {
        return getBundle(type.getTextResourcesPackage() + ".CollationData", locale);
    }

    /**
     * Gets a date format data resource bundle, using privileges
     * to allow accessing a sun.* package.
     */
    public ResourceBundle getDateFormatData(Locale locale) {
        return getBundle(type.getTextResourcesPackage() + ".FormatData", locale);
    }

    public void setSupplementary(ParallelListResourceBundle formatData) {
        if (!formatData.areParallelContentsComplete()) {
            String suppName = type.getTextResourcesPackage() + ".JavaTimeSupplementary";
            setSupplementary(suppName, formatData);
        }
    }

    private boolean setSupplementary(String suppName, ParallelListResourceBundle formatData) {
        ParallelListResourceBundle parent = (ParallelListResourceBundle) formatData.getParent();
        boolean resetKeySet = false;
        if (parent != null) {
            resetKeySet = setSupplementary(suppName, parent);
        }
        OpenListResourceBundle supp = getSupplementary(suppName, formatData.getLocale());
        formatData.setParallelContents(supp);
        resetKeySet |= supp != null;
        // If any parents or this bundle has parallel data, reset keyset to create
        // a new keyset with the data.
        if (resetKeySet) {
            formatData.resetKeySet();
        }
        return resetKeySet;
    }

    /**
     * Gets a number format data resource bundle, using privileges
     * to allow accessing a sun.* package.
     */
    public ResourceBundle getNumberFormatData(Locale locale) {
        return getBundle(type.getTextResourcesPackage() + ".FormatData", locale);
    }

    public static ResourceBundle getBundle(final String baseName, final Locale locale) {
        ResourceBundle bundle;
        bundle = AccessController.doPrivileged(new PrivilegedAction<>() {
            @Override
            public ResourceBundle run() {
                return ResourceBundle.getBundle(baseName, locale);
            }
        });
        // Make sure the bundle is for the given locale
        if (isRequestedBundle(bundle, locale)) {
            return bundle;
        }
        return AccessController.doPrivileged(new PrivilegedAction<>() {
            @Override
            public ResourceBundle run() {
                return ResourceBundle.getBundle(baseName, Locale.ROOT);
            }
        });
    }

    private static OpenListResourceBundle getSupplementary(final String baseName, final Locale locale) {
        return AccessController.doPrivileged(new PrivilegedAction<>() {
           @Override
           public OpenListResourceBundle run() {
               OpenListResourceBundle rb = null;
               try {
                   rb = (OpenListResourceBundle) ResourceBundle.getBundle(baseName,
                                                                          locale);
                   // getSupplementary() requires strict bundle loading for the
                   // given locale.
                   if (!locale.equals(rb.getLocale())) {
                       rb = null;
                   }
               } catch (MissingResourceException e) {
                   // return null if no supplementary is available
               }
               return rb;
           }
        });
    }

    /**
     * Returns true if the targetLocale is the default Locale, or if the Locale
     * of the given bundle is any of the candidate locales (including
     * Locale.ROOT) for the targetLocale.
     */
    private static boolean isRequestedBundle(ResourceBundle bundle, Locale targetLocale) {
        if (targetLocale.equals(Locale.getDefault())) {
            return true;
        }
        Locale bundleLocale = bundle.getLocale();
        if (targetLocale.equals(bundleLocale)) {
            return true;
        }
        List<Locale> candidates = defaultControl.getCandidateLocales("", targetLocale);
        return candidates.stream().anyMatch((candidate) -> (bundleLocale.equals(candidate)));
    }

    private static abstract class LocaleDataResourceBundleProvider extends AbstractResourceBundleProvider {
        protected static final String DOTCLDR = ".cldr";

        abstract protected boolean isSupportedInModule(String baseName, Locale locale);

        /**
         * Changes baseName to its per-language/country package name and
         * calls the super class implementation. For example,
         * if the baseName is "sun.text.resources.FormatData" and locale is ja_JP,
         * the baseName is changed to "sun.text.resources.ja.JP.FormatData". If
         * baseName contains "cldr", such as "sun.text.resources.cldr.FormatData",
         * the name is changed to "sun.text.resources.cldr.ja.JP.FormatData".
         */
        @Override
        protected String toBundleName(String baseName, Locale locale) {
            String newBaseName = baseName;
            String lang = locale.getLanguage();
            String ctry = locale.getCountry();
            if (lang.length() > 0) {
                if (baseName.startsWith(JRE.getUtilResourcesPackage())
                        || baseName.startsWith(JRE.getTextResourcesPackage())) {
                    // Assume the lengths are the same.
                    assert JRE.getUtilResourcesPackage().length()
                        == JRE.getTextResourcesPackage().length();
                    int index = JRE.getUtilResourcesPackage().length();
                    if (baseName.indexOf(DOTCLDR, index) > 0) {
                        index += DOTCLDR.length();
                    }
                    ctry = (ctry.length() == 2) ? ("." + ctry) : "";
                    newBaseName = baseName.substring(0, index + 1) + lang + ctry
                                      + baseName.substring(index);
                }
            }
            return defaultControl.toBundleName(newBaseName, locale);
        }

        @Override
        public ResourceBundle getBundle(String baseName, Locale locale) {
            if (isSupportedInModule(baseName, locale)) {
                Module module = LocaleData.class.getModule();
                String bundleName = toBundleName(baseName, locale);
                return ResourceBundleProviderSupport.loadResourceBundle(module, bundleName);
            }
            return null;
        }
    }

    public static class BaseResourceBundleProvider extends LocaleDataResourceBundleProvider
                                                   implements sun.text.resources.BreakIteratorInfoProvider,
                                                              sun.text.resources.BreakIteratorRulesProvider,
                                                              sun.text.resources.FormatDataProvider,
                                                              sun.text.resources.CollationDataProvider,
                                                              sun.text.resources.cldr.FormatDataProvider,
                                                              sun.util.resources.LocaleNamesProvider,
                                                              sun.util.resources.TimeZoneNamesProvider,
                                                              sun.util.resources.CalendarDataProvider,
                                                              sun.util.resources.CurrencyNamesProvider,
                                                              sun.util.resources.cldr.LocaleNamesProvider,
                                                              sun.util.resources.cldr.TimeZoneNamesProvider,
                                                              sun.util.resources.cldr.CalendarDataProvider,
                                                              sun.util.resources.cldr.CurrencyNamesProvider {
        @Override
        protected boolean isSupportedInModule(String baseName, Locale locale) {
            // TODO: avoid hard-coded Locales
            return locale.equals(Locale.ROOT) ||
                (locale.getLanguage() == "en" &&
                    (locale.getCountry().isEmpty() ||
                     locale.getCountry() == "US" ||
                     locale.getCountry().length() == 3)); // UN.M49
        }
    }

    public static class SupplementaryResourceBundleProvider extends LocaleDataResourceBundleProvider
                                                            implements JavaTimeSupplementaryProvider {
        @Override
        protected boolean isSupportedInModule(String baseName, Locale locale) {
            // TODO: avoid hard-coded Locales
            return locale.equals(Locale.ROOT) || locale.getLanguage() == "en";
        }

        @Override
        public ResourceBundle getBundle(String baseName, Locale locale) {
            ResourceBundle bundle = super.getBundle(baseName, locale);
            if (bundle instanceof OpenListResourceBundle && !locale.equals(Locale.ROOT)) {
                // Set the parent bundle to the empty one so that the getter methods
                // don't look up its parents.
                ((OpenListResourceBundle)bundle).setParentBundle(EmptyBundle.INSTANCE);
            }
            return bundle;
        }
    }

    private static class EmptyBundle extends ResourceBundle {
        private static final EmptyBundle INSTANCE = new EmptyBundle();

        private EmptyBundle() {
        }

        @Override
        protected Object handleGetObject(String key) {
            return null;
        }

        @Override
        protected Set<String> handleKeySet() {
            return Collections.emptySet();
        }

        @Override
        public Enumeration<String> getKeys() {
            return Collections.emptyEnumeration();
        }
    }
}
