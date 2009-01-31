/*
 * Copyright 2007 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Sun designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Sun in the LICENSE file that accompanied this code.
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
 */
package com.sun.media.sound;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioFormat.Encoding;

/**
 * This class is used to convert between 8,16,24,32 bit signed/unsigned
 * big/litle endian fixed/floating point byte buffers and float buffers.
 *
 * @author Karl Helgason
 */
public abstract class AudioFloatConverter {

    public static final Encoding PCM_FLOAT = new Encoding("PCM_FLOAT");

    /***************************************************************************
     *
     * 32 bit float, little/big-endian
     *
     **************************************************************************/

    // PCM 32 bit float, little-endian
    private static class AudioFloatConversion32L extends AudioFloatConverter {

        ByteBuffer bytebuffer = null;
        FloatBuffer floatbuffer = null;

        public float[] toFloatArray(byte[] in_buff, int in_offset,
                float[] out_buff, int out_offset, int out_len) {
            int in_len = out_len * 4;
            if (bytebuffer == null || bytebuffer.capacity() < in_len) {
                bytebuffer = ByteBuffer.allocate(in_len).order(
                        ByteOrder.LITTLE_ENDIAN);
                floatbuffer = bytebuffer.asFloatBuffer();
            }
            bytebuffer.position(0);
            floatbuffer.position(0);
            bytebuffer.put(in_buff, in_offset, in_len);
            floatbuffer.get(out_buff, out_offset, out_len);
            return out_buff;
        }

        public byte[] toByteArray(float[] in_buff, int in_offset, int in_len,
                byte[] out_buff, int out_offset) {
            int out_len = in_len * 4;
            if (bytebuffer == null || bytebuffer.capacity() < out_len) {
                bytebuffer = ByteBuffer.allocate(out_len).order(
                        ByteOrder.LITTLE_ENDIAN);
                floatbuffer = bytebuffer.asFloatBuffer();
            }
            floatbuffer.position(0);
            bytebuffer.position(0);
            floatbuffer.put(in_buff, in_offset, in_len);
            bytebuffer.get(out_buff, out_offset, out_len);
            return out_buff;
        }
    }

    // PCM 32 bit float, big-endian
    private static class AudioFloatConversion32B extends AudioFloatConverter {

        ByteBuffer bytebuffer = null;
        FloatBuffer floatbuffer = null;

        public float[] toFloatArray(byte[] in_buff, int in_offset,
                float[] out_buff, int out_offset, int out_len) {
            int in_len = out_len * 4;
            if (bytebuffer == null || bytebuffer.capacity() < in_len) {
                bytebuffer = ByteBuffer.allocate(in_len).order(
                        ByteOrder.BIG_ENDIAN);
                floatbuffer = bytebuffer.asFloatBuffer();
            }
            bytebuffer.position(0);
            floatbuffer.position(0);
            bytebuffer.put(in_buff, in_offset, in_len);
            floatbuffer.get(out_buff, out_offset, out_len);
            return out_buff;
        }

        public byte[] toByteArray(float[] in_buff, int in_offset, int in_len,
                byte[] out_buff, int out_offset) {
            int out_len = in_len * 4;
            if (bytebuffer == null || bytebuffer.capacity() < out_len) {
                bytebuffer = ByteBuffer.allocate(out_len).order(
                        ByteOrder.BIG_ENDIAN);
                floatbuffer = bytebuffer.asFloatBuffer();
            }
            floatbuffer.position(0);
            bytebuffer.position(0);
            floatbuffer.put(in_buff, in_offset, in_len);
            bytebuffer.get(out_buff, out_offset, out_len);
            return out_buff;
        }
    }

    /***************************************************************************
     *
     * 8 bit signed/unsigned
     *
     **************************************************************************/

    // PCM 8 bit, signed
    private static class AudioFloatConversion8S extends AudioFloatConverter {

