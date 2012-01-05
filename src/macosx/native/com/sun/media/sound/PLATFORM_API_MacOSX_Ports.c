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

//#define USE_ERROR
//#define USE_TRACE

#include <CoreAudio/CoreAudio.h>
#include <IOKit/audio/IOAudioTypes.h>
#include "Ports.h"
#include "PLATFORM_API_MacOSX_Utils.h"

#if USE_PORTS == TRUE

/*
 TODO

 Test devices with >2 channels.
 Compare control names and tree structure to other platforms.
 Implement virtual controls (balance, pan, master volume).
 */

typedef struct {
    struct PortMixer *mixer;

    AudioObjectID control;
    AudioClassID class; // kAudioVolumeControlClassID etc.
    UInt32 scope; // input, output

    void *jcontrol;
    char *jcontrolType; // CONTROL_TYPE_VOLUME etc.

    int channel; // master = 0, channels = 1 2 ...

    AudioValueRange range;
} PortControl;

typedef struct {
    AudioDeviceID deviceID;

    // = # of ports on the mixer
    // cached here in case the values can change
    int numInputStreams;
    int numOutputStreams;
    // streams[0..numInputStreams-1] contains inputs,
    // streams[numInputStreams..numInputStreams+numOutputStreams-1] contains outputs
    AudioStreamID *streams;

    int numDeviceControls;
    PortControl *deviceControls;

    int *numStreamControls;
    PortControl **streamControls;
} PortMixer;

INT32 PORT_GetPortMixerCount() {
    int count = GetAudioDeviceCount();
    TRACE1("< PORT_GetPortMixerCount = %d\n", count);

    return count;
}

INT32 PORT_GetPortMixerDescription(INT32 mixerIndex, PortMixerDescription* mixerDescription) {
    AudioDeviceDescription description;

    description.strLen = PORT_STRING_LENGTH;
    description.name   = mixerDescription->name;
    description.vendor = mixerDescription->vendor;
    description.description = NULL;

    /*
     We can't fill out the 'version' and 'description' fields.
     */

    return GetAudioDeviceDescription(mixerIndex, &description);
}

void* PORT_Open(INT32 mixerIndex) {
    AudioDeviceDescription description = {0};
    PortMixer *mixer = calloc(1, sizeof(PortMixer));

    GetAudioDeviceDescription(mixerIndex, &description);
    mixer->deviceID = description.deviceID;
    mixer->numInputStreams  = description.numInputStreams;
    mixer->numOutputStreams = description.numOutputStreams;

    if (mixer->numInputStreams || mixer->numOutputStreams) {
        mixer->streams = calloc(mixer->numInputStreams + mixer->numOutputStreams, sizeof(AudioStreamID));

        GetAudioObjectProperty(mixer->deviceID, kAudioDevicePropertyScopeInput, kAudioDevicePropertyStreams,
                               mixer->numInputStreams * sizeof(AudioStreamID), mixer->streams, 0);

        GetAudioObjectProperty(mixer->deviceID, kAudioDevicePropertyScopeOutput, kAudioDevicePropertyStreams,
                               mixer->numOutputStreams * sizeof(AudioStreamID),
                               mixer->streams + mixer->numInputStreams, 0);
    }

    TRACE1("< PORT_Open %p\n", mixer);
    return mixer;
}

void PORT_Close(void* id) {
    TRACE1("> PORT_Close %p\n", id);

    if (id) {
        PortMixer *mixer = id;
        free(mixer->streams);
        free(mixer);
    }
}

INT32 PORT_GetPortCount(void* id) {
    PortMixer *mixer = id;
    int numStreams = mixer->numInputStreams + mixer->numOutputStreams;

    TRACE1("< PORT_GetPortCount = %d\n", numStreams);
    return numStreams;
}

