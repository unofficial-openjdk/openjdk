#
# Copyright 1999-2006 Sun Microsystems, Inc.  All Rights Reserved.
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
# Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
# CA 95054 USA or visit www.sun.com if you need additional information or
# have any questions.
#  
#

!include $(WorkSpace)/build/windows/makefiles/compile.make

# Rules for building adlc.exe

# Need exception handling support here
# $(MS_RUNTIME_OPTION) ( with /D_STATIC_CPPLIB)
# causes adlc.exe to link with the static
# multithread Standard C++ library (libcpmt.lib) instead of
# the dynamic version (msvcprt.lib), which is not included
# in any of the free tools.
EXH_FLAGS=$(GX_OPTION) $(MS_RUNTIME_OPTION)

!ifdef ALT_ADLC_PATH
ADLC=$(ALT_ADLC_PATH)\adlc.exe
!else
ADLC=adlc
!endif

!ifdef LP64
ADLCFLAGS=-q -T -D_LP64
!else
ADLCFLAGS=-q -T -U_LP64
!endif


CPP_INCLUDE_DIRS=\
  /I "..\generated"                          \
  /I "$(WorkSpace)\src\share\vm\compiler"    \
  /I "$(WorkSpace)\src\share\vm\code"        \
  /I "$(WorkSpace)\src\share\vm\interpreter" \
  /I "$(WorkSpace)\src\share\vm\classfile"   \
  /I "$(WorkSpace)\src\share\vm\asm"         \
  /I "$(WorkSpace)\src\share\vm\memory"      \
  /I "$(WorkSpace)\src\share\vm\oops"        \
  /I "$(WorkSpace)\src\share\vm\prims"       \
  /I "$(WorkSpace)\src\share\vm\runtime"     \
  /I "$(WorkSpace)\src\share\vm\utilities"   \
  /I "$(WorkSpace)\src\share\vm\libadt"      \
  /I "$(WorkSpace)\src\share\vm\opto"        \
  /I "$(WorkSpace)\src\os\win32\vm"          \
  /I "$(WorkSpace)\src\cpu\$(ARCH)\vm"

# NOTE! If you add any files here, you must also update GENERATED_NAMES_IN_INCL
# and MakeDepsIDEOptions in makedeps.make. 
GENERATED_NAMES=\
  ad_$(ARCH).cpp \
  ad_$(ARCH).hpp \
  ad_$(ARCH)_clone.cpp \
  ad_$(ARCH)_expand.cpp \
  ad_$(ARCH)_format.cpp \
  ad_$(ARCH)_gen.cpp \
  ad_$(ARCH)_misc.cpp \
  ad_$(ARCH)_peephole.cpp \
  ad_$(ARCH)_pipeline.cpp \
  adGlobals_$(ARCH).hpp \
  dfa_$(ARCH).cpp

# NOTE! This must be kept in sync with GENERATED_NAMES
GENERATED_NAMES_IN_INCL=\
  incls/ad_$(ARCH).cpp \
  incls/ad_$(ARCH).hpp \
  incls/ad_$(ARCH)_clone.cpp \
  incls/ad_$(ARCH)_expand.cpp \
  incls/ad_$(ARCH)_format.cpp \
  incls/ad_$(ARCH)_gen.cpp \
  incls/ad_$(ARCH)_misc.cpp \
  incls/ad_$(ARCH)_peephole.cpp \
  incls/ad_$(ARCH)_pipeline.cpp \
  incls/adGlobals_$(ARCH).hpp \
  incls/dfa_$(ARCH).cpp

{$(WorkSpace)\src\share\vm\adlc}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(EXH_FLAGS) $(CPP_INCLUDE_DIRS) /c $<

{$(WorkSpace)\src\share\vm\opto}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(EXH_FLAGS) $(CPP_INCLUDE_DIRS) /c $<

adlc.exe: main.obj adlparse.obj archDesc.obj arena.obj dfa.obj dict2.obj filebuff.obj \
          forms.obj formsopt.obj formssel.obj opcodes.obj output_c.obj output_h.obj
	$(LINK) $(LINK_FLAGS) /subsystem:console /out:$@ $**

$(GENERATED_NAMES_IN_INCL): $(ARCH).ad adlc.exe includeDB.current 
	rm -f $(GENERATED_NAMES)
	$(ADLC) $(ADLCFLAGS) $(ARCH).ad
	mv $(GENERATED_NAMES) incls/

$(ARCH).ad: $(WorkSpace)/src/cpu/$(ARCH)/vm/$(ARCH).ad $(WorkSpace)/src/os_cpu/win32_$(ARCH)/vm/win32_$(ARCH).ad
	rm -f $(ARCH).ad
	cat $(WorkSpace)/src/cpu/$(ARCH)/vm/$(ARCH).ad  \
	    $(WorkSpace)/src/os_cpu/win32_$(ARCH)/vm/win32_$(ARCH).ad >$(ARCH).ad
