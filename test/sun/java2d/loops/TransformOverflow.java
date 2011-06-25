/**
 * @test %W% %E%
 * @bug 7023640
 * @summary Checks for malloc overflow when we transform an image
 *          into a really tall destination
 * @run main/othervm/timeout=5000 -Xmx1g TransformOverflow
 */

import java.awt.image.BufferedImage;
import java.awt.Graphics2D;

public class TransformOverflow {
    public static void main(String argv[]) {
        test(1, 0x20000000);
        System.out.println("done");
    }

    public static void test(int w, int h) {
        BufferedImage bimg =
            new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g2d = bimg.createGraphics();
        g2d.scale(1.0, Math.PI);
        g2d.shear(0.0, 1.0);
        try {
            g2d.drawImage(bimg, 0, 0, null);
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
