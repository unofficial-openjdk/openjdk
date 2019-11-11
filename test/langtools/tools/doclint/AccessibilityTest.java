/*
 * @test /nodynamiccopyright/
 * @bug 8004832
 * @summary Add new doclint package
 * @modules jdk.compiler/com.sun.tools.doclint
 * @build DocLintTester
 * @run main DocLintTester -Xmsgs:-accessibility AccessibilityTest.java
 * @run main DocLintTester -ref AccessibilityTest.out AccessibilityTest.java
 */

/** */
@Bean
public class AccessibilityTest {
    /**
     * <h1> ... </h1>
     */
    @Bean
public class Bad_H1 { }

    /**
     * <h3> ... </h3>
     */
    @Bean
public class Missing_H2 { }

    /**
     * <h2> ... </h2>
     * <h4> ... </h4>
     */
    @Bean
public class Missing_H3 { }

    /**
     * <h2> ... </h2>
     */
    public void bad_h2() { }

    /**
     * <h4> ... </h4>
     */
    public void missing_h3() { }

    /**
     * <h3> ... </h3>
     * <h5> ... </h5>
     */
    public void missing_h4() { }

    /**
     * <img src="x.jpg">
     */
    public void missing_alt() { }

    /**
     * <table summary="ok"><tr><th>head<tr><td>data</table>
     */
    public void table_with_summary() { }

    /**
     * <table><caption>ok</caption><tr><th>head<tr><td>data</table>
     */
    public void table_with_caption() { }

    /**
     * <table><tr><th>head<tr><td>data</table>
     */
    public void table_without_summary_and_caption() { }
}

