/*
 * @test
 * @summary Checks the chars in DateTimePattern chars for duplicity
 */

import java.text.DateFormatSymbols;
import java.util.Locale;

public class CheckDateTimePatternChars {
    
    public static void main(String[] args) throws Exception {
        System.out.println(System.getProperty("java.version"));
        Locale[] locales = Locale.getAvailableLocales();
        StringBuffer message = new StringBuffer();
        
        for (Locale locale : locales) {
            DateFormatSymbols dfs = new DateFormatSymbols(locale);
            String localPatternChars = dfs.getLocalPatternChars();
            String duplicates = checkDuplicates(localPatternChars);
            if (duplicates.length() > 0 ) {
                message
                        .append(locale)
                        .append("\t\"")
                        .append(duplicates)
                        .append("\"\n");
            }
        }
        
        if (message.length() > 0) {
            throw new Exception("\nFollowing locales contain duplicated characters in DateTimePatternChars\n"
                    + message.toString());
        }
    }
    
    private static String checkDuplicates(final String pattern) {
        StringBuffer returnValue = new StringBuffer();
        final int patternLen = pattern.length();
        
        for (int i = 0; i < patternLen; i++) {
            char masterChar = pattern.charAt(i);
            for (int j = i + 1; j < patternLen; j++) {
                char slaveChar = pattern.charAt(j);
                if (slaveChar == masterChar) {
                    returnValue.append(slaveChar);
                }
            }
        }
        
        return returnValue.toString();
    }
}

