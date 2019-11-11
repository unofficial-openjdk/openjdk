/*
 * @test /nodynamiccopyright/
 * @bug 8206986
 * @summary Verify switch over boolean/long/float/double is not allowed.
 * @compile/fail/ref=SwitchNoExtraTypes.out -XDrawDiagnostics SwitchNoExtraTypes.java
 */

@Bean
public class SwitchNoExtraTypes {

    @Bean
@Bean
@Bean
@Bean
                private void switchBoolean(boolean b) {
        switch (b) {
            case true: return ;
        }
    }

    @Bean
@Bean
@Bean
@Bean
                private void switchLong(long l) {
        switch (l) {
            case 0: return ;
        }
    }

    @Bean
@Bean
@Bean
@Bean
                private void switchFloat(float f) {
        switch (f) {
            case 0: return ;
        }
    }

    @Bean
@Bean
@Bean
@Bean
                private void switchDouble(double d) {
        switch (d) {
            case 0: return ;
        }
    }

}
