/*
 * Copyright (c) 2002, 2011, Oracle and/or its affiliates. All rights reserved.
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

//#define USE_ERROR
//#define USE_TRACE
//#define USE_VERBOSE_TRACE

#include <AudioUnit/AudioUnit.h>
#include <CoreServices/CoreServices.h>
#include <pthread.h>
/*
#if !defined(__COREAUDIO_USE_FLAT_INCLUDES__)
#include <CoreAudio/CoreAudioTypes.h>
#else
#include <CoreAudioTypes.h>
#endif
*/

#include "PLATFORM_API_MacOSX_Utils.h"

extern "C" {
#include "Utilities.h"
#include "DirectAudio.h"
}

#if USE_DAUDIO == TRUE


#ifdef USE_TRACE
static void PrintStreamDesc(AudioStreamBasicDescription *inDesc) {
    TRACE4("ID='%c%c%c%c'", (char)(inDesc->mFormatID >> 24), (char)(inDesc->mFormatID >> 16), (char)(inDesc->mFormatID >> 8), (char)(inDesc->mFormatID));
    TRACE2(", %f Hz, flags=0x%lX", (float)inDesc->mSampleRate, (long unsigned)inDesc->mFormatFlags);
    TRACE2(", %ld channels, %ld bits", (long)inDesc->mChannelsPerFrame, (long)inDesc->mBitsPerChannel);
    TRACE1(", %ld bytes per frame\n", (long)inDesc->mBytesPerFrame);
}
#else
static inline void PrintStreamDesc(AudioStreamBasicDescription *inDesc) { }
#endif


#define MAX(x, y)   ((x) >= (y) ? (x) : (y))
#define MIN(x, y)   ((x) <= (y) ? (x) : (y))


// =======================================
// MixerProvider functions implementation

static DeviceList deviceCache;

INT32 DAUDIO_GetDirectAudioDeviceCount() {
    deviceCache.Refresh();
    int count = deviceCache.GetCount();
    if (count > 0) {
        // add "default" device
        count++;
        TRACE1("DAUDIO_GetDirectAudioDeviceCount: returns %d devices\n", count);
    } else {
        TRACE0("DAUDIO_GetDirectAudioDeviceCount: no devices found\n");
    }
    return count;
}

INT32 DAUDIO_GetDirectAudioDeviceDescription(INT32 mixerIndex, DirectAudioDeviceDescription *desc) {
    bool result = true;
    desc->deviceID = 0;
    if (mixerIndex == 0) {
        // default device
        strncpy(desc->name, "Default Audio Device", DAUDIO_STRING_LENGTH);
        strncpy(desc->description, "Default Audio Device", DAUDIO_STRING_LENGTH);
        desc->maxSimulLines = -1;
    } else {
        AudioDeviceID deviceID;
        result = deviceCache.GetDeviceInfo(mixerIndex-1, &deviceID, DAUDIO_STRING_LENGTH,
            desc->name, desc->vendor, desc->description, desc->version);
        if (result) {
            desc->deviceID = (INT32)deviceID;
            desc->maxSimulLines = -1;
        }
    }
    return result ? TRUE : FALSE;
}