INT32 PORT_GetPortType(void* id, INT32 portIndex) {
    PortMixer *mixer = id;

    AudioStreamID streamID = mixer->streams[portIndex];
    UInt32 direction;
    UInt32 terminalType;
    UInt32 size;
    int err, ret;

    err = GetAudioObjectProperty(streamID, kAudioObjectPropertyScopeGlobal, kAudioStreamPropertyTerminalType,
                                 sizeof(terminalType), &terminalType, 1);
    if (err) goto exit;

    err = GetAudioObjectProperty(streamID, kAudioObjectPropertyScopeGlobal, kAudioStreamPropertyDirection,
                                 sizeof(direction), &direction, 1);
    if (err) goto exit;

    /*
     Note that kAudioStreamPropertyTerminalType actually returns values from
     IOAudioTypes.h, not the defined kAudioStreamTerminalType*.
     */

    if (direction) {
        // input
        switch (terminalType) {
            case EXTERNAL_LINE_CONNECTOR:
                ret = PORT_SRC_LINE_IN;
                break;

            case INPUT_MICROPHONE:
                ret = PORT_SRC_MICROPHONE;
                break;

            case EXTERNAL_SPDIF_INTERFACE:
                ret = PORT_SRC_UNKNOWN;
                break;

            default:
                TRACE1("unknown input terminal type %#x\n", terminalType);
#ifdef USE_TRACE
                AudioObjectShow(mixer->deviceID);
                AudioObjectShow(streamID);
#endif
                ret = PORT_SRC_UNKNOWN;
        }
    } else {
        // output
        switch (terminalType) {
            case EXTERNAL_LINE_CONNECTOR:
                ret = PORT_DST_LINE_OUT;
                break;

            case OUTPUT_SPEAKER:
                ret = PORT_DST_SPEAKER;
                break;

            case OUTPUT_HEADPHONES:
                ret = PORT_DST_HEADPHONE;
                break;

            case EXTERNAL_SPDIF_INTERFACE:
                ret = PORT_DST_UNKNOWN;
                break;

            default:
                TRACE1("unknown output terminal type %#x\n", terminalType);
#ifdef USE_TRACE
                AudioObjectShow(mixer->deviceID);
                AudioObjectShow(streamID);
#endif
                ret = PORT_DST_UNKNOWN;
        }
    }

    TRACE1("< PORT_GetPortType = %d\n", ret);
    return ret;
exit:
    ERROR1("< PORT_GetPortType error %d\n", err);
    return -1;
}

INT32 PORT_GetPortName(void* id, INT32 portIndex, char* name, INT32 len) {
    PortMixer *mixer = id;
    AudioStreamID streamID = mixer->streams[portIndex];

    CFStringRef cfname = NULL;
    OSStatus err = noErr;

    err = GetAudioObjectProperty(streamID, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyName,
                                 sizeof(cfname), &cfname, 1);
    if (err && err != kAudioHardwareUnknownPropertyError) goto exit;

    if (!cfname) {
        // use the device's name if the stream has no name (usually the case)
        err = GetAudioObjectProperty(mixer->deviceID, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyName,
                                     sizeof(cfname), &cfname, 1);
    }

    if (cfname) {
        CFStringGetCString(cfname, name, len, kCFStringEncodingUTF8);
    }

exit:
    if (cfname) CFRelease(cfname);

    TRACE1("< PORT_GetPortName %s\n", name);

    return FALSE;
}

static void CreateVolumeControl(PortControlCreator *creator, PortControl *control)
{
    Float32 min = 0, max = 1, precision;
    AudioValueRange *range = &control->range;
    UInt32 size;

    control->jcontrolType = CONTROL_TYPE_VOLUME;

    GetAudioObjectProperty(control->control, kAudioObjectPropertyScopeGlobal, kAudioLevelControlPropertyDecibelRange,
                           sizeof(control->range), &control->range, 1);
    precision = 1. / (range->mMaximum - range->mMinimum);

    control->jcontrol = creator->newFloatControl(creator, control, CONTROL_TYPE_VOLUME, min, max, precision, "");
}

static void CreateMuteControl(PortControlCreator *creator, PortControl *control)
{
    control->jcontrolType = CONTROL_TYPE_MUTE;
    control->jcontrol = creator->newBooleanControl(creator, control, CONTROL_TYPE_MUTE);
}

