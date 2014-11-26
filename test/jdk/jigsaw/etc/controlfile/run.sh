#!/bin/sh

#
# Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.
#
# This code is distributed in the hope that it will be useful, but WITHOUT
# ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
# FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
# version 2 for more details (a copy is included in the LICENSE file that
# accompanied this code).
#
# You should have received a copy of the GNU General Public License version
# 2 along with this work; if not, write to the Free Software Foundation,
# Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
#
# Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
# or visit www.oracle.com if you need additional information or have any
# questions.
#

# @test
# @summary Exercise resolving of modules that are packaged as jmod files
#     with a control file

if [ -z "$TESTJAVA" ]; then
  if [ $# -lt 1 ]; then exit 1; fi
  TESTJAVA="$1"; shift
  COMPILEJAVA="${TESTJAVA}"
  TESTSRC="`pwd`"
  TESTCLASSES="`pwd`"
fi

JAVAC="$COMPILEJAVA/bin/javac"
JAVA="$TESTJAVA/bin/java"
JLINK="$TESTJAVA/bin/jlink"

mkdir -p mods/lib mods/app mlib

# compile and package lib2@.0
$JAVAC -d mods/lib `find $TESTSRC/src/lib -name "*.java"`
$JLINK jlink --format jmod --class-path mods/lib --mid lib@2.0 \
    --output mlib/lib@2.0.jmod

# compile app
$JAVAC -d mods/app -cp mods/lib `find $TESTSRC/src/app -name "*.java"`

failures=0
failuresExpected=`ls $TESTSRC/fail|wc -l`

# package app with the given control file
go() {
    echo ''
    cat $d
    rm -rf mlib/app*.jmod
    sh -xc "$JLINK --format jmod --class-path mods/app \
        --main-class jdk.app.Main --control-file $1 \
        --output mlib/app@1.0.jmod" 2>&1
    if [ $? != 0 ]; then exit 1; fi
    sh -xc "$JAVA ${TESTVMOPTS} -mp mlib -m app" 2>&1
    if [ $? != 0 ]; then failures=`expr $failures + 1`; fi
}

# iterate through checked-in control files
for f in `find $TESTSRC/pass $TESTSRC/fail -type f`; do 
    go $f
done

echo ''
if [ $failures -ne $failuresExpected ];
  then echo "$failures test(s) failed"; exit $failiures;
  else echo "All test(s) passed"; exit 0;
fi
exit $failures
