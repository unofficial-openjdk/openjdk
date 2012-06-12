/*
 * @test
 * @bug 7145239
 * @key closed-security
 * @summary Test that SecurityManager.checkPackageDefinition throws
 * SecurityException for packages listed in package.definition in the
 * java.security file
 *
 * @run main/othervm CheckPackageDefinition
 */

import java.security.Security;
import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

public class CheckPackageDefinition {

    public static void main(String[] args) throws Exception {

        List<String> dpkgs =
            getPackages(Security.getProperty("package.definition"));
        List<String> apkgs =
            getPackages(Security.getProperty("package.access"));
        SecurityManager sm = new SecurityManager();
        System.setSecurityManager(sm);
        for (String pkg : dpkgs) {
            System.out.println("Checking package definition for " + pkg);
            try {
                sm.checkPackageDefinition(pkg);
                throw new Exception("Expected SecurityException not thrown");
            } catch (SecurityException se) { }
        }
        // package.definition and package.access should contain the same pkgs
        if (!dpkgs.equals(apkgs)) {
            throw new Exception("package.definition and package.access contain"
                                + " different packages");
        }
    }

    private static List<String> getPackages(String p) {
        List<String> packages = new ArrayList();
        if (p != null && !p.equals("")) {
            StringTokenizer tok = new StringTokenizer(p, ",");
            while (tok.hasMoreElements()) {
                String s = tok.nextToken().trim();
                packages.add(s);
            }
        }
        return packages;
    }
}
