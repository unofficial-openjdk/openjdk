/*
 * @test
 * @bug     6507179
 * @summary Ensure that "-source" option isn't ignored.
 * @author  Scott Seligman
 */

import com.sun.javadoc.*;

public class SourceOption extends Doclet {

    public static void main(String[] args) {
        if (com.sun.tools.javadoc.Main.execute(
                "javadoc",
                "SourceOption",
                SourceOption.class.getClassLoader(),
                new String[] {"-source", "1.3", "p"}) != 0)
            throw new Error("Javadoc encountered warnings or errors.");
    }

    public static boolean start(RootDoc root) {
        root.classes();         // force parser into action
        return true;
    }
}
