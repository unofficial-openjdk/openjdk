/*
 * Copyright (c) 2003, 2006, Oracle and/or its affiliates. All rights reserved.
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

package sun.awt.shell;

import java.awt.Image;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.security.AccessController;
import java.security.PrivilegedAction;
import javax.swing.SwingConstants;

// NOTE: This class supersedes Win32ShellFolder, which was removed from
//       distribution after version 1.4.2.

/**
 * Win32 Shell Folders
 * <P>
 * <BR>
 * There are two fundamental types of shell folders : file system folders
 * and non-file system folders.  File system folders are relatively easy
 * to deal with.  Non-file system folders are items such as My Computer,
 * Network Neighborhood, and the desktop.  Some of these non-file system
 * folders have special values and properties.
 * <P>
 * <BR>
 * Win32 keeps two basic data structures for shell folders.  The first
 * of these is called an ITEMIDLIST.  Usually a pointer, called an
 * LPITEMIDLIST, or more frequently just "PIDL".  This structure holds
 * a series of identifiers and can be either relative to the desktop
 * (an absolute PIDL), or relative to the shell folder that contains them.
 * Some Win32 functions can take absolute or relative PIDL values, and
 * others can only accept relative values.
 * <BR>
 * The second data structure is an IShellFolder COM interface.  Using
 * this interface, one can enumerate the relative PIDLs in a shell
 * folder, get attributes, etc.
 * <BR>
 * All Win32ShellFolder2 objects which are folder types (even non-file
 * system folders) contain an IShellFolder object. Files are named in
 * directories via relative PIDLs.
 *
 * @author Michael Martak
 * @author Leif Samuelsson
 * @author Kenneth Russell
 * @since 1.4 */

final class Win32ShellFolder2 extends ShellFolder {

    private static native void initIDs();

    private static final boolean is98;

    static {
        String osName = System.getProperty("os.name");
        is98 = (osName != null && osName.startsWith("Windows 98"));

        initIDs();
    }

    // Win32 Shell Folder Constants
    public static final int DESKTOP = 0x0000;
    public static final int INTERNET = 0x0001;
    public static final int PROGRAMS = 0x0002;
    public static final int CONTROLS = 0x0003;
    public static final int PRINTERS = 0x0004;
    public static final int PERSONAL = 0x0005;
    public static final int FAVORITES = 0x0006;
    public static final int STARTUP = 0x0007;
    public static final int RECENT = 0x0008;
    public static final int SENDTO = 0x0009;
    public static final int BITBUCKET = 0x000a;
    public static final int STARTMENU = 0x000b;
    public static final int DESKTOPDIRECTORY = 0x0010;
    public static final int DRIVES = 0x0011;
    public static final int NETWORK = 0x0012;
    public static final int NETHOOD = 0x0013;
    public static final int FONTS = 0x0014;
    public static final int TEMPLATES = 0x0015;
    public static final int COMMON_STARTMENU = 0x0016;
    public static final int COMMON_PROGRAMS = 0X0017;
    public static final int COMMON_STARTUP = 0x0018;
    public static final int COMMON_DESKTOPDIRECTORY = 0x0019;
    public static final int APPDATA = 0x001a;
    public static final int PRINTHOOD = 0x001b;
    public static final int ALTSTARTUP = 0x001d;
    public static final int COMMON_ALTSTARTUP = 0x001e;
    public static final int COMMON_FAVORITES = 0x001f;
    public static final int INTERNET_CACHE = 0x0020;
    public static final int COOKIES = 0x0021;
    public static final int HISTORY = 0x0022;

    // Win32 shell folder attributes
    public static final int ATTRIB_CANCOPY          = 0x00000001;
    public static final int ATTRIB_CANMOVE          = 0x00000002;
    public static final int ATTRIB_CANLINK          = 0x00000004;
    public static final int ATTRIB_CANRENAME        = 0x00000010;
    public static final int ATTRIB_CANDELETE        = 0x00000020;
    public static final int ATTRIB_HASPROPSHEET     = 0x00000040;
    public static final int ATTRIB_DROPTARGET       = 0x00000100;
    public static final int ATTRIB_LINK             = 0x00010000;
    public static final int ATTRIB_SHARE            = 0x00020000;
    public static final int ATTRIB_READONLY         = 0x00040000;
    public static final int ATTRIB_GHOSTED          = 0x00080000;
    public static final int ATTRIB_HIDDEN           = 0x00080000;
    public static final int ATTRIB_FILESYSANCESTOR  = 0x10000000;
    public static final int ATTRIB_FOLDER           = 0x20000000;
    public static final int ATTRIB_FILESYSTEM       = 0x40000000;
    public static final int ATTRIB_HASSUBFOLDER     = 0x80000000;
    public static final int ATTRIB_VALIDATE         = 0x01000000;
    public static final int ATTRIB_REMOVABLE        = 0x02000000;
    public static final int ATTRIB_COMPRESSED       = 0x04000000;
    public static final int ATTRIB_BROWSABLE        = 0x08000000;
    public static final int ATTRIB_NONENUMERATED    = 0x00100000;
    public static final int ATTRIB_NEWCONTENT       = 0x00200000;

    // IShellFolder::GetDisplayNameOf constants
    public static final int SHGDN_NORMAL            = 0;
    public static final int SHGDN_INFOLDER          = 1;
    public static final int SHGDN_INCLUDE_NONFILESYS= 0x2000;
    public static final int SHGDN_FORADDRESSBAR     = 0x4000;
    public static final int SHGDN_FORPARSING        = 0x8000;

