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

#import <stdlib.h>
#import <JavaNativeFoundation/JavaNativeFoundation.h>

#import "sun_java2d_opengl_CGLSurfaceData.h"

#import "jni.h"
#import "jni_util.h"
#import "OGLRenderQueue.h"
#import "CGLGraphicsConfig.h"
#import "CGLSurfaceData.h"
#import "CGLLayer.h"
#import "ThreadUtilities.h"

/* JDK's glext.h is already included and will prevent the Apple glext.h
 * being included, so define the externs directly
 */
extern void glBindFramebufferEXT(GLenum target, GLuint framebuffer);
extern CGLError CGLTexImageIOSurface2D(
        CGLContextObj ctx, GLenum target, GLenum internal_format,
        GLsizei width, GLsizei height, GLenum format, GLenum type,
        IOSurfaceRef ioSurface, GLuint plane);

/**
 * The methods in this file implement the native windowing system specific
 * layer (CGL) for the OpenGL-based Java 2D pipeline.
 */

#pragma mark -
#pragma mark "--- Mac OS X specific methods for GL pipeline ---"

// TODO: hack that's called from OGLRenderQueue to test out unlockFocus behavior
#if 0
void
OGLSD_UnlockFocus(OGLContext *oglc, OGLSDOps *dstOps)
{
    CGLCtxInfo *ctxinfo = (CGLCtxInfo *)oglc->ctxInfo;
    CGLSDOps *cglsdo = (CGLSDOps *)dstOps->privOps;
    fprintf(stderr, "about to unlock focus: %p %p\n",
            cglsdo->peerData, ctxinfo->context);

    NSOpenGLView *nsView = cglsdo->peerData;
    if (nsView != NULL) {
JNF_COCOA_ENTER(env);
        [nsView unlockFocus];
JNF_COCOA_EXIT(env);
    }
}
#endif

/**
 * Makes the given context current to its associated "scratch" surface.  If
 * the operation is successful, this method will return JNI_TRUE; otherwise,
 * returns JNI_FALSE.
 */
static jboolean
CGLSD_MakeCurrentToScratch(JNIEnv *env, OGLContext *oglc)
{
    J2dTraceLn(J2D_TRACE_INFO, "CGLSD_MakeCurrentToScratch");

    if (oglc == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
                      "CGLSD_MakeCurrentToScratch: context is null");
        return JNI_FALSE;
    }

JNF_COCOA_ENTER(env);

    CGLCtxInfo *ctxinfo = (CGLCtxInfo *)oglc->ctxInfo;
#if USE_NSVIEW_FOR_SCRATCH
    [ctxinfo->context makeCurrentContext];
    [ctxinfo->context setView: ctxinfo->scratchSurface];
#else
    [ctxinfo->context clearDrawable];
    [ctxinfo->context makeCurrentContext];
    [ctxinfo->context setPixelBuffer: ctxinfo->scratchSurface
            cubeMapFace: 0
            mipMapLevel: 0
            currentVirtualScreen: [ctxinfo->context currentVirtualScreen]];
#endif

JNF_COCOA_EXIT(env);

    return JNI_TRUE;
}

/**
 * This function disposes of any native windowing system resources associated
 * with this surface.  For instance, if the given OGLSDOps is of type
 * OGLSD_PBUFFER, this method implementation will destroy the actual pbuffer
 * surface.
 */
