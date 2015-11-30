/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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
package jdk.tools.jlink.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * A process execution manager.
 */
public interface ProcessingManager {

    /**
     * A running process.
     */
    public interface RunningProcess {

        public int getExitCode() throws InterruptedException;

        public String getStdout() throws InterruptedException, ExecutionException;

        public String getStderr() throws InterruptedException, ExecutionException;

        public void kill();

        public Process getProcess();
    }

    /**
     * A Session. Each session has a private storage automatically cleared at
     * close time. From a session one can start new processes. Direct execution
     * of Java image is also offered.
     */
    public interface ProcessingSession {

        public String getName();

        /**
         * Private storage.
         *
         * @return
         */
        public Path getStorage();

        /**
         * Run a process. The working directory is the private storage.
         *
         * @param builder
         * @return
         * @throws IOException
         */
        public RunningProcess newRunningProcess(ProcessBuilder builder) throws IOException;

        /**
         * Run the image. The working directory is the private storage.
         *
         * @param args
         * @return
         * @throws IOException
         */
        public RunningProcess newImageProcess(List<String> args) throws IOException;

        public void close() throws IOException;
    }

    /**
     * Create a new ProcessingSession.
     *
     * @param name Session name.
     * @return A new Session.
     * @throws java.io.IOException
     */
    public ProcessingSession newSession(String name) throws IOException;

    /**
     * Return the current image.
     *
     * @return
     */
    public ExecutableImage getImage();
}
