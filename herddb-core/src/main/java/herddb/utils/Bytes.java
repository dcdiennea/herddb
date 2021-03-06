/*
 Licensed to Diennea S.r.l. under one
 or more contributor license agreements. See the NOTICE file
 distributed with this work for additional information
 regarding copyright ownership. Diennea S.r.l. licenses this file
 to you under the Apache License, Version 2.0 (the
 "License"); you may not use this file except in compliance
 with the License.  You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing,
 software distributed under the License is distributed on an
 "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 KIND, either express or implied.  See the License for the
 specific language governing permissions and limitations
 under the License.

 */
package herddb.utils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A wrapper for byte[], in order to use it as keys on HashMaps
 *
 * @author enrico.olivelli
 */
public final class Bytes implements Comparable<Bytes> {

    public final byte[] data;
    private final int hashCode;

    public static Bytes from_string(String s) {
        return new Bytes(s.getBytes(StandardCharsets.UTF_8));
    }

    public static Bytes from_long(long value) {
        byte[] res = new byte[8];
        putLong(res, 0, value);
        return new Bytes(res);
    }

    public static Bytes from_array(byte[] data) {
        return new Bytes(data);
    }

    public byte[] to_array() {
        return data;
    }

    public static Bytes from_int(int value) {
        byte[] res = new byte[4];
        putInt(res, 0, value);
        return new Bytes(res);
    }

    public static Bytes from_timestamp(java.sql.Timestamp value) {
        byte[] res = new byte[8];
        putLong(res, 0, value.getTime());
        return new Bytes(res);
    }

    public long to_long() {
        return toLong(data, 0, 8);
    }

    public int to_int() {
        return toInt(data, 0, 4);
    }

    public String to_string() {
        return new String(data, StandardCharsets.UTF_8);
    }

    public java.sql.Timestamp to_timestamp() {
        return toTimestamp(data, 0, 8);
    }

    public Bytes(byte[] data) {
        this.data = data;
        this.hashCode = Arrays.hashCode(this.data);;
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final Bytes other = (Bytes) obj;
        return Arrays.equals(this.data, other.data);
    }

    public static void putLong(byte[] bytes, int offset, long val) {
        for (int i = offset + 7; i > offset; i--) {
            bytes[i] = (byte) val;
            val >>>= 8;
        }
        bytes[offset] = (byte) val;
    }

    public static void putInt(byte[] bytes, int offset, int val) {
        for (int i = offset + 3; i > offset; i--) {
            bytes[i] = (byte) val;
            val >>>= 8;
        }
        bytes[offset] = (byte) val;
    }

    public static void putTimestamp(byte[] bytes, int offset, int val) {
        for (int i = offset + 7; i > offset; i--) {
            bytes[i] = (byte) val;
            val >>>= 8;
        }
        bytes[offset] = (byte) val;
    }

    public static long toLong(byte[] bytes, int offset, final int length) {
        long l = 0;
        for (int i = offset; i < offset + length; i++) {
            l <<= 8;
            l ^= bytes[i] & 0xFF;
        }
        return l;
    }

    public static int toInt(byte[] bytes, int offset, final int length) {
        int n = 0;
        for (int i = offset; i < (offset + length); i++) {
            n <<= 8;
            n ^= bytes[i] & 0xFF;
        }
        return n;
    }

    public static java.sql.Timestamp toTimestamp(byte[] bytes, int offset, final int length) {
        long l = 0;
        for (int i = offset; i < offset + length; i++) {
            l <<= 8;
            l ^= bytes[i] & 0xFF;
        }
        if (l < 0){
            return null;
        }
        return new java.sql.Timestamp(l);
    }

    @Override
    public int compareTo(Bytes o) {
        return compare(this.data, o.data);
    }

    private static int compare(byte[] left, byte[] right) {
        for (int i = 0, j = 0; i < left.length && j < right.length; i++, j++) {
            int a = (left[i] & 0xff);
            int b = (right[j] & 0xff);
            if (a != b) {
                return a - b;
            }
        }
        return left.length - right.length;
    }

    public String toString() {
        // ONLY FOR TESTS
        return new String(data, StandardCharsets.UTF_8);
    }

}
