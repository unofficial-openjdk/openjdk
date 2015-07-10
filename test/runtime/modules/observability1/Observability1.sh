# 
#  Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
#  DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
# 
#  This code is free software; you can redistribute it and/or modify it
#  under the terms of the GNU General Public License version 2 only, as
#  published by the Free Software Foundation.
# 
#  This code is distributed in the hope that it will be useful, but WITHOUT
#  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
#  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
#  version 2 for more details (a copy is included in the LICENSE file that
#  accompanied this code).
# 
#  You should have received a copy of the GNU General Public License version
#  2 along with this work; if not, write to the Free Software Foundation,
#  Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
# 
#  Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
#  or visit www.oracle.com if you need additional information or have any
#  questions.
# 

# @test
# @summary Ensure -Xbootclasspath/a is considered within boot class loader's observable boundaries

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

echo $TESTSRC
rm -rf mods1

mkdir -p mods1
${JAVAC} -d mods1 $TESTSRC/p2/Observability1_B.java
${JAVAC} -d mods1 $TESTSRC/p2/Observability1_C.java
${JAVAC} -cp mods1 -d $TESTCLASSES $TESTSRC/Observability1_A.java

${JAVA} -Xbootclasspath/a:nonexistent.jar -Xbootclasspath/a:mods1 -cp $TESTCLASSES Observability1_A
exit $?