void PORT_GetControls(void* id, INT32 portIndex, PortControlCreator* creator) {
    PortMixer *mixer = id;
    AudioStreamID streamID = mixer->streams[portIndex];

    UInt32 size;
    OSStatus err;
    int i;

    int numVolumeControls = 0, numMuteControls = 0; // not counting the master
    int hasChannelVolume  = 0, hasChannelMute  = 0;
    PortControl *masterVolume = NULL, *masterMute = NULL;

    UInt32 wantedScope = portIndex < mixer->numInputStreams ? kAudioDevicePropertyScopeInput : kAudioDevicePropertyScopeOutput;

    // initialize the device controls if this is the first stream
    if (!mixer->numDeviceControls) {
        // numDeviceControls / numStreamControls are overestimated
        // because we don't actually filter by if the owned objects are controls
        err = GetAudioObjectPropertySize(mixer->deviceID, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyOwnedObjects,
                                         &size);
        if (err) goto exit;

        mixer->numDeviceControls = size / sizeof(AudioObjectID);

        if (mixer->numDeviceControls) {
            AudioObjectID *controlIDs = calloc(mixer->numDeviceControls, sizeof(AudioObjectID));
            mixer->deviceControls     = calloc(mixer->numDeviceControls, sizeof(PortControl));

            err = GetAudioObjectProperty(mixer->deviceID, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyOwnedObjects,
                                         mixer->numDeviceControls * sizeof(AudioObjectID), controlIDs, 1);
            if (err) goto exit;

            for (i = 0; i < mixer->numDeviceControls; i++) {
                PortControl *control = &mixer->deviceControls[i];

                control->control = controlIDs[i];
                control->mixer = mixer;

                GetAudioObjectProperty(control->control, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyClass,
                                       sizeof(control->class), &control->class, 1);
                err = GetAudioObjectProperty(control->control, kAudioObjectPropertyScopeGlobal, kAudioControlPropertyElement,
                                       sizeof(control->channel), &control->channel, 1);

                if (err) { // not a control
                    control->class = 0;
                    continue;
                }

                GetAudioObjectProperty(control->control, kAudioObjectPropertyScopeGlobal, kAudioControlPropertyScope,
                                       sizeof(control->scope), &control->scope, 1);

                TRACE3("%.4s control, channel %d scope %.4s\n", &control->class, control->channel, &control->scope);
            }
        }
    }

    // count the number of device controls with the appropriate scope
    if (mixer->numDeviceControls) {
        for (i = 0; i < mixer->numDeviceControls; i++) {
            PortControl *control = &mixer->deviceControls[i];

            if (control->scope != wantedScope)
                continue;

            switch (control->class) {
                case kAudioVolumeControlClassID:
                    if (control->channel == 0)
                        masterVolume = control;
                    else {
                        numVolumeControls++;
                        hasChannelVolume = 1;
                    }
                    break;

                case kAudioMuteControlClassID:
                    if (control->channel == 0)
                        masterMute = control;
                    else {
                        numMuteControls++;
                        hasChannelMute = 1;
                    }
                    break;
            }
        }
    }

    TRACE4("volume: channel %d master %d, mute: channel %d master %d\n", numVolumeControls, masterVolume != NULL, numMuteControls, masterMute != NULL);

    if (masterVolume) {
        if (!masterVolume->jcontrol)
            CreateVolumeControl(creator, masterVolume);
        creator->addControl(creator, masterVolume->jcontrol);
    }

    if (masterMute) {
        if (!masterMute->jcontrol)
            CreateMuteControl(creator, masterMute);
        creator->addControl(creator, masterMute->jcontrol);
    }

    if (numVolumeControls) {
        void **jControls = calloc(numVolumeControls, sizeof(void*));
        int j = 0;
        for (i = 0; i < mixer->numDeviceControls && j < numVolumeControls; i++) {
            PortControl *control = &mixer->deviceControls[i];

            if (control->class != kAudioVolumeControlClassID || control->channel == 0 || control->scope != wantedScope)
                continue;

            if (!control->jcontrol)
                CreateVolumeControl(creator, control);
            jControls[j++] = control->jcontrol;
        }

        void *compoundControl = creator->newCompoundControl(creator, "Volume", jControls, numVolumeControls);
        creator->addControl(creator, compoundControl);
        free(jControls);
    }

    if (numMuteControls) {
        void **jControls = calloc(numMuteControls, sizeof(void*));
        int j = 0;
        for (i = 0; i < mixer->numDeviceControls && j < numMuteControls; i++) {
            PortControl *control = &mixer->deviceControls[i];

            if (control->class != kAudioMuteControlClassID || control->channel == 0 || control->scope != wantedScope)
                continue;

            if (!control->jcontrol)
                CreateMuteControl(creator, control);
            jControls[j++] = control->jcontrol;
        }

        void *compoundControl = creator->newCompoundControl(creator, "Mute", jControls, numMuteControls);
        creator->addControl(creator, compoundControl);
        free(jControls);
    }

    if (!mixer->numStreamControls)
        mixer->numStreamControls = calloc(mixer->numInputStreams + mixer->numOutputStreams, sizeof(int));

    err = GetAudioObjectPropertySize(streamID, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyOwnedObjects,
                                     &size);
    if (err) goto exit;

    mixer->numStreamControls[portIndex] = size / sizeof(AudioObjectID);

    TRACE2("%d controls on device, %d on stream\n", mixer->numDeviceControls, mixer->numStreamControls[portIndex]);

exit:
    if (err) {
        ERROR1("PORT_GetControls err %.4s\n", &err);
        free(mixer->deviceControls);
    }

    TRACE0("< PORT_GetControls\n");
}

