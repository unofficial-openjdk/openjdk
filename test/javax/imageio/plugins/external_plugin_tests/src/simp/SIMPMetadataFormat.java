package simp;

import java.util.Arrays;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadataFormat;
import javax.imageio.metadata.IIOMetadataFormatImpl;

public class SIMPMetadataFormat extends IIOMetadataFormatImpl {

    private static IIOMetadataFormat instance = null;

    public static synchronized IIOMetadataFormat getInstance() {
        if (instance == null) {
            instance = new SIMPMetadataFormat();
        }
        return instance;
    }

    public boolean canNodeAppear(String elementName,
                                 ImageTypeSpecifier imageType) {
        return true;
    }

    private SIMPMetadataFormat() {
        super(SIMPMetadata.nativeMetadataFormatName,
              CHILD_POLICY_SOME);

        addElement("ImageDescriptor",
                   SIMPMetadata.nativeMetadataFormatName,
                   CHILD_POLICY_EMPTY);

        addAttribute("ImageDescriptor", "width",
                     DATATYPE_INTEGER, true, null,
                     "1", "127", true, true);
        addAttribute("ImageDescriptor", "height",
                     DATATYPE_INTEGER, true, null,
                     "1", "127", true, true);
    }
}
