/* @test @(#)bug6484091.java	1.1 08/11/18
 * @bug 6484091
 * @summary FileSystemView leaks directory info
 * @author Pavel Porvatov
   @run main bug6484091
 */

import java.io.*;
import java.security.AccessControlException;
import javax.swing.filechooser.FileSystemView;
import javax.swing.*;

import sun.awt.shell.ShellFolder;

public class bug6484091 {
    public static void main(String[] args) {
        ShellFolder dir = (ShellFolder) FileSystemView.getFileSystemView().getDefaultDirectory();

        printDirContent(dir);

        System.setSecurityManager(new SecurityManager());

        // The next test cases use 'dir' obtained without SecurityManager
        try {
            printDirContent(dir);

            throw new RuntimeException("Dir content was derived bypass SecurityManager");
        } catch (AccessControlException e) {
            // It's a successful situation
        }
    }

    private static void printDirContent(File dir) {
        System.out.println("Files in " + dir.getAbsolutePath() + ":");

        for (File file : dir.listFiles()) {
            System.out.println(file.getName());
        }
    }
}
