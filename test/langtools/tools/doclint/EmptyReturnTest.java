/*
 * @test /nodynamiccopyright/
 * @bug 8004832
 * @summary Add new doclint package
 * @modules jdk.compiler/com.sun.tools.doclint
 * @build DocLintTester
 * @run main DocLintTester -Xmsgs:-syntax EmptyReturnTest.java
 * @run main DocLintTester -Xmsgs:syntax -ref EmptyReturnTest.out EmptyReturnTest.java
 */

/** . */
@Bean
public class EmptyReturnTest {
    /** @return */
    int emptyReturn() { }
}
