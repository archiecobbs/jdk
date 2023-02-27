/*
 * Copyright (c) 1999, 2021, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.tools.javac.util;

import java.lang.ref.SoftReference;

import com.sun.tools.javac.util.DefinedBy.Api;

/**
 * Implementation of Name.Table that stores all names in a single shared
 * byte array, expanding it as needed. This avoids the overhead incurred
 * by using an array of bytes for each name.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class SharedNameTable extends Name.Table {
    // maintain a freelist of recently used name tables for reuse.
    private static List<SoftReference<SharedNameTable>> freelist = List.nil();

    public static synchronized SharedNameTable create(Names names) {
        while (freelist.nonEmpty()) {
            SharedNameTable t = freelist.head.get();
            freelist = freelist.tail;
            if (t != null) {
                return t;
            }
        }
        return new SharedNameTable(names);
    }

    private static synchronized void dispose(SharedNameTable t) {
        freelist = freelist.prepend(new SoftReference<>(t));
    }

    /** The hash table for names.
     */
    private NameImpl[] hashes;

    /** The shared byte array holding all encountered names.
     */
    public byte[] bytes;

    /** The mask to be used for hashing
     */
    private int hashMask;

    /** The number of filled bytes in `names'.
     */
    private int nc = 0;

    /** Allocator
     *  @param names The main name table
     *  @param hashSize the (constant) size to be used for the hash table
     *                  needs to be a power of two.
     *  @param nameSize the initial size of the name table.
     */
    public SharedNameTable(Names names, int hashSize, int nameSize) {
        super(names);
        hashMask = hashSize - 1;
        hashes = new NameImpl[hashSize];
        bytes = new byte[nameSize];

    }

    public SharedNameTable(Names names) {
        this(names, 0x8000, 0x20000);
    }

    @Override
    public Name fromChars(char[] cs, int start, int len) {
        int nc = this.nc;
        byte[] bytes = this.bytes = ArrayUtils.ensureCapacity(this.bytes, nc + len * 3);
        int nbytes = Convert.chars2utf(cs, start, bytes, nc, len) - nc;
        int h = hashValue(bytes, nc, nbytes) & hashMask;
        NameImpl n = hashes[h];
        while (n != null &&
                (n.getByteLength() != nbytes ||
                !equals(bytes, n.index, bytes, nc, nbytes))) {
            n = n.next;
        }
        if (n == null) {
            n = newNameImpl(nc, nbytes);
            n.next = hashes[h];
            hashes[h] = n;
            this.nc = nc + nbytes;
            if (nbytes == 0) {
                this.nc++;
            }
        }
        return n;
    }

    @Override
    public Name fromUtf(byte[] cs, int start, int len) {
        int h = hashValue(cs, start, len) & hashMask;
        NameImpl n = hashes[h];
        byte[] names = this.bytes;
        while (n != null &&
                (n.getByteLength() != len || !equals(names, n.index, cs, start, len))) {
            n = n.next;
        }
        if (n == null) {
            int nc = this.nc;
            names = this.bytes = ArrayUtils.ensureCapacity(names, nc + len);
            System.arraycopy(cs, start, names, nc, len);
            n = newNameImpl(nc, len);
            n.next = hashes[h];
            hashes[h] = n;
            this.nc = nc + len;
            if (len == 0) {
                this.nc++;
            }
        }
        return n;
    }

    @Override
    public void dispose() {
        dispose(this);
    }

    // Build and return an AsciiNameImpl, if possible, otherwise a regular NameImpl
    NameImpl newNameImpl(final int index, final int length) {
        int offset = index;
        int remain = length;
        while (remain > 0) {
            if ((bytes[offset++] & 0x80) != 0)
                break;
            remain--;
        }
        return remain == 0 ?
          new AsciiNameImpl(this, index, length) :
          new NameImpl(this, index, length);
    }

    // Implementation used when there are one or more non-ASCII characters
    static class NameImpl extends Name {
        /** The next name occupying the same hash bucket.
         */
        NameImpl next;

        /** The index where the bytes of this name are stored in the global name
         *  buffer `byte'.
         */
        int index;

        /** The number of bytes in this name.
         */
        int length;

        NameImpl(SharedNameTable table, int index, int length) {
            super(table);
            this.index = index;
            this.length = length;
        }

        @Override
        public int getIndex() {
            return index;
        }

        @Override
        public int getByteLength() {
            return length;
        }

        @Override
        public byte getByteAt(int i) {
            return getByteArray()[index + i];
        }

        @Override
        public byte[] getByteArray() {
            return ((SharedNameTable) table).bytes;
        }

        @Override
        public int getByteOffset() {
            return index;
        }

        /** Return the hash value of this name.
         */
        @DefinedBy(Api.LANGUAGE_MODEL)
        public int hashCode() {
            return index;
        }

        /** Is this name equal to other?
         */
        @DefinedBy(Api.LANGUAGE_MODEL)
        public boolean equals(Object other) {
            return (other instanceof Name name)
                    && table == name.table
                    && index == name.getIndex();
        }
    }

    // Implementation used when there are only ASCII (0x00 - 0x7f) characters
    static class AsciiNameImpl extends NameImpl {

        AsciiNameImpl(SharedNameTable table, int index, int length) {
            super(table, index, length);
        }

        @Override
        public boolean contentEquals(CharSequence cs) {
            if (cs.length() != length)
                return false;
            byte[] data = getByteArray();
            int off = index;
            for (int i = 0; i < length; i++) {
                char ch = cs.charAt(i);
                if ((int)ch != (data[off++] & 0xff))
                    return false;
            }
            return true;
        }

        @Override
        public char charAt(int pos) {
            if (pos < 0 || pos >= length)
                throw new IndexOutOfBoundsException();
            return (char)(getByteArray()[index + pos] & 0xff);
        }

        @Override
        public int length() {
            return length;
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            if (start < 0 || end < start || end > length)
                throw new IndexOutOfBoundsException();
            return new AsciiCharSequence(getByteArray(), index + start, end - start);
        }

        @Override
        public String toString() {
            return AsciiNameImpl.toString(getByteArray(), index, length);
        }

        static String toString(byte[] data, int offset, int length) {
            char[] array = new char[length];
            for (int i = 0; i < length; i++)
                array[i] = (char)(data[offset + i] & 0xff);
            return new String(array);
        }
    }

// AsciiCharSequence

    static class AsciiCharSequence implements CharSequence {

        private final byte[] data;
        private final int offset;
        private final int length;

        AsciiCharSequence(byte[] data, int offset, int length) {
            this.data = data;
            this.offset = offset;
            this.length = length;
        }

        @Override
        public char charAt(int index) {
            if (index < 0 || index >= length)
                throw new IndexOutOfBoundsException();
            return (char)(data[offset + index] & 0xff);
        }

        @Override
        public int length() {
            return length;
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            if (start < 0 || end < start || end > length)
                throw new IndexOutOfBoundsException();
            return new AsciiCharSequence(data, offset + start, end - start);
        }

        @Override
        public String toString() {
            return AsciiNameImpl.toString(data, offset, length);
        }
    }
}