    // Values for system call LoadIcon()
    public enum SystemIcon {
        IDI_APPLICATION(32512),
        IDI_HAND(32513),
        IDI_ERROR(32513),
        IDI_QUESTION(32514),
        IDI_EXCLAMATION(32515),
        IDI_WARNING(32515),
        IDI_ASTERISK(32516),
        IDI_INFORMATION(32516),
        IDI_WINLOGO(32517);

        private final int iconID;

        SystemIcon(int iconID) {
            this.iconID = iconID;
        }

        public int getIconID() {
            return iconID;
        }
    }

    static class FolderDisposer implements sun.java2d.DisposerRecord {
        /*
         * This is cached as a concession to getFolderType(), which needs
         * an absolute PIDL.
         */
        long absolutePIDL;
        /*
         * We keep track of shell folders through the IShellFolder
         * interface of their parents plus their relative PIDL.
         */
        long pIShellFolder;
        long relativePIDL;

        boolean disposed;
        public void dispose() {
            if (disposed) return;
            new ComTask<Void>() {
                public Void call() throws Exception {
                    if (relativePIDL != 0) {
                        releasePIDL(relativePIDL);
                    }
                    if (absolutePIDL != 0) {
                        releasePIDL(absolutePIDL);
                    }
                    if (pIShellFolder != 0) {
                        releaseIShellFolder(pIShellFolder);
                    }
                    return null;
                }
            }.execute();
            disposed = true;
        }
    }
    FolderDisposer disposer = new FolderDisposer();
    private void setIShellFolder(long pIShellFolder) {
        disposer.pIShellFolder = pIShellFolder;
    }
    private void setRelativePIDL(long relativePIDL) {
        disposer.relativePIDL = relativePIDL;
    }
    /*
     * The following are for caching various shell folder properties.
     */
    private long pIShellIcon = -1L;
    private String folderType = null;
    private String displayName = null;
    private Image smallIcon = null;
    private Image largeIcon = null;
    private Boolean isDir = null;

    /*
     * The following is to identify the My Documents folder as being special
     */
    private boolean isPersonal;


    private static String composePathForCsidl(int csidl) throws IOException {
        String path = getFileSystemPath(csidl);
        return path == null
                ? ("ShellFolder: 0x" + Integer.toHexString(csidl))
                : path;
    }

    /**
     * Create a system special shell folder, such as the
     * desktop or Network Neighborhood.
     */
    Win32ShellFolder2(final int csidl) throws IOException {
        // Desktop is parent of DRIVES and NETWORK, not necessarily
        // other special shell folders.
        super(null, composePathForCsidl(csidl));
        new ComTask<Void>() {
            public Void call() throws Exception {
                if (csidl == DESKTOP) {
                    initDesktop();
                } else {
                    initSpecial(getDesktop().getIShellFolder(), csidl);
                    // At this point, the native method initSpecial() has set our relativePIDL
                    // relative to the Desktop, which may not be our immediate parent. We need
                    // to traverse this ID list and break it into a chain of shell folders from
                    // the top, with each one having an immediate parent and a relativePIDL
                    // relative to that parent.
                    long pIDL = disposer.relativePIDL;
                    parent = getDesktop();
                    while (pIDL != 0) {
                        // Get a child pidl relative to 'parent'
                        long childPIDL = copyFirstPIDLEntry(pIDL);
                        if (childPIDL != 0) {
                            // Get a handle to the the rest of the ID list
                            // i,e, parent's grandchilren and down
                            pIDL = getNextPIDLEntry(pIDL);
                            if (pIDL != 0) {
                                // Now we know that parent isn't immediate to 'this' because it
                                // has a continued ID list. Create a shell folder for this child
                                // pidl and make it the new 'parent'.
                                parent = new Win32ShellFolder2((Win32ShellFolder2)parent, childPIDL);
                            } else {
                                // No grandchildren means we have arrived at the parent of 'this',
                                // and childPIDL is directly relative to parent.
                                disposer.relativePIDL = childPIDL;
                            }
                        } else {
                            break;
                        }
                    }
                }
                return null;
            }
        }.execute();

        sun.java2d.Disposer.addRecord(this, disposer);
    }


    /**
     * Create a system shell folder
     */
    Win32ShellFolder2(Win32ShellFolder2 parent, long pIShellFolder, long relativePIDL, String path) {
        super(parent, (path != null) ? path : "ShellFolder: ");
        this.disposer.pIShellFolder = pIShellFolder;
        this.disposer.relativePIDL = relativePIDL;
        sun.java2d.Disposer.addRecord(this, disposer);
    }


    /**
     * Creates a shell folder with a parent and relative PIDL
     */
    Win32ShellFolder2(final Win32ShellFolder2 parent, final long relativePIDL) {
        super(parent,
                new ComTask<String>() {
                    public String call() throws Exception {
                        return getFileSystemPath(parent.getIShellFolder(), relativePIDL);
                    }
                }.execute()
        );
        this.disposer.relativePIDL = relativePIDL;
        getAbsolutePath();
        sun.java2d.Disposer.addRecord(this, disposer);
    }

    // Initializes the desktop shell folder
    /* NOTE: this method uses COM and must be called on the 'COM thread'. See ComTask for the details */
    private native void initDesktop();
    // Initializes a special, non-file system shell folder
    // from one of the above constants
    /* NOTE: this method uses COM and must be called on the 'COM thread'. See ComTask for the details */
    private native void initSpecial(long desktopIShellFolder, int csidl);

