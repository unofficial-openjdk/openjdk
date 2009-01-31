/**
 * @test @(#)Test.java	1.2 07/04/24
 * @bug 6547163
 * @summary regression in arraycopy on sparc
 *
 * @run main Test
 */


public class Test {

    static boolean failed = false;
    static final int basz = 256;
    private static byte[] bbuf = new byte[basz];
    private static char[] cbuf = new char[basz/2];
    private static  int[] ibuf = new  int[basz/4];
    private static long[] lbuf = new long[basz/8];
    private static Integer[] obuf = new Integer[basz/4];

    private static byte[] bbuf1 = new byte[basz];
    private static byte[] bbuf2 = new byte[basz];
    private static byte[] bbuf3 = new byte[basz*2];
    private static byte[] bbuf4 = new byte[basz*2];
    private static char[] cbuf1 = new char[basz/2];
    private static char[] cbuf2 = new char[basz/2];
    private static char[] cbuf3 = new char[basz];
    private static char[] cbuf4 = new char[basz];
    private static  int[] ibuf1 = new  int[basz/4];
    private static  int[] ibuf2 = new  int[basz/4];
    private static  int[] ibuf3 = new  int[basz/2];
    private static  int[] ibuf4 = new  int[basz/2];
    private static long[] lbuf1 = new long[basz/8];
    private static long[] lbuf2 = new long[basz/8];
    private static long[] lbuf3 = new long[basz/4];
    private static long[] lbuf4 = new long[basz/4];
    private static Integer[] obuf1  = new Integer[basz/4];
    private static Integer[] obuf2  = new Integer[basz/4];
    private static Integer[] obuf3  = new Integer[basz/2];
    private static Integer[] obuf4  = new Integer[basz/2];

    private static int[] tbuf = new int[basz*2];

