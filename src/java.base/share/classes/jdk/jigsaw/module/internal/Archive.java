package jdk.jigsaw.module.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * An Archive of all content, classes, resources, configuration files, and
 * other, for a module.
 */
public interface Archive {
    /**
     * The module name.
     */
    String moduleName();

    /**
     * Visits all classes and resources.
     */
    void visitResources(Consumer<Resource> consumer);

    /**
     * Visits all entries in the Archive.
     */
    void visitEntries(Consumer<Entry> consumer) ;

    /**
     * An entries in the Archive.
     */
    interface Entry {
        String getName();
        InputStream getInputStream();
        boolean isDirectory();
    }

    /**
     * A Consumer suitable for writing Entries from this Archive.
     */
    Consumer<Entry> defaultImageWriter(Path path, OutputStream out);
}
