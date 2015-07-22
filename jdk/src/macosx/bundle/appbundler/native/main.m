/*
 * Copyright 2012, Oracle and/or its affiliates. All rights reserved.
 *
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

#import <Cocoa/Cocoa.h>
#include <jni.h>

#define JAVA_LAUNCH_ERROR "JavaLaunchError"

#define JAVA_VM_KEY "JavaVM"
#define RUNTIME_KEY "Runtime"
#define MAIN_CLASS_NAME_KEY "MainClassName"
#define OPTIONS_KEY "Options"
#define ARGUMENTS_KEY "Arguments"

// TODO Remove these; they are defined by the makefile
#define FULL_VERSION "1.7.0"
#define DOT_VERSION "1.7.0"
#define DEFAULT_POLICY 0

typedef int (JNICALL *JLI_Launch_t)(int argc, char ** argv,
                                    int jargc, const char** jargv,
                                    int appclassc, const char** appclassv,
                                    const char* fullversion,
                                    const char* dotversion,
                                    const char* pname,
                                    const char* lname,
                                    jboolean javaargs,
                                    jboolean cpwildcard,
                                    jboolean javaw,
                                    jint ergo);

int launch(char *);
int jli_launch(char *, NSURL *, NSString *, NSString *, NSString *, NSArray *, NSArray *);

int main(int argc, char *argv[]) {
    NSAutoreleasePool *pool = [[NSAutoreleasePool alloc] init];

    int result;
    @try {
        launch(argv[0]);
        result = 0;
    } @catch (NSException *exception) {
        NSLog(@"%@: %@", exception, [exception callStackSymbols]);
        result = 1;
    }

    [pool drain];

    return result;
}

int launch(char *commandName) {
    // Get the main bundle
    NSBundle *mainBundle = [NSBundle mainBundle];
    NSDictionary *infoDictionary = [mainBundle infoDictionary];

    NSString *javaVMPath = [[mainBundle bundlePath] stringByAppendingString:@"/Contents/JavaVM"];

    // Get the Java dictionary
    NSDictionary *javaVMDictionary = [infoDictionary objectForKey:@JAVA_VM_KEY];
    if (javaVMDictionary == nil) {
        [NSException raise:@JAVA_LAUNCH_ERROR format:@"%@ is required.", @JAVA_VM_KEY];
    }

    // Get the runtime bundle URL
    NSString *runtime = [javaVMDictionary objectForKey:@RUNTIME_KEY];

    // TODO If unspecified, use default runtime location

    NSURL *runtimeBundleURL = [[mainBundle builtInPlugInsURL] URLByAppendingPathComponent:runtime];

    // Get the main class name
    NSString *mainClassName = [javaVMDictionary objectForKey:@MAIN_CLASS_NAME_KEY];
    if (mainClassName == nil) {
        [NSException raise:@JAVA_LAUNCH_ERROR format:@"%@ is required.", @MAIN_CLASS_NAME_KEY];
    }

    // Set the class path
    NSString *classPathFormat = @"-Djava.class.path=%@/Classes";
    NSMutableString *classPath = [[NSString stringWithFormat:classPathFormat, javaVMPath] mutableCopy];

    NSFileManager *defaultFileManager = [NSFileManager defaultManager];
    NSArray *javaDirectoryContents = [defaultFileManager contentsOfDirectoryAtPath:javaVMPath error:nil];
    if (javaDirectoryContents == nil) {
        [NSException raise:@JAVA_LAUNCH_ERROR format:@"Could not enumerate Java directory contents."];
    }

    for (NSString *file in javaDirectoryContents) {
        if ([file hasSuffix:@".jar"]) {
            [classPath appendFormat:@":%@/%@", javaVMPath, file];
        }
    }

    // Set the library path
    NSString *libraryPathFormat = @"-Djava.library.path=%@";
    NSString *libraryPath = [NSString stringWithFormat:libraryPathFormat, javaVMPath];

    // Get the VM options
    NSArray *options = [javaVMDictionary objectForKey:@OPTIONS_KEY];
    if (options == nil) {
        options = [NSArray array];
    }

    // Get the application arguments
    NSArray *arguments = [javaVMDictionary objectForKey:@ARGUMENTS_KEY];
    if (arguments == nil) {
        arguments = [NSArray array];
    }

    return jli_launch(commandName, runtimeBundleURL,
                      mainClassName, classPath, libraryPath,
                      options, arguments);
}

int jli_launch(char *commandName, NSURL *runtimeBundleURL,
               NSString *mainClassName, NSString *classPath, NSString *libraryPath,
               NSArray *options, NSArray *arguments) {
    // Load the runtime bundle
    CFBundleRef runtimeBundle = CFBundleCreate(NULL, (CFURLRef)runtimeBundleURL);

    NSError *bundleLoadError = nil;
    Boolean runtimeBundleLoaded = CFBundleLoadExecutableAndReturnError(runtimeBundle, (CFErrorRef *)&bundleLoadError);
    if (bundleLoadError != nil || !runtimeBundleLoaded) {
        [NSException raise:@JAVA_LAUNCH_ERROR format:@"Could not load JRE from %@.", bundleLoadError];
    }

    // Get the JLI_Launch() function pointer
    JLI_Launch_t JLI_LaunchFxnPtr = CFBundleGetFunctionPointerForName(runtimeBundle, CFSTR("JLI_Launch"));
    if (JLI_LaunchFxnPtr == NULL) {
        [NSException raise:@JAVA_LAUNCH_ERROR format:@"Could not get function pointer for JLI_Launch."];
    }

    // Initialize the arguments to JLI_Launch()
    int argc = 1 + [options count] + 2 + [arguments count] + 1;
    char *argv[argc];

    int i = 0;
    argv[i++] = commandName;
    argv[i++] = strdup([classPath UTF8String]);
    argv[i++] = strdup([libraryPath UTF8String]);

    for (NSString *option in options) {
        argv[i++] = strdup([option UTF8String]);
    }

    argv[i++] = strdup([mainClassName UTF8String]);

    for (NSString *argument in arguments) {
        argv[i++] = strdup([argument UTF8String]);
    }

    // Invoke JLI_Launch()
    return JLI_LaunchFxnPtr(argc, argv,
                            0, NULL,
                            0, NULL,
                            FULL_VERSION,
                            DOT_VERSION,
                            "java",
                            "java",
                            FALSE,
                            FALSE,
                            FALSE,
                            DEFAULT_POLICY);
}
