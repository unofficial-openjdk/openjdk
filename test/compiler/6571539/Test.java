/**
 * @test @(#)Test.java	1.1 07/07/26
 * @bug 6571539
 * @summary Crash in Interface Dispatch
 *
 * @compile C1.java C2.java C3.java C4.java Test.java
 * @compile bad/C4.java
 * @run main Test
 */

public class Test {
    static Iterable object0 = new C1();
    static Iterable object1 = new C2();
    static Iterable object2 = new C3();
    static Iterable badObject = new C4();

    public static void main(String[] args) {
        for (int i = 0; i < 200000; i++) {
            Iterable object = null;
            switch (i % 3) {
            case 0: object = object0; break;
            case 1: object = object1; break;
            case 2: object = object2; break;
            }
            if (i > 150000) object = badObject;
            try {
                if (object.iterator() != null) {
                }
            } catch (IncompatibleClassChangeError icce) {
                if (object == badObject) {
                    System.out.println("ok");
                    return;
                }
                System.out.println("incorrectly thrown exception");
                throw icce;
            }
        }
        throw new InternalError("should have thrown IncompatibleClassChangeError");
    }
}
