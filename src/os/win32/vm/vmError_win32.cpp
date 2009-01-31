  #ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "@(#)vmError_win32.cpp	1.14 07/05/05 17:04:44 JVM"
#endif
/*
 * Copyright 2003-2007 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 *  
 */

# include "incls/_precompiled.incl"
# include "incls/_vmError_win32.cpp.incl"

// Run the specified command in a separate process. Return its exit value,
// or -1 on failure (e.g. can't create a new process).
int VMError::fork_and_exec(char* cmd) {
  STARTUPINFO si;
  PROCESS_INFORMATION pi;

  memset(&si, 0, sizeof(si));
  si.cb = sizeof(si);
  memset(&pi, 0, sizeof(pi));
  BOOL rslt = CreateProcess(NULL,   // executable name - use command line
                            cmd,    // command line
                            NULL,   // process security attribute
                            NULL,   // thread security attribute
                            TRUE,   // inherits system handles
                            0,      // no creation flags
                            NULL,   // use parent's environment block
                            NULL,   // use parent's starting directory
                            &si,    // (in) startup information
                            &pi);   // (out) process information

  if (rslt) {
    // Wait until child process exits.
    WaitForSingleObject(pi.hProcess, INFINITE);

    DWORD exit_code;
    GetExitCodeProcess(pi.hProcess, &exit_code);

    // Close process and thread handles.
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);

    return (int)exit_code;
  } else {
    return -1;
  }
}

void VMError::show_message_box(char *buf, int buflen) {
  bool yes;
  do {
    error_string(buf, buflen);
    int len = (int)strlen(buf);
    char *p = &buf[len];

    jio_snprintf(p, buflen - len,
               "\n\n"
               "Do you want to debug the problem?\n\n"
               "To debug, attach Visual Studio to process %d; then switch to thread 0x%x\n"
               "Select 'Yes' to launch Visual Studio automatically (PATH must include msdev)\n"
               "Otherwise, select 'No' to abort...",
               os::current_process_id(), os::current_thread_id());

    yes = os::message_box("Unexpected Error", buf) != 0;

    if (yes) {
      // yes, user asked VM to launch debugger
      //
      // os::breakpoint() calls DebugBreak(), which causes a breakpoint
      // exception. If VM is running inside a debugger, the debugger will
      // catch the exception. Otherwise, the breakpoint exception will reach
      // the default windows exception handler, which can spawn a debugger and
      // automatically attach to the dying VM.
      os::breakpoint();
    }
  } while (yes);
}

int VMError::get_resetted_sigflags(int sig) {
  return -1;
}

address VMError::get_resetted_sighandler(int sig) {
  return NULL;
}

LONG WINAPI crash_handler(struct _EXCEPTION_POINTERS* exceptionInfo) {
  DWORD exception_code = exceptionInfo->ExceptionRecord->ExceptionCode;
  VMError err(NULL, exception_code, NULL, 
		exceptionInfo->ExceptionRecord, exceptionInfo->ContextRecord);
  err.report_and_die();
  return EXCEPTION_CONTINUE_SEARCH;
}

void VMError::reset_signal_handlers() {
  SetUnhandledExceptionFilter(crash_handler);
}

