/*
 * Copyright (c) 2003, 2011, Oracle and/or its affiliates. All rights reserved.
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

//#define USE_TRACE
//#define USE_ERROR

#include "PLATFORM_API_MacOSX_Utils.h"
#include "Ports.h"

typedef struct OSXAudioDevice {
    AudioDeviceID deviceID;
    int numInputStreams;
    int numOutputStreams;

    int numInputChannels;
    int numOutputChannels;
    Float64 inputSampleRate;
} OSXAudioDevice;

typedef struct {
    int numDevices;
    OSXAudioDevice *devices;

    OSXAudioDevice defaultAudioDevice;
} AudioDeviceContext;

static AudioDeviceContext deviceCtx;

static int UpdateAudioDeviceInfo(OSXAudioDevice *device)
{
    AudioStreamBasicDescription asbd = {0};
    AudioDeviceID deviceID, inputDeviceID;
    int streamID, inputStreamID;
    OSStatus err = noErr;
    UInt32 size;

    if (device->deviceID == 0) {
        err = GetAudioObjectProperty(kAudioObjectSystemObject, kAudioObjectPropertyScopeGlobal, kAudioHardwarePropertyDefaultOutputDevice,
                               sizeof(deviceID), &deviceID, 1);
        if (err) goto exit;
        err = GetAudioObjectProperty(kAudioObjectSystemObject, kAudioObjectPropertyScopeGlobal, kAudioHardwarePropertyDefaultInputDevice,
                               sizeof(inputDeviceID), &inputDeviceID, 1);
        if (err) goto exit;
    } else {
        inputDeviceID = deviceID = device->deviceID;
    }

    err = GetAudioObjectPropertySize(inputDeviceID, kAudioDevicePropertyScopeInput, kAudioDevicePropertyStreams,
                               &size);
    device->numInputStreams  = size / sizeof(AudioStreamID);
    if (err) goto exit;

    err = GetAudioObjectPropertySize(deviceID, kAudioDevicePropertyScopeOutput, kAudioDevicePropertyStreams,
                               &size);
    device->numOutputStreams = size / sizeof(AudioStreamID);
    if (err) goto exit;

    if (device->numOutputStreams) {
        err = GetAudioObjectProperty(deviceID, kAudioDevicePropertyScopeOutput, kAudioDevicePropertyStreams,
                                     sizeof(streamID), &streamID, 1);
        if (err) goto exit;

        if (streamID) {
            err = GetAudioObjectProperty(streamID, kAudioObjectPropertyScopeGlobal, kAudioStreamPropertyVirtualFormat,
                                         sizeof(asbd), &asbd, 1);
            if (err) goto exit;

            device->numOutputChannels = asbd.mChannelsPerFrame;
        }
    }

    if (device->numInputStreams) {
        err = GetAudioObjectProperty(inputDeviceID, kAudioDevicePropertyScopeInput, kAudioDevicePropertyStreams,
                                     sizeof(inputStreamID), &inputStreamID, 1);
        if (err) goto exit;

        if (streamID) {
            err = GetAudioObjectProperty(inputStreamID, kAudioObjectPropertyScopeGlobal, kAudioStreamPropertyVirtualFormat,
                                         sizeof(asbd), &asbd, 1);
            if (err) goto exit;

            device->numInputChannels = asbd.mChannelsPerFrame;
            device->inputSampleRate  = asbd.mSampleRate;
        }
    }

    return noErr;

exit:
    ERROR1("UpdateAudioDeviceInfo err %#x\n", err);
    return err;
}

int GetAudioDeviceCount()
{
    const AudioObjectPropertyAddress devicesAddress = {kAudioHardwarePropertyDevices, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMaster};
    UInt32 size;
    int i;

    if (!deviceCtx.numDevices) {
        GetAudioObjectPropertySize(kAudioObjectSystemObject, kAudioObjectPropertyScopeGlobal, kAudioHardwarePropertyDevices,
                                   &size);
        deviceCtx.numDevices = size / sizeof(AudioDeviceID);

        if (deviceCtx.numDevices) {
            AudioDeviceID deviceIDs[deviceCtx.numDevices];
            deviceCtx.devices = calloc(deviceCtx.numDevices, sizeof(OSXAudioDevice));

            size = deviceCtx.numDevices * sizeof(AudioDeviceID);
            AudioObjectGetPropertyData(kAudioObjectSystemObject, &devicesAddress, 0, NULL, &size, deviceIDs);
            deviceCtx.numDevices = size / sizeof(AudioDeviceID); // in case of an unplug

            for (i = 0; i < deviceCtx.numDevices; i++) {
                OSXAudioDevice *device = &deviceCtx.devices[i];

                device->deviceID = deviceIDs[i];
                UpdateAudioDeviceInfo(device);
            }
        }

        UpdateAudioDeviceInfo(&deviceCtx.defaultAudioDevice);
    }

    return deviceCtx.numDevices;
}

int GetAudioDeviceDescription(int index, AudioDeviceDescription *description)
{
    OSXAudioDevice *device;
    CFStringRef name = NULL, vendor = NULL;
    OSStatus err = noErr;
    int isDefault = index == -1;
    UInt32 size;

    device = isDefault ? &deviceCtx.defaultAudioDevice : &deviceCtx.devices[index];

    description->deviceID         = device->deviceID;
    description->numInputStreams  = device->numInputStreams;
    description->numOutputStreams = device->numOutputStreams;
    description->numInputChannels = device->numInputChannels;
    description->numOutputChannels= device->numOutputChannels;
    description->inputSampleRate  = device->inputSampleRate;

    if (isDefault) {
        if (description->name)
            strncpy(description->name, "Default Audio Device", description->strLen);
        if (description->description)
            strncpy(description->description, "Default Audio Device", description->strLen);
    } else {
        if (description->name) {
            err = GetAudioObjectProperty(device->deviceID, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyName,
                                         sizeof(name), &name, 1);
            if (err) goto exit;

            CFStringGetCString(name, description->name, description->strLen, kCFStringEncodingUTF8);
            if (description->description)
                CFStringGetCString(name, description->description, description->strLen, kCFStringEncodingUTF8);
        }

        if (description->vendor) {
            err = GetAudioObjectProperty(device->deviceID, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyManufacturer,
                                         sizeof(vendor), &vendor, 1);
            if (err) goto exit;

            CFStringGetCString(vendor, description->vendor, description->strLen, kCFStringEncodingUTF8);
        }
    }

exit:
    if (err) {
        ERROR1("GetAudioDeviceDescription error %.4s\n", &err);
    }
    if (name)   CFRelease(name);
    if (vendor) CFRelease(vendor);

    return err ? FALSE : TRUE;
}

OSStatus GetAudioObjectProperty(AudioObjectID object, AudioObjectPropertyScope scope, AudioObjectPropertySelector property, UInt32 size, void *data, int checkSize)
{
    const AudioObjectPropertyAddress address = {property, scope, kAudioObjectPropertyElementMaster};
    UInt32 oldSize = size;
    OSStatus err;

    err = AudioObjectGetPropertyData(object, &address, 0, NULL, &size, data);

    if (!err && checkSize && size != oldSize)
        return kAudioHardwareBadPropertySizeError;
    return err;
}

OSStatus GetAudioObjectPropertySize(AudioObjectID object, AudioObjectPropertyScope scope, AudioObjectPropertySelector property, UInt32 *size)
{
    const AudioObjectPropertyAddress address = {property, scope, kAudioObjectPropertyElementMaster};
    OSStatus err;

    err = AudioObjectGetPropertyDataSize(object, &address, 0, NULL, size);

    return err;
}
