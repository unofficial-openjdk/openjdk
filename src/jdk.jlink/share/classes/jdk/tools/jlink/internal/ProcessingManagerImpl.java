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
package jdk.tools.jlink.internal;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import jdk.tools.jlink.plugins.ExecutableImage;
import jdk.tools.jlink.plugins.ProcessingManager;

/**
 * A process manager.
 */
class ProcessingManagerImpl implements ProcessingManager {

    private static final class StreamReader implements Runnable {

        private final InputStream in;
        private final OutputStream out;
        private final FutureTask<Void> processingTask = new FutureTask<>(this, null);

        private StreamReader(InputStream in, OutputStream out) {
            this.in = in;
            this.out = out;
        }

        @Override
        public void run() {
            try (BufferedInputStream is = new BufferedInputStream(in)) {
                byte[] buf = new byte[1024];
                int len = 0;
                while ((len = is.read(buf)) > 0 && !Thread.interrupted()) {
                    out.write(buf, 0, len);
                }
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                try {
                    out.flush();
                } catch (IOException e) {
                }
                try {
                    in.close();
                } catch (IOException e) {
                }
            }
        }

        final public Future<Void> process() {
            Thread t = new Thread(() -> {
                processingTask.run();
            });
            t.setDaemon(true);
            t.start();

            return processingTask;
        }
    }

    public static final class RunningProcessImpl implements RunningProcess {

        private final ByteArrayOutputStream stderrBuffer = new ByteArrayOutputStream();
        private final ByteArrayOutputStream stdoutBuffer = new ByteArrayOutputStream();
        private final Future<Void> outTask;
        private final Future<Void> errTask;
        private final Process p;

        @Override
        public int getExitCode() throws InterruptedException {
            return p.waitFor();
        }

        @Override
        public String getStdout() throws InterruptedException, ExecutionException {
            outTask.get();
            return stdoutBuffer.toString();

        }

        @Override
        public String getStderr() throws InterruptedException, ExecutionException {
            errTask.get();
            return stderrBuffer.toString();
        }

        @Override
        public void kill() {
            p.destroyForcibly();
        }

        private RunningProcessImpl(ProcessBuilder builder) throws IOException {
            p = builder.start();
            StreamReader outReader = new StreamReader(p.getInputStream(), stdoutBuffer);
            StreamReader errReader = new StreamReader(p.getErrorStream(), stderrBuffer);
            outTask = outReader.process();
            errTask = errReader.process();
        }

        @Override
        public Process getProcess() {
            return p;
        }

        void close() {
            if (p.isAlive()) {
                kill();
            }
        }
    }

    private final class ProcessingSessionImpl implements ProcessingSession {

        private boolean closed;
        private final Path dir;
        private final String name;
        private final List<RunningProcessImpl> processes = new ArrayList<>();

        private ProcessingSessionImpl(String name, Path dir) {
            this.name = name;
            this.dir = dir;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public Path getStorage() {
            return dir;
        }

        @Override
        public RunningProcess newRunningProcess(ProcessBuilder builder) throws IOException {
            ProcessBuilder pb = builder.directory(dir.toFile());
            RunningProcessImpl p = new RunningProcessImpl(pb);
            processes.add(p);
            return p;
        }

        @Override
        public RunningProcess newImageProcess(List<String> args) throws IOException {
            RunningProcessImpl p = new RunningProcessImpl(newJavaProcessBuilder(args));
            processes.add(p);
            return p;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                throw new IllegalArgumentException("Session " + name
                        + " already closed");
            }
            closed = true;
            for (RunningProcessImpl r : processes) {
                r.close();
            }
        }

        private ProcessBuilder newJavaProcessBuilder(List<String> args) throws IOException {
            List<String> javaArgs = new ArrayList<>();
            javaArgs.addAll(img.getExecutionArgs());
            javaArgs.addAll(args);
            ProcessBuilder builder = new ProcessBuilder().command(javaArgs).
                    directory(dir.toFile());
            return builder;
        }

    }

    private final Path tmp;
    private final Map<String, ProcessingSession> sessions = new HashMap<>();
    private final ExecutableImage img;

    ProcessingManagerImpl(ExecutableImage img) throws IOException {
        Objects.requireNonNull(img);
        this.img = img;
        String dir = "jlink_tmp_" + System.currentTimeMillis();
        Path dirPath = img.getHome().resolve(dir);
        tmp = Files.createDirectory(dirPath);
    }

    @Override
    public ProcessingSession newSession(String name) throws IOException {
        String id = name.replaceAll(" ", "_") + System.currentTimeMillis();
        Path dirPath = tmp.resolve(id);
        Files.createDirectory(dirPath);
        return new ProcessingSessionImpl(name, tmp);
    }

    @Override
    public ExecutableImage getImage() {
        return img;
    }

    public void close() throws IOException {
        for (ProcessingSession session : sessions.values()) {
            session.close();
        }
        Files.walkFileTree(tmp, new FileVisitor<Path>() {

            @Override
            public FileVisitResult preVisitDirectory(Path t, BasicFileAttributes bfa) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path t, BasicFileAttributes bfa) throws IOException {
                Files.deleteIfExists(t);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path t, IOException ioe) throws IOException {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path t, IOException ioe) throws IOException {
                Files.deleteIfExists(t);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