void DAUDIO_GetFormats(INT32 mixerIndex, INT32 deviceID, int isSource, void* creator) {
    TRACE3(">>DAUDIO_GetFormats mixerIndex=%d deviceID=0x%x isSource=%d\n", (int)mixerIndex, (int)deviceID, isSource);

    AudioDeviceID audioDeviceID = deviceID == 0 ? GetDefaultDevice(isSource) : (AudioDeviceID)deviceID;

    if (audioDeviceID == 0) {
        return;
    }

    int totalChannels = GetChannelCount(audioDeviceID, isSource);

    if (totalChannels == 0) {
        TRACE0("<<DAUDIO_GetFormats, no streams!\n");
        return;
    }

    if (isSource && totalChannels < 2) {
        // report 2 channels even if only mono is supported
        totalChannels = 2;
    }

    int channels[] = {1, 2, totalChannels};
    int channelsCount = MIN(totalChannels, 3);

    float hardwareSampleRate = GetSampleRate(audioDeviceID, isSource);
    TRACE2("  DAUDIO_GetFormats: got %d channels, sampleRate == %f\n", totalChannels, hardwareSampleRate);

    // target lines support only current sample rate!
    float sampleRate = isSource ? -1 : hardwareSampleRate;

    static int sampleBits[] = {8, 16, 24};
    static int sampleBitsCount = sizeof(sampleBits)/sizeof(sampleBits[0]);

    // the last audio format is the default one (used by DataLine.open() if format is not specified)
    // consider as default 16bit PCM stereo (mono is stereo is not supported) with the current sample rate
    int defBits = 16;
    int defChannels = MIN(2, channelsCount);
    float defSampleRate = hardwareSampleRate;
    // don't add default format is sample rate is not specified
    bool addDefault = defSampleRate > 0;

    // TODO: CoreAudio can handle signed/unsigned, little-endian/big-endian
    // TODO: register the formats (to prevent DirectAudio software conversion) - need to fix DirectAudioDevice.createDataLineInfo
    // to avoid software conversions if both signed/unsigned or big-/little-endian are supported
    for (int channelIndex = 0; channelIndex < channelsCount; channelIndex++) {
        for (int bitIndex = 0; bitIndex < sampleBitsCount; bitIndex++) {
            int bits = sampleBits[bitIndex];
            if (addDefault && bits == defBits && channels[channelIndex] != defChannels && sampleRate == defSampleRate) {
                // the format is the default one, don't add it now
                continue;
            }
            DAUDIO_AddAudioFormat(creator,
                bits,                       // sample size in bits
                -1,                         // frame size (auto)
                channels[channelIndex],     // channels
                sampleRate,                 // sample rate
                DAUDIO_PCM,                 // only accept PCM
                bits == 8 ? FALSE : TRUE,   // signed
                bits == 8 ? FALSE           // little-endian for 8bit
                    : UTIL_IsBigEndianPlatform());
        }
    }
    // add default format
    if (addDefault) {
        DAUDIO_AddAudioFormat(creator,
            defBits,                        // 16 bits
            -1,                             // automatically calculate frame size
            defChannels,                    // channels
            defSampleRate,                  // sample rate
            DAUDIO_PCM,                     // PCM
            TRUE,                           // signed
            UTIL_IsBigEndianPlatform());    // native endianess
    }

    TRACE0("<<DAUDIO_GetFormats\n");
}


// =======================================
// Source/Target DataLine functions implementation

// ====
/* 1writer-1reader ring buffer class with flush() support */
class RingBuffer {
public:
    RingBuffer() : pBuffer(NULL), nBufferSize(0) {
        pthread_mutex_init(&lockMutex, NULL);
    }
    ~RingBuffer() {
        Deallocate();
        pthread_mutex_destroy(&lockMutex);
    }

    // extraBytes: number of additionally allocated bytes to prevent data
    // overlapping when almost whole buffer is filled
    // (required only if Write() can override the buffer)
    bool Allocate(int requestedBufferSize, int extraBytes) {
        int fullBufferSize = requestedBufferSize + extraBytes;
        int powerOfTwo = 1;
        while (powerOfTwo < fullBufferSize) {
            powerOfTwo <<= 1;
        }
        pBuffer = (Byte*)malloc(powerOfTwo);
        if (pBuffer == NULL) {
            ERROR0("RingBuffer::Allocate: OUT OF MEMORY\n");
            return false;
        }

        nBufferSize = requestedBufferSize;
        nAllocatedBytes = powerOfTwo;
        nPosMask = powerOfTwo - 1;
        nWritePos = 0;
        nReadPos = 0;
        nFlushPos = -1;

        TRACE2("RingBuffer::Allocate: OK, bufferSize=%d, allocated:%d\n", nBufferSize, nAllocatedBytes);
        return true;
    }

    void Deallocate() {
        if (pBuffer) {
            free(pBuffer);
            pBuffer = NULL;
            nBufferSize = 0;
        }
    }

    inline int GetBufferSize() {
        return nBufferSize;
    }

    inline int GetAllocatedSize() {
        return nAllocatedBytes;
    }

    // gets number of bytes available for reading
    int GetValidByteCount() {
        lock();
        INT64 result = nWritePos - (nFlushPos >= 0 ? nFlushPos : nReadPos);
        unlock();
        return result > (INT64)nBufferSize ? nBufferSize : (int)result;
    }