    /** Marks this folder as being the My Documents (Personal) folder */
    public void setIsPersonal() {
        isPersonal = true;
    }

    /**
     * This method is implemented to make sure that no instances
     * of <code>ShellFolder</code> are ever serialized. If <code>isFileSystem()</code> returns
     * <code>true</code>, then the object is representable with an instance of
     * <code>java.io.File</code> instead. If not, then the object depends
     * on native PIDL state and should not be serialized.
     *
     * @return a <code>java.io.File</code> replacement object. If the folder
     * is a not a normal directory, then returns the first non-removable
     * drive (normally "C:\").
     */
    protected Object writeReplace() throws java.io.ObjectStreamException {
        return new ComTask<File>() {
            public File call() throws Exception {
                if (isFileSystem()) {
                    return new File(getPath());
                } else {
                    Win32ShellFolder2 drives = Win32ShellFolderManager2.getDrives();
                    if (drives != null) {
                        File[] driveRoots = drives.listFiles();
                        if (driveRoots != null) {
                            for (int i = 0; i < driveRoots.length; i++) {
                                if (driveRoots[i] instanceof Win32ShellFolder2) {
                                    Win32ShellFolder2 sf = (Win32ShellFolder2)driveRoots[i];
                                    if (sf.isFileSystem() && !sf.hasAttribute(ATTRIB_REMOVABLE)) {
                                        return new File(sf.getPath());
                                    }
                                }
                            }
                        }
                    }
                    // Ouch, we have no hard drives. Return something "valid" anyway.
                    return new File("C:\\");
                }
            }
        }.execute();
    }


    /**
     * Finalizer to clean up any COM objects or PIDLs used by this object.
     */
    protected void dispose() {
        disposer.dispose();
    }


    // Given a (possibly multi-level) relative PIDL (with respect to
    // the desktop, at least in all of the usage cases in this code),
    // return a pointer to the next entry. Does not mutate the PIDL in
    // any way. Returns 0 if the null terminator is reached.
    // Needs to be accessible to Win32ShellFolderManager2
    static native long getNextPIDLEntry(long pIDL);

    // Given a (possibly multi-level) relative PIDL (with respect to
    // the desktop, at least in all of the usage cases in this code),
    // copy the first entry into a newly-allocated PIDL. Returns 0 if
    // the PIDL is at the end of the list.
    // Needs to be accessible to Win32ShellFolderManager2
    static native long copyFirstPIDLEntry(long pIDL);

    // Given a parent's absolute PIDL and our relative PIDL, build an absolute PIDL
    private static native long combinePIDLs(long ppIDL, long pIDL);

    // Release a PIDL object
    // Needs to be accessible to Win32ShellFolderManager2
    static native void releasePIDL(long pIDL);

    /* NOTE: this method uses COM and must be called on the 'COM thread'. See ComTask for the details */
    private static native void releaseIShellFolder(long pIShellFolder);

    /**
     * Accessor for IShellFolder
     */
    public long getIShellFolder() {
        if (disposer.pIShellFolder == 0) {
            disposer.pIShellFolder =
                new ComTask<Long>() {
                    public Long call() throws Exception {
                        assert(isDirectory());
                        assert(parent != null);
                        long parentIShellFolder = getParentIShellFolder();
                        if (parentIShellFolder == 0) {
                            throw new InternalError("Parent IShellFolder was null for "
                                    + getAbsolutePath());
                        }
                        // We are a directory with a parent and a relative PIDL.
                        // We want to bind to the parent so we get an
                        // IShellFolder instance associated with us.
                        long pIShellFolder = bindToObject(parentIShellFolder,
                                                        disposer.relativePIDL);
                        if (pIShellFolder == 0) {
                            throw new InternalError("Unable to bind "
                                    + getAbsolutePath() + " to parent");
                        }
                        return pIShellFolder;
                    }
                }.execute();
        }
        return disposer.pIShellFolder;
    }

    /**
     * Get the parent ShellFolder's IShellFolder interface
     */
    public long getParentIShellFolder() {
        Win32ShellFolder2 parent = (Win32ShellFolder2)getParentFile();
        if (parent == null) {
            // Parent should only be null if this is the desktop, whose
            // relativePIDL is relative to its own IShellFolder.
            return getIShellFolder();
        }
        return parent.getIShellFolder();
    }

    /**
     * Accessor for relative PIDL
     */
    public long getRelativePIDL() {
        if (disposer.relativePIDL == 0) {
            throw new InternalError("Should always have a relative PIDL");
        }
        return disposer.relativePIDL;
    }

    private long getAbsolutePIDL() {
        if (parent == null) {
            // This is the desktop
            return getRelativePIDL();
        } else {
            if (disposer.absolutePIDL == 0) {
                disposer.absolutePIDL = combinePIDLs(
                        ((Win32ShellFolder2)parent).getAbsolutePIDL(),
                        getRelativePIDL());
            }

            return disposer.absolutePIDL;
        }
    }

    /**
     * Helper function to return the desktop
     */
    public Win32ShellFolder2 getDesktop() {
        return Win32ShellFolderManager2.getDesktop();
    }

    /**
     * Helper function to return the desktop IShellFolder interface
     */
    public long getDesktopIShellFolder() {
        return getDesktop().getIShellFolder();
    }

