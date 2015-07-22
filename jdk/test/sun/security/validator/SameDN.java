/*
 * Copyright (c) 2010, Oracle and/or its affiliates. All rights reserved.
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
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test %I% %W%
 * @bug 6958869
 * @summary regression: PKIXValidator fails when multiple trust anchors
 * have same dn
 */

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.security.cert.X509Certificate;
import sun.security.validator.Validator;

public class SameDN {

    // Pre-generated certificates:
    // c1: A root CA
    // c2: Another root CA, having the same subject name with c1
    // u1: A user cert signed by c1
    // u2: A user cert signed by c2
    // Both c1 and c2's expiration dates are 2009, therefore expired.
    // u1 and u2's expiration dates are 2037, should be OK for quite some time

    private static String u1 =
    "-----BEGIN CERTIFICATE-----\n" +
    "MIIC3DCCAcSgAwIBAgIEKwQjZzANBgkqhkiG9w0BAQsFADANMQswCQYDVQQDEwJDQTAeFw0xMDA2\n" +
    "MDcwMjI3NDlaFw0zNzEwMjMwMjI3NDlaMA8xDTALBgNVBAMTBFVzZXIwggEiMA0GCSqGSIb3DQEB\n" +
    "AQUAA4IBDwAwggEKAoIBAQC6y278cHH6HX2TukGn5wd4pqJbw7LeGqJBtDj7T7arz0Hr3ELtKKjN\n" +
    "85UGRm9taLLRQ33eSr8topgU/3RBS4kOR2wQ/HkWcq+Cy5n+/rixrWnzqyxjVWIy60tCgZhHUVZQ\n" +
    "ks8KYguzoKmFYTbIh6AtIfjrdcc8s3OIiWy43R2MtM9UXmcalTSVgkW9px5qJ/4OnHz/7iygckKJ\n" +
    "5bJfCKHY3RSMcaf+fgzVStbCustFelmfBF5+38qZhGFlhQVOzKjswPrT8RPmK41hCmjkuzp5jHnJ\n" +
    "F+SXCe0DFN5bjaaAlAnfyOS7Bpqd9gAyCnWG3mUQVmmlFu/O3YKDw70DFuwlAgMBAAGjQjBAMB0G\n" +
    "A1UdDgQWBBRSxOcOqKefiDex08kyIs27xAAC9zAfBgNVHSMEGDAWgBTs+4guzmMZ4iKIb1+dM4+9\n" +
    "yOOgTTANBgkqhkiG9w0BAQsFAAOCAQEAJSMo8bNGo9t/m91I2m4UXKCczJ5D41R/KBNN5WRNIjeb\n" +
    "g/qvrnyJMQAhMdfhyQ4ncnuIjsxukbxUgcy/UuTrJSxY0KZ/Je3I6ZrIcrLdQ15wEBVeh/nIdHQU\n" +
    "c+LLwelz6vM4fVXOZxFJ0eteo5ap54TABg847Vd6L7ac80XqZPeJ9ADiIygWX4zL+Va9HWkK34Kn\n" +
    "l/v7OFIKja0Q30OrxiVx5H1nvmpWsbgQFUIBIR8k+O2kf6sUsBGjOX8NaiCTqoKMyAXkGfBnxVM2\n" +
    "lT/3bLQzpNFVz1jYcxgjZSmpLwsADuwo8fCMHTByVo7oMX7kNcSS3eDUBlqhweXj3oI4BQ==\n" +
    "-----END CERTIFICATE-----\n";
    private static String c1 =
    "-----BEGIN CERTIFICATE-----\n" +
    "MIICxzCCAa+gAwIBAgIEeY6jrjANBgkqhkiG9w0BAQQFADANMQswCQYDVQQDEwJDQTAeFw0wOTA2\n" +
    "MDcwMjI3NDBaFw0wOTA5MDUwMjI3NDBaMA0xCzAJBgNVBAMTAkNBMIIBIjANBgkqhkiG9w0BAQEF\n" +
    "AAOCAQ8AMIIBCgKCAQEAmI6zRWxNHFvtad6QE69OdDZfpS+FCgR5/hGBO7t6NmXnOfdpi+0hmhYq\n" +
    "BV78Os8Ho5nXubZP1CwMUx53NGE7+xdihD48SyWRy2nhmfmyAScGhi6kWgdL63MTZor05laPGyZ1\n" +
    "wJRntMiyAbqvBiOxhcbPyGpAH4gmh6sbxMDszH/5+hyDWpwR0t7X6+VgYBw6Mjodmvyj3bTU9oSu\n" +
    "bElZmtmGsDlkN9220Tav9sR6wxbKhQMk88Sc11fwafzmX8aSdyD1QCUOWe7m+dl+CG1iqRR1J6Br\n" +
    "H40diKsP4dZ7J1pgvYTZUIvWIwKnrQ4HnOomz+o19P16MeplE8SZfQbOWQIDAQABoy8wLTAdBgNV\n" +
    "HQ4EFgQU7PuILs5jGeIiiG9fnTOPvcjjoE0wDAYDVR0TBAUwAwEB/zANBgkqhkiG9w0BAQQFAAOC\n" +
    "AQEANBdApVrjR7+vbQZNkWrS4QY1n7yYLHGOXmazxIYlPKWgJMNtx6YO2Evw0jvhVedoIjswQOxC\n" +
    "VuwgAxO7y6g1yc4BCWFhPEj81cEeJ7olndj5QmGr1boHKFsEqRNPWa9aDo6QywLXkChifSjej9KL\n" +
    "54TgDqa0ehk8dShcWON3xqiqaIQRG4HTlLM3cz29k7a2Q6A3XNGZLUJXvcra+UFtiPTSGPm40Bse\n" +
    "HGKR9pSJhjZlwLAupcvWqBeLE9nlbzT4ah68s317+Zolw0FyV6MVhAI7TMo6FA0vFHu27q1K1hIx\n" +
    "ZpY1kpnAZY5obdFumfTtno8kW0/rDe4F9TGM0Ly4vw==\n" +
    "-----END CERTIFICATE-----\n";
    private static String u2 =
    "-----BEGIN CERTIFICATE-----\n" +
    "MIIC3DCCAcSgAwIBAgIEb91yITANBgkqhkiG9w0BAQsFADANMQswCQYDVQQDEwJDQTAeFw0xMDA2\n" +
    "MDcwMjI3NTBaFw0zNzEwMjMwMjI3NTBaMA8xDTALBgNVBAMTBFVzZXIwggEiMA0GCSqGSIb3DQEB\n" +
    "AQUAA4IBDwAwggEKAoIBAQC6y278cHH6HX2TukGn5wd4pqJbw7LeGqJBtDj7T7arz0Hr3ELtKKjN\n" +
    "85UGRm9taLLRQ33eSr8topgU/3RBS4kOR2wQ/HkWcq+Cy5n+/rixrWnzqyxjVWIy60tCgZhHUVZQ\n" +
    "ks8KYguzoKmFYTbIh6AtIfjrdcc8s3OIiWy43R2MtM9UXmcalTSVgkW9px5qJ/4OnHz/7iygckKJ\n" +
    "5bJfCKHY3RSMcaf+fgzVStbCustFelmfBF5+38qZhGFlhQVOzKjswPrT8RPmK41hCmjkuzp5jHnJ\n" +
    "F+SXCe0DFN5bjaaAlAnfyOS7Bpqd9gAyCnWG3mUQVmmlFu/O3YKDw70DFuwlAgMBAAGjQjBAMB0G\n" +
    "A1UdDgQWBBRSxOcOqKefiDex08kyIs27xAAC9zAfBgNVHSMEGDAWgBQIwNB278hTOfbaE664NTlk\n" +
    "M8qnSTANBgkqhkiG9w0BAQsFAAOCAQEAMdLtWIFjd6VjmysLsCWLq14ricnuwUxwHokQrDLKSEKY\n" +
    "MFfWZT4nTKsLtBpEmLRqabAk1LNzXHjGL9+Wla4PQ87DEQ11KF2TegwwLsqQ5dVgGz+yBxm+cOel\n" +
    "jwx/K2FW0gYERyPV10EObcDCZmo2Rq/9KVhltTXg4+68o+YwpbtKK3WFyGrKVumPL1mICOGS6+xq\n" +
    "7SbitSUYwNrlK2KFXKEhNTc+DgIIlWRsG9IFlbPHFOR+M/8EKwO8Dg7SBANFYrwcZ9VuXKLszX5O\n" +
    "2+ypnNNhyqm0BF4ZDXlB0hx4poWHXnm9rkAeiw19E3K6lluEtSheBbN5osAuTkV/pTXToQ==\n" +
    "-----END CERTIFICATE-----\n";
    private static String c2 =
    "-----BEGIN CERTIFICATE-----\n" +
    "MIICxzCCAa+gAwIBAgIEBOCFyTANBgkqhkiG9w0BAQUFADANMQswCQYDVQQDEwJDQTAeFw0wOTA2\n" +
    "MDcwMjI3NDZaFw0wOTA5MDUwMjI3NDZaMA0xCzAJBgNVBAMTAkNBMIIBIjANBgkqhkiG9w0BAQEF\n" +
    "AAOCAQ8AMIIBCgKCAQEAkp5VJRNkGemKd4eEBVkqQkA4ULNqIOcM+Pu2VuQQXnuT8oTVQgEsAZu1\n" +
    "W8Q0QL6zd3Y1KO1rkKJJ7yY5qAJeyBqhHVMmx0ORteZu5VRYsP694wCku4P6++qvZQRN7a5QbYZp\n" +
    "//q8Aak1AIdJYfl5BBUNzMy1hg3sMn/AWgAi2FTicV5PWcvwmk3n49n7lhi1nNzthd8IdJOIou+Q\n" +
    "YtRfIOjKK+Oe8rWusOzHM7iGdlnoEDy+ctezynZtDkU1KrcsYZdlpLYfXkxzFqbVJtJKdzPznJlW\n" +
    "KQMI3Lzc/xgdR3fOC8VfEk9o8qig+WpM3qixM9JISWg2ttw9OEwiNkkmnQIDAQABoy8wLTAdBgNV\n" +
    "HQ4EFgQUCMDQdu/IUzn22hOuuDU5ZDPKp0kwDAYDVR0TBAUwAwEB/zANBgkqhkiG9w0BAQUFAAOC\n" +
    "AQEAXfuIiXtVTXJLcinbMKUSAMfFkJmbDZdMqZ2RlacxJaMiS6xM7MBbl2Mt6jJCz/Gvs6C3e3U/\n" +
    "qwcCqQyKKtUIC/QcfedFd9zD3sSfB7gQWIEjZQ1/8fDemOiv0MBs146Jfsciv7m+o5kzuzbQCfJQ\n" +
    "jO5pk0Yr3oMPmtmMgD0hKh/vKljfW3xxnOK+9bd9aLKBsTRjIU7I/K87+kNkxF7uWl5JtpBbueQ9\n" +
    "keeai7fiH8z4/LizQ1mP4O1n6zTrvfE6dZt7KgeQjj3wcHmf5fmvALmht9g/PoKUVodCzHli0Wzi\n" +
    "mjzeeCGrWn/QucauLWAvGA1rCOpKs3ttOhh0OzwTwA==\n" +
    "-----END CERTIFICATE-----\n";

