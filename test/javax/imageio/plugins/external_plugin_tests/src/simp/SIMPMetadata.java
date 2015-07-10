package simp;

import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import org.w3c.dom.Node;

public class SIMPMetadata extends IIOMetadata {

   static final String nativeMetadataFormatName = "simp_metadata_1.0";

   private int width, height;

   public SIMPMetadata() {
       super(true,
             nativeMetadataFormatName, "simp.SIMPMetadataFormat", null, null);
   }

   public SIMPMetadata(int width, int height) {
       this();
       this.width = width;
       this.height = height;
   }

   public boolean isReadOnly() {
        return true;
   }

   public void setFromTree(String formatName, Node root) {
    }

    public void mergeTree(String formatName, Node root) {
        throw new IllegalStateException("read only metadata");
    }

    public void reset() {
        throw new IllegalStateException("read only metadata");
    }

    private IIOMetadataNode addChildNode(IIOMetadataNode root,
                                         String name,
                                         Object object) {
        IIOMetadataNode child = new IIOMetadataNode(name);
        if (object != null) {
            child.setUserObject(object);
            child.setNodeValue(object.toString());
        }
        root.appendChild(child);
        return child;
    }

    private Node getNativeTree() {
        IIOMetadataNode root =
            new IIOMetadataNode(nativeMetadataFormatName);
        addChildNode(root, "width", width);
        addChildNode(root, "weight", height);
        return root;
    }

    public Node getAsTree(String formatName) {
        if (formatName.equals(nativeMetadataFormatName)) {
            return getNativeTree();
        } else if (formatName.equals
                   (IIOMetadataFormatImpl.standardMetadataFormatName)) {
            return getStandardTree();
        } else {
            throw new IllegalArgumentException("unsupported format");
        }
    }
}