void
OGLSD_DestroyOGLSurface(JNIEnv *env, OGLSDOps *oglsdo)
{
    J2dTraceLn(J2D_TRACE_INFO, "OGLSD_DestroyOGLSurface");

JNF_COCOA_ENTER(env);

    CGLSDOps *cglsdo = (CGLSDOps *)oglsdo->privOps;
    if (oglsdo->drawableType == OGLSD_PBUFFER) {
        if (oglsdo->textureID != 0) {
            j2d_glDeleteTextures(1, &oglsdo->textureID);
            oglsdo->textureID = 0;
        }
        if (cglsdo->pbuffer != NULL) {
            [cglsdo->pbuffer release];
            cglsdo->pbuffer = NULL;
        }
    } else if (oglsdo->drawableType == OGLSD_WINDOW) {
#if USE_INTERMEDIATE_BUFFER
        // REMIND: duplicates code in OGLSD_Delete invoked from the Dispose thread
        if (oglsdo->textureID != 0) {
            j2d_glDeleteTextures(1, &oglsdo->textureID);
            oglsdo->textureID = 0;
        }
        if (oglsdo->depthID != 0) {
            j2d_glDeleteRenderbuffersEXT(1, &oglsdo->depthID);
            oglsdo->depthID = 0;
        }
        if (oglsdo->fbobjectID != 0) {
            j2d_glDeleteFramebuffersEXT(1, &oglsdo->fbobjectID);
            oglsdo->fbobjectID = 0;
        }
#else
        // detach the NSView from the NSOpenGLContext
        CGLGraphicsConfigInfo *cglInfo = cglsdo->configInfo;
        OGLContext *oglc = cglInfo->context;
        CGLCtxInfo *ctxinfo = (CGLCtxInfo *)oglc->ctxInfo;
        [ctxinfo->context clearDrawable];
#endif
    }

    oglsdo->drawableType = OGLSD_UNDEFINED;

JNF_COCOA_EXIT(env);
}

/**
 * Returns a pointer (as a jlong) to the native CGLGraphicsConfigInfo
 * associated with the given OGLSDOps.  This method can be called from
 * shared code to retrieve the native GraphicsConfig data in a platform-
 * independent manner.
 */
jlong
OGLSD_GetNativeConfigInfo(OGLSDOps *oglsdo)
{
    J2dTraceLn(J2D_TRACE_INFO, "OGLSD_GetNativeConfigInfo");

    if (oglsdo == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "OGLSD_GetNativeConfigInfo: ops are null");
        return 0L;
    }

    CGLSDOps *cglsdo = (CGLSDOps *)oglsdo->privOps;
    if (cglsdo == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "OGLSD_GetNativeConfigInfo: cgl ops are null");
        return 0L;
    }

    return ptr_to_jlong(cglsdo->configInfo);
}

/**
 * Makes the given GraphicsConfig's context current to its associated
 * "scratch" surface.  If there is a problem making the context current,
 * this method will return NULL; otherwise, returns a pointer to the
 * OGLContext that is associated with the given GraphicsConfig.
 */
OGLContext *
OGLSD_SetScratchSurface(JNIEnv *env, jlong pConfigInfo)
{
    J2dTraceLn(J2D_TRACE_INFO, "OGLSD_SetScratchContext");

    CGLGraphicsConfigInfo *cglInfo = (CGLGraphicsConfigInfo *)jlong_to_ptr(pConfigInfo);
    if (cglInfo == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "OGLSD_SetScratchContext: cgl config info is null");
        return NULL;
    }

    OGLContext *oglc = cglInfo->context;
    CGLCtxInfo *ctxinfo = (CGLCtxInfo *)oglc->ctxInfo;

JNF_COCOA_ENTER(env);

    // avoid changing the context's target view whenever possible, since
    // calling setView causes flickering; as long as our context is current
    // to some view, it's not necessary to switch to the scratch surface
    if ([ctxinfo->context view] == nil) {
        // it seems to be necessary to explicitly flush between context changes
        OGLContext *currentContext = OGLRenderQueue_GetCurrentContext();
        if (currentContext != NULL) {
            j2d_glFlush();
        }

        if (!CGLSD_MakeCurrentToScratch(env, oglc)) {
            return NULL;
        }
    } else if ([NSOpenGLContext currentContext] == nil) {
        [ctxinfo->context makeCurrentContext];
    }

    if (OGLC_IS_CAP_PRESENT(oglc, CAPS_EXT_FBOBJECT)) {
        // the GL_EXT_framebuffer_object extension is present, so this call
        // will ensure that we are bound to the scratch surface (and not
        // some other framebuffer object)
        j2d_glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);
    }

JNF_COCOA_EXIT(env);

    return oglc;
}

/**
 * Makes a context current to the given source and destination
 * surfaces.  If there is a problem making the context current, this method
 * will return NULL; otherwise, returns a pointer to the OGLContext that is
 * associated with the destination surface.
 */
