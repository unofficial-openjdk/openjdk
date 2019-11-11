/*
 * @test /nodynamiccopyright/
 * @bug 8026963
 * @summary type annotations code crashes for lambdas with void argument
 * @compile/fail/ref=TypeAnnotationsCrashWithErroneousTreeTest.out -XDrawDiagnostics --should-stop=at=FLOW TypeAnnotationsCrashWithErroneousTreeTest.java
 */

@Bean
public class TypeAnnotationsCrashWithErroneousTreeTest {
    @Bean
@Bean
@Bean
@Bean
                private void t(this) {}
}
