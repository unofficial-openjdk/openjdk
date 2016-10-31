#
# Copyright (c) 2012, 2015, Oracle and/or its affiliates. All rights reserved.
# DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
#
# This code is free software; you can redistribute it and/or modify it
# under the terms of the GNU General Public License version 2 only, as
# published by the Free Software Foundation.  Oracle designates this
# particular file as subject to the "Classpath" exception as provided
# by Oracle in the LICENSE file that accompanied this code.
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

###
### This file is just a very small wrapper needed to run the real make/Init.gmk.
### It also performs some sanity checks on make.
###

# The shell code below will be executed on /usr/bin/make on Solaris, but not in GNU Make.
# /usr/bin/make lacks basically every other flow control mechanism.
.TEST_FOR_NON_GNUMAKE:sh=echo You are not using GNU Make/gmake, this is a requirement. Check your path. 1>&2 && exit 1

# The .FEATURES variable is likely to be unique for GNU Make.
ifeq ($(.FEATURES), )
  $(info Error: '$(MAKE)' does not seem to be GNU Make, which is a requirement.)
  $(info Check your path, or upgrade to GNU Make 3.81 or newer.)
  $(error Cannot continue)
endif

# Assume we have GNU Make, but check version.
ifeq ($(strip $(foreach v, 3.81% 3.82% 4.%, $(filter $v, $(MAKE_VERSION)))), )
  $(info Error: This version of GNU Make is too low ($(MAKE_VERSION)).)
  $(info Check your path, or upgrade to GNU Make 3.81 or newer.)
  $(error Cannot continue)
endif

#ifeq关键字用来判断参数是否相等
#strip函数 ：去掉字串(若干单词,使用若干空字符分割)“STRINT”开头和结尾的空字符,并将其中多个连续空字符合并为一个空字符 
#           eg.$(strip ' a   b c  ') 结果：'a b c'
#foreach函数语法：
#  $(foreach VAR,LIST,TEXT)
#  首先展开变量“VAR”和“LIST”的引用;而表达式“TEXT”中的变量 引用不展开。执行时把“LIST”中使用空格分割的单词依次取出赋值给变量 “VAR”,然后执行“TEXT”表达式。重复直到“LIST”的最后一个单词(为 空时结束)。“TEXT”中的变量或者函数引用在执行时才被展开
#  返回值:空格分割的多次表达式“TEXT”的计算的结果。
#MAKE_VERSION：  内嵌变量“MAKE_VERSION”代表当前的make版本。
#filter函数语法：
#  $(filter PATTERN ,TEXT)
#  过滤掉字串“TEXT”中所有不符合模式“PATTERN”的单词,保留所有符合此模式的单词。可以使用多个模式。模式中一般需要包含模式字符“%”。存在多个模式时,模式表达式之间使用空格分割
#返回值：空格分割的“TEXT”字串中所有符合模式“PATTERN”的字串



# In Cygwin, the MAKE variable gets prepended with the current directory if the
# make executable is called using a Windows mixed path (c:/cygwin/bin/make.exe).
ifneq ($(findstring :, $(MAKE)), )
  MAKE := $(patsubst $(CURDIR)%, %, $(patsubst $(CURDIR)/%, %, $(MAKE)))
endif

# Locate this Makefile
ifeq ($(filter /%, $(lastword $(MAKEFILE_LIST))),)
  makefile_path := $(CURDIR)/$(strip $(lastword $(MAKEFILE_LIST)))
else
  makefile_path := $(lastword $(MAKEFILE_LIST))
endif
topdir := $(strip $(patsubst %/, %, $(dir $(makefile_path))))

# ... and then we can include the real makefile
include $(topdir)/make/Init.gmk