OGLContext *
OGLSD_MakeOGLContextCurrent(JNIEnv *env, OGLSDOps *srcOps, OGLSDOps *dstOps)
{
    J2dTraceLn(J2D_TRACE_INFO, "OGLSD_MakeOGLContextCurrent");

    CGLSDOps *dstCGLOps = (CGLSDOps *)dstOps->privOps;

    J2dTraceLn4(J2D_TRACE_VERBOSE, "  src: %d %p dst: %d %p", srcOps->drawableType, srcOps, dstOps->drawableType, dstOps);

    OGLContext *oglc = dstCGLOps->configInfo->context;
    if (oglc == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "OGLSD_MakeOGLContextCurrent: context is null");
        return NULL;
    }

    CGLCtxInfo *ctxinfo = (CGLCtxInfo *)oglc->ctxInfo;

    // it seems to be necessary to explicitly flush between context changes
    OGLContext *currentContext = OGLRenderQueue_GetCurrentContext();
    if (currentContext != NULL) {
        j2d_glFlush();
    }

    if (dstOps->drawableType == OGLSD_FBOBJECT) {
        // first make sure we have a current context (if the context isn't
        // already current to some drawable, we will make it current to
        // its scratch surface)
        if (oglc != currentContext) {
            if (!CGLSD_MakeCurrentToScratch(env, oglc)) {
                return NULL;
            }
        }

        // now bind to the fbobject associated with the destination surface;
        // this means that all rendering will go into the fbobject destination
        // (note that we unbind the currently bound texture first; this is
        // recommended procedure when binding an fbobject)
#ifndef USE_IOS
        j2d_glBindTexture(GL_TEXTURE_2D, 0);
#else
        GLenum target = GL_TEXTURE_RECTANGLE_ARB;
        j2d_glBindTexture(target, 0);
        j2d_glDisable(target);
#endif
        j2d_glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, dstOps->fbobjectID);

        return oglc;
    }

JNF_COCOA_ENTER(env);

    // set the current surface
    if (dstOps->drawableType == OGLSD_PBUFFER) {
        // REMIND: pbuffers are not fully tested yet...
        [ctxinfo->context clearDrawable];
        [ctxinfo->context makeCurrentContext];
        [ctxinfo->context setPixelBuffer: dstCGLOps->pbuffer
                cubeMapFace: 0
                mipMapLevel: 0
                currentVirtualScreen: [ctxinfo->context currentVirtualScreen]];
    } else {
#if USE_INTERMEDIATE_BUFFER
#ifndef USE_IOS
        j2d_glBindTexture(GL_TEXTURE_2D, 0);
#else
    GLenum target = GL_TEXTURE_RECTANGLE_ARB;
    j2d_glBindTexture(target, 0);
    j2d_glDisable(target);
#endif
        j2d_glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, dstOps->fbobjectID);
#else
        CGLSDOps *cglsdo = (CGLSDOps *)dstOps->privOps;
        NSView *nsView = (NSView *)cglsdo->peerData;

        if ([ctxinfo->context view] != nsView) {
            [ctxinfo->context makeCurrentContext];
            [ctxinfo->context setView: nsView];
        }
#endif
    }

#ifndef USE_INTERMEDIATE_BUFFER
    if (OGLC_IS_CAP_PRESENT(oglc, CAPS_EXT_FBOBJECT)) {
        // the GL_EXT_framebuffer_object extension is present, so we
        // must bind to the default (windowing system provided)
        // framebuffer
        j2d_glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);
    }
#endif

    if ((srcOps != dstOps) && (srcOps->drawableType == OGLSD_PBUFFER)) {
        // bind pbuffer to the render texture object (since we are preparing
        // to copy from the pbuffer)
        CGLSDOps *srcCGLOps = (CGLSDOps *)srcOps->privOps;
        j2d_glBindTexture(GL_TEXTURE_2D, srcOps->textureID);
        [ctxinfo->context
                setTextureImageToPixelBuffer: srcCGLOps->pbuffer
                colorBuffer: GL_FRONT];
    }

JNF_COCOA_EXIT(env);

    return oglc;
}

/**
 * Returns true if OpenGL textures can have non-power-of-two dimensions
 * when using the basic GL_TEXTURE_2D target.
 */
BOOL isTexNonPow2Available(CGLGraphicsConfigInfo *cglinfo) {
    jint caps;
    if ((cglinfo == NULL) || (cglinfo->context == NULL)) {
        return FALSE;
    } else {
        caps = cglinfo->context->caps;
    }
    return ((caps & CAPS_TEXNONPOW2) != 0);
}