        public float[] toFloatArray(byte[] in_buff, int in_offset,
                float[] out_buff, int out_offset, int out_len) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < out_len; i++)
                out_buff[ox++] = in_buff[ix++] * (1.0f / 127.0f);
            return out_buff;
        }

        public byte[] toByteArray(float[] in_buff, int in_offset, int in_len,
                byte[] out_buff, int out_offset) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < in_len; i++)
                out_buff[ox++] = (byte)(in_buff[ix++] * 127.0f);
            return out_buff;
        }
    }

    // PCM 8 bit, unsigned
    private static class AudioFloatConversion8U extends AudioFloatConverter {

        public float[] toFloatArray(byte[] in_buff, int in_offset,
                float[] out_buff, int out_offset, int out_len) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < out_len; i++)
                out_buff[ox++] = ((in_buff[ix++] & 0xFF) - 127) * (1.0f/127.0f);
            return out_buff;
        }

        public byte[] toByteArray(float[] in_buff, int in_offset, int in_len,
                byte[] out_buff, int out_offset) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < in_len; i++)
                out_buff[ox++] = (byte)(127 + in_buff[ix++] * 127.0f);
            return out_buff;
        }
    }

    /***************************************************************************
     *
     * 16 bit signed/unsigned, little/big-endian
     *
     **************************************************************************/

    // PCM 16 bit, signed, little-endian
    private static class AudioFloatConversion16SL extends AudioFloatConverter {

        public float[] toFloatArray(byte[] in_buff, int in_offset,
                float[] out_buff, int out_offset, int out_len) {
            int ix = in_offset;
            int len = out_offset + out_len;
            for (int ox = out_offset; ox < len; ox++) {
                out_buff[ox] = ((short)((in_buff[ix++] & 0xFF) |
                        (in_buff[ix++] << 8))) * (1.0f / 32767.0f);
            }

            return out_buff;
        }

        public byte[] toByteArray(float[] in_buff, int in_offset, int in_len,
                byte[] out_buff, int out_offset) {
            int ox = out_offset;
            int len = in_offset + in_len;
            for (int ix = in_offset; ix < len; ix++) {
                int x = (int)(in_buff[ix] * 32767.0);
                out_buff[ox++] = (byte)x;
                out_buff[ox++] = (byte)(x >>> 8);
            }
            return out_buff;
        }
    }

    // PCM 16 bit, signed, big-endian
    private static class AudioFloatConversion16SB extends AudioFloatConverter {

        public float[] toFloatArray(byte[] in_buff, int in_offset,
                float[] out_buff, int out_offset, int out_len) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < out_len; i++) {
                out_buff[ox++] = ((short)((in_buff[ix++] << 8) |
                        (in_buff[ix++] & 0xFF))) * (1.0f / 32767.0f);
            }
            return out_buff;
        }

        public byte[] toByteArray(float[] in_buff, int in_offset, int in_len,
                byte[] out_buff, int out_offset) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < in_len; i++) {
                int x = (int)(in_buff[ix++] * 32767.0);
                out_buff[ox++] = (byte)(x >>> 8);
                out_buff[ox++] = (byte)x;
            }
            return out_buff;
        }
    }

    // PCM 16 bit, unsigned, little-endian
    private static class AudioFloatConversion16UL extends AudioFloatConverter {

        public float[] toFloatArray(byte[] in_buff, int in_offset,
                float[] out_buff, int out_offset, int out_len) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < out_len; i++) {
                int x = (in_buff[ix++] & 0xFF) | ((in_buff[ix++] & 0xFF) << 8);
                out_buff[ox++] = (x - 32767) * (1.0f / 32767.0f);
            }
            return out_buff;
        }

        public byte[] toByteArray(float[] in_buff, int in_offset, int in_len,
                byte[] out_buff, int out_offset) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < in_len; i++) {
                int x = 32767 + (int)(in_buff[ix++] * 32767.0);
                out_buff[ox++] = (byte)x;
                out_buff[ox++] = (byte)(x >>> 8);
            }
            return out_buff;
        }
    }

    // PCM 16 bit, unsigned, big-endian
    private static class AudioFloatConversion16UB extends AudioFloatConverter {

        public float[] toFloatArray(byte[] in_buff, int in_offset,
                float[] out_buff, int out_offset, int out_len) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < out_len; i++) {
                int x = ((in_buff[ix++] & 0xFF) << 8) | (in_buff[ix++] & 0xFF);
                out_buff[ox++] = (x - 32767) * (1.0f / 32767.0f);
            }
            return out_buff;
        }

        public byte[] toByteArray(float[] in_buff, int in_offset, int in_len,
                byte[] out_buff, int out_offset) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < in_len; i++) {
                int x = 32767 + (int)(in_buff[ix++] * 32767.0);
                out_buff[ox++] = (byte)(x >>> 8);
                out_buff[ox++] = (byte)x;
            }
            return out_buff;
        }
    }

    /***************************************************************************
     *
     * 24 bit signed/unsigned, little/big-endian
     *
     **************************************************************************/

    // PCM 24 bit, signed, little-endian
    private static class AudioFloatConversion24SL extends AudioFloatConverter {

        public float[] toFloatArray(byte[] in_buff, int in_offset,
                float[] out_buff, int out_offset, int out_len) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < out_len; i++) {
                int x = (in_buff[ix++] & 0xFF) | ((in_buff[ix++] & 0xFF) << 8)
                        | ((in_buff[ix++] & 0xFF) << 16);
                if (x > 0x7FFFFF)
                    x -= 0x1000000;
                out_buff[ox++] = x * (1.0f / (float)0x7FFFFF);
            }
            return out_buff;
        }

        public byte[] toByteArray(float[] in_buff, int in_offset, int in_len,
                byte[] out_buff, int out_offset) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < in_len; i++) {
                int x = (int)(in_buff[ix++] * (float)0x7FFFFF);
                if (x < 0)
                    x += 0x1000000;
                out_buff[ox++] = (byte)x;
                out_buff[ox++] = (byte)(x >>> 8);
                out_buff[ox++] = (byte)(x >>> 16);
            }
            return out_buff;
        }
    }

    // PCM 24 bit, signed, big-endian
    private static class AudioFloatConversion24SB extends AudioFloatConverter {

        public float[] toFloatArray(byte[] in_buff, int in_offset,
                float[] out_buff, int out_offset, int out_len) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < out_len; i++) {
                int x = ((in_buff[ix++] & 0xFF) << 16)
                        | ((in_buff[ix++] & 0xFF) << 8) | (in_buff[ix++] & 0xFF);
                if (x > 0x7FFFFF)
                    x -= 0x1000000;
                out_buff[ox++] = x * (1.0f / (float)0x7FFFFF);
            }
            return out_buff;
        }

        public byte[] toByteArray(float[] in_buff, int in_offset, int in_len,
                byte[] out_buff, int out_offset) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < in_len; i++) {
                int x = (int)(in_buff[ix++] * (float)0x7FFFFF);
                if (x < 0)
                    x += 0x1000000;
                out_buff[ox++] = (byte)(x >>> 16);
                out_buff[ox++] = (byte)(x >>> 8);
                out_buff[ox++] = (byte)x;
            }
            return out_buff;
        }
    }

    // PCM 24 bit, unsigned, little-endian
    private static class AudioFloatConversion24UL extends AudioFloatConverter {

        public float[] toFloatArray(byte[] in_buff, int in_offset,
                float[] out_buff, int out_offset, int out_len) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < out_len; i++) {
                int x = (in_buff[ix++] & 0xFF) | ((in_buff[ix++] & 0xFF) << 8)
                        | ((in_buff[ix++] & 0xFF) << 16);
                x -= 0x7FFFFF;
                out_buff[ox++] = x * (1.0f / (float)0x7FFFFF);
            }
            return out_buff;
        }

        public byte[] toByteArray(float[] in_buff, int in_offset, int in_len,
                byte[] out_buff, int out_offset) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < in_len; i++) {
                int x = (int)(in_buff[ix++] * (float)0x7FFFFF);
                x += 0x7FFFFF;
                out_buff[ox++] = (byte)x;
                out_buff[ox++] = (byte)(x >>> 8);
                out_buff[ox++] = (byte)(x >>> 16);
            }
            return out_buff;
        }
    }

    // PCM 24 bit, unsigned, big-endian
    private static class AudioFloatConversion24UB extends AudioFloatConverter {

        public float[] toFloatArray(byte[] in_buff, int in_offset,
                float[] out_buff, int out_offset, int out_len) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < out_len; i++) {
                int x = ((in_buff[ix++] & 0xFF) << 16)
                        | ((in_buff[ix++] & 0xFF) << 8) | (in_buff[ix++] & 0xFF);
                x -= 0x7FFFFF;
                out_buff[ox++] = x * (1.0f / (float)0x7FFFFF);
            }
            return out_buff;
        }

        public byte[] toByteArray(float[] in_buff, int in_offset, int in_len,
                byte[] out_buff, int out_offset) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < in_len; i++) {
                int x = (int)(in_buff[ix++] * (float)0x7FFFFF);
                x += 0x7FFFFF;
                out_buff[ox++] = (byte)(x >>> 16);
                out_buff[ox++] = (byte)(x >>> 8);
                out_buff[ox++] = (byte)x;
            }
            return out_buff;
        }
    }

    /***************************************************************************
     *
     * 32 bit signed/unsigned, little/big-endian
     *
     **************************************************************************/

    // PCM 32 bit, signed, little-endian
    private static class AudioFloatConversion32SL extends AudioFloatConverter {

        public float[] toFloatArray(byte[] in_buff, int in_offset,
                float[] out_buff, int out_offset, int out_len) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < out_len; i++) {
                int x = (in_buff[ix++] & 0xFF) | ((in_buff[ix++] & 0xFF) << 8) |
                        ((in_buff[ix++] & 0xFF) << 16) |
                        ((in_buff[ix++] & 0xFF) << 24);
                out_buff[ox++] = x * (1.0f / (float)0x7FFFFFFF);
            }
            return out_buff;
        }

        public byte[] toByteArray(float[] in_buff, int in_offset, int in_len,
                byte[] out_buff, int out_offset) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < in_len; i++) {
                int x = (int)(in_buff[ix++] * (float)0x7FFFFFFF);
                out_buff[ox++] = (byte)x;
                out_buff[ox++] = (byte)(x >>> 8);
                out_buff[ox++] = (byte)(x >>> 16);
                out_buff[ox++] = (byte)(x >>> 24);
            }
            return out_buff;
        }
    }

    // PCM 32 bit, signed, big-endian
    private static class AudioFloatConversion32SB extends AudioFloatConverter {

        public float[] toFloatArray(byte[] in_buff, int in_offset,
                float[] out_buff, int out_offset, int out_len) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < out_len; i++) {
                int x = ((in_buff[ix++] & 0xFF) << 24) |
                        ((in_buff[ix++] & 0xFF) << 16) |
                        ((in_buff[ix++] & 0xFF) << 8) | (in_buff[ix++] & 0xFF);
                out_buff[ox++] = x * (1.0f / (float)0x7FFFFFFF);
            }
            return out_buff;
        }

        public byte[] toByteArray(float[] in_buff, int in_offset, int in_len,
                byte[] out_buff, int out_offset) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < in_len; i++) {
                int x = (int)(in_buff[ix++] * (float)0x7FFFFFFF);
                out_buff[ox++] = (byte)(x >>> 24);
                out_buff[ox++] = (byte)(x >>> 16);
                out_buff[ox++] = (byte)(x >>> 8);
                out_buff[ox++] = (byte)x;
            }
            return out_buff;
        }
    }

    // PCM 32 bit, unsigned, little-endian
    private static class AudioFloatConversion32UL extends AudioFloatConverter {

        public float[] toFloatArray(byte[] in_buff, int in_offset,
                float[] out_buff, int out_offset, int out_len) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < out_len; i++) {
                int x = (in_buff[ix++] & 0xFF) | ((in_buff[ix++] & 0xFF) << 8) |
                        ((in_buff[ix++] & 0xFF) << 16) |
                        ((in_buff[ix++] & 0xFF) << 24);
                x -= 0x7FFFFFFF;
                out_buff[ox++] = x * (1.0f / (float)0x7FFFFFFF);
            }
            return out_buff;
        }

        public byte[] toByteArray(float[] in_buff, int in_offset, int in_len,
                byte[] out_buff, int out_offset) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < in_len; i++) {
                int x = (int)(in_buff[ix++] * (float)0x7FFFFFFF);
                x += 0x7FFFFFFF;
                out_buff[ox++] = (byte)x;
                out_buff[ox++] = (byte)(x >>> 8);
                out_buff[ox++] = (byte)(x >>> 16);
                out_buff[ox++] = (byte)(x >>> 24);
            }
            return out_buff;
        }
    }

    // PCM 32 bit, unsigned, big-endian
    private static class AudioFloatConversion32UB extends AudioFloatConverter {

        public float[] toFloatArray(byte[] in_buff, int in_offset,
                float[] out_buff, int out_offset, int out_len) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < out_len; i++) {
                int x = ((in_buff[ix++] & 0xFF) << 24) |
                        ((in_buff[ix++] & 0xFF) << 16) |
                        ((in_buff[ix++] & 0xFF) << 8) | (in_buff[ix++] & 0xFF);
                x -= 0x7FFFFFFF;
                out_buff[ox++] = x * (1.0f / (float)0x7FFFFFFF);
            }
            return out_buff;
        }

        public byte[] toByteArray(float[] in_buff, int in_offset, int in_len,
                byte[] out_buff, int out_offset) {
            int ix = in_offset;
            int ox = out_offset;
            for (int i = 0; i < in_len; i++) {
                int x = (int)(in_buff[ix++] * (float)0x7FFFFFFF);
                x += 0x7FFFFFFF;
                out_buff[ox++] = (byte)(x >>> 24);
                out_buff[ox++] = (byte)(x >>> 16);
                out_buff[ox++] = (byte)(x >>> 8);
                out_buff[ox++] = (byte)x;
            }
            return out_buff;
        }
    }

    public static AudioFloatConverter getConverter(AudioFormat format) {
        AudioFloatConverter conv = null;
        if (format.getFrameSize() !=
                (format.getSampleSizeInBits() / 8) * format.getChannels()) {
            return null;
        }
        if (format.getEncoding().equals(Encoding.PCM_SIGNED)) {
            if (format.isBigEndian()) {
                if (format.getSampleSizeInBits() == 8) {
                    conv = new AudioFloatConversion8S();
                } else if (format.getSampleSizeInBits() > 8 &&
                        format.getSampleSizeInBits() <= 16) {
                    conv = new AudioFloatConversion16SB();
                } else if (format.getSampleSizeInBits() > 16 &&
                        format.getSampleSizeInBits() <= 24) {
                    conv = new AudioFloatConversion24SB();
                } else if (format.getSampleSizeInBits() > 24 &&
                        format.getSampleSizeInBits() <= 32) {
                    conv = new AudioFloatConversion32SB();
                }
            } else {
                if (format.getSampleSizeInBits() == 8) {
                    conv = new AudioFloatConversion8S();
                } else if (format.getSampleSizeInBits() > 8 &&
                        format.getSampleSizeInBits() <= 16) {
                    conv = new AudioFloatConversion16SL();
                } else if (format.getSampleSizeInBits() > 16 &&
                        format.getSampleSizeInBits() <= 24) {
                    conv = new AudioFloatConversion24SL();
                } else if (format.getSampleSizeInBits() > 24 &&
                        format.getSampleSizeInBits() <= 32) {
                    conv = new AudioFloatConversion32SL();
                }
            }
        } else if (format.getEncoding().equals(Encoding.PCM_UNSIGNED)) {
            if (format.isBigEndian()) {
                if (format.getSampleSizeInBits() == 8) {
                    conv = new AudioFloatConversion8U();
                } else if (format.getSampleSizeInBits() > 8 &&
                        format.getSampleSizeInBits() <= 16) {
                    conv = new AudioFloatConversion16UB();
                } else if (format.getSampleSizeInBits() > 16 &&
                        format.getSampleSizeInBits() <= 24) {
                    conv = new AudioFloatConversion24UB();
                } else if (format.getSampleSizeInBits() > 24 &&
                        format.getSampleSizeInBits() <= 32) {
                    conv = new AudioFloatConversion32UB();
                }
            } else {
                if (format.getSampleSizeInBits() == 8) {
                    conv = new AudioFloatConversion8U();
                } else if (format.getSampleSizeInBits() > 8 &&
                        format.getSampleSizeInBits() <= 16) {
                    conv = new AudioFloatConversion16UL();
                } else if (format.getSampleSizeInBits() > 16 &&
                        format.getSampleSizeInBits() <= 24) {
                    conv = new AudioFloatConversion24UL();
                } else if (format.getSampleSizeInBits() > 24 &&
                        format.getSampleSizeInBits() <= 32) {
                    conv = new AudioFloatConversion32UL();
                }
            }
        } else if (format.getEncoding().equals(PCM_FLOAT)) {
            if (format.getSampleSizeInBits() == 32) {
                if (format.isBigEndian())
                    conv = new AudioFloatConversion32B();
                else
                    conv = new AudioFloatConversion32L();
            }
        }

        if (conv != null)
            conv.format = format;
        return conv;
    }
    private AudioFormat format;

    public AudioFormat getFormat() {
        return format;
    }

    public abstract float[] toFloatArray(byte[] in_buff, int in_offset,
            float[] out_buff, int out_offset, int out_len);

    public float[] toFloatArray(byte[] in_buff, float[] out_buff,
            int out_offset, int out_len) {
        return toFloatArray(in_buff, 0, out_buff, out_offset, out_len);
    }

    public float[] toFloatArray(byte[] in_buff, int in_offset,
            float[] out_buff, int out_len) {
        return toFloatArray(in_buff, in_offset, out_buff, 0, out_len);
    }

    public float[] toFloatArray(byte[] in_buff, float[] out_buff, int out_len) {
        return toFloatArray(in_buff, 0, out_buff, 0, out_len);
    }

    public float[] toFloatArray(byte[] in_buff, float[] out_buff) {
        return toFloatArray(in_buff, 0, out_buff, 0, out_buff.length);
    }

    public abstract byte[] toByteArray(float[] in_buff, int in_offset,
            int in_len, byte[] out_buff, int out_offset);

    public byte[] toByteArray(float[] in_buff, int in_len, byte[] out_buff,
            int out_offset) {
        return toByteArray(in_buff, 0, in_len, out_buff, out_offset);
    }

    public byte[] toByteArray(float[] in_buff, int in_offset, int in_len,
            byte[] out_buff) {
        return toByteArray(in_buff, in_offset, in_len, out_buff, 0);
    }

    public byte[] toByteArray(float[] in_buff, int in_len, byte[] out_buff) {
        return toByteArray(in_buff, 0, in_len, out_buff, 0);
    }

    public byte[] toByteArray(float[] in_buff, byte[] out_buff) {
        return toByteArray(in_buff, 0, in_buff.length, out_buff, 0);
    }
}
