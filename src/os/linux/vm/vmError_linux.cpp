#ifdef USE_PRAGMA_IDENT_SRC
#pragma ident "@(#)vmError_linux.cpp	1.13 07/05/05 17:04:37 JVM"
#endif
/*
 * Copyright 2003-2006 Sun Microsystems, Inc.  All Rights Reserved.
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
# include "incls/_vmError_linux.cpp.incl"

#include <sys/types.h>
#include <sys/wait.h>
#include <sys/syscall.h>
#include <unistd.h>
#include <signal.h>

extern char** environ;

#ifndef __NR_fork
#define __NR_fork IA32_ONLY(2) IA64_ONLY(not defined) AMD64_ONLY(57)
#endif

#ifndef __NR_execve
#define __NR_execve IA32_ONLY(11) IA64_ONLY(1033) AMD64_ONLY(59)
#endif

// Run the specified command in a separate process. Return its exit value,
// or -1 on failure (e.g. can't fork a new process).
// Unlike system(), this function can be called from signal handler. It
// doesn't block SIGINT et al.
int VMError::fork_and_exec(char* cmd) {
  char * argv[4];
  argv[0] = "sh";
  argv[1] = "-c";
  argv[2] = cmd;
  argv[3] = NULL;

  // fork() in LinuxThreads/NPTL is not async-safe. It needs to run 
  // pthread_atfork handlers and reset pthread library. All we need is a 
  // separate process to execve. Make a direct syscall to fork process.
  // On IA64 there's no fork syscall, we have to use fork() and hope for
  // the best...
  pid_t pid = NOT_IA64(syscall(__NR_fork);) 
              IA64_ONLY(fork();)

  if (pid < 0) {
    // fork failed
    return -1;

  } else if (pid == 0) {
    // child process

    // execve() in LinuxThreads will call pthread_kill_other_threads_np() 
    // first to kill every thread on the thread list. Because this list is 
    // not reset by fork() (see notes above), execve() will instead kill 
    // every thread in the parent process. We know this is the only thread 
    // in the new process, so make a system call directly.
    // IA64 should use normal execve() from glibc to match the glibc fork() 
    // above.
    NOT_IA64(syscall(__NR_execve, "/bin/sh", argv, environ);)
    IA64_ONLY(execve("/bin/sh", argv, environ);)

    // execve failed
    _exit(-1);

  } else  {
    // copied from J2SE ..._waitForProcessExit() in UNIXProcess_md.c; we don't
    // care about the actual exit code, for now.
   
    int status;

    // Wait for the child process to exit.  This returns immediately if
    // the child has already exited. */
    while (waitpid(pid, &status, 0) < 0) {
        switch (errno) {
        case ECHILD: return 0;
        case EINTR: break;
        default: return -1;
        }
    }

    if (WIFEXITED(status)) {
       // The child exited normally; get its exit code.
       return WEXITSTATUS(status);
    } else if (WIFSIGNALED(status)) {
       // The child exited because of a signal
       // The best value to return is 0x80 + signal number,
       // because that is what all Unix shells do, and because
       // it allows callers to distinguish between process exit and
       // process death by signal.
       return 0x80 + WTERMSIG(status);
    } else {
       // Unknown exit code; pass it through
       return status;
    }
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
               "To debug, run 'gdb /proc/%d/exe %d'; then switch to thread " INTX_FORMAT "\n"
               "Enter 'yes' to launch gdb automatically (PATH must include gdb)\n"
               "Otherwise, press RETURN to abort...",
               os::current_process_id(), os::current_process_id(),
               os::current_thread_id());

    yes = os::message_box("Unexpected Error", buf);

    if (yes) {
      // yes, user asked VM to launch debugger
      jio_snprintf(buf, buflen, "gdb /proc/%d/exe %d", 
                   os::current_process_id(), os::current_process_id());

      fork_and_exec(buf);
    }
  } while (yes);
}

// Space for our "saved" signal flags and handlers
static int resettedSigflags[2];
static address resettedSighandler[2]; 

static void save_signal(int idx, int sig)
{
  struct sigaction sa;
  sigaction(sig, NULL, &sa);
  resettedSigflags[idx]   = sa.sa_flags;
  resettedSighandler[idx] = (sa.sa_flags & SA_SIGINFO)
                              ? CAST_FROM_FN_PTR(address, sa.sa_sigaction)
                              : CAST_FROM_FN_PTR(address, sa.sa_handler);
}

int VMError::get_resetted_sigflags(int sig) {
  if(SIGSEGV == sig) {
    return resettedSigflags[0];  
  } else if(SIGBUS == sig) {
    return resettedSigflags[1];
  }
  return -1;
}

address VMError::get_resetted_sighandler(int sig) {
  if(SIGSEGV == sig) {
    return resettedSighandler[0];  
  } else if(SIGBUS == sig) {
    return resettedSighandler[1];
  }
  return NULL;
}

static void crash_handler(int sig, siginfo_t* info, void* ucVoid) {
  // unmask current signal
  sigset_t newset;
  sigemptyset(&newset);
  sigaddset(&newset, sig);
  sigprocmask(SIG_UNBLOCK, &newset, NULL);

  VMError err(NULL, sig, NULL, info, ucVoid);
  err.report_and_die();
}

void VMError::reset_signal_handlers() {
  // Save sigflags for resetted signals
  save_signal(0, SIGSEGV);
  save_signal(1, SIGBUS);
  os::signal(SIGSEGV, CAST_FROM_FN_PTR(void *, crash_handler));
  os::signal(SIGBUS, CAST_FROM_FN_PTR(void *, crash_handler));
}