/**
 * Returns true if OpenGL textures can have non-power-of-two dimensions
 * when using the GL_TEXTURE_RECTANGLE_ARB target (only available when the
 * GL_ARB_texture_rectangle extension is present).
 */
BOOL isTexRectAvailable(CGLGraphicsConfigInfo *cglinfo) {
    jint caps;
    if ((cglinfo == NULL) || (cglinfo->context == NULL)) {
        return FALSE;
    } else {
        caps = cglinfo->context->caps;
    }
    return ((caps & CAPS_EXT_TEXRECT) != 0);
}

/**
 * Recreates the intermediate buffer associated with the given OGLSDOps
 * and with the buffer's new size specified in OGLSDOps.
 */
jboolean RecreateBuffer(JNIEnv *env, OGLSDOps *oglsdo)
{
    // destroy previous buffer first
    OGLSD_DestroyOGLSurface(env, oglsdo);

    CGLSDOps *cglsdo = (CGLSDOps *)oglsdo->privOps;
    jboolean result =
        OGLSurfaceData_initFBObject(env, NULL, ptr_to_jlong(oglsdo), oglsdo->isOpaque,
                                    isTexNonPow2Available(cglsdo->configInfo),
                                    isTexRectAvailable(cglsdo->configInfo),
                                    oglsdo->width, oglsdo->height);

    // NOTE: OGLSD_WINDOW type is reused for offscreen rendering
    //       when intermediate buffer is enabled
    oglsdo->drawableType = OGLSD_WINDOW;

    AWTView *view = cglsdo->peerData;
    CGLLayer *layer = (CGLLayer *)view.cglLayer;
    layer.textureID = oglsdo->textureID;
    layer.target = GL_TEXTURE_2D;
    layer.textureWidth = oglsdo->width;
    layer.textureHeight = oglsdo->height;

    return result;
}


static inline IOSurfaceRef createIoSurface(int width, int height)
{
    // Get an error return for 0 size. Maybe should skip creation
    // for that case, but for now make a 1X1 surface
    if (width <= 0) width = 1;
    if (height <= 0) height = 1;

    NSAutoreleasePool * pool = [[NSAutoreleasePool alloc] init];
    NSMutableDictionary *properties = [NSMutableDictionary dictionaryWithCapacity:4];
    [properties setObject:[NSNumber numberWithInt:width] forKey:(id)kIOSurfaceWidth];
    [properties setObject:[NSNumber numberWithInt:height] forKey:(id)kIOSurfaceHeight];
    [properties setObject:[NSNumber numberWithInt:4] forKey:(id)kIOSurfaceBytesPerElement];
    IOSurfaceRef surface = IOSurfaceCreate((CFDictionaryRef)properties);
    CFRetain(surface); // REMIND: do I need to do this ?
    [pool drain];

    if (surface == NULL) {
        NSLog(@"IOSurfaceCreate error, surface: %p", surface);
    } else {
        //NSLog(@"Plugin iosurface OK");
        //NSLog(@"    surface: %p", surface);
        //NSLog(@"    IOSurfaceGetID(self->surface): %d", IOSurfaceGetID(surface));
    }
    return surface;
}

/**
 * Recreates the intermediate buffer associated with the given OGLSDOps
 * and with the buffer's new size specified in OGLSDOps.
 */
