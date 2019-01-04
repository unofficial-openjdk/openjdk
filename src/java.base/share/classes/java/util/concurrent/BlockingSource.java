/*
 * Copyright (c) 2018, Oracle and/or its affiliates. All rights reserved.
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
package java.util.concurrent;

import java.time.Duration;

/**
 * A source of elements that are retrieved by methods that block when there
 * are no elements present.
 *
 * <p> BlockingSource implementations should be thread-safe.
 *
 * @apiNote If this interface is useful then we could retrofit BlockingQueue,
 * CompletionService, java.nio.file.WatchService, and maybe others to extend it.
 *
 * @param <E> the type of elements
 */
public interface BlockingSource<E> {

    /**
     * Retrieves the next element, waiting if none are yet present.
     *
     * @return the next element
     * @throws InterruptedException if interrupted while waiting
     */
    E take() throws InterruptedException;

    /**
     * Retrieves the next element or {@code null} if none are present.
     *
     * @return the next element or {@code null} if none are present
     */
    E poll();

    /**
     * Retrieves the next element, waiting if necessary up to the specified wait
     * time if none are yet present.
     *
     * @param timeout how long to wait before giving up
     * @return the next element {@code null} if the specified waiting time
     *         elapses before one is present
     * @throws InterruptedException if interrupted while waiting
     */
    E poll(Duration timeout) throws InterruptedException;
}
