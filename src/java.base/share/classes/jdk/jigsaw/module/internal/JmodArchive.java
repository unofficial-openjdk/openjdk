package jdk.jigsaw.module.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * An Archive backed by a jmod file.
 */
public class JmodArchive implements Archive {
    private static final String JMOD_EXT = ".jmod";
    private final Path jmod;
    private final String moduleName;

    public JmodArchive(Path jmod) {
        String filename = jmod.getFileName().toString();
        if (!filename.endsWith(JMOD_EXT))
            throw new UnsupportedOperationException("Unsupported format: " + filename);
        this.jmod = jmod;
        moduleName = filename.substring(0, filename.length() - JMOD_EXT.length());
    }

    @Override
    public String moduleName() {
        return moduleName;
    }

    @Override
    public void visitResources(Consumer<Resource> consumer) {
        try (ZipFile zf = new ZipFile(jmod.toFile())) {
            zf.stream()
                .filter(ze -> !ze.isDirectory() &&
                        ze.getName().startsWith("classes"))
                .filter(ze -> !ze.getName().startsWith("classes/_") &&
                        !ze.getName().equals("classes/module-info.class"))
                .map(JmodArchive::toResource)
                .forEach(consumer::accept);
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }

    private static Resource toResource(ZipEntry ze) {
        String name = ze.getName();
        // trim the "classes/" path
        String fn = name.substring(name.indexOf('/') + 1);
        long entrySize = ze.getSize();
        return new Resource(fn, entrySize, 0 /* no compression support yet */);
    }

    @Override
    public void visitEntries(Consumer<Entry> consumer) {
        try (final ZipFile zf = new ZipFile(jmod.toFile())) {
            zf.stream()
              .filter(ze -> !ze.isDirectory() && !ze.getName().startsWith("classes/_"))
              .map(ze -> JmodArchive.toEntry(zf, ze))
              .forEach(consumer::accept);
        } catch (IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
    }

    static private class SimpleEntry implements Entry {
        private final String name;
        private final InputStream is;
        private final boolean isDirectory;
        SimpleEntry(String name, InputStream is, boolean isDirectory) {
            this.name = name;
            this.is = is;
            this.isDirectory = isDirectory;
        }
        public String getName() {
            return name;
        }
        public InputStream getInputStream() {
            return is;
        }
        public boolean isDirectory() {
            return isDirectory;
        }
    }

    private static Entry toEntry(ZipFile zf, ZipEntry ze) {
        try {
            return new SimpleEntry(ze.getName(), zf.getInputStream(ze), ze.isDirectory());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Consumer<Entry> defaultImageWriter(Path path, OutputStream out) {
        return new JmodEntryWriter(path, out);
    }
}

