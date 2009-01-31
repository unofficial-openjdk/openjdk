#
# Copyright 1997-2007 Sun Microsystems, Inc.  All Rights Reserved.
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

# Resource file containing VERSIONINFO
Res_Files=.\version.res

!ifdef RELEASE 
!ifdef DEVELOP
CPP_FLAGS=$(CPP_FLAGS) /D "DEBUG"
!else
CPP_FLAGS=$(CPP_FLAGS) /D "PRODUCT"
!endif
!else
CPP_FLAGS=$(CPP_FLAGS) /D "ASSERT"
!endif

!if "$(Variant)" == "core"
# No need to define anything, CORE is defined as !COMPILER1 && !COMPILER2
!endif

!if "$(Variant)" == "kernel"
CPP_FLAGS=$(CPP_FLAGS) /D "KERNEL"
!endif

!if "$(Variant)" == "compiler1"
CPP_FLAGS=$(CPP_FLAGS) /D "COMPILER1"
!endif

!if "$(Variant)" == "compiler2"
CPP_FLAGS=$(CPP_FLAGS) /D "COMPILER2"
!endif

!if "$(Variant)" == "tiered"
CPP_FLAGS=$(CPP_FLAGS) /D "COMPILER1" /D "COMPILER2"
!endif

!if "$(BuildUser)" != ""
CPP_FLAGS=$(CPP_FLAGS) /D "HOTSPOT_BUILD_USER=\"$(BuildUser)\""
!endif

CPP_FLAGS=$(CPP_FLAGS) /D "HOTSPOT_RELEASE_VERSION=\"$(HS_BUILD_VER)\""
CPP_FLAGS=$(CPP_FLAGS) /D "JRE_RELEASE_VERSION=\"$(JRE_RELEASE_VER)\""
CPP_FLAGS=$(CPP_FLAGS) /D "HOTSPOT_BUILD_TARGET=\"$(BUILD_FLAVOR)\""

# Define HOTSPOT_VM_DISTRO if HOTSPOT_VM_DISTRO is set,
# and if it is not see if we have the src/closed directory
!if "$(HOTSPOT_VM_DISTRO)" != ""
CPP_FLAGS=$(CPP_FLAGS) /D "HOTSPOT_VM_DISTRO=\"$(HOTSPOT_VM_DISTRO)\""
!else
!if exists($(WorkSpace)\src\closed)
CPP_FLAGS=$(CPP_FLAGS) /D "HOTSPOT_VM_DISTRO=\"Java HotSpot(TM)\""
!else
CPP_FLAGS=$(CPP_FLAGS) /D "HOTSPOT_VM_DISTRO=\"OpenJDK\""
!endif
!endif

CPP_FLAGS=$(CPP_FLAGS) /D "WIN32" /D "_WINDOWS" $(CPP_INCLUDE_DIRS)

# Must specify this for sharedRuntimeTrig.cpp
CPP_FLAGS=$(CPP_FLAGS) /D "VM_LITTLE_ENDIAN"

# Define that so jni.h is on correct side
CPP_FLAGS=$(CPP_FLAGS) /D "_JNI_IMPLEMENTATION_"

!if "$(ARCH)" == "ia64"
STACK_SIZE="/STACK:1048576,262144"
!else
STACK_SIZE=
!endif

!if "$(ARCH)" == "ia64"
# AsyncGetCallTrace is not supported on IA64 yet
AGCT_EXPORT=
!else
AGCT_EXPORT=/export:AsyncGetCallTrace
!endif

LINK_FLAGS=$(LINK_FLAGS) $(STACK_SIZE) /subsystem:windows /dll /base:0x8000000  \
  /export:JNI_GetDefaultJavaVMInitArgs /export:JNI_CreateJavaVM    \
  /export:JNI_GetCreatedJavaVMs /export:jio_snprintf               \
  /export:jio_printf /export:jio_fprintf                           \
  /export:jio_vfprintf /export:jio_vsnprintf $(AGCT_EXPORT)        \
  /export:JVM_GetVersionInfo /export:JVM_GetVersionInfo            \
  /export:JVM_GetThreadStateNames /export:JVM_GetThreadStateValues \
  /export:JVM_EnqueueOperation /export:JVM_InitAgentProperties