    private static boolean pathsEqual(String path1, String path2) {
        // Same effective implementation as Win32FileSystem
        return path1.equalsIgnoreCase(path2);
    }

    /**
     * Check to see if two ShellFolder objects are the same
     */
    public boolean equals(Object o) {
        if (o == null || !(o instanceof Win32ShellFolder2)) {
            // Short-circuit circuitous delegation path
            if (!(o instanceof File)) {
                return super.equals(o);
            }
            return pathsEqual(getPath(), ((File) o).getPath());
        }
        Win32ShellFolder2 rhs = (Win32ShellFolder2) o;
        if ((parent == null && rhs.parent != null) ||
            (parent != null && rhs.parent == null)) {
            return false;
        }

        if (isFileSystem() && rhs.isFileSystem()) {
            // Only folders with identical parents can be equal
            return (pathsEqual(getPath(), rhs.getPath()) &&
                    (parent == rhs.parent || parent.equals(rhs.parent)));
        }

        if (parent == rhs.parent || parent.equals(rhs.parent)) {
            return pidlsEqual(getParentIShellFolder(), disposer.relativePIDL, rhs.disposer.relativePIDL);
        }

        return false;
    }

    private static boolean pidlsEqual(final long pIShellFolder, final long pidl1, final long pidl2) {
        return new ComTask<Boolean>() {
            public Boolean call() throws Exception {
                return (compareIDs(pIShellFolder, pidl1, pidl2) == 0);
            }
        }.execute();
    }
    /* NOTE: this method uses COM and must be called on the 'COM thread'. See ComTask for the details */
    private static native int compareIDs(long pParentIShellFolder, long pidl1, long pidl2);

    /**
     * @return Whether this is a file system shell folder
     */
    public boolean isFileSystem() {
        return hasAttribute(ATTRIB_FILESYSTEM);
    }

    /**
     * Return whether the given attribute flag is set for this object
     */
    public boolean hasAttribute(final int attribute) {
        return new ComTask<Boolean>() {
            public Boolean call() throws Exception {
                // Caching at this point doesn't seem to be cost efficient
                return (getAttributes0(getParentIShellFolder(),
                                    getRelativePIDL(), attribute)
                        & attribute) != 0;
            }
        }.execute();
    }

    /**
     * Returns the queried attributes specified in attrsMask.
     *
     * Could plausibly be used for attribute caching but have to be
     * very careful not to touch network drives and file system roots
     * with a full attrsMask
     * NOTE: this method uses COM and must be called on the 'COM thread'. See ComTask for the details
     * */
    private static native int getAttributes0(long pParentIShellFolder, long pIDL, int attrsMask);

    // Return the path to the underlying file system object
    private static String getFileSystemPath(final long parentIShellFolder, final long relativePIDL) {
        return new ComTask<String>() {
            public String call() throws Exception {
                int linkedFolder = ATTRIB_LINK | ATTRIB_FOLDER;
                if (parentIShellFolder == Win32ShellFolderManager2.getNetwork().getIShellFolder() &&
                    getAttributes0(parentIShellFolder, relativePIDL, linkedFolder) == linkedFolder) {

                    String s =
                        getFileSystemPath(Win32ShellFolderManager2.getDesktop().getIShellFolder(),
                                          getLinkLocation(parentIShellFolder, relativePIDL, false));
                    if (s != null && s.startsWith("\\\\")) {
                        return s;
                    }
                }
                return getDisplayNameOf(parentIShellFolder, relativePIDL, SHGDN_FORPARSING);
            }
        }.execute();
    }
    // Needs to be accessible to Win32ShellFolderManager2
    static String getFileSystemPath(final int csidl) throws IOException {
        return new ComTask<String>() {
            public String call() throws Exception {
                return getFileSystemPath0(csidl);
            }
        }.execute();
    }
    /* NOTE: this method uses COM and must be called on the 'COM thread'. See ComTask for the details */
    private static native String getFileSystemPath0(int csidl) throws IOException;

    // Return whether the path is a network root.
    // Path is assumed to be non-null
    private static boolean isNetworkRoot(String path) {
        return (path.equals("\\\\") || path.equals("\\") || path.equals("//") || path.equals("/"));
    }

    /**
     * @return The parent shell folder of this shell folder, null if
     * there is no parent
     */
    public File getParentFile() {
        return parent;
    }

    public boolean isDirectory() {
        if (isDir == null) {
            // Folders with SFGAO_BROWSABLE have "shell extension" handlers and are
            // not traversable in JFileChooser. An exception is "My Documents" on
            // Windows 98.
            if ((hasAttribute(ATTRIB_HASSUBFOLDER) || hasAttribute(ATTRIB_FOLDER))
                && (!hasAttribute(ATTRIB_BROWSABLE) ||
                    (is98 && equals(Win32ShellFolderManager2.getPersonal())))) {
                isDir = Boolean.TRUE;
            } else if (isLink()) {
                ShellFolder linkLocation = getLinkLocation(false);
                isDir = Boolean.valueOf(linkLocation != null && linkLocation.isDirectory());
            } else {
                isDir = Boolean.FALSE;
            }
        }
        return isDir.booleanValue();
    }

