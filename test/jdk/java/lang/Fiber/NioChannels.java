/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/**
 * @test
 * @run testng NioChannels
 * @summary Basic tests for Fibers doing blocking I/O with NIO channels
 */

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.Pipe;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;

import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;
import static org.testng.Assert.*;

@Test
public class NioChannels {

    private static final long DELAY = 2000;

    private interface TestCase {
        void run() throws IOException;
    }

    private void test(TestCase test) {
        Fiber.schedule(() -> { test.run(); return null; }).join();
    }

    /**
     * SocketChannel read/write, no blocking
     */
    public void testSocketChannelReadWrite1() {
        test(() -> {
            try (var connection = new Connection()) {
                SocketChannel sc1 = connection.channel1();
                SocketChannel sc2 = connection.channel2();

                // write should not block
                ByteBuffer bb = ByteBuffer.wrap("XXX".getBytes("UTF-8"));
                int n = sc1.write(bb);
                assertTrue(n > 0);

                // read should not block
                bb = ByteBuffer.allocate(10);
                n = sc2.read(bb);
                assertTrue(n > 0);
                assertTrue(bb.get(0) == 'X');
            }
        });
    }

    /**
     * Fiber blocks in SocketChannel.read
     */
    public void testSocketChannelReadWrite2() {
        test(() -> {
            try (var connection = new Connection()) {
                SocketChannel sc1 = connection.channel1();
                SocketChannel sc2 = connection.channel2();

                // schedule write
                ByteBuffer bb = ByteBuffer.wrap("XXX".getBytes("UTF-8"));
                ScheduledWriter.schedule(sc1, bb, DELAY);

                // read should block
                bb = ByteBuffer.allocate(10);
                int n = sc2.read(bb);
                assertTrue(n > 0);
                assertTrue(bb.get(0) == 'X');
            }
        });
    }

    /**
     * Fiber blocks in SocketChannel.write
     */
    public void testSocketChannelReadWrite3() {
        test(() -> {
            try (var connection = new Connection()) {
                SocketChannel sc1 = connection.channel1();
                SocketChannel sc2 = connection.channel2();

                // schedule thread to read to EOF
                ScheduledReader.schedule(sc2, true, DELAY);

                // write should block
                ByteBuffer bb = ByteBuffer.allocate(100*10024);
                for (int i=0; i<1000; i++) {
                    int n = sc1.write(bb);
                    assertTrue(n > 0);
                    bb.clear();
                }
            }
        });
    }

    /**
     * SocketChannel close while Fiber blocked in read
     */
    public void testSocketChannelReadAsyncClose() {
        test(() -> {
            try (var connection = new Connection()) {
                SocketChannel sc = connection.channel1();
                ScheduledCloser.schedule(sc, DELAY);
                try {
                    int n = sc.read(ByteBuffer.allocate(100));
                    throw new RuntimeException("read returned " + n);
                } catch (AsynchronousCloseException expected) { }
            }
        });
    }

    /**
     * Fiber interrupted while blocked in SocketChannel.read
     */
    public void testSocketChannelReadInterrupt() {
        test(() -> {
            try (var connection = new Connection()) {
                SocketChannel sc = connection.channel1();
                ScheduledInterrupter.schedule(Thread.currentThread(), DELAY);
                try {
                    int n = sc.read(ByteBuffer.allocate(100));
                    throw new RuntimeException("read returned " + n);
                } catch (ClosedByInterruptException expected) { }
            }
        });
    }

    /**
     * Fiber cancelled while blocked in SocketChannel.read
     */
    public void testSocketChannelReadCancel() {
        test(() -> {
            try (var connection = new Connection()) {
                SocketChannel sc = connection.channel1();
                var fiber = Fiber.current().orElseThrow();
                ScheduledCanceller.schedule(fiber, DELAY);
                try {
                    int n = sc.read(ByteBuffer.allocate(100));
                    throw new RuntimeException("read returned " + n);
                } catch (IOException expected) { }
            }
        });
    }

    /**
     * SocketChannel close while Fiber blocked in write
     */
    public void testSocketChannelWriteAsyncClose() {
        test(() -> {
            try (var connection = new Connection()) {
                SocketChannel sc = connection.channel1();
                ScheduledCloser.schedule(sc, DELAY);
                try {
                    ByteBuffer bb = ByteBuffer.allocate(100*10024);
                    for (;;) {
                        int n = sc.write(bb);
                        assertTrue(n > 0);
                        bb.clear();
                    }
                } catch (AsynchronousCloseException expected) { }
            }
        });
    }

