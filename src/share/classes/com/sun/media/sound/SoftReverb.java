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

import java.util.Arrays;

/**
 * Reverb effect based on allpass/comb filters. First audio is send to 8
 * parelled comb filters and then mixed together and then finally send thru 3
 * different allpass filters.
 *
 * @author Karl Helgason
 */
public class SoftReverb implements SoftAudioProcessor {

    private class Delay {

        private float[] delaybuffer;
        private int rovepos = 0;

        public Delay() {
            delaybuffer = null;
        }

        public void setDelay(int delay) {
            if (delay == 0)
                delaybuffer = null;
            else
                delaybuffer = new float[delay];
            rovepos = 0;
        }

        public void processReplace(float[] in, float[] out) {
            float[] delaybuffer = this.delaybuffer;
            if (delaybuffer == null)
                return;
            int len = in.length;
            int rnlen = delaybuffer.length;
            int rovepos = this.rovepos;

            for (int i = 0; i < len; i++) {
                float x = in[i];
                out[i] = delaybuffer[rovepos];
                delaybuffer[rovepos] = x;
                rovepos = rovepos + 1;
                if (rovepos == rnlen)
                    rovepos = 0;
                //rovepos = (rovepos + 1) % rnlen;
            }
            this.rovepos = rovepos;
        }
    }

    private class AllPass {

        private float[] delaybuffer;
        private int delaybuffersize;
        private int rovepos = 0;
        private float feedback;

        public AllPass(int size) {
            delaybuffer = new float[size];
            delaybuffersize = size;
        }

        public void setFeedBack(float feedback) {
            this.feedback = feedback;
        }
        int ucount = 0;

        public void processReplace(float in[], float out[]) {
            int len = in.length;
            for (int i = 0; i < len; i++) {

                float delayout = delaybuffer[rovepos];

                // undenormalise(delayout)
                /*
                if (((delayout > 0.0) && (delayout < 1.0E-10))
                        || ((delayout < 0.0) && (delayout > -1.0E-10)))
                    delayout = 0;
                */

                float input = in[i];
                out[i] = -input + delayout;
                delaybuffer[rovepos] = input + delayout * feedback;
                if (++rovepos == delaybuffersize)
                    rovepos = 0;
            }

            ucount++;
            if (ucount == 10) {
                ucount = 0;
                for (int i = 0; i < delaybuffer.length; i++) {
                    double v = delaybuffer[i];
                    if (((v > 0.0) && (v < 1.0E-10))
                            || ((v < 0.0) && (v > -1.0E-10))) {
                        delaybuffer[i] = 0;
                    }
                }
            }

        }
    }

    private class Comb {

        private float[] delaybuffer;
        private int delaybuffersize;
        private int rovepos = 0;
        private float feedback;
        private float filtertemp = 0;
        private float filtercoeff1 = 0;
        private float filtercoeff2 = 1;

        public Comb(int size) {
            delaybuffer = new float[size];
            delaybuffersize = size;
        }

        public void setFeedBack(float feedback) {
            this.feedback = feedback;
        }
        int ucount = 0;

        public void processMix(float in[], float out[]) {
            int len = in.length;

            float filtercoeff2 = this.filtercoeff2 * feedback;

            for (int i = 0; i < len; i++) {
                float delayout = delaybuffer[rovepos];

                // One Pole Lowpass Filter
                filtertemp = (delayout * filtercoeff2)
                        + (filtertemp * filtercoeff1);

                // undenormalise(filtertemp)
                /*
                if (((filtertemp > 0.0) && (filtertemp < 1.0E-10))
                        || ((filtertemp < 0.0) && (filtertemp > -1.0E-10)))
                    filtertemp = 0;
                */
                out[i] += delayout;
                delaybuffer[rovepos] = in[i] + (filtertemp);// * feedback);

                if (++rovepos == delaybuffersize)
                    rovepos = 0;

            }
            ucount++;
            if (ucount == 10) {
                ucount = 0;
                if (((filtertemp > 0.0) && (filtertemp < 1.0E-10))
                        || ((filtertemp < 0.0) && (filtertemp > -1.0E-10))) {
                    filtertemp = 0;
                }
                for (int i = 0; i < delaybuffer.length; i++) {
                    double v = delaybuffer[i];
                    if (((v > 0.0) && (v < 1.0E-10))
                            || ((v < 0.0) && (v > -1.0E-10))) {
                        delaybuffer[i] = 0;
                    }
                }
            }


        }