INT32 PORT_GetIntValue(void* controlIDV) {
    PortControl *control = controlIDV;
    UInt32 value = 0;
    OSStatus err = 0;
    UInt32 size;

    switch (control->class) {
        case kAudioMuteControlClassID:
        {
            err = GetAudioObjectProperty(control->control, kAudioObjectPropertyScopeGlobal,
                                         kAudioBooleanControlPropertyValue, sizeof(value), &value, 1);
            if (err) goto exit;
        }
            break;

        default:
            ERROR0("SetIntValue requested for non-Int control\n");
            goto exit;
    }

    TRACE1("< PORT_GetIntValue = %d\n", value);
    return value;

exit:
    if (err) {
        ERROR1("PORT_GetIntValue err %d\n", err);
    }
    return FALSE;
}

void PORT_SetIntValue(void* controlIDV, INT32 value) {
    TRACE1("> PORT_SetIntValue = %d\n", value);
    PortControl *control = controlIDV;
    OSStatus err = 0;

    switch (control->class) {
        case kAudioMuteControlClassID:
        {
            const AudioObjectPropertyAddress address =
            { kAudioBooleanControlPropertyValue, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMaster };
            err = AudioObjectSetPropertyData(control->control, &address, 0, NULL, sizeof(value), &value);
            if (err) goto exit;
        }
            break;

        default:
            ERROR0("SetIntValue requested for non-Int control\n");
    }

    return;

exit:
    if (err) {
        ERROR1("PORT_SetIntValue err %d\n", err);
    }
}

float PORT_GetFloatValue(void* controlIDV) {
    PortControl *control = controlIDV;
    Float32 value = 0;
    OSStatus err = 0;
    UInt32 size;

    switch (control->class) {
        case kAudioVolumeControlClassID:
        {
            err = GetAudioObjectProperty(control->control, kAudioObjectPropertyScopeGlobal,
                                             kAudioLevelControlPropertyDecibelValue, sizeof(value), &value, 1);
            if (err) goto exit;

            // convert decibel to 0-1 logarithmic
            value = (value - control->range.mMinimum) / (control->range.mMaximum - control->range.mMinimum);
        }
            break;

        default:
            ERROR0("GetFloatValue requested for non-Float control\n");
            break;
    }

    TRACE1("< PORT_GetFloatValue = %f\n", value);
    return value;

exit:
    if (err) {
        ERROR1("PORT_GetFloatValue err %d\n", err);
    }
    return 0;
}

void PORT_SetFloatValue(void* controlIDV, float value) {
    TRACE1("> PORT_SetFloatValue = %f\n", value);
    PortControl *control = controlIDV;
    OSStatus err = 0;

    switch (control->class) {
        case kAudioVolumeControlClassID:
        {
            const AudioObjectPropertyAddress address =
            { kAudioLevelControlPropertyDecibelValue, kAudioObjectPropertyScopeGlobal, kAudioObjectPropertyElementMaster };

            value = (value * (control->range.mMaximum - control->range.mMinimum)) + control->range.mMinimum;
            err = AudioObjectSetPropertyData(control->control, &address, 0, NULL, sizeof(value), &value);
            if (err) goto exit;
        }
            break;

        default:
            ERROR0("SetFloatValue requested for non-Float control\n");
            break;
    }

    return;

exit:
    if (err) {
        ERROR1("PORT_GetFloatValue err %d\n", err);
    }
}

#endif // USE_PORTS
