package simptest;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormat;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;

public class TestSIMPPlugin {

    static byte[] simpData = { (byte)'S', (byte)'I', (byte)'M', (byte)'P',
                               1, 1, 0, 0, 0};

    public static void main(String args[]) throws Exception {
        Iterator<ImageReader> readers = ImageIO.getImageReadersBySuffix("simp");
        ImageReader simpReader = null;
        if (readers.hasNext()) {
            simpReader = readers.next();
            System.out.println("reader="+simpReader);
        }
        if (simpReader == null) {
            throw new RuntimeException("Reader not found.");
        }
        ByteArrayInputStream bais = new ByteArrayInputStream(simpData);
        ImageInputStream iis = new MemoryCacheImageInputStream(bais);
        simpReader.setInput(iis);
        BufferedImage bi = simpReader.read(0);
        System.out.println(bi);
        IIOMetadata metadata = simpReader.getImageMetadata(0);
        System.out.println("Image metadata="+metadata);
        IIOMetadataFormat format =
            metadata.getMetadataFormat("simp_metadata_1.0");
        System.out.println("Image metadata format="+format);
        if (format == null) {
            throw new RuntimeException("MetadataFormat not found.");
        }
    }
}
