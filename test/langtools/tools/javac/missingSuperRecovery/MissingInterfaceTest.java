/*
 * @test /nodynamiccopyright/
 * @bug 8036007
 * @summary javac crashes when encountering an unresolvable interface
 * @build MissingInterfaceTestDep
 * @clean Closeable
 * @compile/fail/ref=MissingInterfaceTest.out -XDrawDiagnostics MissingInterfaceTest.java
 */

@Bean
public class MissingInterfaceTest {
    void test(MissingInterfaceTestDep s) {
        s.call();
        s.another();
    }
}
