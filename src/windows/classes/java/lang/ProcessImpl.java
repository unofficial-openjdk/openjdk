/*
 * Copyright (c) 1995, 2007, Oracle and/or its affiliates. All rights reserved.
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

package java.lang;

import java.io.*;

/* This class is for the exclusive use of ProcessBuilder.start() to
 * create new processes.
 *
 * @author Martin Buchholz
 * @since   1.5
 */

final class ProcessImpl extends Process {

    // System-dependent portion of ProcessBuilder.start()
    static Process start(String cmdarray[],
                         java.util.Map<String,String> environment,
                         String dir,
                         boolean redirectErrorStream)
        throws IOException
    {
        String envblock = ProcessEnvironment.toEnvironmentBlock(environment);
        return new ProcessImpl(cmdarray, envblock, dir, redirectErrorStream);
    }

    // We guarantee the only command file execution for implicit [cmd.exe] run.
    //    http://technet.microsoft.com/en-us/library/bb490954.aspx
    private static final char CMD_BAT_ESCAPE[] = {' ', '\t', '<', '>', '&', '|', '^'};
    private static final char WIN32_EXECUTABLE_ESCAPE[] = {' ', '\t', '<', '>'};

    private static boolean isQuoted(boolean noQuotesInside, String arg,
            String errorMessage) {
        int lastPos = arg.length() - 1;
        if (lastPos >=1 && arg.charAt(0) == '"' && arg.charAt(lastPos) == '"') {
            // The argument has already been quoted.
            if (noQuotesInside) {
                if (arg.indexOf('"', 1) != lastPos) {
                    // There is ["] inside.
                    throw new IllegalArgumentException(errorMessage);
                }
            }
            return true;
        }
        if (noQuotesInside) {
            if (arg.indexOf('"') >= 0) {
                // There is ["] inside.
                throw new IllegalArgumentException(errorMessage);
            }
        }
        return false;
    }

    private static boolean needsEscaping(boolean isCmdFile, String arg) {
        // Switch off MS heuristic for internal ["].
        // Please, use the explicit [cmd.exe] call
        // if you need the internal ["].
        //    Example: "cmd.exe", "/C", "Extended_MS_Syntax"

        // For [.exe] or [.com] file the unpaired/internal ["]
        // in the argument is not a problem.
        boolean argIsQuoted = isQuoted(isCmdFile, arg,
            "Argument has embedded quote, use the explicit CMD.EXE call.");

        if (!argIsQuoted) {
            char testEscape[] = isCmdFile
                    ? CMD_BAT_ESCAPE
                    : WIN32_EXECUTABLE_ESCAPE;
            for (int i = 0; i < testEscape.length; ++i) {
                if (arg.indexOf(testEscape[i]) >= 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String getExecutablePath(String path)
        throws IOException
    {
        boolean pathIsQuoted = isQuoted(true, path,
                "Executable name has embedded quote, split the arguments");

        // Win32 CreateProcess requires path to be normalized
        File fileToRun = new File(pathIsQuoted
            ? path.substring(1, path.length() - 1)
            : path);

        // From the [CreateProcess] function documentation:
        //
        // "If the file name does not contain an extension, .exe is appended.
        // Therefore, if the file name extension is .com, this parameter
        // must include the .com extension. If the file name ends in
        // a period (.) with no extension, or if the file name contains a path,
        // .exe is not appended."
        //
        // "If the file name !does not contain a directory path!,
        // the system searches for the executable file in the following
        // sequence:..."
        //
        // In practice ANY non-existent path is extended by [.exe] extension
        // in the [CreateProcess] funcion with the only exception:
        // the path ends by (.)

        return fileToRun.getPath();
    }


    private long handle = 0;
    private FileDescriptor stdin_fd;
    private FileDescriptor stdout_fd;
    private FileDescriptor stderr_fd;
    private OutputStream stdin_stream;
    private InputStream stdout_stream;
    private InputStream stderr_stream;

    private ProcessImpl(String cmd[],
                        String envblock,
                        String path,
                        boolean redirectErrorStream)
        throws IOException
    {
        // The [executablePath] is not quoted for any case.
        String executablePath = getExecutablePath(cmd[0]);

        // We need to extend the argument verification procedure
        // to guarantee the only command file execution for implicit [cmd.exe]
        // run.
        String upPath = executablePath.toUpperCase();
        boolean isCmdFile = (upPath.endsWith(".CMD") || upPath.endsWith(".BAT"));

        StringBuilder cmdbuf = new StringBuilder(80);

        // Quotation protects from interpretation of the [path] argument as
        // start of longer path with spaces. Quotation has no influence to
        // [.exe] extension heuristic.
        cmdbuf.append('"');
        cmdbuf.append(executablePath);
        cmdbuf.append('"');

        for (int i = 1; i < cmd.length; i++) {
            cmdbuf.append(' ');
            String s = cmd[i];
            if (needsEscaping(isCmdFile, s)) {
                cmdbuf.append('"');
                cmdbuf.append(s);
                cmdbuf.append('"');
            } else {
                cmdbuf.append(s);
            }
        }
        String cmdstr = cmdbuf.toString();

        stdin_fd  = new FileDescriptor();
        stdout_fd = new FileDescriptor();
        stderr_fd = new FileDescriptor();

        handle = create(cmdstr, envblock, path, redirectErrorStream,
                        stdin_fd, stdout_fd, stderr_fd);

        java.security.AccessController.doPrivileged(
            new java.security.PrivilegedAction() {
            public Object run() {
                stdin_stream =
                    new BufferedOutputStream(new FileOutputStream(stdin_fd));
                stdout_stream =
                    new BufferedInputStream(new FileInputStream(stdout_fd));
                stderr_stream =
                    new FileInputStream(stderr_fd);
                return null;
            }
        });
    }

    public OutputStream getOutputStream() {
        return stdin_stream;
    }

    public InputStream getInputStream() {
        return stdout_stream;
    }

    public InputStream getErrorStream() {
        return stderr_stream;
    }

    public void finalize() {
        closeHandle(handle);
    }

    private static final int STILL_ACTIVE = getStillActive();
    private static native int getStillActive();

    public int exitValue() {
        int exitCode = getExitCodeProcess(handle);
        if (exitCode == STILL_ACTIVE)
            throw new IllegalThreadStateException("process has not exited");
        return exitCode;
    }
    private static native int getExitCodeProcess(long handle);

    public int waitFor() throws InterruptedException {
        waitForInterruptibly(handle);
        if (Thread.interrupted())
            throw new InterruptedException();
        return exitValue();
    }
    private static native void waitForInterruptibly(long handle);

    public void destroy() { terminateProcess(handle); }
    private static native void terminateProcess(long handle);

    private static native long create(String cmdstr,
                                      String envblock,
                                      String dir,
                                      boolean redirectErrorStream,
                                      FileDescriptor in_fd,
                                      FileDescriptor out_fd,
                                      FileDescriptor err_fd)
        throws IOException;

    private static native boolean closeHandle(long handle);
}
