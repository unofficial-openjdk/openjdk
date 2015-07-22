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

package com.oracle.appbundler;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.types.FileSet;

/**
 * App bundler Ant task.
 */
public class AppBundlerTask extends Task {
    // Output folder for generated bundle
    private File outputDirectory = null;

    // General bundle properties
    private String name = null;
    private String displayName = null;
    private String identifier = null;
    private File icon = null;

    private String shortVersion = "1.0";
    private String signature = "????";
    private String copyright = "";

    // JVM info properties
    private File runtime = null;
    private String mainClassName = null;
    private ArrayList<File> classPath = new ArrayList<>();
    private ArrayList<File> nativeLibraries = new ArrayList<>();
    private ArrayList<String> options = new ArrayList<>();
    private ArrayList<String> arguments = new ArrayList<>();

    public static final String EXECUTABLE_NAME = "JavaAppLauncher";
    public static final String DEFAULT_ICON_NAME = "GenericApp.icns";
    public static final String OS_TYPE_CODE = "APPL";
    public static final String CLASS_EXTENSION = ".class";

    public static final String PLIST_DTD = "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">";
    public static final String PLIST_TAG = "plist";
    public static final String PLIST_VERSION_ATTRIBUTE = "version";
    public static final String DICT_TAG = "dict";
    public static final String KEY_TAG = "key";
    public static final String ARRAY_TAG = "array";
    public static final String STRING_TAG = "string";

    public static final int BUFFER_SIZE = 1024;