        public void setDamp(float val) {
            filtercoeff1 = val;
            filtercoeff2 = 1 - filtercoeff1;
        }
    }
    private float roomsize;
    private float damp;
    private float gain = 1;
    private Delay delay;
    private Comb[] combL;
    private Comb[] combR;
    private AllPass[] allpassL;
    private AllPass[] allpassR;
    private float[] input;
    private float[] outR;
    private float[] outL;
    private boolean mix = true;
    private SoftAudioBuffer inputA;
    private SoftAudioBuffer left;
    private SoftAudioBuffer right;
    private SoftSynthesizer synth;
    private boolean dirty = true;
    private float dirty_roomsize;
    private float dirty_damp;
    private float dirty_predelay;
    private float dirty_gain;

    public void init(SoftSynthesizer synth) {
        this.synth = synth;
        double samplerate = synth.getFormat().getSampleRate();

        double freqscale = ((double) samplerate) / 44100.0;
        // freqscale = 1.0/ freqscale;

        int stereospread = 23;

        delay = new Delay();

        combL = new Comb[8];
        combR = new Comb[8];
        combL[0] = new Comb((int) (freqscale * (1116)));
        combR[0] = new Comb((int) (freqscale * (1116 + stereospread)));
        combL[1] = new Comb((int) (freqscale * (1188)));
        combR[1] = new Comb((int) (freqscale * (1188 + stereospread)));
        combL[2] = new Comb((int) (freqscale * (1277)));
        combR[2] = new Comb((int) (freqscale * (1277 + stereospread)));
        combL[3] = new Comb((int) (freqscale * (1356)));
        combR[3] = new Comb((int) (freqscale * (1356 + stereospread)));
        combL[4] = new Comb((int) (freqscale * (1422)));
        combR[4] = new Comb((int) (freqscale * (1422 + stereospread)));
        combL[5] = new Comb((int) (freqscale * (1491)));
        combR[5] = new Comb((int) (freqscale * (1491 + stereospread)));
        combL[6] = new Comb((int) (freqscale * (1557)));
        combR[6] = new Comb((int) (freqscale * (1557 + stereospread)));
        combL[7] = new Comb((int) (freqscale * (1617)));
        combR[7] = new Comb((int) (freqscale * (1617 + stereospread)));

        allpassL = new AllPass[4];
        allpassR = new AllPass[4];
        allpassL[0] = new AllPass((int) (freqscale * (556)));
        allpassR[0] = new AllPass((int) (freqscale * (556 + stereospread)));
        allpassL[1] = new AllPass((int) (freqscale * (441)));
        allpassR[1] = new AllPass((int) (freqscale * (441 + stereospread)));
        allpassL[2] = new AllPass((int) (freqscale * (341)));
        allpassR[2] = new AllPass((int) (freqscale * (341 + stereospread)));
        allpassL[3] = new AllPass((int) (freqscale * (225)));
        allpassR[3] = new AllPass((int) (freqscale * (225 + stereospread)));

        for (int i = 0; i < allpassL.length; i++) {
            allpassL[i].setFeedBack(0.5f);
            allpassR[i].setFeedBack(0.5f);
        }

        /* Init other settings */
        globalParameterControlChange(new int[]{0x01 * 128 + 0x01}, 0, 4);

    }

    public void setInput(int pin, SoftAudioBuffer input) {
        if (pin == 0)
            inputA = input;
    }

    public void setOutput(int pin, SoftAudioBuffer output) {
        if (pin == 0)
            left = output;
        if (pin == 1)
            right = output;
    }

    public void setMixMode(boolean mix) {
        this.mix = mix;
    }
    private double silentcounter = 1000;