jboolean RecreateIOSBuffer(JNIEnv *env, OGLSDOps *oglsdo)
{
    CGLSDOps *cglsdo = (CGLSDOps *)oglsdo->privOps;
    // destroy previous buffer first
    if (oglsdo->textureID != 0) {
        OGLSD_DestroyOGLSurface(env, oglsdo);
        if (cglsdo->surfaceRef != NULL) {
            CFRelease(cglsdo->surfaceRef);
            cglsdo->surfaceRef = NULL;
        }
    }

    oglsdo->textureID = 0;
    cglsdo->surfaceRef = NULL;
    int width = oglsdo->width;
    int height = oglsdo->height;
    if (width <= 0) width = 1;
    if (height <= 0) height = 1;
    IOSurfaceRef _surfaceRef = createIoSurface(width, height);
    if (_surfaceRef == NULL) {
        return JNI_FALSE;
    }

    GLenum target = GL_TEXTURE_RECTANGLE_ARB;
    glEnable(target);
    GLuint texture;
    glGenTextures(1, &texture);
    glBindTexture(target, texture);
    glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    OGLContext *oglc = cglsdo->configInfo->context;
    CGLCtxInfo *ctxinfo = (CGLCtxInfo *)oglc->ctxInfo;
    CGLContextObj context = ctxinfo->context.CGLContextObj;

    /* These parameters are documented only in the header file
     * and apart from the requirement that it must be one of
     * the combinations listed there its not as clear as I'd like
     * what the choices mean.
     */
    GLenum format = GL_BGRA;
    GLenum internal_format = GL_RGB;
    GLenum type = GL_UNSIGNED_INT_8_8_8_8_REV;

    CGLError err =
    CGLTexImageIOSurface2D(context, target, internal_format,
         width, height, format, type, _surfaceRef, 0);

    if (err != kCGLNoError) {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
                      "OGLSurfaceData_RecreateIOSBuffer: could not init texture");
        j2d_glDeleteTextures(1, &texture);
        CFRelease(_surfaceRef);
        return JNI_FALSE;
    }

    oglsdo->drawableType = OGLSD_FBOBJECT;
    oglsdo->xOffset = 0;
    oglsdo->yOffset = 0;
    oglsdo->width = width;
    oglsdo->height = height;
    oglsdo->textureID = texture;
    oglsdo->textureWidth = width;
    oglsdo->textureHeight = height;
    // init_FBO fails if we don't use target GL_TEXTURE_RECTANGLE_ARB for the IOS texture.
    oglsdo->textureTarget = target;
    OGLSD_INIT_TEXTURE_FILTER(oglsdo, GL_NEAREST);
    OGLSD_RESET_TEXTURE_WRAP(target);

    // initialize framebuffer object using color texture created above
    GLuint fbobjectID, depthID;
    if (!OGLSD_InitFBObject(&fbobjectID, &depthID,
                            oglsdo->textureID, oglsdo->textureTarget,
                            oglsdo->textureWidth, oglsdo->textureHeight))
    {
        J2dRlsTraceLn(J2D_TRACE_ERROR,
                      "OGLSurfaceData_RecreateIOSBuffer: could not init fbobject");
        j2d_glDeleteTextures(1, &oglsdo->textureID);
        CFRelease(_surfaceRef);
        return JNI_FALSE;
    }

    oglsdo->fbobjectID = fbobjectID;
    oglsdo->depthID = depthID;
    // NOTE: OGLSD_WINDOW type is reused for offscreen rendering
    //       when intermediate buffer is enabled
    oglsdo->drawableType = OGLSD_WINDOW;

    OGLSD_SetNativeDimensions(env, oglsdo,
                              oglsdo->textureWidth, oglsdo->textureHeight);

    cglsdo->surfaceRef = _surfaceRef;
    AWTView *view = cglsdo->peerData;
    CGLLayer *layer = (CGLLayer *)view.cglLayer;
    layer.textureID = oglsdo->textureID;
    layer.target = target;
    layer.textureWidth = oglsdo->width;
    layer.textureHeight = oglsdo->height;

    glBindFramebufferEXT(GL_FRAMEBUFFER_EXT, 0);

    return JNI_TRUE;
}

/**
 * This function initializes a native window surface and caches the window
 * bounds in the given OGLSDOps.  Returns JNI_TRUE if the operation was
 * successful; JNI_FALSE otherwise.
 */
jboolean
OGLSD_InitOGLWindow(JNIEnv *env, OGLSDOps *oglsdo)
{
    J2dTraceLn(J2D_TRACE_INFO, "OGLSD_InitOGLWindow");

    if (oglsdo == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "OGLSD_InitOGLWindow: ops are null");
        return JNI_FALSE;
    }

    CGLSDOps *cglsdo = (CGLSDOps *)oglsdo->privOps;
    if (cglsdo == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "OGLSD_InitOGLWindow: cgl ops are null");
        return JNI_FALSE;
    }

    AWTView *v = cglsdo->peerData;
    if (v == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "OGLSD_InitOGLWindow: view is invalid");
        return JNI_FALSE;
    }

