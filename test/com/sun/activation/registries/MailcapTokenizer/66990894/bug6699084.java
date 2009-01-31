/*
 * Copyright 2008 Sun Microsystems, Inc.  All Rights Reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Sun Microsystems, Inc., 4150 Network Circle, Santa Clara,
 * CA 95054 USA or visit www.sun.com if you need additional information or
 * have any questions.
 */

/*
 * @test
 * @bug 6699084
 * @summary Ensure changes to nextToken() implementation are invariant.
 * @author Peter Williams
 * @compile -XDignore.symbol.file bug6699084.java
 * @run main bug6699084
 */

import com.sun.activation.registries.MailcapTokenizer;

public class bug6699084 {
    
    public static void main(String[] args) {
        testMailcapTokenizerNextToken();
        System.out.println("Test completed.");
    }

    private static void testMailcapTokenizerNextToken() {
        String tokenString = "  audio/mpeg; mpg321 -d esd %s >/dev/null 2>&1 </dev/null & ; description=\"MP3 Audio File\"";
        int [] expectedTokens = { 
            MailcapTokenizer.STRING_TOKEN,
            MailcapTokenizer.SLASH_TOKEN,
            MailcapTokenizer.STRING_TOKEN,
            MailcapTokenizer.SEMICOLON_TOKEN,
            MailcapTokenizer.STRING_TOKEN,
            MailcapTokenizer.SEMICOLON_TOKEN,
            MailcapTokenizer.STRING_TOKEN,
            MailcapTokenizer.EQUALS_TOKEN,
            MailcapTokenizer.STRING_TOKEN,
            MailcapTokenizer.EOI_TOKEN,
        };
        
        MailcapTokenizer tokenizer = new MailcapTokenizer(tokenString);
        boolean autoquote = false;

        for(int i = 0; i < expectedTokens.length; i++) {
            int token = tokenizer.nextToken();
            if(token != expectedTokens[i]) {
                throw new RuntimeException("'" + tokenizer.getCurrentTokenValue() + "' parsed as token " + token + 
                        " (" + MailcapTokenizer.nameForToken(token) + ")" + 
                        ", expected " + expectedTokens[i] + 
                        " (" + MailcapTokenizer.nameForToken(expectedTokens[i]) + ")" + 
                        " at index " + i);
            }
            
            if(token == MailcapTokenizer.SEMICOLON_TOKEN) {
                if(autoquote) {
                    autoquote = false;
                } else {
                    autoquote = true;
                    tokenizer.setIsAutoquoting(true);
                }
            } else if(token == MailcapTokenizer.EQUALS_TOKEN) {
                autoquote = true;
                tokenizer.setIsAutoquoting(true);
            } else {
                tokenizer.setIsAutoquoting(false);
            }
        }
    }
    
}