    public void processAudio() {
        if (this.inputA.isSilent()) {
            silentcounter += 1 / synth.getControlRate();

            if (silentcounter > 60) {
                if (!mix) {
                    left.clear();
                    right.clear();
                }
                return;
            }
        } else
            silentcounter = 0;

        float[] inputA = this.inputA.array();
        float[] left = this.left.array();
        float[] right = this.right == null ? null : this.right.array();

        int numsamples = inputA.length;
        if (input == null || input.length < numsamples)
            input = new float[numsamples];

        float again = gain * 0.018f / 2;

        for (int i = 0; i < numsamples; i++)
            input[i] = inputA[i] * again;

        delay.processReplace(input, input);


        if (right != null) {
            if (outR == null || outR.length < numsamples)
                outR = new float[numsamples];
            Arrays.fill(outR, 0);
            for (int i = 0; i < combR.length; i++)
                combR[i].processMix(input, outR);
            for (int i = 0; i < allpassL.length; i++)
                allpassR[i].processReplace(outR, outR);

            if (mix) {
                for (int i = 0; i < numsamples; i++)
                    right[i] += outR[i];
            } else {
                for (int i = 0; i < numsamples; i++)
                    right[i] = outR[i];
            }
        }


        if (outL == null || outL.length < numsamples)
            outL = new float[numsamples];
        Arrays.fill(outL, 0);
        for (int i = 0; i < combL.length; i++)
            combL[i].processMix(input, outL);
        for (int i = 0; i < allpassL.length; i++)
            allpassL[i].processReplace(outL, outL);

        if (mix) {
            for (int i = 0; i < numsamples; i++)
                left[i] += outL[i];
        } else {
            for (int i = 0; i < numsamples; i++)
                left[i] = outL[i];
        }


    }

    public void globalParameterControlChange(int[] slothpath, long param,
            long value) {
        if (slothpath.length == 1) {
            if (slothpath[0] == 0x01 * 128 + 0x01) {

                if (param == 0) {
                    if (value == 0) {
                        // Small Room A small size room with a length
                        // of 5m or so.
                        dirty_roomsize = (1.1f);
                        dirty_damp = (5000);
                        dirty_predelay = (0);
                        dirty_gain = (4);
                        dirty = true;
                    }
                    if (value == 1) {
                        // Medium Room A medium size room with a length
                        // of 10m or so.
                        dirty_roomsize = (1.3f);
                        dirty_damp = (5000);
                        dirty_predelay = (0);
                        dirty_gain = (3);
                        dirty = true;
                    }
                    if (value == 2) {
                        // Large Room A large size room suitable for
                        // live performances.
                        dirty_roomsize = (1.5f);
                        dirty_damp = (5000);
                        dirty_predelay = (0);
                        dirty_gain = (2);
                        dirty = true;
                    }
                    if (value == 3) {
                        // Medium Hall A medium size concert hall.
                        dirty_roomsize = (1.8f);
                        dirty_damp = (24000);
                        dirty_predelay = (0.02f);
                        dirty_gain = (1.5f);
                        dirty = true;
                    }
                    if (value == 4) {
                        // Large Hall A large size concert hall
                        // suitable for a full orchestra.
                        dirty_roomsize = (1.8f);
                        dirty_damp = (24000);
                        dirty_predelay = (0.03f);
                        dirty_gain = (1.5f);
                        dirty = true;
                    }
                    if (value == 8) {
                        // Plate A plate reverb simulation.
                        dirty_roomsize = (1.3f);
                        dirty_damp = (2500);
                        dirty_predelay = (0);
                        dirty_gain = (6);
                        dirty = true;
                    }
                } else if (param == 1) {
                    dirty_roomsize = ((float) (Math.exp((value - 40) * 0.025)));
                    dirty = true;
                }

            }
        }
    }

    public void processControlLogic() {
        if (dirty) {
            dirty = false;
            setRoomSize(dirty_roomsize);
            setDamp(dirty_damp);
            setPreDelay(dirty_predelay);
            setGain(dirty_gain);
        }
    }

    public void setRoomSize(float value) {
        roomsize = 1 - (0.17f / value);

        for (int i = 0; i < 8; i++) {
            combL[i].feedback = roomsize;
            combR[i].feedback = roomsize;
        }
    }

    public void setPreDelay(float value) {
        delay.setDelay((int)(value * synth.getFormat().getSampleRate()));
    }

    public void setGain(float gain) {
        this.gain = gain;
    }

    public void setDamp(float value) {
        double x = (value / synth.getFormat().getSampleRate()) * (2 * Math.PI);
        double cx = 2 - Math.cos(x);
        damp = (float)(cx - Math.sqrt(cx * cx - 1));
        if (damp > 1)
            damp = 1;
        if (damp < 0)
            damp = 0;

        // damp = value * 0.4f;
        for (int i = 0; i < 8; i++) {
            combL[i].setDamp(damp);
            combR[i].setDamp(damp);
        }

    }
}