JNF_COCOA_ENTER(env);
    NSRect surfaceBounds = [v bounds];
    oglsdo->drawableType = OGLSD_WINDOW;
#ifndef USE_INTERMEDIATE_BUFFER
    oglsdo->isOpaque = JNI_TRUE;
#endif

    oglsdo->width = surfaceBounds.size.width;
    oglsdo->height = surfaceBounds.size.height;
JNF_COCOA_EXIT(env);

    jboolean result = JNI_TRUE;

#if USE_INTERMEDIATE_BUFFER
#ifdef USE_IOS
    result = RecreateIOSBuffer(env, oglsdo);
#else
    result = RecreateBuffer(env, oglsdo);
#endif
#endif

    J2dTraceLn2(J2D_TRACE_VERBOSE, "  created window: w=%d h=%d", oglsdo->width, oglsdo->height);

    return result;
}

void
OGLSD_SwapBuffers(JNIEnv *env, jlong pPeerData)
{
    J2dTraceLn(J2D_TRACE_INFO, "OGLSD_SwapBuffers");

JNF_COCOA_ENTER(env);
    [[NSOpenGLContext currentContext] flushBuffer];
JNF_COCOA_EXIT(env);
}

void
OGLSD_Flush(JNIEnv *env)
{
    OGLSDOps *dstOps = OGLRenderQueue_GetCurrentDestination();
    if (dstOps != NULL) {
        CGLSDOps *dstCGLOps = (CGLSDOps *)dstOps->privOps;
        CGLLayer *layer = (CGLLayer*)dstCGLOps->layer;
        if (layer != NULL) {
            [JNFRunLoop performOnMainThreadWaiting:NO withBlock:^(){
                AWT_ASSERT_APPKIT_THREAD;
                [layer setNeedsDisplay];
            }];
        }
    }
#if USE_INTERMEDIATE_BUFFER
    OGLSDOps *dstOps = OGLRenderQueue_GetCurrentDestination();
    if (dstOps != NULL) {
        [JNFRunLoop performOnMainThreadWaiting:NO withBlock:^(){
            AWT_ASSERT_APPKIT_THREAD;
            CGLSDOps *dstCGLOps = (CGLSDOps *)dstOps->privOps;
            AWTView *view = dstCGLOps->peerData;
            [view.cglLayer setNeedsDisplay];
#ifdef REMOTELAYER
            /* If there's a remote layer (being used for testing)
             * then we want to have that also receive the texture.
             * First sync. up its dimensions with that of the layer
             * we have attached to the local window and tell it that
             * it also needs to copy the texture.
             */
             CGLLayer* cglLayer = (CGLLayer*)view.cglLayer;
             if (cglLayer.remoteLayer != nil) {
                 CGLLayer* remoteLayer = cglLayer.remoteLayer;
                 remoteLayer.target = GL_TEXTURE_2D;
                 remoteLayer.textureID = cglLayer.textureID;
                 remoteLayer.textureWidth = cglLayer.textureWidth;
                 remoteLayer.textureHeight = cglLayer.textureHeight;
                 [remoteLayer setNeedsDisplay];
            }
#endif /* REMOTELAYER */
        }];
    }
#endif
}

#pragma mark -
#pragma mark "--- CGLSurfaceData methods ---"

extern LockFunc        OGLSD_Lock;
extern GetRasInfoFunc  OGLSD_GetRasInfo;
extern UnlockFunc      OGLSD_Unlock;
extern DisposeFunc     OGLSD_Dispose;

