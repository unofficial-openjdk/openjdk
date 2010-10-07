#!/bin/sh

##
## Copyright (c) 2010, Oracle and/or its affiliates. All rights reserved.
## DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
##
## This code is free software; you can redistribute it and/or modify it
## under the terms of the GNU General Public License version 2 only, as
## published by the Free Software Foundation.
##
## This code is distributed in the hope that it will be useful, but WITHOUT
## ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
## FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
## version 2 for more details (a copy is included in the LICENSE file that
## accompanied this code).
##
## You should have received a copy of the GNU General Public License version
## 2 along with this work; if not, write to the Free Software Foundation,
## Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
##
## Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
## or visit www.oracle.com if you need additional information or have any
## questions.
##

if [ "${TESTSRC}" = "" ]
then TESTSRC=.
fi

if [ "${TESTJAVA}" = "" ]
then
  PARENT=`dirname \`which java\``
  TESTJAVA=`dirname ${PARENT}`
  echo "TESTJAVA not set, selecting " ${TESTJAVA}
  echo "If this is incorrect, try setting the variable manually."
fi

if [ "${TESTCLASSES}" = "" ]
then
  echo "TESTCLASSES not set.  Test cannot execute.  Failed."
  exit 1
fi

BIT_FLAG=""

# set platform-dependent variables
OS=`uname -s`
case "$OS" in
  SunOS | Linux )
    NULL=/dev/null
    PS=":"
    FS="/"
    ## for solaris, linux it's HOME
    FILE_LOCATION=$HOME
    if [ -f ${FILE_LOCATION}${FS}JDK64BIT -a ${OS} = "SunOS" ]
    then
        BIT_FLAG=`cat ${FILE_LOCATION}${FS}JDK64BIT | grep -v '^#'`
    fi
    ;;
  Windows_* )
    NULL=NUL
    PS=";"
    FS="\\"
    ;;
  * )
    echo "Unrecognized system!"
    exit 1;
    ;;
esac

JEMMYPATH=${CPAPPEND}
CLASSPATH=.${PS}${TESTCLASSES}${PS}${JEMMYPATH} ; export CLASSPATH

THIS_DIR=`pwd`

${TESTJAVA}${FS}bin${FS}java ${BIT_FLAG} -version

cp ${TESTSRC}${FS}*.java .
chmod 777 *.java

${TESTJAVA}${FS}bin${FS}javac SerialRace.java

${TESTJAVA}${FS}bin${FS}java ${BIT_FLAG} SerialRace > test.out 2>&1

cat test.out

STATUS=0

egrep "java.io.NotActiveException|not in readObject invocation or fields already read|^Victim" test.out

if [ $? = 0 ]
then
    STATUS=1
else
    grep "java.lang.NullPointerException" test.out

    if [ $? != 0 ]; then
        STATUS=1
    fi
fi

exit $STATUS