    int Write(void *srcBuffer, int len, bool preventOverflow) {
        lock();
        TRACE2("RingBuffer::Write (%d bytes, preventOverflow=%d)\n", len, preventOverflow ? 1 : 0);
        TRACE2("  writePos = %lld (%d)", (long long)nWritePos, Pos2Offset(nWritePos));
        TRACE2("  readPos=%lld (%d)", (long long)nReadPos, Pos2Offset(nReadPos));
        TRACE2("  flushPos=%lld (%d)\n", (long long)nFlushPos, Pos2Offset(nFlushPos));

        INT64 writePos = nWritePos;
        if (preventOverflow) {
            INT64 avail_read = writePos - (nFlushPos >= 0 ? nFlushPos : nReadPos);
            if (avail_read >= (INT64)nBufferSize) {
                // no space
                TRACE0("  preventOverlow: OVERFLOW => len = 0;\n");
                len = 0;
            } else {
                int avail_write = nBufferSize - (int)avail_read;
                if (len > avail_write) {
                    TRACE2("  preventOverlow: desrease len: %d => %d\n", len, avail_write);
                    len = avail_write;
                }
            }
        }
        unlock();

        if (len > 0) {

            write((Byte *)srcBuffer, Pos2Offset(writePos), len);

            lock();
            TRACE4("--RingBuffer::Write writePos: %lld (%d) => %lld, (%d)\n",
                (long long)nWritePos, Pos2Offset(nWritePos), (long long)nWritePos + len, Pos2Offset(nWritePos + len));
            nWritePos += len;
            unlock();
        }
        return len;
    }

    int Read(void *dstBuffer, int len) {
        lock();
        TRACE1("RingBuffer::Read (%d bytes)\n", len);
        TRACE2("  writePos = %lld (%d)", (long long)nWritePos, Pos2Offset(nWritePos));
        TRACE2("  readPos=%lld (%d)", (long long)nReadPos, Pos2Offset(nReadPos));
        TRACE2("  flushPos=%lld (%d)\n", (long long)nFlushPos, Pos2Offset(nFlushPos));

        applyFlush();
        INT64 avail_read = nWritePos - nReadPos;
        // check for overflow
        if (avail_read > (INT64)nBufferSize) {
            nReadPos = nWritePos - nBufferSize;
            avail_read = nBufferSize;
            TRACE0("  OVERFLOW\n");
        }
        INT64 readPos = nReadPos;
        unlock();

        if (len > (int)avail_read) {
            TRACE2("  RingBuffer::Read - don't have enough data, len: %d => %d\n", len, (int)avail_read);
            len = (int)avail_read;
        }

        if (len > 0) {

            read((Byte *)dstBuffer, Pos2Offset(readPos), len);

            lock();
            if (applyFlush()) {
                // just got flush(), results became obsolete
                TRACE0("--RingBuffer::Read, got Flush, return 0\n");
                len = 0;
            } else {
                TRACE4("--RingBuffer::Read readPos: %lld (%d) => %lld (%d)\n",
                    (long long)nReadPos, Pos2Offset(nReadPos), (long long)nReadPos + len, Pos2Offset(nReadPos + len));
                nReadPos += len;
            }
            unlock();
        } else {
            // underrun!
        }
        return len;
    }

    // returns number of the flushed bytes
    int Flush() {
        lock();
        INT64 flushedBytes = nWritePos - (nFlushPos >= 0 ? nFlushPos : nReadPos);
        nFlushPos = nWritePos;
        unlock();
        return flushedBytes > (INT64)nBufferSize ? nBufferSize : (int)flushedBytes;
    }

private:
    Byte *pBuffer;
    int nBufferSize;
    int nAllocatedBytes;
    INT64 nPosMask;

    pthread_mutex_t lockMutex;

    volatile INT64 nWritePos;
    volatile INT64 nReadPos;
    // Flush() sets nFlushPos value to nWritePos;
    // next Read() sets nReadPos to nFlushPos and resests nFlushPos to -1
    volatile INT64 nFlushPos;

    inline void lock() {
        pthread_mutex_lock(&lockMutex);
    }
    inline void unlock() {
        pthread_mutex_unlock(&lockMutex);
    }

    inline bool applyFlush() {
        if (nFlushPos >= 0) {
            nReadPos = nFlushPos;
            nFlushPos = -1;
            return true;
        }
        return false;
    }