JNIEXPORT void JNICALL
Java_sun_java2d_opengl_CGLSurfaceData_initOps
    (JNIEnv *env, jobject cglsd,
     jlong pConfigInfo, jlong pPeerData, jlong layerPtr,
     jint xoff, jint yoff, jboolean isOpaque)
{
    J2dTraceLn(J2D_TRACE_INFO, "CGLSurfaceData_initOps");
    J2dTraceLn1(J2D_TRACE_INFO, "  pPeerData=%p", jlong_to_ptr(pPeerData));
    J2dTraceLn2(J2D_TRACE_INFO, "  xoff=%d, yoff=%d", (int)xoff, (int)yoff);

    OGLSDOps *oglsdo = (OGLSDOps *)
        SurfaceData_InitOps(env, cglsd, sizeof(OGLSDOps));
    CGLSDOps *cglsdo = (CGLSDOps *)malloc(sizeof(CGLSDOps));
    if (cglsdo == NULL) {
        JNU_ThrowOutOfMemoryError(env, "creating native cgl ops");
        return;
    }

    oglsdo->privOps = cglsdo;

    oglsdo->sdOps.Lock               = OGLSD_Lock;
    oglsdo->sdOps.GetRasInfo         = OGLSD_GetRasInfo;
    oglsdo->sdOps.Unlock             = OGLSD_Unlock;
    oglsdo->sdOps.Dispose            = OGLSD_Dispose;

    oglsdo->drawableType = OGLSD_UNDEFINED;
    oglsdo->activeBuffer = GL_FRONT;
    oglsdo->needsInit = JNI_TRUE;
    oglsdo->xOffset = xoff;
    oglsdo->yOffset = yoff;
    oglsdo->isOpaque = isOpaque;

    cglsdo->peerData = (AWTView *)jlong_to_ptr(pPeerData);
    cglsdo->layer = (CGLLayer *)jlong_to_ptr(layerPtr);    
    cglsdo->configInfo = (CGLGraphicsConfigInfo *)jlong_to_ptr(pConfigInfo);
    
    if (cglsdo->configInfo == NULL) {
        free(cglsdo);
        JNU_ThrowNullPointerException(env, "Config info is null in initOps");
    }
}

JNIEXPORT void JNICALL
Java_sun_java2d_opengl_CGLSurfaceData_clearWindow
(JNIEnv *env, jobject cglsd)
{
    J2dTraceLn(J2D_TRACE_INFO, "CGLSurfaceData_clearWindow");

    OGLSDOps *oglsdo = (OGLSDOps*) SurfaceData_GetOps(env, cglsd);
    CGLSDOps *cglsdo = (CGLSDOps*) oglsdo->privOps;

    cglsdo->peerData = NULL;
    cglsdo->layer = NULL;
}

JNIEXPORT jboolean JNICALL
Java_sun_java2d_opengl_CGLSurfaceData_initPbuffer
    (JNIEnv *env, jobject cglsd,
     jlong pData, jlong pConfigInfo, jboolean isOpaque,
     jint width, jint height)
{
    J2dTraceLn3(J2D_TRACE_INFO, "CGLSurfaceData_initPbuffer: w=%d h=%d opq=%d", width, height, isOpaque);

    OGLSDOps *oglsdo = (OGLSDOps *)jlong_to_ptr(pData);
    if (oglsdo == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "CGLSurfaceData_initPbuffer: ops are null");
        return JNI_FALSE;
    }

    CGLSDOps *cglsdo = (CGLSDOps *)oglsdo->privOps;
    if (cglsdo == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "CGLSurfaceData_initPbuffer: cgl ops are null");
        return JNI_FALSE;
    }

    CGLGraphicsConfigInfo *cglInfo = (CGLGraphicsConfigInfo *)
        jlong_to_ptr(pConfigInfo);
    if (cglInfo == NULL) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "CGLSurfaceData_initPbuffer: cgl config info is null");
        return JNI_FALSE;
    }

    // find the maximum allowable texture dimensions (this value ultimately
    // determines our maximum pbuffer size)
    int pbMax = 0;
    j2d_glGetIntegerv(GL_MAX_TEXTURE_SIZE, &pbMax);

    int pbWidth = 0;
    int pbHeight = 0;
    if (OGLC_IS_CAP_PRESENT(cglInfo->context, CAPS_TEXNONPOW2)) {
        // use non-power-of-two dimensions directly
        pbWidth = (width <= pbMax) ? width : 0;
        pbHeight = (height <= pbMax) ? height : 0;
    } else {
        // find the appropriate power-of-two dimensions
        pbWidth = OGLSD_NextPowerOfTwo(width, pbMax);
        pbHeight = OGLSD_NextPowerOfTwo(height, pbMax);
    }

    J2dTraceLn3(J2D_TRACE_VERBOSE, "  desired pbuffer dimensions: w=%d h=%d max=%d", pbWidth, pbHeight, pbMax);

    // if either dimension is 0, we cannot allocate a pbuffer/texture with the
    // requested dimensions
    if (pbWidth == 0 || pbHeight == 0) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "CGLSurfaceData_initPbuffer: dimensions too large");
        return JNI_FALSE;
    }

    int format = isOpaque ? GL_RGB : GL_RGBA;

