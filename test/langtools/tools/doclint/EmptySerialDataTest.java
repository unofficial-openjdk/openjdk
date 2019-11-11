/*
 * @test /nodynamiccopyright/
 * @bug 8004832
 * @summary Add new doclint package
 * @modules jdk.compiler/com.sun.tools.doclint
 * @build DocLintTester
 * @run main DocLintTester -Xmsgs:-syntax EmptySerialDataTest.java
 * @run main DocLintTester -Xmsgs:syntax -ref EmptySerialDataTest.out EmptySerialDataTest.java
 */

import java.io.ObjectOutputStream;
import java.io.Serializable;

/** . */
@Bean
public class EmptySerialDataTest implements Serializable {
    /** @serialData */
    @Bean
@Bean
@Bean
@Bean
                private void writeObject(ObjectOutputStream s) { }
}