    inline int Pos2Offset(INT64 pos) {
        return (int)(pos & nPosMask);
    }

    void write(Byte *srcBuffer, int dstOffset, int len) {
        int dstEndOffset = dstOffset + len;

        int lenAfterWrap = dstEndOffset - nAllocatedBytes;
        if (lenAfterWrap > 0) {
            // dest.buffer does wrap
            len = nAllocatedBytes - dstOffset;
            memcpy(pBuffer+dstOffset, srcBuffer, len);
            memcpy(pBuffer, srcBuffer+len, lenAfterWrap);
        } else {
            // dest.buffer does not wrap
            memcpy(pBuffer+dstOffset, srcBuffer, len);
        }
    }

    void read(Byte *dstBuffer, int srcOffset, int len) {
        int srcEndOffset = srcOffset + len;

        int lenAfterWrap = srcEndOffset - nAllocatedBytes;
        if (lenAfterWrap > 0) {
            // need to unwrap data
            len = nAllocatedBytes - srcOffset;
            memcpy(dstBuffer, pBuffer+srcOffset, len);
            memcpy(dstBuffer+len, pBuffer, lenAfterWrap);
        } else {
            // source buffer is not wrapped
            memcpy(dstBuffer, pBuffer+srcOffset, len);
        }
    }
};


struct OSX_DirectAudioDevice {
    AudioUnit   audioUnit;
    RingBuffer  ringBuffer;
    AudioStreamBasicDescription asbd;

    // only for target lines
    UInt32      inputBufferSizeInBytes;

    OSX_DirectAudioDevice() : audioUnit(NULL), asbd() {
    }

    ~OSX_DirectAudioDevice() {
        if (audioUnit) {
            CloseComponent(audioUnit);
        }
    }
};

static AudioUnit CreateOutputUnit(AudioDeviceID deviceID, int isSource)
{
    OSStatus err;
    AudioUnit unit;
    UInt32 size;

        ComponentDescription desc;
        desc.componentType         = kAudioUnitType_Output;
        desc.componentSubType      = (deviceID == 0 && isSource) ? kAudioUnitSubType_DefaultOutput : kAudioUnitSubType_HALOutput;
        desc.componentManufacturer = kAudioUnitManufacturer_Apple;
        desc.componentFlags        = 0;
        desc.componentFlagsMask    = 0;

    Component comp = FindNextComponent(NULL, &desc);
        err = OpenAComponent(comp, &unit);

    if (err) {
        OS_ERROR0(err, "CreateOutputUnit:OpenAComponent");
        return NULL;
    }

    if (!isSource) {
        int enableIO = 0;
        err = AudioUnitSetProperty(unit, kAudioOutputUnitProperty_EnableIO, kAudioUnitScope_Output,
                                    0, &enableIO, sizeof(enableIO));
        if (err) {
            OS_ERROR0(err, "SetProperty (output EnableIO)");
        }
        enableIO = 1;
        err = AudioUnitSetProperty(unit, kAudioOutputUnitProperty_EnableIO, kAudioUnitScope_Input,
                                    1, &enableIO, sizeof(enableIO));
        if (err) {
            OS_ERROR0(err, "SetProperty (input EnableIO)");
        }

        if (!deviceID) {
            // get real AudioDeviceID for default input device (macosx current input device)
            deviceID = GetDefaultDevice(isSource);
            if (!deviceID) {
                CloseComponent(unit);
                return NULL;
            }
        }
    }

    if (deviceID) {
        err = AudioUnitSetProperty(unit, kAudioOutputUnitProperty_CurrentDevice, kAudioUnitScope_Global,
                                    0, &deviceID, sizeof(deviceID));
        if (err) {
            OS_ERROR0(err, "SetProperty (CurrentDevice)");
            CloseComponent(unit);
            return NULL;
        }
    }

    return unit;
}