JNF_COCOA_ENTER(env);

    cglsdo->pbuffer =
        [[NSOpenGLPixelBuffer alloc]
            initWithTextureTarget: GL_TEXTURE_2D
            textureInternalFormat: format
            textureMaxMipMapLevel: 0
            pixelsWide: pbWidth
            pixelsHigh: pbHeight];
    if (cglsdo->pbuffer == nil) {
        J2dRlsTraceLn(J2D_TRACE_ERROR, "CGLSurfaceData_initPbuffer: could not create pbuffer");
        return JNI_FALSE;
    }

    // make sure the actual dimensions match those that we requested
    GLsizei actualWidth  = [cglsdo->pbuffer pixelsWide];
    GLsizei actualHeight = [cglsdo->pbuffer pixelsHigh];
    if (actualWidth != pbWidth || actualHeight != pbHeight) {
        J2dRlsTraceLn2(J2D_TRACE_ERROR, "CGLSurfaceData_initPbuffer: actual (w=%d h=%d) != requested", actualWidth, actualHeight);
        [cglsdo->pbuffer release];
        return JNI_FALSE;
    }

    GLuint texID = 0;
    j2d_glGenTextures(1, &texID);
    j2d_glBindTexture(GL_TEXTURE_2D, texID);

    oglsdo->drawableType = OGLSD_PBUFFER;
    oglsdo->isOpaque = isOpaque;
    oglsdo->width = width;
    oglsdo->height = height;
    oglsdo->textureID = texID;
    oglsdo->textureWidth = pbWidth;
    oglsdo->textureHeight = pbHeight;
    oglsdo->activeBuffer = GL_FRONT;
    oglsdo->needsInit = JNI_TRUE;

    OGLSD_INIT_TEXTURE_FILTER(oglsdo, GL_NEAREST);

JNF_COCOA_EXIT(env);

    return JNI_TRUE;
}

#pragma mark -
#pragma mark "--- CGLSurfaceData methods - Mac OS X specific ---"

// Must be called on the QFT...
JNIEXPORT void JNICALL
Java_sun_java2d_opengl_CGLSurfaceData_validate
    (JNIEnv *env, jobject jsurfacedata,
     jint xoff, jint yoff, jint width, jint height, jboolean isOpaque)
{
    J2dTraceLn2(J2D_TRACE_INFO, "CGLSurfaceData_validate: w=%d h=%d", width, height);

    OGLSDOps *oglsdo = (OGLSDOps*)SurfaceData_GetOps(env, jsurfacedata);
    oglsdo->needsInit = JNI_TRUE;
    oglsdo->xOffset = xoff;
    oglsdo->yOffset = yoff;

    BOOL newSize = (oglsdo->width != width || oglsdo->height != height);
    BOOL newOpaque = (oglsdo->isOpaque != isOpaque);

    oglsdo->width = width;
    oglsdo->height = height;
    oglsdo->isOpaque = isOpaque;

    if (oglsdo->drawableType == OGLSD_WINDOW) {
JNF_COCOA_ENTER(env);
#ifdef USE_INTERMEDIATE_BUFFER
        if (newSize || newOpaque) {
#ifdef USE_IOS
            RecreateIOSBuffer(env, oglsdo);
#else
            RecreateBuffer(env, oglsdo);
#endif
        }
#endif
JNF_COCOA_EXIT(env);

        OGLContext_SetSurfaces(env, ptr_to_jlong(oglsdo), ptr_to_jlong(oglsdo));

        // we have to explicitly tell the NSOpenGLContext that its target
        // drawable has changed size
        CGLSDOps *cglsdo = (CGLSDOps *)oglsdo->privOps;
        OGLContext *oglc = cglsdo->configInfo->context;
        CGLCtxInfo *ctxinfo = (CGLCtxInfo *)oglc->ctxInfo;

JNF_COCOA_ENTER(env);
#ifndef USE_INTERMEDIATE_BUFFER
        [ctxinfo->context update];
#endif
JNF_COCOA_EXIT(env);
    }
}
