/*
 * Copyright (c) 2003, Oracle and/or its affiliates. All rights reserved.
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


#ifndef REGISTRYKEY_H
#define REGISTRYKEY_H


/**
 * Meaning of the following variables:
 * - UNVERIFIED: this value has not yet been tested (and needs to be)
 * - TESTING: this value is currently being tested.  If we get this value
 * from the registry, this indicates that we probably crashed while we were
 * testing it last time, so we should disable the appropriate component.
 * - FAILURE: this component failed testing, so it should be disabled
 * - SUCCESS: this component succeeded, so we can enable it
 */
#define J2D_ACCEL_UNVERIFIED   -1
#define J2D_ACCEL_TESTING      0
#define J2D_ACCEL_FAILURE      1
#define J2D_ACCEL_SUCCESS      2

class RegistryKey {
public:
    RegistryKey(WCHAR *keyName, REGSAM permissions);
    ~RegistryKey();

    DWORD EnumerateSubKeys(DWORD index, WCHAR *subKeyName,
                           DWORD *buffSize);

    int  GetIntValue(WCHAR *valueName);

    static int  GetIntValue(WCHAR *keyName, WCHAR *valueName);

    BOOL SetIntValue(WCHAR *valueName, int regValue, BOOL flush);

    static BOOL SetIntValue(WCHAR *keyName, WCHAR *valueName, int regValue,
                            BOOL flush);

    static void DeleteKey(WCHAR *keyName);

    static void PrintValue(WCHAR *keyName, WCHAR *valueName,
                           WCHAR *msg);

private:
    HKEY hKey;
    static void PrintRegistryError(LONG errNum, char *message);
};

#endif REGISTRYKEY_H