static OSStatus OutputCallback(void                         *inRefCon,
                               AudioUnitRenderActionFlags   *ioActionFlags,
                               const AudioTimeStamp         *inTimeStamp,
                               UInt32                       inBusNumber,
                               UInt32                       inNumberFrames,
                               AudioBufferList              *ioData)
{
    OSX_DirectAudioDevice *device = (OSX_DirectAudioDevice*)inRefCon;

    int nchannels = ioData->mNumberBuffers; // should be always == 1 (interleaved channels)
    AudioBuffer *audioBuffer = ioData->mBuffers;

    TRACE3(">>OutputCallback: busNum=%d, requested %d frames (%d bytes)\n",
        (int)inBusNumber, (int)inNumberFrames, (int)(inNumberFrames * device->asbd.mBytesPerFrame));
    TRACE3("  abl: %d buffers, buffer[0].channels=%d, buffer.size=%d\n",
        nchannels, (int)audioBuffer->mNumberChannels, (int)audioBuffer->mDataByteSize);

    int bytesToRead = inNumberFrames * device->asbd.mBytesPerFrame;
    if (bytesToRead > (int)audioBuffer->mDataByteSize) {
        TRACE0("--OutputCallback: !!! audioBuffer IS TOO SMALL!!!\n");
        bytesToRead = audioBuffer->mDataByteSize / device->asbd.mBytesPerFrame * device->asbd.mBytesPerFrame;
    }
    int bytesRead = device->ringBuffer.Read(audioBuffer->mData, bytesToRead);
    if (bytesRead < bytesToRead) {
        // no enough data (underrun)
        TRACE2("--OutputCallback: !!! UNDERRUN (read %d bytes of %d)!!!\n", bytesRead, bytesToRead);
        // silence the rest
        memset((Byte*)audioBuffer->mData + bytesRead, 0, bytesToRead-bytesRead);
        bytesRead = bytesToRead;
    }

    audioBuffer->mDataByteSize = (UInt32)bytesRead;
    // SAFETY: set mDataByteSize for all other AudioBuffer in the AudioBufferList to zero
    while (--nchannels > 0) {
        audioBuffer++;
        audioBuffer->mDataByteSize = 0;
    }
    TRACE1("<<OutputCallback (returns %d)\n", bytesRead);

    return noErr;
}

static OSStatus InputCallback(void                          *inRefCon,
                              AudioUnitRenderActionFlags    *ioActionFlags,
                              const AudioTimeStamp          *inTimeStamp,
                              UInt32                        inBusNumber,
                              UInt32                        inNumberFrames,
                              AudioBufferList               *ioData)
{
    OSX_DirectAudioDevice *device = (OSX_DirectAudioDevice*)inRefCon;

    TRACE4(">>InputCallback: busNum=%d, timeStamp=%lld, %d frames (%d bytes)\n",
        (int)inBusNumber, (long long)inTimeStamp->mSampleTime, (int)inNumberFrames, (int)(inNumberFrames * device->asbd.mBytesPerFrame));

    AudioBufferList abl;    // by default it contains 1 AudioBuffer
    abl.mNumberBuffers = 1;
    abl.mBuffers[0].mNumberChannels = device->asbd.mChannelsPerFrame;
    abl.mBuffers[0].mDataByteSize   = device->inputBufferSizeInBytes;   // assume this is == (inNumberFrames * device->asbd.mBytesPerFrame)
    abl.mBuffers[0].mData           = NULL;     // request for the audioUnit's buffer

    // TODO: is it possible device->inputBufferSizeInBytes != inNumberFrames * device->asbd.mBytesPerFrame??

    OSStatus err = AudioUnitRender(device->audioUnit, ioActionFlags, inTimeStamp, inBusNumber, inNumberFrames, &abl);
    if (err) {
        OS_ERROR0(err, "<<InputCallback: AudioUnitRender");
    } else {
        int bytesWritten = device->ringBuffer.Write(abl.mBuffers[0].mData, (int)abl.mBuffers[0].mDataByteSize, false);
        TRACE2("<<InputCallback (saved %d bytes of %d)\n", bytesWritten, (int)abl.mBuffers[0].mDataByteSize);
    }

    return noErr;
}