    /**
     * Fiber interrupted while blocked in SocketChannel.write
     */
    public void testSocketChannelWriteInterrupt() {
        test(() -> {
            try (var connection = new Connection()) {
                SocketChannel sc = connection.channel1();
                ScheduledInterrupter.schedule(Thread.currentThread(), DELAY);
                try {
                    ByteBuffer bb = ByteBuffer.allocate(100*10024);
                    for (;;) {
                        int n = sc.write(bb);
                        assertTrue(n > 0);
                        bb.clear();
                    }
                } catch (ClosedByInterruptException expected) { }
            }
        });
    }

    /**
     * Fiber cancelled while blocked in SocketChannel.write
     */
    public void testSocketChannelWritCeancel() {
        test(() -> {
            try (var connection = new Connection()) {
                SocketChannel sc = connection.channel1();
                var fiber = Fiber.current().orElseThrow();
                ScheduledCanceller.schedule(fiber, DELAY);
                try {
                    ByteBuffer bb = ByteBuffer.allocate(100*10024);
                    for (;;) {
                        int n = sc.write(bb);
                        assertTrue(n > 0);
                        bb.clear();
                    }
                } catch (IOException expected) { }
            }
        });
    }

    /**
     * ServerSocketChannel accept, no blocking
     */
    public void testServerSocketChannelAccept1() {
        test(() -> {
            try (var ssc = ServerSocketChannel.open()) {
                ssc.bind(new InetSocketAddress(InetAddress.getLocalHost(), 0));
                var sc1 = SocketChannel.open(ssc.getLocalAddress());
                // accept should not block
                var sc2 = ssc.accept();
                sc1.close();
                sc2.close();
            }
        });
    }

    /**
     * Fiber blocks in ServerSocketChannel.accept
     */
    public void testServerSocketChannelAccept2() {
        test(() -> {
            try (var ssc = ServerSocketChannel.open()) {
                ssc.bind(new InetSocketAddress(InetAddress.getLocalHost(), 0));
                var sc1 = SocketChannel.open();
                ScheduledConnector.schedule(sc1, ssc.getLocalAddress(), DELAY);
                // accept will block
                var sc2 = ssc.accept();
                sc1.close();
                sc2.close();
            }
        });
    }

    /**
     * SeverSocketChannel close while Fiber blocked in accept
     */
    public void testServerSocketChannelAcceptAsyncClose() {
        test(() -> {
            try (var ssc = ServerSocketChannel.open()) {
                InetAddress lh = InetAddress.getLocalHost();
                ssc.bind(new InetSocketAddress(lh, 0));
                ScheduledCloser.schedule(ssc, DELAY);
                try {
                    SocketChannel sc = ssc.accept();
                    sc.close();
                    throw new RuntimeException("connection accepted???");
                } catch (AsynchronousCloseException expected) { }
            }
        });
    }

    /**
     * Fiber interrupted while blocked in ServerSocketChannel.accept
     */
    public void testServerSocketChannelAcceptInterrupt() {
        test(() -> {
            try (var ssc = ServerSocketChannel.open()) {
                InetAddress lh = InetAddress.getLocalHost();
                ssc.bind(new InetSocketAddress(lh, 0));
                ScheduledInterrupter.schedule(Thread.currentThread(), DELAY);
                try {
                    SocketChannel sc = ssc.accept();
                    sc.close();
                    throw new RuntimeException("connection accepted???");
                } catch (ClosedByInterruptException expected) { }
            }
        });
    }

    /**
     * Fiber cancelled while blocked in ServerSocketChannel.accept
     */
    public void testServerSocketChannelAcceptCancel() {
        test(() -> {
            try (var ssc = ServerSocketChannel.open()) {
                InetAddress lh = InetAddress.getLocalHost();
                ssc.bind(new InetSocketAddress(lh, 0));
                var fiber = Fiber.current().orElseThrow();
                ScheduledCanceller.schedule(fiber, DELAY);
                try {
                    SocketChannel sc = ssc.accept();
                    sc.close();
                    throw new RuntimeException("connection accepted???");
                } catch (IOException expected) { }
            }
        });
    }

