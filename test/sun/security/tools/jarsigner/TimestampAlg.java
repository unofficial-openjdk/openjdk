/*
 * Copyright (c) 2014, Oracle and/or its affiliates. All rights reserved.
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

/**
 * @test
 * @bug 8049480
 * @summary Current versions of Java can't verify jars signed and timestamped with Java 9
 */

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class TimestampAlg {

    public static void main(String[] args) throws Exception {
        // This is a very simple jar file signed by JDK 9 with a timestamp
        // using the SHA-256 message digest algorithm.
        String var =
            "504b0304140008080800c28ee844000000000000000000000000140000004d45" +
            "54412d494e462f4d414e49464553542e4d4615cd4d0b82301c80f1fb60df61c7" +
            "42666a9928749846f4aa8924745cf9d716b6c59c48df3ebd3e87df73e152d4d0" +
            "195a82ee849211716d07a344033750d1f83785d076e830b8be1fb80e15d28096" +
            "bca535ef4c058fbe21b34cf3670b2451faab3437a333c708a3947f20220ca362" +
            "cfa8e7afe95634e32b22af821df2d6786d5ff637cf3abfe3f05e4190a42779b5" +
            "76470532cbd56a798105db4cd01f504b07082c3740c69c000000a6000000504b" +
            "0304140008080800c28ee8440000000000000000000000000f0000004d455441" +
            "2d494e462f4f4c442e534675cf416f823018c6f13b09dfa1c7ed50575014493c" +
            "80bae8a6a091383db6f082955a595b86ecd34fb3db92dd9ed32fff67c74b494d" +
            "a300ef41697e9501727ac4b6768b10bbde10cf7809dae03595bcf81d5ce2d018" +
            "c5596340072872bdecd02554f17c70f16fc730cfb4a8c6b0ad934fdfb6d0e854" +
            "fbb794ecd52eeecbe5e45f394071bd9ef30c34131f87a3e956c561efb0938e56" +
            "b7573d5c6cd3599bbe2ddd28acfafe9d992aa006721c758fe2718fe0b6753c6f" +
            "e410cca50125a9c005d52607d694e82951341380a657555f1535f7a3cfb6655b" +
            "31bd4080c2bf55016a98a8f2512948f7e5968dfbbe495fcca6383bdfe753a467" +
            "90ca41a297861544cd270fe807504b0708521a35550201000048010000504b03" +
            "04140008080800c28ee844000000000000000000000000100000004d4554412d" +
            "494e462f4f4c442e52534185947b38d37d1fc7fdb61923530eb39e688622a77e" +
            "5b666ab9939c9544c831ee39d42c9511e576d8c8b9876745b5743b5414268718" +
            "b969548e95d61c2224b47423a648393d73dd5d4fe5b9afe7f9f3f3b9deefeff5" +
            "bd3e9ff7e7053210ba708476bc55fc6719400292c340a8800cc4460800e06441" +
            "1938c23bde0af083c22080b81828fd5d08e430200f4006a41664003772a01000" +
            "028145a66e3cba6af9a601a44516246e1d2805873ac1a0f2d093545f70b3920c" +
            "ce00248246e04ec20e02e8262a09200e6ff0adfc2f3d0350fbf149d12fa00c40" +
            "564cd497823000406cb4ef6571fdec06c95a370fde860e89425861e16e0b19a1" +
            "4c6c4f52d6102dd3a6bf45e740d9a0bed342ee5e832885fa1a979dc7e6161253" +
            "4c2cda0362e5ec02ae440e44e80c56bffc4ab4f349f3b1f6bddae8c2979fca4b" +
            "5e7e46ac2fa529481dd8d750a26dcd7d74deb8dde7fc70c072465af0a1e359af" +
            "4cf4b1aaa14fc4238cee096ca3133575d52179a7e7da8990f6ed5da71ca84c37" +
            "2d3117c356b1ac2cdde3c8600ead44f8878b2f8f10831f4d5d590a28d95b316c" +
            "cf456277a83533e12ea42a14c8fc05da6b2483b74ab7c4e70a2dd60f417d2f97" +
            "3359bb233e3d6b5ab60202e7bd7b4fd7f228febd1f0403b0c62f4190b69264bf" +
            "72426589ab516d00040a880137b0200654114d4d050943c11490e82d88b38ecd" +
            "26a42c027cd749ffe67955e7d9357b81aece4e877d49ee62b8afeed7a755fa9e" +
            "786d6edc5d7e5ae3603e5d6e39d0cbc74300c35eb354282842a183159763c67a" +
            "35b0b1ac1929e7b8ad510da5aa454d5eb3034cf11d27f594326384873f7c8949" +
            "1a7f4a3cfccb892faf873c621292bd597072cdcd732d1ee7490e611ec28c90f9" +
            "81bbd71ef0e4b50c4e93869c9af8c9d3d154169bbf8dff7aa706ebac1f6d2589" +
            "8078532a9867e31d7f576365bc0c6c132fb7719b58d88ebcf438715768aea361" +
            "f7017f614acfaf465e6d794b84e317b2937d66a8f5cea13666d541cce02705a8" +
            "cef2d6d2f69b96ac88fec9998228a7c1d96d09c315b29797ecda02250dc7383a" +
            "6f2bf75b0f17d48c07afa48c640d3478dd3ad18963c0712003ae27ca3a885a9b" +
            "bcef71fef1027e8e228c01885dede0446717674996f8f413f59a5c3f91a001c0" +
            "b632f78891424ce58a58df39727a6cc492ea4c94d5f23f6ad1276561d6eb057f" +
            "befab3e5baff5dec50224a891fb49c29ab6cd6cab3f7731d097fc3d65e949b33" +
            "b7a578f0b379e47a98c0a91b5fb0ce685952a78afc5cfa0e373df4a035c15132" +
            "bb62a6387ad8caaadab680b8a169805e49b69e5ad8409de9b7079b6def2af45a" +
            "dada5e7cdee4b5359892d1803dfc765dd18849fd0bf44b64866ed654fc117ea6" +
            "5e6b1922eff99b73f0c77257e994d307defc9a29cedd1ac7be68a626d6edf96e" +
            "c692bdf22ee792369dd535a0dfa7d711396996f692895df223eaaa5f47414f45" +
            "b2c3b39d65772575c5cf4a437319b00990011b834b7f1b0c623d048963c07a44" +
            "4dfe4f60813d12b5b87f0b16ea0f6e0096e30973075d454238d411260ee2d6a8" +
            "61aa68a6bb3119ab837aaa39abd612eab39f9da4665c977e0cff019201a19384" +
            "d3222b5a160ffe07340620c10d220998e65d3431411e13916b4e44ae191143fa" +
            "be91cb1605ff6d2db96470d220e2af3c40f6ed5d032ee2cfe022bafd240fa1fd" +
            "5f6e3567dd9071543e381b57bdd80cb1575a376abcc947495167e61e6e32a1ae" +
            "93d416c8bc197b6bda347e6bcc6553839ed0a78d7ac28d57f2f7f8cd857d14e4" +
            "497caa1cd34e1829aa7574aa3984ad39934e765598bdf0356c31a8f5c4a316d6" +
            "c6beedf3add584c5769a6901a7836d3b32e3a03c58c739a6969e0260a002f463" +
            "2773e84174c7faa41086e0d5d0ceaefc4df5a443d4e8af8a462dd67181ce11be" +
            "a6ef64ce907252a0d0e6f03ef467e6763c244c48574856017d294607d3f87d5b" +
            "0eb9eba2c60dc6c70dddfb837914babf172574ef99a4bb1e361353de4d95c576" +
            "c494e1e9bce62d4fd08ad5e731691d1895636525bb79d76b2f1ce07d1604aa95" +
            "8d9dfc8b5b6ee01110b3ca2d75181a44c528300ede3c612715c04d7baf786a18" +
            "87b5f95861180fa256055b006005b67aa0923a70405c5c022af903f0187b3458" +
            "748cc0bdfeac15adcb7029a17883d0f5ef80573caa61574bb66747bc61aae3ca" +
            "0c3fe8bcd223d54fd7f5a2270843f9559a99f76aff45d965ba91374fa05a706f" +
            "479416999389d4e29eb74a1984a30face935fd2118a580a3cd5a6567c2e21e92" +
            "bb358c54822aaa049a8f47712889ee4507ae4ffd150ba50f444f99701c49fa97" +
            "72edc44e69cfcda9cffc9f75c49fdb6b28b9699a2567289979dfd42664626232" +
            "75b682be2baf3856623e37bb47c15e3e8e084771e4f3e7b53265efbc48e616cd" +
            "ed18c4a546de3f5bd793857c9c7a99615161b7cdcca53fa1d4c119a33bb150a4" +
            "6e4996450724eed76ae3d2dbc02f147228353241beba27e585a3dd556b8e5c2e" +
            "5f4a3328fd214663223ec27d4fb134873a85630026a204ee5e059ee29a807f3f" +
            "82ffcd3b2437cc1bf95a6813dec5195b38cd227002c6f5f53dcd1212f7182038" +
            "9877b7ddf1ff2c0c65993b9524d3b41eca82ded2b951471c871b741fddd9fdc7" +
            "70a79bb91c3d91f3a9aa8f880ec22099418c65654a7e3a25c4d8925e98e1e390" +
            "1437e8767c9debf521e5c93ad5026ab8e5ef9696983ba6d0335e17de6ba65747" +
            "91a0fb6ecdf20bde55874082a7f19b4332ddbba24d4eb98a9b0e55e4294f37be" +
            "edd62a3e3ebccf8ded49f3a029fa76becf3389a386d2d62faca4f85cbb643489" +
            "2e2abfd66dbe727f4fe28d8f8d551e0f8f4c8eda346c66d1bc6fa7a68eabdfb7" +
            "0d0a34e65910bba73413ad8680dfaa473753ae9e2fd032d8f6cc66d19c57e679" +
            "7fcc33cce8df504b0708267c480f1b08000030090000504b0304140008080800" +
            "b78ee844000000000000000000000000090004004d4554412d494e462ffeca00" +
            "000300504b0708000000000200000000000000504b0304140008080800b78ee8" +
            "440000000000000000000000000100000041f3cb2fc9c8cc4be70200504b0708" +
            "3c0a34d30a00000008000000504b01021400140008080800c28ee8442c3740c6" +
            "9c000000a60000001400000000000000000000000000000000004d4554412d49" +
            "4e462f4d414e49464553542e4d46504b01021400140008080800c28ee844521a" +
            "355502010000480100000f00000000000000000000000000de0000004d455441" +
            "2d494e462f4f4c442e5346504b01021400140008080800c28ee844267c480f1b" +
            "0800003009000010000000000000000000000000001d0200004d4554412d494e" +
            "462f4f4c442e525341504b01021400140008080800b78ee84400000000020000" +
            "00000000000900040000000000000000000000760a00004d4554412d494e462f" +
            "feca0000504b01021400140008080800b78ee8443c0a34d30a00000008000000" +
            "0100000000000000000000000000b30a000041504b0506000000000500050027" +
            "010000ec0a00000000";
        byte[] data = new byte[var.length()/2];
        for (int i=0; i<data.length; i++) {
            data[i] = Integer.valueOf(var.substring(2*i,2*i+2), 16).byteValue();
        }
        Files.write(Paths.get("x.jar"), data);

        try (JarFile jf = new JarFile("x.jar")) {
            JarEntry je = jf.getJarEntry("A");
            try (InputStream is = jf.getInputStream(je)) {
                is.read(new byte[10]);
            }
            if (je.getCertificates().length != 1) {
                throw new Exception();
            }
        }
    }
}
