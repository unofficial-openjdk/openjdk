/*
 * @test    @(#)EdgeNoOpCrash.java	1.1 08/10/02
 * @bug     6726779
 * @summary Test verifies that ConvolveOp with the EDGE_NO_OP edge condition
 *          does not cause JVM crash if size of source raster elements is
 *          greather than size of the destination raster element.
 *
 * @run     main EdgeNoOpCrash
 */
import java.awt.Point;
import java.awt.image.ConvolveOp;
import java.awt.image.DataBuffer;
import java.awt.image.ImagingOpException;
import java.awt.image.Kernel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.util.Arrays;

public class EdgeNoOpCrash {
    private static final int w = 3000;
    private static final int h = 200;
    
    public static void main(String[] args) {
        crashTest();
    }
    
    private static void crashTest() {
        Raster src = createSrcRaster();
        WritableRaster dst = createDstRaster();
        ConvolveOp op = createConvolveOp(ConvolveOp.EDGE_NO_OP);
        try {
            op.filter(src, dst);
        } catch (ImagingOpException e) {
            /* 
             * The test pair of source and destination rasters
             * may cause failure of the medialib convolution routine,
             * so this exception is expected.
             * 
             * The JVM crash is the only manifestation of this
             * test failure.
             */
        }
        System.out.println("Test PASSED.");
    }
    
    private static Raster createSrcRaster() {
        WritableRaster r = Raster.createInterleavedRaster(DataBuffer.TYPE_USHORT,
                w, h, 4, new Point(0, 0));
        
        return r;
    }
    
    private static WritableRaster createDstRaster() {
        WritableRaster r = Raster.createInterleavedRaster(DataBuffer.TYPE_BYTE,
                w, h, 4, new Point(0, 0));

        return r;
    }
    
    private static ConvolveOp createConvolveOp(int edgeHint) {
        final int kw = 3;
        final int kh = 3;
        float[] kdata = new float[kw * kh];
        float v = 1f / kdata.length;
        Arrays.fill(kdata, v);
        
        Kernel k = new Kernel(kw, kh, kdata);
        ConvolveOp op = new ConvolveOp(k, edgeHint, null);
        
        return op;
    }
}