    /**
     * DatagramChannel receive/send, no blocking
     */
    public void testDatagramhannelSendReceive1() {
        test(() -> {
            try (DatagramChannel dc1 = DatagramChannel.open();
                 DatagramChannel dc2 = DatagramChannel.open()) {

                InetAddress lh = InetAddress.getLocalHost();
                dc2.bind(new InetSocketAddress(lh, 0));

                // send should not block
                ByteBuffer bb = ByteBuffer.wrap("XXX".getBytes("UTF-8"));
                int n = dc1.send(bb, dc2.getLocalAddress());
                assertTrue(n > 0);

                // receive should not block
                bb = ByteBuffer.allocate(10);
                dc2.receive(bb);
                assertTrue(bb.get(0) == 'X');
            }
        });
    }

    /**
     * Fiber blocks in DatagramChannel.receive
     */
    public void testDatagramhannelSendReceive2() {
        test(() -> {
            try (DatagramChannel dc1 = DatagramChannel.open();
                 DatagramChannel dc2 = DatagramChannel.open()) {

                InetAddress lh = InetAddress.getLocalHost();
                dc2.bind(new InetSocketAddress(lh, 0));

                // schedule send
                ByteBuffer bb = ByteBuffer.wrap("XXX".getBytes("UTF-8"));
                ScheduledSender.schedule(dc1, bb, dc2.getLocalAddress(), DELAY);

                // read should block
                bb = ByteBuffer.allocate(10);
                dc2.receive(bb);
                assertTrue(bb.get(0) == 'X');
            }
        });
    }

    /**
     * DatagramChannel close while Fiber blocked in receive
     */
    public void testDatagramhannelReceiveAsyncClose() {
        test(() -> {
            try (DatagramChannel dc = DatagramChannel.open()) {
                InetAddress lh = InetAddress.getLocalHost();
                dc.bind(new InetSocketAddress(lh, 0));
                ScheduledCloser.schedule(dc, DELAY);
                try {
                    dc.receive(ByteBuffer.allocate(100));
                    throw new RuntimeException("receive returned");
                } catch (AsynchronousCloseException expected) { }
            }
        });
    }

    /**
     * Fiber interrupted while blocked in DatagramChannel.receive
     */
    public void testDatagramhannelReceiveInterrupt() {
        test(() -> {
            try (DatagramChannel dc = DatagramChannel.open()) {
                InetAddress lh = InetAddress.getLocalHost();
                dc.bind(new InetSocketAddress(lh, 0));
                ScheduledInterrupter.schedule(Thread.currentThread(), DELAY);
                try {
                    dc.receive(ByteBuffer.allocate(100));
                    throw new RuntimeException("receive returned");
                } catch (ClosedByInterruptException expected) { }
            }
        });
    }

    /**
     * Fiber cancelled while blocked in DatagramChannel.receive
     */
    public void testDatagramhannelReceiveCancel() {
        test(() -> {
            try (DatagramChannel dc = DatagramChannel.open()) {
                InetAddress lh = InetAddress.getLocalHost();
                dc.bind(new InetSocketAddress(lh, 0));
                var fiber = Fiber.current().orElseThrow();
                ScheduledCanceller.schedule(fiber, DELAY);
                try {
                    dc.receive(ByteBuffer.allocate(100));
                    throw new RuntimeException("receive returned");
                } catch (IOException expected) { }
            }
        });
    }

    /**
     * Pipe read/write, no blocking
     */
    public void testPipeReadWrite1() {
        test(() -> {
            Pipe p = Pipe.open();
            try (Pipe.SinkChannel sink = p.sink();
                 Pipe.SourceChannel source = p.source()) {

                // write should not block
                ByteBuffer bb = ByteBuffer.wrap("XXX".getBytes("UTF-8"));
                int n = sink.write(bb);
                assertTrue(n > 0);

                // read should not block
                bb = ByteBuffer.allocate(10);
                n = source.read(bb);
                assertTrue(n > 0);
                assertTrue(bb.get(0) == 'X');
            }
        });
    }

    /**
     * Fiber blocks in Pipe.SourceChannel.read
     */
    public void testPipeReadWrite2() {
        test(() -> {
            Pipe p = Pipe.open();
            try (Pipe.SinkChannel sink = p.sink();
                 Pipe.SourceChannel source = p.source()) {

                // schedule write
                ByteBuffer bb = ByteBuffer.wrap("XXX".getBytes("UTF-8"));
                ScheduledWriter.schedule(sink, bb, DELAY);

                // read should block
                bb = ByteBuffer.allocate(10);
                int n = source.read(bb);
                assertTrue(n > 0);
                assertTrue(bb.get(0) == 'X');
            }
        });
    }