    public void setOutputDirectory(File outputDirectory) {
        this.outputDirectory = outputDirectory;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public void setIcon(File icon) {
        this.icon = icon;
    }

    public void setShortVersion(String shortVersion) {
        this.shortVersion = shortVersion;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public void setCopyright(String copyright) {
        this.copyright = copyright;
    }

    public File getRuntime() {
        return runtime;
    }

    public void setRuntime(File runtime) {
        this.runtime = runtime;
    }

    public void setMainClassName(String mainClassName) {
        this.mainClassName = mainClassName;
    }

    public void addConfiguredClassPath(FileSet classPath) {
        File parent = classPath.getDir();

        DirectoryScanner directoryScanner = classPath.getDirectoryScanner(getProject());
        String[] includedFiles = directoryScanner.getIncludedFiles();

        for (int i = 0; i < includedFiles.length; i++) {
            this.classPath.add(new File(parent, includedFiles[i]));
        }
    }

    public void addNativeLibrary(File nativeLibrary) throws BuildException {
        if (nativeLibrary.isDirectory()) {
            throw new BuildException("Native library cannot be a directory.");
        }

        nativeLibraries.add(nativeLibrary);
    }

    public void addConfiguredOption(Option option) throws BuildException {
        String value = option.getValue();

        if (value == null) {
            throw new BuildException("Value is required.");
        }

        options.add(value);
    }

    public void addConfiguredArgument(Argument argument) throws BuildException {
        String value = argument.getValue();

        if (value == null) {
            throw new BuildException("Value is required.");
        }

        arguments.add(value);
    }

    @Override
    public void execute() throws BuildException {
        // Validate required properties
        if (outputDirectory == null) {
            throw new IllegalStateException("Destination directory is required.");
        }

        if (!outputDirectory.exists()) {
            throw new IllegalStateException("Destination directory does not exist.");
        }

        if (!outputDirectory.isDirectory()) {
            throw new IllegalStateException("Invalid destination directory.");
        }

        if (name == null) {
            throw new IllegalStateException("Name is required.");
        }

        if (displayName == null) {
            throw new IllegalStateException("Display name is required.");
        }

        if (identifier == null) {
            throw new IllegalStateException("Identifier is required.");
        }

        if (icon != null) {
            if (!icon.exists()) {
                throw new IllegalStateException("Icon does not exist.");
            }

            if (icon.isDirectory()) {
                throw new IllegalStateException("Invalid icon.");
            }
        }

        if (shortVersion == null) {
            throw new IllegalStateException("Short version is required.");
        }

        if (signature == null) {
            throw new IllegalStateException("Signature is required.");
        }

        if (signature.length() != 4) {
            throw new IllegalStateException("Invalid signature.");
        }

        if (copyright == null) {
            throw new IllegalStateException("Copyright is required.");
        }

        if (runtime != null) {
            if (!runtime.exists()) {
                throw new IllegalStateException("Runtime does not exist.");
            }

            if (!runtime.isDirectory()) {
                throw new IllegalStateException("Invalid runtime.");
            }
        }

        if (mainClassName == null) {
            throw new IllegalStateException("Main class name is required.");
        }

        if (classPath.isEmpty()) {
            throw new IllegalStateException("Class path is required.");
        }

        // Create directory structure
        try {
            System.out.println("Creating app bundle: " + name);

            File rootDirectory = new File(outputDirectory, name + ".app");
            delete(rootDirectory);
            rootDirectory.mkdir();

            File contentsDirectory = new File(rootDirectory, "Contents");
            contentsDirectory.mkdir();

            File macOSDirectory = new File(contentsDirectory, "MacOS");
            macOSDirectory.mkdir();

            File javaDirectory = new File(contentsDirectory, "JavaVM");
            javaDirectory.mkdir();

            File classesDirectory = new File(javaDirectory, "Classes");
            classesDirectory.mkdir();

            File plugInsDirectory = new File(contentsDirectory, "PlugIns");
            plugInsDirectory.mkdir();

            File resourcesDirectory = new File(contentsDirectory, "Resources");
            resourcesDirectory.mkdir();

            // Generate Info.plist
            File infoPlistFile = new File(contentsDirectory, "Info.plist");
            infoPlistFile.createNewFile();
            writeInfoPlist(infoPlistFile);

            // Generate PkgInfo
            File pkgInfoFile = new File(contentsDirectory, "PkgInfo");
            pkgInfoFile.createNewFile();
            writePkgInfo(pkgInfoFile);

            // Copy executable to MacOS folder
            File executableFile = new File(macOSDirectory, EXECUTABLE_NAME);
            copy(getClass().getResource(EXECUTABLE_NAME), executableFile);

            executableFile.setExecutable(true);

            // Copy runtime to PlugIns folder (if specified)
            if (runtime != null) {
                copy(runtime, new File(plugInsDirectory, runtime.getName()));
            }

            // Copy class path entries to Java folder
            for (File entry : classPath) {
                String name = entry.getName();

                if (entry.isDirectory() || name.endsWith(CLASS_EXTENSION)) {
                    copy(entry, new File(classesDirectory, name));
                } else {
                    copy(entry, new File(javaDirectory, name));
                }
            }

            // Copy native libraries to Java folder
            for (File nativeLibrary : nativeLibraries) {
                copy(nativeLibrary, new File(javaDirectory, nativeLibrary.getName()));
            }

            // Copy icon to Resources folder
            if (icon == null) {
                copy(getClass().getResource(DEFAULT_ICON_NAME), new File(resourcesDirectory,
                    DEFAULT_ICON_NAME));
            } else {
                copy(icon, new File(resourcesDirectory, icon.getName()));
            }
        } catch (IOException exception) {
            throw new BuildException(exception);
        }
    }

    private void writeInfoPlist(File file) throws IOException {
        Writer out = new BufferedWriter(new FileWriter(file));
        XMLOutputFactory output = XMLOutputFactory.newInstance();

        try {
            XMLStreamWriter xout = output.createXMLStreamWriter(out);

            // Write XML declaration
            xout.writeStartDocument();
            xout.writeCharacters("\n");

            // Write plist DTD declaration
            xout.writeDTD(PLIST_DTD);
            xout.writeCharacters("\n");

            // Begin root element
            xout.writeStartElement(PLIST_TAG);
            xout.writeAttribute(PLIST_VERSION_ATTRIBUTE, "1.0");
            xout.writeCharacters("\n");

            // Begin root dictionary
            xout.writeStartElement(DICT_TAG);
            xout.writeCharacters("\n");

            // Write bundle properties
            writeProperty(xout, "CFBundleDevelopmentRegion", "English");
            writeProperty(xout, "CFBundleExecutable", EXECUTABLE_NAME);
            writeProperty(xout, "CFBundleIconFile", (icon == null) ? DEFAULT_ICON_NAME : icon.getName());
            writeProperty(xout, "CFBundleIdentifier", identifier);
            writeProperty(xout, "CFBundleDisplayName", displayName);
            writeProperty(xout, "CFBundleInfoDictionaryVersion", "6.0");
            writeProperty(xout, "CFBundleName", name);
            writeProperty(xout, "CFBundlePackageType", OS_TYPE_CODE);
            writeProperty(xout, "CFBundleShortVersionString", shortVersion);
            writeProperty(xout, "CFBundleSignature", signature);
            writeProperty(xout, "CFBundleVersion", "1");
            writeProperty(xout, "NSHumanReadableCopyright", copyright);

            // Start Java properties
            writeKey(xout, "JavaVM");
            xout.writeStartElement(DICT_TAG);

            // Write runtime
            writeProperty(xout, "Runtime", runtime.getName());

            // Write main class name
            writeProperty(xout, "MainClassName", mainClassName);

            // Write options
            writeKey(xout, "Options");

            xout.writeStartElement(ARRAY_TAG);
            xout.writeCharacters("\n");

            for (String option : options) {
                writeString(xout, option);
            }

            xout.writeEndElement();
            xout.writeCharacters("\n");

            // Write arguments
            writeKey(xout, "Arguments");

            xout.writeStartElement(ARRAY_TAG);
            xout.writeCharacters("\n");

            for (String argument : arguments) {
                writeString(xout, argument);
            }

            xout.writeEndElement();
            xout.writeCharacters("\n");

            // End Java properties
            xout.writeEndElement();
            xout.writeCharacters("\n");

            // End root dictionary
            xout.writeEndElement();
            xout.writeCharacters("\n");

            // End root element
            xout.writeEndElement();
            xout.writeCharacters("\n");

            // Close document
            xout.writeEndDocument();
            xout.writeCharacters("\n");

            out.flush();
        } catch (XMLStreamException exception) {
            throw new IOException(exception);
        } finally {
            out.close();
        }
    }

    private void writeKey(XMLStreamWriter xout, String key) throws XMLStreamException {
        xout.writeStartElement(KEY_TAG);
        xout.writeCharacters(key);
        xout.writeEndElement();
        xout.writeCharacters("\n");
    }

    private void writeString(XMLStreamWriter xout, String value) throws XMLStreamException {
        xout.writeStartElement(STRING_TAG);
        xout.writeCharacters(value);
        xout.writeEndElement();
        xout.writeCharacters("\n");
    }

    private void writeProperty(XMLStreamWriter xout, String key, String value) throws XMLStreamException {
        writeKey(xout, key);
        writeString(xout, value);
    }

    private void writePkgInfo(File file) throws IOException {
        Writer out = new BufferedWriter(new FileWriter(file));

        try {
            out.write(OS_TYPE_CODE + signature);
            out.flush();
        } finally {
            out.close();
        }
    }

    private static void delete(File file) throws IOException {
        Path filePath = file.toPath();

        if (Files.exists(filePath, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isDirectory(filePath, LinkOption.NOFOLLOW_LINKS)) {
                File[] files = file.listFiles();

                for (int i = 0; i < files.length; i++) {
                    delete(files[i]);
                }
            }

            Files.delete(filePath);
        }
    }

    private static void copy(URL location, File file) throws IOException {
        try (InputStream in = location.openStream()) {
            Files.copy(in, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void copy(File source, File destination) throws IOException {
        Path sourcePath = source.toPath();
        Path destinationPath = destination.toPath();

        Files.copy(sourcePath, destinationPath, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);

        if (Files.isDirectory(sourcePath, LinkOption.NOFOLLOW_LINKS)) {
            String[] files = source.list();

            for (int i = 0; i < files.length; i++) {
                String file = files[i];
                copy(new File(source, file), new File(destination, file));
            }
        }
    }
}
