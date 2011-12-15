/*
 * Copyright (c) 2011, Oracle and/or its affiliates. All rights reserved.
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

#import <AppKit/AppKit.h>
#import <JavaNativeFoundation/JavaNativeFoundation.h>

#import "CTrayIcon.h"
#import "ThreadUtilities.h"


@implementation AWTTrayIcon

- (id) initWithPeer:(jobject)thePeer {
    if (!(self = [super init])) return nil;

    peer = thePeer;

    theItem = [[NSStatusBar systemStatusBar] statusItemWithLength:30.0];
    [theItem retain];

    [theItem setTitle: NSLocalizedString(@"123", @"")];
    [theItem setHighlightMode:YES];

    button = [[AWTNSButton alloc] initWithTrayIcon:self];
    [theItem setView:button];

    return self;
}

-(void) dealloc {
    JNIEnv *env = [ThreadUtilities getJNIEnvUncached];
    JNFDeleteGlobalRef(env, peer);

    [button release];
    [theItem release];

    [super dealloc];
}

- (void) setTooltip:(NSString *) tooltip{
    [[self button] setToolTip:tooltip];
}

- (void) menuDidClose:(NSMenu *)menu {
}

-(NSStatusItem *) theItem{
    return theItem;
}

- (jobject) peer{
    return peer;
}


-(NSButton *)button{
    return button;
}

- (void) setImage:(NSImage *) imagePtr sizing:(BOOL)autosize{
    //TODO: get rid of hardcoded constants.
    [imagePtr setSize:NSMakeSize(20, 20)];
    [button setImage:imagePtr];
}

@end //AWTTrayIcon
//================================================

@implementation AWTNSButton

-(id)initWithTrayIcon:(AWTTrayIcon *)theTrayIcon {
    NSRect rect;
    rect.origin.x = 0;
    rect.origin.y = 0;
    rect.size.width = 30;
    rect.size.height = 30;

    self = [super initWithFrame:rect];

    trayIcon = theTrayIcon;

    [self setBordered:NO];

    //if this line is missed then the tooltip doesn't appear
    [self setToolTip:@""];
    return self;
}

- (void) mouseDown:(NSEvent *)e {
    //find CTrayIcon.getPopupMenuModel method and call it to get popup menu ptr.
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    static JNF_CLASS_CACHE(jc_CTrayIcon, "sun/lwawt/macosx/CTrayIcon");
    static JNF_MEMBER_CACHE(jm_getPopupMenuModel, jc_CTrayIcon, "getPopupMenuModel", "()J");
    static JNF_MEMBER_CACHE(jm_performAction, jc_CTrayIcon, "performAction", "()V");
    jlong res = JNFCallLongMethod(env, trayIcon.peer, jm_getPopupMenuModel);
    if (res != 0) {
        CPopupMenu *cmenu = jlong_to_ptr(res);
        [trayIcon.theItem popUpStatusItemMenu:[cmenu menu]];
    } else {
        JNFCallVoidMethod(env, trayIcon.peer, jm_performAction);
    }
    [super mouseDown:e];
}

- (void) rightMouseDown:(NSEvent *)e {
    // Call CTrayIcon.performAction() method on right mouse press
    JNIEnv *env = [ThreadUtilities getJNIEnv];
    static JNF_CLASS_CACHE(jc_CTrayIcon, "sun/lwawt/macosx/CTrayIcon");
    static JNF_MEMBER_CACHE(jm_performAction, jc_CTrayIcon, "performAction", "()V");
    JNFCallVoidMethod(env, trayIcon.peer, jm_performAction);
    [super rightMouseDown:e];
}

- (void) otherMouseDown:(NSEvent *)e { }

- (void) mouseUp:(NSEvent *)e{
    [super mouseUp:e];
}
- (void) rightMouseUp:(NSEvent *)e { }

- (void) otherMouseUp:(NSEvent *)e { }

- (void) mouseEntered:(NSEvent *)e { }

- (void) mouseExited:(NSEvent *)e { }

@end //AWTNSButton
//================================================

/*
 * Class:     sun_lwawt_macosx_CTrayIcon
 * Method:    nativeCreate
 * Signature: ()J
 */
JNIEXPORT jlong JNICALL Java_sun_lwawt_macosx_CTrayIcon_nativeCreate
(JNIEnv *env, jobject peer) {
    __block AWTTrayIcon *trayIcon = nil;

JNF_COCOA_ENTER(env);
AWT_ASSERT_NOT_APPKIT_THREAD;

    jobject thePeer = JNFNewGlobalRef(env, peer);
    [JNFRunLoop performOnMainThreadWaiting:YES withBlock:^(){
        trayIcon = [[AWTTrayIcon alloc] initWithPeer:thePeer];
    }];

JNF_COCOA_EXIT(env);

    return ptr_to_jlong(trayIcon);
}


/*
 * Class: java_awt_TrayIcon
 * Method: initIDs
 * Signature: ()V
 */
JNIEXPORT void JNICALL Java_java_awt_TrayIcon_initIDs
(JNIEnv *env, jclass cls) {
    //Do nothing.
}

/*
 * Class:     sun_lwawt_macosx_CTrayIcon
 * Method:    nativeSetToolTip
 * Signature: (JLjava/lang/String;)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CTrayIcon_nativeSetToolTip
(JNIEnv *env, jobject self, jlong model, jstring jtooltip) {
JNF_COCOA_ENTER(env);
AWT_ASSERT_NOT_APPKIT_THREAD;

    AWTTrayIcon *icon = jlong_to_ptr(model);
    NSString *tooltip = JNFJavaToNSString(env, jtooltip);
    [JNFRunLoop performOnMainThreadWaiting:NO withBlock:^(){
        [icon setTooltip:tooltip];
    }];

JNF_COCOA_EXIT(env);
}

/*
 * Class:     sun_lwawt_macosx_CTrayIcon
 * Method:    setNativeImage
 * Signature: (JJZ)V
 */
JNIEXPORT void JNICALL Java_sun_lwawt_macosx_CTrayIcon_setNativeImage
(JNIEnv *env, jobject self, jlong model, jlong imagePtr, jboolean autosize) {
JNF_COCOA_ENTER(env);
AWT_ASSERT_NOT_APPKIT_THREAD;

    AWTTrayIcon *icon = jlong_to_ptr(model);
    [JNFRunLoop performOnMainThreadWaiting:NO withBlock:^(){
        [icon setImage:jlong_to_ptr(imagePtr) sizing:autosize];
    }];

JNF_COCOA_EXIT(env);
}