    /**
     * Fiber blocks in Pipe.SinkChannel write
     */
    public void testPipeReadWrite3() {
        test(() -> {
            Pipe p = Pipe.open();
            try (Pipe.SinkChannel sink = p.sink();
                 Pipe.SourceChannel source = p.source()) {

                // schedule thread to read to EOF
                ScheduledReader.schedule(source, true, DELAY);

                // write should block
                ByteBuffer bb = ByteBuffer.allocate(100*10024);
                for (int i=0; i<1000; i++) {
                    int n = sink.write(bb);
                    assertTrue(n > 0);
                    bb.clear();
                }
            }
        });
    }

    /**
     * Pipe.SourceChannel close while Fiber blocked in read
     */
    public void testPipeReadAsyncClose() {
        test(() -> {
            Pipe p = Pipe.open();
            try (Pipe.SourceChannel source = p.source()) {
                ScheduledCloser.schedule(source, DELAY);
                try {
                    int n = source.read(ByteBuffer.allocate(100));
                    throw new RuntimeException("read returned " + n);
                } catch (AsynchronousCloseException expected) { }
            }
        });
    }

    /**
     * Fiber interrupted while blocked in Pipe.SourceChannel read
     */
    public void testPipeReadInterrupt() {
        test(() -> {
            Pipe p = Pipe.open();
            try (Pipe.SourceChannel source = p.source()) {
                ScheduledInterrupter.schedule(Thread.currentThread(), DELAY);
                try {
                    int n = source.read(ByteBuffer.allocate(100));
                    throw new RuntimeException("read returned " + n);
                } catch (ClosedByInterruptException expected) { }
            }
        });
    }

    /**
     * Fiber cancelled while blocked in Pipe.SourceChannel read
     */
    public void testPipeReadCancel() {
        test(() -> {
            Pipe p = Pipe.open();
            try (Pipe.SourceChannel source = p.source()) {
                var fiber = Fiber.current().orElseThrow();
                ScheduledCanceller.schedule(fiber, DELAY);
                try {
                    int n = source.read(ByteBuffer.allocate(100));
                    throw new RuntimeException("read returned " + n);
                } catch (IOException expected) { }
            }
        });
    }

    /**
     * Pipe.SinkChannel close while Fiber blocked in write
     */
    public void testPipeWriteAsyncClose() {
        test(() -> {
            Pipe p = Pipe.open();
            try (Pipe.SinkChannel sink = p.sink()) {
                ScheduledCloser.schedule(sink, DELAY);
                try {
                    ByteBuffer bb = ByteBuffer.allocate(100*10024);
                    for (;;) {
                        int n = sink.write(bb);
                        assertTrue(n > 0);
                        bb.clear();
                    }
                } catch (AsynchronousCloseException expected) { }
            }
        });
    }

    /**
     * Fiber interrupted while blocked in Pipe.SinkChannel write
     */
    public void testPipeWriteInterrupt() {
        test(() -> {
            Pipe p = Pipe.open();
            try (Pipe.SinkChannel sink = p.sink()) {
                ScheduledInterrupter.schedule(Thread.currentThread(), DELAY);
                try {
                    ByteBuffer bb = ByteBuffer.allocate(100*10024);
                    for (;;) {
                        int n = sink.write(bb);
                        assertTrue(n > 0);
                        bb.clear();
                    }
                } catch (ClosedByInterruptException expected) { }
            }
        });
    }

    /**
     * Fiber cancelled while blocked in Pipe.SinkChannel write
     */
    public void testPipeWriteCancel() {
        test(() -> {
            Pipe p = Pipe.open();
            try (Pipe.SinkChannel sink = p.sink()) {
                var fiber = Fiber.current().orElseThrow();
                ScheduledCanceller.schedule(fiber, DELAY);
                try {
                    ByteBuffer bb = ByteBuffer.allocate(100*10024);
                    for (;;) {
                        int n = sink.write(bb);
                        assertTrue(n > 0);
                        bb.clear();
                    }
                } catch (IOException expected) { }
            }
        });
    }

    // -- supporting classes --


    /**
     * Creates a loopback connection
     */
    static class Connection implements Closeable {
        private final ServerSocketChannel ssc;
        private final SocketChannel sc1;
        private final SocketChannel sc2;
        Connection() throws IOException {
            var lh = InetAddress.getLocalHost();
            this.ssc = ServerSocketChannel.open().bind(new InetSocketAddress(lh, 0));
            this.sc1 = SocketChannel.open(ssc.getLocalAddress());
            this.sc2 = ssc.accept();
        }
        SocketChannel channel1() {
            return sc1;
        }
        SocketChannel channel2() {
            return sc2;
        }
        @Override
        public void close() throws IOException {
            if (ssc != null) ssc.close();
            if (sc1 != null) sc1.close();
            if (sc2 != null) sc2.close();
        }
    }