    /*
     * Functions for enumerating an IShellFolder's children
     */
    // Returns an IEnumIDList interface for an IShellFolder.  The value
    // returned must be released using releaseEnumObjects().
    private long getEnumObjects(long pIShellFolder, final boolean includeHiddenFiles) {
        final boolean isDesktop = (disposer.pIShellFolder == getDesktopIShellFolder());
        return new ComTask<Long>() {
            public Long call() throws Exception {
                return getEnumObjects(disposer.pIShellFolder, isDesktop, includeHiddenFiles);
            }
        }.execute();
    }
    // Returns an IEnumIDList interface for an IShellFolder.  The value
    // returned must be released using releaseEnumObjects().
    /* NOTE: this method uses COM and must be called on the 'COM thread'. See ComTask for the details */
    private native long getEnumObjects(long pIShellFolder, boolean isDesktop,
                                       boolean includeHiddenFiles);
    // Returns the next sequential child as a relative PIDL
    // from an IEnumIDList interface.  The value returned must
    // be released using releasePIDL().
    /* NOTE: this method uses COM and must be called on the 'COM thread'. See ComTask for the details */
    private native long getNextChild(long pEnumObjects);
    // Releases the IEnumIDList interface
    /* NOTE: this method uses COM and must be called on the 'COM thread'. See ComTask for the details */
    private native void releaseEnumObjects(long pEnumObjects);

    // Returns the IShellFolder of a child from a parent IShellFolder
    // and a relative PIDL.  The value returned must be released
    // using releaseIShellFolder().
    /* NOTE: this method uses COM and must be called on the 'COM thread'. See ComTask for the details */
    private static native long bindToObject(long parentIShellFolder, long pIDL);

    /**
     * @return An array of shell folders that are children of this shell folder
     *         object. The array will be empty if the folder is empty.  Returns
     *         <code>null</code> if this shellfolder does not denote a directory.
     */
    public File[] listFiles(final boolean includeHiddenFiles) {
        SecurityManager security = System.getSecurityManager();
        if (security != null) {
            security.checkRead(getPath());
        }

        return new ComTask<File[]>() {
            public File[] call() throws Exception {
                if (!isDirectory()) {
                    return null;
                }
                // Links to directories are not directories and cannot be parents.
                // This does not apply to folders in My Network Places (NetHood)
                // because they are both links and real directories!
                if (isLink() && !hasAttribute(ATTRIB_FOLDER)) {
                    return new File[0];
                }

                Win32ShellFolder2 desktop = Win32ShellFolderManager2.getDesktop();
                Win32ShellFolder2 personal = Win32ShellFolderManager2.getPersonal();

                // If we are a directory, we have a parent and (at least) a
                // relative PIDL. We must first ensure we are bound to the
                // parent so we have an IShellFolder to query.
                long pIShellFolder = getIShellFolder();
                // Now we can enumerate the objects in this folder.
                ArrayList list = new ArrayList();
                long pEnumObjects = getEnumObjects(pIShellFolder, includeHiddenFiles);
                if (pEnumObjects != 0) {
                    long childPIDL = 0;
                    int testedAttrs = ATTRIB_FILESYSTEM | ATTRIB_FILESYSANCESTOR;
                    do {
                        if (Thread.currentThread().isInterrupted()) {
                            return new File[0];
                        }
                        childPIDL = getNextChild(pEnumObjects);
                        boolean releasePIDL = true;
                        if (childPIDL != 0 &&
                            (getAttributes0(pIShellFolder, childPIDL, testedAttrs) & testedAttrs) != 0) {
                            Win32ShellFolder2 childFolder = null;
                            if (this.equals(desktop)
                                && personal != null
                                && pidlsEqual(pIShellFolder, childPIDL, personal.disposer.relativePIDL)) {
                                childFolder = personal;
                            } else {
                                childFolder = new Win32ShellFolder2(Win32ShellFolder2.this, childPIDL);
                                releasePIDL = false;
                            }
                            list.add(childFolder);
                        }
                        if (releasePIDL) {
                            releasePIDL(childPIDL);
                        }
                    } while (childPIDL != 0);
                    releaseEnumObjects(pEnumObjects);
                }
                return (ShellFolder[])list.toArray(new ShellFolder[list.size()]);
            }
        }.execute();
    }


    /**
     * Look for (possibly special) child folder by it's path
     *
     * @return The child shellfolder, or null if not found.
     */
    Win32ShellFolder2 getChildByPath(final String filePath) {

        return new ComTask<Win32ShellFolder2>() {
            public Win32ShellFolder2 call() throws Exception {
                long pIShellFolder = getIShellFolder();
                long pEnumObjects =  getEnumObjects(pIShellFolder, true);
                Win32ShellFolder2 child = null;
                long childPIDL = 0;

                while ((childPIDL = getNextChild(pEnumObjects)) != 0) {
                    if (getAttributes0(pIShellFolder, childPIDL, ATTRIB_FILESYSTEM) != 0) {
                        String path = getFileSystemPath(pIShellFolder, childPIDL);
                        if (path != null && path.equalsIgnoreCase(filePath)) {
                            long childIShellFolder = bindToObject(pIShellFolder, childPIDL);
                            child = new Win32ShellFolder2(Win32ShellFolder2.this,
                                            childIShellFolder, childPIDL, path);
                            break;
                        }
                    }
                    releasePIDL(childPIDL);
                }
                releaseEnumObjects(pEnumObjects);
                return child;
            }
        }.execute();
    }


    /**
     * @return Whether this shell folder is a link
     */
    public boolean isLink() {
        return hasAttribute(ATTRIB_LINK);
    }