    static void initarrays() {
        for (int i = 0; i < basz; i++) {
            bbuf[i] = (byte)i;
            if ((i & 1) == 0) { // basz/2
                int j = i >> 1;
                cbuf[j] = (char)j;
                if ((j & 1) == 0) { // basz/4
                    int k = j >> 1;
                    ibuf[k] = k;
                    obuf[k] = new Integer(k);
                    if ((k & 1) == 0) { // basz/8
                        int l = k >> 1;
                        lbuf[l] = (long)l;
                    }
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < basz*2; i++) {
            tbuf[i]  = i;
            bbuf3[i] = (byte)i;
            if ((i & 1) == 0) { // basz
                int j = i >> 1;
                bbuf1[j] = (byte)j;
                cbuf3[j] = (char)j;
                if ((j & 1) == 0) { // basz/2
                    int k = j >> 1;
                    cbuf1[k] = (char)k;
                    ibuf3[k] = k;
                    obuf3[k] = new Integer(k);
                    if ((k & 1) == 0) { // basz/4
                        int l = k >> 1;
                        ibuf1[l] = l;
                        lbuf3[l] = (long)l;
                        obuf1[l] = new Integer(l);
                        if ((l & 1) == 0) { // basz/8
                            int m = l >> 1;
                            lbuf1[m] = (long)m;
                        }
                    }
                }
            }
        }
        for (int i = 0; i < 3; i++) {
            System.out.println("");
            System.out.println("Disjoint arraycopy:");
            test(bbuf1, bbuf2, basz, "bytes", basz, true);
            verify(bbuf2, basz);
            test(cbuf1, cbuf2, basz/2,  "chars", basz, true);
            verify(cbuf2, basz/2);
            test(ibuf1, ibuf2, basz/4,  " ints ", basz, true);
            verify(ibuf2, basz/4);
            test(lbuf1, lbuf2, basz/8,  " longs", basz, true);
            verify(lbuf2, basz/8);
            test(obuf1, obuf2, basz/4,  " oops ", basz, true);
            verify(obuf2, basz/4);

            System.arraycopy(bbuf3, 0, bbuf4, 0, basz*2);
            System.arraycopy(cbuf3, 0, cbuf4, 0, basz);
            System.arraycopy(ibuf3, 0, ibuf4, 0, basz/2);
            System.arraycopy(lbuf3, 0, lbuf4, 0, basz/4);
            System.arraycopy(obuf3, 0, obuf4, 0, basz/2);

            System.out.println("");
            System.out.println("Disjoint touched arraycopy:");
            testc(bbuf4, basz-1, basz-1, 1000000, "bytes", basz-1, true);
            verifyc(bbuf4, basz-1, basz-1);
            testc(cbuf4, basz/2-1, basz/2-1, 1000000, "chars", basz-2, true);
            verifyc(cbuf4, basz/2-1, basz/2-1);
            testc(ibuf4, basz/4-1, basz/4-1,  1000000, " ints ", basz-4, true);
            verifyc(ibuf4, basz/4-1, basz/4-1);
            testc(lbuf4, basz/8-1,  basz/8-1, 1000000, " longs", basz-8, true);
            verifyc(lbuf4, basz/8-1, basz/8-1);
            testc(obuf4, basz/4-1, basz/4-1,  1000000, " oops ", basz-4, true);
            verifyc(obuf4, basz/4-1, basz/4-1);

            System.arraycopy(bbuf3, 0, bbuf4, 0, basz*2);
            System.arraycopy(cbuf3, 0, cbuf4, 0, basz);
            System.arraycopy(ibuf3, 0, ibuf4, 0, basz/2);
            System.arraycopy(lbuf3, 0, lbuf4, 0, basz/4);
            System.arraycopy(obuf3, 0, obuf4, 0, basz/2);

            System.out.println("");
            System.out.println("Conjoint (1 element overlaped) arraycopy:");
            testc(bbuf4, basz-1, basz, 1, "bytes", basz, false);
            verifyc(bbuf4, basz-1, basz);
            testc(cbuf4, basz/2-1, basz/2, 1, "chars", basz, false);
            verifyc(cbuf4, basz/2-1, basz/2);
            testc(ibuf4, basz/4-1, basz/4, 1,  " ints ", basz, false);
            verifyc(ibuf4, basz/4-1, basz/4);
            testc(lbuf4, basz/8-1, basz/8, 1,  " longs", basz, false);
            verifyc(lbuf4, basz/8-1, basz/8);
            testc(obuf4, basz/4-1, basz/4, 1,  " oops ", basz, false);
            verifyc(obuf4, basz/4-1, basz/4);

            testc(bbuf4, basz-1, basz, 1000000, "bytes", basz, true);
            testc(cbuf4, basz/2-1, basz/2, 1000000, "chars", basz, true);
            testc(ibuf4, basz/4-1, basz/4, 1000000,  " ints ", basz, true);
            testc(lbuf4, basz/8-1, basz/8, 1000000,  " longs", basz, true);
            testc(obuf4, basz/4-1, basz/4, 1000000,  " oops ", basz, true);

            System.arraycopy(bbuf3, 0, bbuf4, 0, basz*2);
            System.arraycopy(cbuf3, 0, cbuf4, 0, basz);
            System.arraycopy(ibuf3, 0, ibuf4, 0, basz/2);
            System.arraycopy(lbuf3, 0, lbuf4, 0, basz/4);
            System.arraycopy(obuf3, 0, obuf4, 0, basz/2);

            System.arraycopy(bbuf3, 0, bbuf4, 0, basz*2);
            System.arraycopy(cbuf3, 0, cbuf4, 0, basz);
            System.arraycopy(ibuf3, 0, ibuf4, 0, basz/2);
            System.arraycopy(lbuf3, 0, lbuf4, 0, basz/4);
            System.arraycopy(obuf3, 0, obuf4, 0, basz/2);

            System.out.println("");
            System.out.println("Conjoint (8 elements overlaped) arraycopy:");
            testc(bbuf4, basz-8, basz, 1, "bytes", basz, false);
            verifyc(bbuf4, basz-8, basz);
            testc(cbuf4, basz/2-8, basz/2, 1, "chars", basz, false);
            verifyc(cbuf4, basz/2-8, basz/2);
            testc(ibuf4, basz/4-8, basz/4, 1,  " ints ", basz, false);
            verifyc(ibuf4, basz/4-8, basz/4);
            testc(lbuf4, basz/8-8, basz/8, 1,  " longs", basz, false);
            verifyc(lbuf4, basz/8-8, basz/8);
            testc(obuf4, basz/4-8, basz/4, 1,  " oops ", basz, false);
            verifyc(obuf4, basz/4-8, basz/4);

            testc(bbuf4, basz-8, basz, 1000000, "bytes", basz, true);
            testc(cbuf4, basz/2-8, basz/2, 1000000, "chars", basz, true);
            testc(ibuf4, basz/4-8, basz/4, 1000000,  " ints ", basz, true);
            testc(lbuf4, basz/8-8, basz/8, 1000000,  " longs", basz, true);
            testc(obuf4, basz/4-8, basz/4, 1000000,  " oops ", basz, true);

            System.arraycopy(bbuf3, 0, bbuf4, 0, basz*2);
            System.arraycopy(cbuf3, 0, cbuf4, 0, basz);
            System.arraycopy(ibuf3, 0, ibuf4, 0, basz/2);
            System.arraycopy(lbuf3, 0, lbuf4, 0, basz/4);
            System.arraycopy(obuf3, 0, obuf4, 0, basz/2);

            initarrays();
            System.out.println("");
            System.out.println("Disjoint +4 bytes offset arraycopy:");
            test4(bbuf, basz/2, 1,   "bytes", basz/2, false);
            verifyoff(bbuf, 4, basz/2);
            test4(cbuf, basz/4, 1,  " chars", basz/2, false);
            verifyoff(cbuf, 2, basz/4);
            test4(ibuf, basz/8, 1,  "  ints", basz/2, false);
            verifyoff(ibuf, 1, basz/8);
            test4(obuf, basz/8, 1,  "  oops", basz/2, false);
            verifyoff(obuf, 1, basz/8);

            test4(bbuf, basz/2, 1000000,   "bytes", basz/2, true);
            test4(cbuf, basz/4, 1000000,  " chars", basz/2, true);
            test4(ibuf, basz/8, 1000000,  "  ints", basz/2, true);
            test4(obuf, basz/8, 1000000,  "  oops", basz/2, true);

            initarrays();
            System.out.println("");
            System.out.println("Disjoint +8 bytes offset arraycopy:");
            test8(bbuf, basz/2, 1,   "bytes", basz/2, false);
            verifyoff(bbuf, 8, basz/2);
            test8(cbuf, basz/4, 1,  " chars", basz/2, false);
            verifyoff(cbuf, 4, basz/4);
            test8(ibuf, basz/8, 1,  "  ints", basz/2, false);
            verifyoff(ibuf, 2, basz/8);
            test8(obuf, basz/8, 1,  "  oops", basz/2, false);
            verifyoff(obuf, 2, basz/8);
            test8(lbuf, basz/16,1,  " longs", basz/2, false);
            verifyoff(lbuf, 1, basz/16);

            test8(bbuf, basz/2, 1000000,   "bytes", basz/2, true);
            test8(cbuf, basz/4, 1000000,  " chars", basz/2, true);
            test8(ibuf, basz/8, 1000000,  "  ints", basz/2, true);
            test8(obuf, basz/8, 1000000,  "  oops", basz/2, true);
            test8(lbuf, basz/16,1000000,  " longs", basz/2, true);

            initarrays();
            System.out.println("");
            System.out.println("Conjoint +4 bytes offset arraycopy:");
            testc4(bbuf, basz/2, 1,   "bytes", basz/2, false);
            verifyc(bbuf, 4, basz/2);
            testc4(cbuf, basz/4, 1,  " chars", basz/2, false);
            verifyc(cbuf, 2, basz/4);
            testc4(ibuf, basz/8, 1,  "  ints", basz/2, false);
            verifyc(ibuf, 1, basz/8);
            testc4(obuf, basz/8, 1,  "  oops", basz/2, false);
            verifyc(obuf, 1, basz/8);

            testc4(bbuf, basz/2, 1000000,   "bytes", basz/2, true);
            testc4(cbuf, basz/4, 1000000,  " chars", basz/2, true);
            testc4(ibuf, basz/8, 1000000,  "  ints", basz/2, true);
            testc4(obuf, basz/8, 1000000,  "  oops", basz/2, true);

            initarrays();
            System.out.println("");
            System.out.println("Conjoint +8 bytes offset arraycopy:");
            testc8(bbuf, basz/2, 1,   "bytes", basz/2, false);
            verifyc(bbuf, 8, basz/2);
            testc8(cbuf, basz/4, 1,  " chars", basz/2, false);
            verifyc(cbuf, 4, basz/4);
            testc8(ibuf, basz/8, 1,  "  ints", basz/2, false);
            verifyc(ibuf, 2, basz/8);
            testc8(obuf, basz/8, 1,  "  oops", basz/2, false);
            verifyc(obuf, 2, basz/8);
            testc8(lbuf, basz/16, 1, " longs", basz/2, false);
            verifyc(lbuf, 1, basz/16);

            testc8(bbuf, basz/2, 1000000, "bytes", basz/2, true);
            testc8(cbuf, basz/4, 1000000,  " chars", basz/2, true);
            testc8(ibuf, basz/8, 1000000,  "  ints", basz/2, true);
            testc8(obuf, basz/8, 1000000,  "  oops", basz/2, true);
            testc8(lbuf, basz/16, 1000000, " longs", basz/2, true);

            initarrays();
            System.out.println("");
            System.out.println("Disjoint +1 bytes offset arraycopy:");
            test1(bbuf, basz/2, 1,   "bytes", basz/2, false);
            verifyoff(bbuf, 1, basz/2);
            test1(bbuf, basz/2, 1000000, "bytes", basz/2, true);

            initarrays();
            System.out.println("Disjoint +3 bytes offset arraycopy:");
            test3(bbuf, basz/2, 1,   "bytes", basz/2, false);
            verifyoff(bbuf, 3, basz/2);
            test3(bbuf, basz/2, 1000000, "bytes", basz/2, true);

            initarrays();
            System.out.println("Conjoint +1 bytes offset arraycopy:");
            testc1(bbuf, basz/2, 1,   "bytes", basz/2, false);
            verifyc(bbuf, 1, basz/2);
            testc1(bbuf, basz/2, 1000000, "bytes", basz/2, true);

            initarrays();
            System.out.println("Conjoint +3 bytes offset arraycopy:");
            testc3(bbuf, basz/2, 1,   "bytes", basz/2, false);
            verifyc(bbuf, 3, basz/2);
            testc3(bbuf, basz/2, 1000000, "bytes", basz/2, true);

            initarrays();
            System.out.println("");
            System.out.println("Disjoint +2 bytes offset arraycopy:");
            test2(bbuf, basz/2, 1,   "bytes", basz/2, false);
            verifyoff(bbuf, 2, basz/2);
            test2(bbuf, basz/2, 1000000, "bytes", basz/2, true);
            test2(cbuf, basz/4, 1,  " chars", basz/2, false);
            verifyoff(cbuf, 1, basz/4);
            test2(cbuf, basz/4, 1000000,  " chars", basz/2, true);

            initarrays();
            System.out.println("Conjoint +2 bytes offset arraycopy:");
            testc2(bbuf, basz/2, 1,   "bytes", basz/2, false);
            verifyc(bbuf, 2, basz/2);
            testc2(bbuf, basz/2, 1000000, "bytes", basz/2, true);
            testc2(cbuf, basz/4, 1,  " chars", basz/2, false);
            verifyc(cbuf, 1, basz/4);
            testc2(cbuf, basz/4, 1000000,  " chars", basz/2, true);

            initarrays();
            System.out.println("");
            System.out.println("Disjoint +5 bytes offset arraycopy:");
            test5(bbuf, basz/2, 1,   "bytes", basz/2, false);
            verifyoff(bbuf, 5, basz/2);
            test5(bbuf, basz/2, 1000000, "bytes", basz/2, true);

            initarrays();
            System.out.println("Disjoint +7 bytes offset arraycopy:");
            test7(bbuf, basz/2, 1,   "bytes", basz/2, false);
            verifyoff(bbuf, 7, basz/2);
            test7(bbuf, basz/2, 1000000, "bytes", basz/2, true);

            initarrays();
            System.out.println("Conjoint +5 bytes offset arraycopy:");
            testc5(bbuf, basz/2, 1,   "bytes", basz/2, false);
            verifyc(bbuf, 5, basz/2);
            testc5(bbuf, basz/2, 1000000, "bytes", basz/2, true);

            initarrays();
            System.out.println("Conjoint +7 bytes offset arraycopy:");
            testc7(bbuf, basz/2, 1,   "bytes", basz/2, false);
            verifyc(bbuf, 7, basz/2);
            testc7(bbuf, basz/2, 1000000, "bytes", basz/2, true);

            initarrays();
            System.out.println("");
            System.out.println("Disjoint +6 bytes offset arraycopy:");
            test6(bbuf, basz/2, 1,   "bytes", basz/2, false);
            verifyoff(bbuf, 6, basz/2);
            test6(bbuf, basz/2, 1000000, "bytes", basz/2, true);
            test6(cbuf, basz/4, 1,  " chars", basz/2, false);
            verifyoff(cbuf, 3, basz/4);
            test6(cbuf, basz/4, 1000000,  " chars", basz/2, true);

            initarrays();
            System.out.println("Conjoint +6 bytes offset arraycopy:");
            testc6(bbuf, basz/2, 1,   "bytes", basz/2, false);
            verifyc(bbuf, 6, basz/2);
            testc6(bbuf, basz/2, 1000000, "bytes", basz/2, true);
            testc6(cbuf, basz/4, 1,  " chars", basz/2, false);
            verifyc(cbuf, 3, basz/4);
            testc6(cbuf, basz/4, 1000000,  " chars", basz/2, true);
        }
        if (failed) {
            System.out.println("TEST FAILED");
            System.exit(97);
        }
    }

    private static void test(byte[] o1, byte[] o2, int len,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < 1000000; i++)
            System.arraycopy(o1, 0, o2, 0, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void verify(byte[] o2, int len) {
        int length = o2.length;
        for (int i = 0; i < length; i++) {
            if (o2[i] != (byte)tbuf[i]) {
              failed = true;
              System.out.println("byte[" + i + "] (" + o2[i] + ") != " +
                                  (byte)tbuf[i]);
            }
        }
    }

    private static void test(char[] o1, char[] o2, int len,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < 1000000; i++)
            System.arraycopy(o1, 0, o2, 0, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void verify(char[] o2, int len) {
        int length = o2.length;
        for (int i = 0; i < length; i++) {
            if (o2[i] != (char)tbuf[i]) {
              failed = true;
              System.out.println("char[" + i + "] (" + (int)o2[i] + ") != " +
                                  tbuf[i]);
            }
        }
    }

    private static void test(int[] o1, int[] o2, int len,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < 1000000; i++)
            System.arraycopy(o1, 0, o2, 0, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void verify(int[] o2, int len) {
        int length = o2.length;
        for (int i = 0; i < length; i++) {
            if (o2[i] != tbuf[i]) {
              failed = true;
              System.out.println("int[" + i + "] (" + o2[i] + ") != " +
                                  tbuf[i]);
            }
        }
    }

    private static void test(long[] o1, long[] o2, int len,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < 1000000; i++)
            System.arraycopy(o1, 0, o2, 0, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void verify(long[] o2, int len) {
        int length = o2.length;
        for (int i = 0; i < length; i++) {
            if (o2[i] != tbuf[i]) {
              failed = true;
              System.out.println("long[" + i + "] (" + o2[i] + ") != " +
                                  tbuf[i]);
            }
        }
    }

    private static void test(Integer[] o1, Integer[] o2, int len,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < 1000000; i++)
            System.arraycopy(o1, 0, o2, 0, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void verify(Integer[] o2, int len) {
        int length = o2.length;
        for (int i = 0; i < length; i++) {
            if (o2[i].intValue() != tbuf[i]) {
              failed = true;
              System.out.println("Integer[" + i + "] (" + o2[i].intValue() + 
                                 ") != " + tbuf[i]);
            }
        }
    }

    private static void testc(byte[] o1, int off, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 0, o1, off, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void verifyc(byte[] o2, int off, int len) {
        for (int i = 0; i < off; i++) {
            if (o2[i] != (byte)tbuf[i]) {
              failed = true;
              System.out.println("byte["+(i)+"] ("+(int)o2[i]+") != " +
                                  tbuf[i]);
            }
        }
        for (int i = 0; i < len; i++) {
            if (o2[off+i] != (byte)tbuf[i]) {
              failed = true;
              System.out.println("byte["+(off+i)+"] ("+(int)o2[off+i]+") != " +
                                  tbuf[i]);
            }
        }
        int length = o2.length;
        for (int i = off+len; i < length; i++) {
            if (o2[i] != (byte)tbuf[i]) {
              failed = true;
              System.out.println("byte["+(i)+"] ("+(int)o2[i]+") != " +
                                  tbuf[i]);
            }
        }
    }

    private static void testc(char[] o1, int off, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 0, o1, off, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void verifyc(char[] o2, int off, int len) {
        for (int i = 0; i < off; i++) {
            if (o2[i] != (char)tbuf[i]) {
              failed = true;
              System.out.println("char["+(i)+"] ("+(int)o2[i]+") != " +
                                  tbuf[i]);
            }
        }
        for (int i = 0; i < len; i++) {
            if (o2[off+i] != (char)tbuf[i]) {
              failed = true;
              System.out.println("char["+(off+i)+"] ("+(int)o2[off+i]+") != " +
                                  tbuf[i]);
            }
        }
        int length = o2.length;
        for (int i = off+len; i < length; i++) {
            if (o2[i] != (char)tbuf[i]) {
              failed = true;
              System.out.println("char["+(i)+"] ("+(int)o2[i]+") != " +
                                  tbuf[i]);
            }
        }
    }

    private static void testc(int[] o1, int off, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 0, o1, off, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void verifyc(int[] o2, int off, int len) {
        for (int i = 0; i < off; i++) {
            if (o2[i] != tbuf[i]) {
              failed = true;
              System.out.println("int["+(i)+"] ("+o2[i]+") != " +
                                  tbuf[i]);
            }
        }
        for (int i = 0; i < len; i++) {
            if (o2[off+i] != tbuf[i]) {
              failed = true;
              System.out.println("int["+(off+i)+"] ("+o2[off+i]+") != " +
                                  tbuf[i]);
            }
        }
        int length = o2.length;
        for (int i = off+len; i < length; i++) {
            if (o2[i] != tbuf[i]) {
              failed = true;
              System.out.println("int["+(i)+"] ("+o2[i]+") != " +
                                  tbuf[i]);
            }
        }
    }

    private static void testc(long[] o1, int off, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 0, o1, off, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void verifyc(long[] o2, int off, int len) {
        for (int i = 0; i < off; i++) {
            if (o2[i] != tbuf[i]) {
              failed = true;
              System.out.println("long["+(i)+"] ("+o2[i]+") != " +
                                  tbuf[i]);
            }
        }
        for (int i = 0; i < len; i++) {
            if (o2[off+i] != tbuf[i]) {
              failed = true;
              System.out.println("long["+(off+i)+"] ("+o2[off+i]+") != " +
                                  tbuf[i]);
            }
        }
        int length = o2.length;
        for (int i = off+len; i < length; i++) {
            if (o2[i] != tbuf[i]) {
              failed = true;
              System.out.println("long["+(i)+"] ("+o2[i]+") != " +
                                  tbuf[i]);
            }
        }
    }

    private static void testc(Integer[] o1, int off, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 0, o1, off, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void verifyc(Integer[] o2, int off, int len) {
        for (int i = 0; i < off; i++) {
            if (o2[i].intValue() != tbuf[i]) {
              failed = true;
              System.out.println("Integer["+(i)+"] ("+o2[i].intValue()+ 
                                 ") != " + tbuf[i]);
            }
        }
        for (int i = 0; i < len; i++) {
            if (o2[off+i].intValue() != tbuf[i]) {
              failed = true;
              System.out.println("Integer["+(off+i)+"] ("+o2[off+i].intValue()+ 
                                 ") != " + tbuf[i]);
            }
        }
        int length = o2.length;
        for (int i = off+len; i < length; i++) {
            if (o2[i].intValue() != tbuf[i]) {
              failed = true;
              System.out.println("Integer["+(i)+"] ("+o2[i].intValue()+ 
                                 ") != " + tbuf[i]);
            }
        }
    }

    private static void test1(byte[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 1, o1, 0, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void test2(byte[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 2, o1, 0, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void test3(byte[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 3, o1, 0, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void test4(byte[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 4, o1, 0, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void test5(byte[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 5, o1, 0, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void test6(byte[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 6, o1, 0, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void test7(byte[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 7, o1, 0, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void test8(byte[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 8, o1, 0, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void verifyoff(byte[] o2, int off, int len) {
        for (int i = 0; i < len; i++) {
            if (o2[i] != (byte)tbuf[off+i]) {
              failed = true;
              System.out.println("byte["+(i)+"] ("+(int)o2[i]+") != " +
                                  tbuf[off+i]);
            }
        }
        int length = o2.length;
        for (int i = len; i < length; i++) {
            if (o2[i] != (byte)tbuf[i]) {
              failed = true;
              System.out.println("byte["+(i)+"] ("+(int)o2[i]+") != " +
                                  tbuf[i]);
            }
        }
    }

    private static void test2(char[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 1, o1, 0, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void test4(char[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 2, o1, 0, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void test6(char[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 3, o1, 0, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void test8(char[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 4, o1, 0, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void verifyoff(char[] o2, int off, int len) {
        for (int i = 0; i < len; i++) {
            if (o2[i] != (char)tbuf[off+i]) {
              failed = true;
              System.out.println("char["+(i)+"] ("+(int)o2[i]+") != " +
                                  tbuf[off+i]);
            }
        }
        int length = o2.length;
        for (int i = len; i < length; i++) {
            if (o2[i] != (char)tbuf[i]) {
              failed = true;
              System.out.println("char["+(i)+"] ("+(int)o2[i]+") != " +
                                  tbuf[i]);
            }
        }
    }

    private static void test4(int[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 1, o1, 0, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void test8(int[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 2, o1, 0, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void verifyoff(int[] o2, int off, int len) {
        for (int i = 0; i < len; i++) {
            if (o2[i] != tbuf[off+i]) {
              failed = true;
              System.out.println("int["+(i)+"] ("+o2[i]+") != " +
                                  tbuf[off+i]);
            }
        }
        int length = o2.length;
        for (int i = len; i < length; i++) {
            if (o2[i] != tbuf[i]) {
              failed = true;
              System.out.println("int["+(i)+"] ("+o2[i]+") != " +
                                  tbuf[i]);
            }
        }
    }

    private static void test8(long[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 1, o1, 0, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void verifyoff(long[] o2, int off, int len) {
        for (int i = 0; i < len; i++) {
            if (o2[i] != tbuf[off+i]) {
              failed = true;
              System.out.println("long["+(i)+"] ("+o2[i]+") != " +
                                  tbuf[off+i]);
            }
        }
        int length = o2.length;
        for (int i = len; i < length; i++) {
            if (o2[i] != tbuf[i]) {
              failed = true;
              System.out.println("long["+(i)+"] ("+o2[i]+") != " +
                                  tbuf[i]);
            }
        }
    }

    private static void test4(Integer[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 1, o1, 0, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void test8(Integer[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 2, o1, 0, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void verifyoff(Integer[] o2, int off, int len) {
        for (int i = 0; i < len; i++) {
            if (o2[i].intValue() != tbuf[off+i]) {
              failed = true;
              System.out.println("Integer["+(i)+"] ("+o2[i].intValue()+ 
                                 ") != " + tbuf[off+i]);
            }
        }
        int length = o2.length;
        for (int i = len; i < length; i++) {
            if (o2[i].intValue() != tbuf[i]) {
              failed = true;
              System.out.println("Integer["+(i)+"] ("+o2[i].intValue()+ 
                                 ") != " + tbuf[i]);
            }
        }
    }

    private static void testc1(byte[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 0, o1, 1, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void testc2(byte[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 0, o1, 2, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void testc3(byte[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 0, o1, 3, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void testc4(byte[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 0, o1, 4, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void testc5(byte[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 0, o1, 5, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void testc6(byte[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 0, o1, 6, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void testc7(byte[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 0, o1, 7, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void testc8(byte[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 0, o1, 8, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }

    private static void testc2(char[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 0, o1, 1, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void testc4(char[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 0, o1, 2, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void testc6(char[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 0, o1, 3, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void testc8(char[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 0, o1, 4, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }

    private static void testc4(int[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 0, o1, 1, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void testc8(int[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 0, o1, 2, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }

    private static void testc8(long[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 0, o1, 1, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }

    private static void testc4(Integer[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 0, o1, 1, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
    private static void testc8(Integer[] o1, int len, int iter,
                             String type, int nBytes, boolean output) {
        long then = System.currentTimeMillis();
        for (int i = 0; i < iter; i++)
            System.arraycopy(o1, 0, o1, 2, len);
        long now = System.currentTimeMillis();
        if (output)
            System.out.println("Time to copy " + len + " " + type +
                        " (" + nBytes + " bytes): " + (now - then));
    }
}
