#
# Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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
# @summary Basic test for ResourceBundle with modules;
#          ResourceBundle.Control is provided by a ResourceBundleControlProvider,
#          resource bundles are grouped in main, EU, and Asia.

set -e

if [ -z "$TESTJAVA" ]; then
  if [ $# -lt 1 ]; then exit 1; fi
  TESTJAVA="$1"; shift
  COMPILEJAVA="${TESTJAVA}"
  TESTSRC="`pwd`"
  TESTCLASSES="`pwd`"
fi

JAVAC="$COMPILEJAVA/bin/javac"
JAVA="$TESTJAVA/bin/java"

# This test is temporarily converted to use ResourceBundleProviderSupport class
# to avoid calling Control.newBundle
EXTRA_OPTS="-XX:AddModuleExports=java.base/sun.util.locale.provider"

rm -rf mods

CP=
for I in main eu asia
do
  B=${I}bundles
  mkdir -p mods/$B
  CLASSES="`find $TESTSRC/src/$B -name '*.java'`"
  if [ "x$CLASSES" != x ]; then
    $JAVAC ${EXTRA_OPTS} -g -d mods -modulesourcepath $TESTSRC/src $CP $CLASSES
  fi
  PROPS="`(cd $TESTSRC/src/$B; find . -name '*.properties')`"
  if [ "x$PROPS" != x ]; then
      for P in $PROPS
      do
        D=`dirname $P`
        mkdir -p mods/$B/$D
        cp $TESTSRC/src/$B/$P mods/$B/$D/
      done
  fi
  CP="-cp mods/mainbundles"
done

mkdir -p mods/test
$JAVAC ${EXTRA_OPTS} -g -cp mods/mainbundles -d mods -modulesourcepath $TESTSRC/src \
    `find $TESTSRC/src/test -name "*.java"`

$JAVA ${EXTRA_OPTS} -mp mods -m test/jdk.test.Main de fr ja zh-tw en de

exit $?