    /**
     * @return Whether this shell folder is marked as hidden
     */
    public boolean isHidden() {
        return hasAttribute(ATTRIB_HIDDEN);
    }


    // Return the link location of a shell folder
    /* NOTE: this method uses COM and must be called on the 'COM thread'. See ComTask for the details */
    private static native long getLinkLocation(long parentIShellFolder,
                                        long relativePIDL, boolean resolve);

    /**
     * @return The shell folder linked to by this shell folder, or null
     * if this shell folder is not a link or is a broken or invalid link
     */
    public ShellFolder getLinkLocation()  {
        return getLinkLocation(true);
    }

    private ShellFolder getLinkLocation(final boolean resolve)  {
        return new ComTask<ShellFolder>() {
            public ShellFolder call() throws Exception {
                if (!isLink()) {
                    return null;
                }

                ShellFolder location = null;
                long linkLocationPIDL = getLinkLocation(getParentIShellFolder(),
                                                        getRelativePIDL(), resolve);
                if (linkLocationPIDL != 0) {
                    try {
                        location =
                            Win32ShellFolderManager2.createShellFolderFromRelativePIDL(
                                                        getDesktop(), linkLocationPIDL);
                    } catch (InternalError e) {
                        // Could be a link to a non-bindable object,
                        // such as a network connection
                        // TODO: getIShellFolder() should throw FileNotFoundException instead
                    }
                }
                return location;
            }
        }.execute();
    }

