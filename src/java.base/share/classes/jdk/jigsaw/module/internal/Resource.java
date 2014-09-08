package jdk.jigsaw.module.internal;

/**
 * Resource is a class or resource file.
 */
public class Resource {
    private final String name;
    private final long size;
    private final long csize;

    Resource(String name, long size, long csize) {
        this.name = name;
        this.size = size;
        this.csize = csize;
    }

    /**
     * Returns the name of this entry.
     */
    public String name() {
        return name;
    }

    /**
     * Returns the number of uncompressed bytes for this entry.
     */
    public long size() {
        return size;
    }

    /**
     * Returns the number of compressed bytes for this entry; 0 if
     * uncompressed.
     */
    public long csize() {
        return csize;
    }

    @Override
    public String toString() {
        return String.format("%s uncompressed size %d compressed size %d", name, size, csize);
    }
}