    public static void main(String[] args) throws Exception {
        // A keystore with both CA certs inside
        KeyStore ks = KeyStore.getInstance("JKS");
        ks.load(null, null);
        ks.setCertificateEntry("ca1", createPath(c1)[0]);
        ks.setCertificateEntry("ca2", createPath(c2)[0]);
        Validator v = Validator.getInstance
            (Validator.TYPE_PKIX, Validator.VAR_GENERIC, ks);
        // Validating chain (u1, c1)
        X509Certificate[] chain1 = createPath(u1 + c1);
        for (X509Certificate c: v.validate(chain1)) {
            System.out.println("   " + c.getSubjectX500Principal() +
                    " issued by " + c.getIssuerX500Principal());
        }
        // Validating chain (u2, c2)
        X509Certificate[] chain2 = createPath(u2 + c2);
        for (X509Certificate c: v.validate(chain2)) {
            System.out.println("   " + c.getSubjectX500Principal() +
                    " issued by " + c.getIssuerX500Principal());
        }
    }

    public static X509Certificate[] createPath(String chain) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List list = new ArrayList();
        for (Certificate c: cf.generateCertificates(
                new ByteArrayInputStream(chain.getBytes()))) {
            list.add((X509Certificate)c);
        }
        return (X509Certificate[]) list.toArray(new X509Certificate[0]);
    }
}
