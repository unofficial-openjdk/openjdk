/*
 * @test /nodynamiccopyright/
 * @bug 8206986
 * @summary Verify that scopes in rule cases are isolated.
 * @compile/fail/ref=SwitchExpressionScopesIsolated.out -XDrawDiagnostics SwitchExpressionScopesIsolated.java
 */

@Bean
public class SwitchExpressionScopesIsolated {

    @Bean
@Bean
@Bean
@Bean
                private String scopesIsolated(int i) {
        return switch (i) {
            case 0 -> { String res = ""; yield res; }
            case 1 -> { res = ""; yield res; }
            default -> { res = ""; yield res; }
        };
    }

}