    /**
     * Closes a channel after a delay
     */
    static class ScheduledCloser implements Runnable {
        private final Closeable c;
        private final long delay;
        ScheduledCloser(Closeable c, long delay) {
            this.c = c;
            this.delay = delay;
        }
        @Override
        public void run() {
            try {
                Thread.sleep(delay);
                c.close();
            } catch (Exception e) { }
        }
        static void schedule(Closeable c, long delay) {
            new Thread(new ScheduledCloser(c, delay)).start();
        }
    }

    /**
     * Interrupts a thread or fiber after a delay
     */
    static class ScheduledInterrupter implements Runnable {
        private final Thread thread;
        private final long delay;

        ScheduledInterrupter(Thread thread, long delay) {
            this.thread = thread;
            this.delay = delay;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(delay);
                thread.interrupt();
            } catch (Exception e) { }
        }

        static void schedule(Thread thread, long delay) {
            new Thread(new ScheduledInterrupter(thread, delay)).start();
        }
    }

    /**
     * Cancel a fiber after a delay
     */
    static class ScheduledCanceller implements Runnable {
        private final Fiber fiber;
        private final long delay;

        ScheduledCanceller(Fiber fiber, long delay) {
            this.fiber = fiber;
            this.delay = delay;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(delay);
                fiber.cancel();
            } catch (Exception e) { }
        }

        static void schedule(Fiber fiber, long delay) {
            new Thread(new ScheduledCanceller(fiber, delay)).start();
        }
    }

    /**
     * Establish a connection to a socket address after a delay
     */
    static class ScheduledConnector implements Runnable {
        private final SocketChannel sc;
        private final SocketAddress address;
        private final long delay;

        ScheduledConnector(SocketChannel sc, SocketAddress address, long delay) {
            this.sc = sc;
            this.address = address;
            this.delay = delay;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(delay);
                sc.connect(address);
            } catch (Exception e) { }
        }

        static void schedule(SocketChannel sc, SocketAddress address, long delay) {
            new Thread(new ScheduledConnector(sc, address, delay)).start();
        }
    }

    /**
     * Reads from a connection, and to EOF, after a delay
     */
    static class ScheduledReader implements Runnable {
        private final ReadableByteChannel rbc;
        private final boolean readAll;
        private final long delay;

        ScheduledReader(ReadableByteChannel rbc, boolean readAll, long delay) {
            this.rbc = rbc;
            this.readAll = readAll;
            this.delay = delay;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(delay);
                ByteBuffer bb = ByteBuffer.allocate(8192);
                for (;;) {
                    int n = rbc.read(bb);
                    if (n == -1 || !readAll)
                        break;
                    bb.clear();
                }
            } catch (Exception e) { }
        }

        static void schedule(ReadableByteChannel rbc, boolean readAll, long delay) {
            new Thread(new ScheduledReader(rbc, readAll, delay)).start();
        }
    }

    /**
     * Writes to a connection after a delay
     */
    static class ScheduledWriter implements Runnable {
        private final WritableByteChannel wbc;
        private final ByteBuffer buf;
        private final long delay;

        ScheduledWriter(WritableByteChannel wbc, ByteBuffer buf, long delay) {
            this.wbc = wbc;
            this.buf = buf;
            this.delay = delay;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(delay);
                wbc.write(buf);
            } catch (Exception e) { }
        }

        static void schedule(WritableByteChannel wbc, ByteBuffer buf, long delay) {
            new Thread(new ScheduledWriter(wbc, buf, delay)).start();
        }
    }

    /**
     * Sends a datagram to a target address after a delay
     */
    static class ScheduledSender implements Runnable {
        private final DatagramChannel dc;
        private final ByteBuffer buf;
        private final SocketAddress address;
        private final long delay;

        ScheduledSender(DatagramChannel dc, ByteBuffer buf, SocketAddress address, long delay) {
            this.dc = dc;
            this.buf = buf;
            this.address = address;
            this.delay = delay;
        }

        @Override
        public void run() {
            try {
                Thread.sleep(delay);
                dc.send(buf, address);
            } catch (Exception e) { }
        }

        static void schedule(DatagramChannel dc, ByteBuffer buf,
                             SocketAddress address, long delay) {
            new Thread(new ScheduledSender(dc, buf, address, delay)).start();
        }
    }

}