CPP_INCLUDE_DIRS=\
  /I "..\generated"                          \
  /I "..\generated\jvmtifiles"               \
  /I "$(WorkSpace)\src\share\vm\c1"          \
  /I "$(WorkSpace)\src\share\vm\compiler"    \
  /I "$(WorkSpace)\src\share\vm\code"        \
  /I "$(WorkSpace)\src\share\vm\interpreter" \
  /I "$(WorkSpace)\src\share\vm\ci"          \
  /I "$(WorkSpace)\src\share\vm\classfile"   \
  /I "$(WorkSpace)\src\share\vm\gc_implementation\parallelScavenge"\
  /I "$(WorkSpace)\src\share\vm\gc_implementation\shared"\
  /I "$(WorkSpace)\src\share\vm\gc_implementation\parNew"\
  /I "$(WorkSpace)\src\share\vm\gc_implementation\concurrentMarkSweep"\
  /I "$(WorkSpace)\src\share\vm\gc_interface"\
  /I "$(WorkSpace)\src\share\vm\asm"         \
  /I "$(WorkSpace)\src\share\vm\memory"      \
  /I "$(WorkSpace)\src\share\vm\oops"        \
  /I "$(WorkSpace)\src\share\vm\prims"       \
  /I "$(WorkSpace)\src\share\vm\runtime"     \
  /I "$(WorkSpace)\src\share\vm\services"    \
  /I "$(WorkSpace)\src\share\vm\utilities"   \
  /I "$(WorkSpace)\src\share\vm\libadt"      \
  /I "$(WorkSpace)\src\share\vm\opto"        \
  /I "$(WorkSpace)\src\os\win32\vm"          \
  /I "$(WorkSpace)\src\os_cpu\win32_$(ARCH)\vm" \
  /I "$(WorkSpace)\src\cpu\$(ARCH)\vm"

CPP_USE_PCH=/Fp"vm.pch" /Yu"incls/_precompiled.incl"

# Where to find the source code for the virtual machine
VM_PATH=../generated/incls
VM_PATH=$(VM_PATH);../generated/jvmtifiles
VM_PATH=$(VM_PATH);$(WorkSpace)/src/share/vm/c1
VM_PATH=$(VM_PATH);$(WorkSpace)/src/share/vm/compiler
VM_PATH=$(VM_PATH);$(WorkSpace)/src/share/vm/code
VM_PATH=$(VM_PATH);$(WorkSpace)/src/share/vm/interpreter
VM_PATH=$(VM_PATH);$(WorkSpace)/src/share/vm/ci
VM_PATH=$(VM_PATH);$(WorkSpace)/src/share/vm/classfile
VM_PATH=$(VM_PATH);$(WorkSpace)/src/share/vm/gc_implementation/parallelScavenge
VM_PATH=$(VM_PATH);$(WorkSpace)/src/share/vm/gc_implementation/shared
VM_PATH=$(VM_PATH);$(WorkSpace)/src/share/vm/gc_implementation/parNew
VM_PATH=$(VM_PATH);$(WorkSpace)/src/share/vm/gc_implementation/concurrentMarkSweep
VM_PATH=$(VM_PATH);$(WorkSpace)/src/share/vm/gc_interface
VM_PATH=$(VM_PATH);$(WorkSpace)/src/share/vm/asm
VM_PATH=$(VM_PATH);$(WorkSpace)/src/share/vm/memory
VM_PATH=$(VM_PATH);$(WorkSpace)/src/share/vm/oops
VM_PATH=$(VM_PATH);$(WorkSpace)/src/share/vm/prims
VM_PATH=$(VM_PATH);$(WorkSpace)/src/share/vm/runtime
VM_PATH=$(VM_PATH);$(WorkSpace)/src/share/vm/services
VM_PATH=$(VM_PATH);$(WorkSpace)/src/share/vm/utilities
VM_PATH=$(VM_PATH);$(WorkSpace)/src/share/vm/libadt
VM_PATH=$(VM_PATH);$(WorkSpace)/src/os/win32/vm
VM_PATH=$(VM_PATH);$(WorkSpace)/src/os_cpu/win32_$(ARCH)/vm
VM_PATH=$(VM_PATH);$(WorkSpace)/src/cpu/$(ARCH)/vm
VM_PATH=$(VM_PATH);$(WorkSpace)/src/share/vm/opto

