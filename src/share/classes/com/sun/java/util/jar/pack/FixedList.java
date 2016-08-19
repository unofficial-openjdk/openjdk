/*
 * Copyright (c) 2010, Oracle and/or its affiliates. All rights reserved.
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
package com.sun.java.util.jar.pack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;

/*
 * @author ksrini
 */

/*
 * This class provides an ArrayList implementation which has a fixed size,
 * thus all the operations which modifies the size have been rendered
 * inoperative. This essentially allows us to use generified array
 * lists in lieu of arrays.
 */
final class FixedList<E> implements List<E> {

    private final ArrayList<E> flist;

    protected FixedList(int capacity) {
        flist = new ArrayList<E>(capacity);
        // initialize the list to null
        for (int i = 0 ; i < capacity ; i++) {
            flist.add(null);
        }
    }
    public int size() {
        return flist.size();
    }

    public boolean isEmpty() {
        return flist.isEmpty();
    }

    public boolean contains(Object o) {
        return flist.contains(o);
    }

    public Iterator<E> iterator() {
        return flist.iterator();
    }

    public Object[] toArray() {
        return flist.toArray();
    }

    public <T> T[] toArray(T[] a) {
        return flist.toArray(a);
    }

    public boolean add(E e) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("operation not permitted");
    }

    public boolean remove(Object o) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("operation not permitted");
    }

    public boolean containsAll(Collection<?> c) {
        return flist.containsAll(c);
    }

    public boolean addAll(Collection<? extends E> c) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("operation not permitted");
    }

    public boolean addAll(int index, Collection<? extends E> c) throws UnsupportedOperationException {
         throw new UnsupportedOperationException("operation not permitted");
    }

    public boolean removeAll(Collection<?> c)  throws UnsupportedOperationException  {
         throw new UnsupportedOperationException("operation not permitted");
    }

    public boolean retainAll(Collection<?> c)  throws UnsupportedOperationException  {
        throw new UnsupportedOperationException("operation not permitted");
    }

    public void clear()  throws UnsupportedOperationException {
        throw new UnsupportedOperationException("operation not permitted");
    }

    public E get(int index) {
        return flist.get(index);
    }

    public E set(int index, E element) {
        return flist.set(index, element);
    }

    public void add(int index, E element)  throws UnsupportedOperationException {
        throw new UnsupportedOperationException("operation not permitted");
    }

    public E remove(int index)   throws UnsupportedOperationException {
        throw new UnsupportedOperationException("operation not permitted");
    }

    public int indexOf(Object o) {
        return flist.indexOf(o);
    }

    public int lastIndexOf(Object o) {
        return flist.lastIndexOf(o);
    }

    public ListIterator<E> listIterator() {
        return flist.listIterator();
    }

    public ListIterator<E> listIterator(int index) {
        return flist.listIterator(index);
    }

    public List<E> subList(int fromIndex, int toIndex) {
        return flist.subList(fromIndex, toIndex);
    }

    @Override
    public String toString() {
        return "FixedList{" + "plist=" + flist + '}';
    }
}