    // Parse a display name into a PIDL relative to the current IShellFolder.
    long parseDisplayName(final String name) throws FileNotFoundException {
        try {
            return new ComTask<Long>() {
                public Long call() throws Exception {
                    return parseDisplayName0(getIShellFolder(), name);
                }
            }.execute();
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException) {
                throw new FileNotFoundException("Could not find file " + name);
            }
            throw e;
        }
    }
    /* NOTE: this method uses COM and must be called on the 'COM thread'. See ComTask for the details */
    private static native long parseDisplayName0(long pIShellFolder, String name) throws IOException;

    // Return the display name of a shell folder
    /* NOTE: this method uses COM and must be called on the 'COM thread'. See ComTask for the details */
    private static native String getDisplayNameOf(long parentIShellFolder,
                                                  long relativePIDL,
                                                  int attrs);

    /**
     * @return The name used to display this shell folder
     */
    public String getDisplayName() {
        if (displayName == null) {
            displayName =
                new ComTask<String>() {
                    public String call() throws Exception {
                        return getDisplayNameOf(getParentIShellFolder(),
                                        getRelativePIDL(), SHGDN_NORMAL);
                    }
                }.execute();
        }
        return displayName;
    }

    // Return the folder type of a shell folder
    /* NOTE: this method uses COM and must be called on the 'COM thread'. See ComTask for the details */
    private static native String getFolderType(long pIDL);

    /**
     * @return The type of shell folder as a string
     */
    public String getFolderType() {
        if (folderType == null) {
            final long absolutePIDL = getAbsolutePIDL();
            folderType =
                new ComTask<String>() {
                    public String call() throws Exception {
                        return getFolderType(absolutePIDL);
                    }
                }.execute();
        }
        return folderType;
    }

    // Return the executable type of a file system shell folder
    private native String getExecutableType(String path);

    /**
     * @return The executable type as a string
     */
    public String getExecutableType() {
        if (!isFileSystem()) {
            return null;
        }
        return getExecutableType(getAbsolutePath());
    }



    // Icons

    private static Map smallSystemImages = new HashMap();
    private static Map largeSystemImages = new HashMap();
    private static Map smallLinkedSystemImages = new HashMap();
    private static Map largeLinkedSystemImages = new HashMap();

    /* NOTE: this method uses COM and must be called on the 'COM thread'. See ComTask for the details */
    private static native long getIShellIcon(long pIShellFolder);
    /* NOTE: this method uses COM and must be called on the 'COM thread'. See ComTask for the details */
    private static native int getIconIndex(long parentIShellIcon, long relativePIDL);

    // Return the icon of a file system shell folder in the form of an HICON
    private static native long getIcon(String absolutePath, boolean getLargeIcon);
    /* NOTE: this method uses COM and must be called on the 'COM thread'. See ComTask for the details */
    private static native long extractIcon(long parentIShellFolder, long relativePIDL,
                                           boolean getLargeIcon);

    // Returns an icon from the Windows system icon list in the form of an HICON
    private static native long getSystemIcon(int iconID);
    private static native long getIconResource(String libName, int iconID,
                                               int cxDesired, int cyDesired,
                                               boolean useVGAColors);
                                               // Note: useVGAColors is ignored on XP and later

    // Return the bits from an HICON.  This has a side effect of setting
    // the imageHash variable for efficient caching / comparing.
    private static native int[] getIconBits(long hIcon, int iconSize);
    // Dispose the HICON
    private static native void disposeIcon(long hIcon);

    public static native int[] getFileChooserBitmapBits();

    private long getIShellIcon() {
        if (pIShellIcon == -1L) {
            pIShellIcon =
                new ComTask<Long>() {
                    public Long call() throws Exception {
                        return getIShellIcon(getIShellFolder());
                    }
                }.execute();
        }
        return pIShellIcon;
    }


    static int[] fileChooserBitmapBits = null;
    static Image[] fileChooserIcons = new Image[47];

    static Image getFileChooserIcon(int i) {
        if (fileChooserIcons[i] != null) {
            return fileChooserIcons[i];
        } else {
            if (fileChooserBitmapBits == null) {
                fileChooserBitmapBits = getFileChooserBitmapBits();
            }
            if (fileChooserBitmapBits != null) {
                int nImages = fileChooserBitmapBits.length / (16*16);
                int[] bitmapBits = new int[16 * 16];
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        bitmapBits[y * 16 + x] = fileChooserBitmapBits[y * (nImages * 16) + (i * 16) + x];
                    }
                }
                BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
                img.setRGB(0, 0, 16, 16, bitmapBits, 0, 16);
                fileChooserIcons[i] = img;
            }
        }
        return fileChooserIcons[i];
    }


    private static Image makeIcon(long hIcon, boolean getLargeIcon) {
        if (hIcon != 0L && hIcon != -1L) {
            // Get the bits.  This has the side effect of setting the imageHash value for this object.
            int size = getLargeIcon ? 32 : 16;
            int[] iconBits = getIconBits(hIcon, size);
            if (iconBits != null) {
                BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
                img.setRGB(0, 0, size, size, iconBits, 0, size);
                return img;
            }
        }
        return null;
    }


    /**
     * @return The icon image used to display this shell folder
     */
    public Image getIcon(final boolean getLargeIcon) {
        Image icon = getLargeIcon ? largeIcon : smallIcon;
        if (icon == null) {
            icon =
                new ComTask<Image>() {
                    public Image call() throws Exception {

                        Image newIcon = null;
                        if (isFileSystem()) {
                            long parentIShellIcon = (parent != null)
                                    ? ((Win32ShellFolder2)parent).getIShellIcon()
                                    : 0L;
                            long relativePIDL = getRelativePIDL();

                            // These are cached per type (using the index in the system image list)
                            int index = getIconIndex(parentIShellIcon, relativePIDL);
                            if (index > 0) {
                                Map imageCache;
                                if (isLink()) {
                                    imageCache = getLargeIcon
                                            ? largeLinkedSystemImages
                                            : smallLinkedSystemImages;
                                } else {
                                    imageCache = getLargeIcon
                                            ? largeSystemImages
                                            : smallSystemImages;
                                }
                                newIcon = (Image) imageCache.get(Integer.valueOf(index));
                                if (newIcon == null) {
                                    long hIcon = getIcon(getAbsolutePath(), getLargeIcon);
                                    newIcon = makeIcon(hIcon, getLargeIcon);
                                    disposeIcon(hIcon);
                                    if (newIcon != null) {
                                        imageCache.put(Integer.valueOf(index), newIcon);
                                    }
                                }
                            }
                        }

                        if (newIcon == null) {
                            // These are only cached per object
                            long hIcon = extractIcon(getParentIShellFolder(),
                                            getRelativePIDL(), getLargeIcon);
                            newIcon = makeIcon(hIcon, getLargeIcon);
                            disposeIcon(hIcon);
                        }

                        if (newIcon == null) {
                            newIcon = Win32ShellFolder2.super.getIcon(getLargeIcon);
                        }
                        return newIcon;
                    }
                }.execute();
            if (getLargeIcon) {
                largeIcon = icon;
            } else {
                smallIcon = icon;
            }
        }
        return icon;
    }

    /**
     * Gets an icon from the Windows system icon list as an <code>Image</code>
     */
    static Image getSystemIcon(SystemIcon iconType) {
        long hIcon = getSystemIcon(iconType.getIconID());
        Image icon = makeIcon(hIcon, true);
        disposeIcon(hIcon);
        return icon;
    }

    /**
     * Gets an icon from the Windows system icon list as an <code>Image</code>
     */
    static Image getShell32Icon(int iconID) {
        boolean useVGAColors = true; // Will be ignored on XP and later

        Toolkit toolkit = Toolkit.getDefaultToolkit();
        String shellIconBPP = (String)toolkit.getDesktopProperty("win.icon.shellIconBPP");
        if (shellIconBPP != null) {
            useVGAColors = shellIconBPP.equals("4");
        }

        long hIcon = getIconResource("shell32.dll", iconID, 16, 16, useVGAColors);
        if (hIcon != 0) {
            Image icon = makeIcon(hIcon, false);
            disposeIcon(hIcon);
            return icon;
        }
        return null;
    }

    /**
     * Returns the canonical form of this abstract pathname.  Equivalent to
     * <code>new&nbsp;Win32ShellFolder2(getParentFile(), this.{@link java.io.File#getCanonicalPath}())</code>.
     *
     * @see java.io.File#getCanonicalFile
     */
    public File getCanonicalFile() throws IOException {
        return this;
    }

    /*
     * Indicates whether this is a special folder (includes My Documents)
     */
    public boolean isSpecial() {
        return isPersonal || !isFileSystem() || (this == getDesktop());
    }

    /**
     * Compares this object with the specified object for order.
     *
     * @see sun.awt.shell.ShellFolder#compareTo(File)
     */
    public int compareTo(File file2) {
        if (!(file2 instanceof Win32ShellFolder2)) {
            if (isFileSystem() && !isSpecial()) {
                return super.compareTo(file2);
            } else {
                return -1; // Non-file shellfolders sort before files
            }
        }
        return Win32ShellFolderManager2.compareShellFolders(this, (Win32ShellFolder2) file2);
    }

    // native constants from commctrl.h
    private static final int LVCFMT_LEFT = 0;
    private static final int LVCFMT_RIGHT = 1;
    private static final int LVCFMT_CENTER = 2;

    public ShellFolderColumnInfo[] getFolderColumns() {
        return new ComTask<ShellFolderColumnInfo[]>() {
            public ShellFolderColumnInfo[] call() throws Exception {
                ShellFolderColumnInfo[] columns = doGetColumnInfo(getIShellFolder());

                if (columns != null) {
                    List<ShellFolderColumnInfo> notNullColumns =
                            new ArrayList<ShellFolderColumnInfo>();
                    for (int i = 0; i < columns.length; i++) {
                        ShellFolderColumnInfo column = columns[i];
                        if (column != null) {
                            column.setAlignment(column.getAlignment() == LVCFMT_RIGHT
                                    ? SwingConstants.RIGHT
                                        : column.getAlignment() == LVCFMT_CENTER
                                    ? SwingConstants.CENTER
                                        : SwingConstants.LEADING);

                            column.setComparator(new ColumnComparator(i));

                            notNullColumns.add(column);
                        }
                    }
                    columns = new ShellFolderColumnInfo[notNullColumns.size()];
                    notNullColumns.toArray(columns);
                }
                return columns;
            }
        }.execute();
    }

    public Object getFolderColumnValue(final int column) {
        return new ComTask<Object>() {
            public Object call() throws Exception {
                return doGetColumnValue(getParentIShellFolder(), getRelativePIDL(), column);
            }
        }.execute();
    }

    /* NOTE: this method uses COM and must be called on the 'COM thread'. See ComTask for the details */
    private native ShellFolderColumnInfo[] doGetColumnInfo(long iShellFolder2);
    /* NOTE: this method uses COM and must be called on the 'COM thread'. See ComTask for the details */
    private native Object doGetColumnValue(long parentIShellFolder2, long childPIDL, int columnIdx);
    /* NOTE: this method uses COM and must be called on the 'COM thread'. See ComTask for the details */
    private native int compareIDsByColumn(long pParentIShellFolder, long pidl1, long pidl2, int columnIdx);


    private class ColumnComparator implements Comparator {
        private final int columnIdx;

        public ColumnComparator(int columnIdx) {
            this.columnIdx = columnIdx;
        }

        // compares 2 objects within this folder by the specified column
        public int compare(final Object o, final Object o1) {
            return new ComTask<Integer>() {
                public Integer call() throws Exception {
                    if (o instanceof Win32ShellFolder2
                            && o1 instanceof Win32ShellFolder2) {
                        // delegates comparison to native method
                        return compareIDsByColumn(getIShellFolder(),
                                ((Win32ShellFolder2) o).getRelativePIDL(),
                                ((Win32ShellFolder2) o1).getRelativePIDL(),
                                columnIdx);
                    }
                    return 0;
                }
            }.execute();
        }
    }

    private static native void initializeCom();
    private static native void uninitializeCom();

    private static class ComTaskExecutor extends ThreadPoolExecutor implements ThreadFactory {
        private final static ComTaskExecutor executor = new ComTaskExecutor();
        private Thread thread;

        private ComTaskExecutor() {
            // Executor with the single thread that never dies
            super(1, 1, 0, TimeUnit.DAYS, new LinkedBlockingQueue<Runnable>());
            allowCoreThreadTimeOut(false);
            setThreadFactory(this);

            final Runnable shutdownHook = new Runnable() {
                public void run() {
                    AccessController.doPrivileged(new PrivilegedAction<Void>() {
                        public Void run() {
                            shutdownNow();
                            return null;
                        }
                    });
                }
            };
            AccessController.doPrivileged(new PrivilegedAction<Void>() {
                public Void run() {
                    Runtime.getRuntime().addShutdownHook(
                        new Thread(shutdownHook)
                    );
                    return null;
                }
            });
        }

        public synchronized Thread newThread(final Runnable task) {
            final Runnable comRun = new Runnable() {
                public void run() {
                    try {
                        initializeCom();
                        task.run();
                    } finally {
                        uninitializeCom();
                    }
                }
            };
            thread =
                AccessController.doPrivileged(
                    new PrivilegedAction<Thread>() {
                        public Thread run() {
                            /* The thread must be a member of a thread group
                             * which will not get GCed before VM exit.
                             * Make its parent the top-level thread group.
                             */
                            ThreadGroup tg = Thread.currentThread().getThreadGroup();
                            for (ThreadGroup tgn = tg;
                                 tgn != null;
                                 tg = tgn, tgn = tg.getParent());
                            Thread thread = new Thread(tg, comRun, "Swing-Shell");
                            thread.setDaemon(true);
                            return thread;
                        }
                    }
                );
            return thread;
        }

        public synchronized boolean isAlreadyOnComThread() {
            return Thread.currentThread() == thread;
        }
    }

    private static abstract class ComTask<T> implements Callable<T> {
        public T execute() {
            try {
                return ComTaskExecutor.executor.isAlreadyOnComThread()
                        // if it's already called from the COM
                        // thread, we don't need to delegate the task
                        ? this.call()
                        : ComTaskExecutor.executor.submit(this).get();
            } catch (Exception e) {
                Throwable cause = (e instanceof ExecutionException) ? e.getCause() : e;
                if (cause instanceof RuntimeException) { throw (RuntimeException) cause; }
                if (cause instanceof Error)            { throw (Error) cause; }
                throw new RuntimeException(cause);
            }
        }
    }

}