VM_PATH={$(VM_PATH)}

# Special case files not using precompiled header files.

c1_RInfo_$(ARCH).obj: $(WorkSpace)\src\cpu\$(ARCH)\vm\c1_RInfo_$(ARCH).cpp 
	 $(CPP) $(CPP_FLAGS) /c $(WorkSpace)\src\cpu\$(ARCH)\vm\c1_RInfo_$(ARCH).cpp

os_win32.obj: $(WorkSpace)\src\os\win32\vm\os_win32.cpp
        $(CPP) $(CPP_FLAGS) /c $(WorkSpace)\src\os\win32\vm\os_win32.cpp

os_win32_$(ARCH).obj: $(WorkSpace)\src\os_cpu\win32_$(ARCH)\vm\os_win32_$(ARCH).cpp
        $(CPP) $(CPP_FLAGS) /c $(WorkSpace)\src\os_cpu\win32_$(ARCH)\vm\os_win32_$(ARCH).cpp

osThread_win32.obj: $(WorkSpace)\src\os\win32\vm\osThread_win32.cpp
        $(CPP) $(CPP_FLAGS) /c $(WorkSpace)\src\os\win32\vm\osThread_win32.cpp

conditionVar_win32.obj: $(WorkSpace)\src\os\win32\vm\conditionVar_win32.cpp
        $(CPP) $(CPP_FLAGS) /c $(WorkSpace)\src\os\win32\vm\conditionVar_win32.cpp

getThread_win32_$(ARCH).obj: $(WorkSpace)\src\os_cpu\win32_$(ARCH)\vm\getThread_win32_$(ARCH).cpp
        $(CPP) $(CPP_FLAGS) /c $(WorkSpace)\src\os_cpu\win32_$(ARCH)\vm\getThread_win32_$(ARCH).cpp

opcodes.obj: $(WorkSpace)\src\share\vm\opto\opcodes.cpp
        $(CPP) $(CPP_FLAGS) /c $(WorkSpace)\src\share\vm\opto\opcodes.cpp

# Default rules for the Virtual Machine
{$(WorkSpace)\src\share\vm\c1}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

{$(WorkSpace)\src\share\vm\compiler}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

{$(WorkSpace)\src\share\vm\code}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

{$(WorkSpace)\src\share\vm\interpreter}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

{$(WorkSpace)\src\share\vm\ci}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

{$(WorkSpace)\src\share\vm\classfile}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

{$(WorkSpace)\src\share\vm\gc_implementation\parallelScavenge}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

{$(WorkSpace)\src\share\vm\gc_implementation\shared}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

{$(WorkSpace)\src\share\vm\gc_implementation\parNew}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

{$(WorkSpace)\src\share\vm\gc_implementation\concurrentMarkSweep}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

{$(WorkSpace)\src\share\vm\gc_interface}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

{$(WorkSpace)\src\share\vm\asm}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

{$(WorkSpace)\src\share\vm\memory}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

{$(WorkSpace)\src\share\vm\oops}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

{$(WorkSpace)\src\share\vm\prims}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

{$(WorkSpace)\src\share\vm\runtime}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

{$(WorkSpace)\src\share\vm\services}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

{$(WorkSpace)\src\share\vm\utilities}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

{$(WorkSpace)\src\share\vm\libadt}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

{$(WorkSpace)\src\share\vm\opto}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

{$(WorkSpace)\src\os\win32\vm}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

# This guy should remain a single colon rule because
# otherwise we can't specify the output filename.
{$(WorkSpace)\src\os\win32\vm}.rc.res:
        @$(RC) $(RC_FLAGS) /fo"$@" $<

{$(WorkSpace)\src\cpu\$(ARCH)\vm}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

{$(WorkSpace)\src\os_cpu\win32_$(ARCH)\vm}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

{..\generated\incls}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

{..\generated\jvmtifiles}.cpp.obj::
        $(CPP) $(CPP_FLAGS) $(CPP_USE_PCH) /c $<

default::

_build_pch_file.obj:
        @echo #include "incls/_precompiled.incl" > ../generated/_build_pch_file.cpp
        $(CPP) $(CPP_FLAGS) /Fp"vm.pch" /Yc"incls/_precompiled.incl" /c ../generated/_build_pch_file.cpp