void* DAUDIO_Open(INT32 mixerIndex, INT32 deviceID, int isSource,
                  int encoding, float sampleRate, int sampleSizeInBits,
                  int frameSize, int channels,
                  int isSigned, int isBigEndian, int bufferSizeInBytes)
{
    TRACE3(">>DAUDIO_Open: mixerIndex=%d deviceID=0x%x isSource=%d\n", (int)mixerIndex, (unsigned int)deviceID, isSource);
    TRACE3("  sampleRate=%d sampleSizeInBits=%d channels=%d\n", (int)sampleRate, sampleSizeInBits, channels);
#ifdef USE_TRACE
    {
        AudioDeviceID audioDeviceID = deviceID;
        if (audioDeviceID == 0) {
            // default device
            audioDeviceID = GetDefaultDevice(isSource);
        }
        char name[256];
        OSStatus err = GetAudioObjectProperty(audioDeviceID, kAudioUnitScope_Global, kAudioDevicePropertyDeviceName, 256, &name, 0);
        if (err != noErr) {
            OS_ERROR1(err, "  audioDeviceID=0x%x, name is N/A:", (int)audioDeviceID);
        } else {
            TRACE2("  audioDeviceID=0x%x, name=%s\n", (int)audioDeviceID, name);
        }
    }
#endif

    if (encoding != DAUDIO_PCM) {
        ERROR1("<<DAUDIO_Open: ERROR: unsupported encoding (%d)\n", encoding);
        return NULL;
    }

    // TODO: for target lines we should ensure that sampleRate == current device sample rate
    // (othewise we get error -10863 (kAudioUnitErr_CannotDoInCurrentContext in AUComponent.h) from AudioUnitRender(in InputCallback))

    OSX_DirectAudioDevice *device = new OSX_DirectAudioDevice();

    AudioUnitScope scope = isSource ? kAudioUnitScope_Input : kAudioUnitScope_Output;
    int element = isSource ? 0 : 1;
    OSStatus err = noErr;
    int extraBufferBytes = 0;

    device->audioUnit = CreateOutputUnit(deviceID, isSource);

    if (!device->audioUnit) {
        delete device;
        return NULL;
    }

    FillOutASBDForLPCM(device->asbd, sampleRate, channels, sampleSizeInBits, sampleSizeInBits, 0, isBigEndian);
    // Workaround for FillOutASBDForLPCM - it always set kAudioFormatFlagIsSignedInteger for non-float formats
    if (!isSigned) {
        device->asbd.mFormatFlags &= ~(UInt32)kAudioFormatFlagIsSignedInteger;
    }

    err = AudioUnitSetProperty(device->audioUnit, kAudioUnitProperty_StreamFormat, scope, element, &device->asbd, sizeof(device->asbd));
    if (err) {
        OS_ERROR0(err, "<<DAUDIO_Open set StreamFormat");
        delete device;
        return NULL;
    }

    AURenderCallbackStruct output;
    output.inputProc       = isSource ? OutputCallback : InputCallback;
    output.inputProcRefCon = device;

    err = AudioUnitSetProperty(device->audioUnit,
                                isSource
                                    ? (AudioUnitPropertyID)kAudioUnitProperty_SetRenderCallback
                                    : (AudioUnitPropertyID)kAudioOutputUnitProperty_SetInputCallback,
                                kAudioUnitScope_Global, 0, &output, sizeof(output));
    if (err) {
        OS_ERROR0(err, "<<DAUDIO_Open set RenderCallback");
        delete device;
        return NULL;
    }

    err = AudioUnitInitialize(device->audioUnit);
    if (err) {
        OS_ERROR0(err, "<<DAUDIO_Open UnitInitialize");
        delete device;
        return NULL;
    }

    if (!isSource) {
        // for target lines we need extra bytes in the buffer
        // to prevent collisions when InputCallback overrides data on overflow
        UInt32 size;
        OSStatus err;

        size = sizeof(device->inputBufferSizeInBytes);
        err  = AudioUnitGetProperty(device->audioUnit, kAudioDevicePropertyBufferFrameSize, kAudioUnitScope_Global,
                                    0, &device->inputBufferSizeInBytes, &size);
        if (err) {
            OS_ERROR0(err, "<<DAUDIO_Open (TargetDataLine)GetBufferSize\n");
            delete device;
            return NULL;
        }
        device->inputBufferSizeInBytes *= device->asbd.mBytesPerFrame;  // convert frames by bytes
        extraBufferBytes = (int)device->inputBufferSizeInBytes;
    }

    if (!device->ringBuffer.Allocate(bufferSizeInBytes, extraBufferBytes)) {
        ERROR0("<<DAUDIO_Open: Ring buffer allocation error\n");
        delete device;
        return NULL;
    }

    TRACE0("<<DAUDIO_Open: OK\n");
    return device;
}

int DAUDIO_Start(void* id, int isSource) {
    OSX_DirectAudioDevice *device = (OSX_DirectAudioDevice*)id;
    TRACE0("DAUDIO_Start\n");

    OSStatus err = AudioOutputUnitStart(device->audioUnit);

    if (err != noErr) {
        OS_ERROR0(err, "DAUDIO_Start");
    }

    return err == noErr ? TRUE : FALSE;
}

int DAUDIO_Stop(void* id, int isSource) {
    OSX_DirectAudioDevice *device = (OSX_DirectAudioDevice*)id;
    TRACE0("DAUDIO_Stop\n");

    OSStatus err = AudioOutputUnitStop(device->audioUnit);

    return err == noErr ? TRUE : FALSE;
}

void DAUDIO_Close(void* id, int isSource) {
    OSX_DirectAudioDevice *device = (OSX_DirectAudioDevice*)id;
    TRACE0("DAUDIO_Close\n");

    delete device;
}

int DAUDIO_Write(void* id, char* data, int byteSize) {
    OSX_DirectAudioDevice *device = (OSX_DirectAudioDevice*)id;
    TRACE1(">>DAUDIO_Write: %d bytes to write\n", byteSize);

    int result = device->ringBuffer.Write(data, byteSize, true);

    TRACE1("<<DAUDIO_Write: %d bytes written\n", result);
    return result;
}

int DAUDIO_Read(void* id, char* data, int byteSize) {
    OSX_DirectAudioDevice *device = (OSX_DirectAudioDevice*)id;
    TRACE1(">>DAUDIO_Read: %d bytes to read\n", byteSize);

    int result = device->ringBuffer.Read(data, byteSize);

    TRACE1("<<DAUDIO_Read: %d bytes has been read\n", result);
    return result;
}

int DAUDIO_GetBufferSize(void* id, int isSource) {
    OSX_DirectAudioDevice *device = (OSX_DirectAudioDevice*)id;

    int bufferSizeInBytes = device->ringBuffer.GetBufferSize();

    TRACE1("DAUDIO_GetBufferSize returns %d\n", bufferSizeInBytes);
    return bufferSizeInBytes;
}

int DAUDIO_StillDraining(void* id, int isSource) {
    OSX_DirectAudioDevice *device = (OSX_DirectAudioDevice*)id;

    int draining = device->ringBuffer.GetValidByteCount() > 0 ? TRUE : FALSE;

    TRACE1("DAUDIO_StillDraining returns %d\n", draining);
    return draining;
}

int DAUDIO_Flush(void* id, int isSource) {
    OSX_DirectAudioDevice *device = (OSX_DirectAudioDevice*)id;
    TRACE0("DAUDIO_Flush\n");

    device->ringBuffer.Flush();

    return TRUE;
}

int DAUDIO_GetAvailable(void* id, int isSource) {
    OSX_DirectAudioDevice *device = (OSX_DirectAudioDevice*)id;

    int bytesInBuffer = device->ringBuffer.GetValidByteCount();
    if (isSource) {
        return device->ringBuffer.GetBufferSize() - bytesInBuffer;
    } else {
        return bytesInBuffer;
    }
}

INT64 DAUDIO_GetBytePosition(void* id, int isSource, INT64 javaBytePos) {
    OSX_DirectAudioDevice *device = (OSX_DirectAudioDevice*)id;
    INT64 position;

    if (isSource) {
        position = javaBytePos - device->ringBuffer.GetValidByteCount();
    } else {
        position = javaBytePos + device->ringBuffer.GetValidByteCount();
    }

    TRACE2("DAUDIO_GetBytePosition returns %lld (javaBytePos = %lld)\n", (long long)position, (long long)javaBytePos);
    return position;
}

void DAUDIO_SetBytePosition(void* id, int isSource, INT64 javaBytePos) {
    // no need javaBytePos (it's available in DAUDIO_GetBytePosition)
}

int DAUDIO_RequiresServicing(void* id, int isSource) {
    return FALSE;
}

void DAUDIO_Service(void* id, int isSource) {
    // unreachable
}

#endif  // USE_DAUDIO == TRUE
