import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jdk.tools.jlink.internal.ResourcePoolImpl;
import jdk.tools.jlink.internal.plugins.asm.AsmPlugin;
import jdk.tools.jlink.internal.plugins.asm.AsmPools;
import jdk.tools.jlink.plugins.ResourcePool;
import jdk.tools.jlink.plugins.ResourcePool.Resource;
import jdk.tools.jlink.plugins.StringTable;

public abstract class AsmPluginTestBase {

    protected static final String TEST_MODULE = "jlink.test";
    protected static final Map<String, List<String>> MODULES;

    private static final Predicate<Resource> isClass = r -> r.getPath().endsWith(".class");
    private final List<String> classes;
    private final List<String> resources;
    private final ResourcePool pool;

    static {
        Map<String, List<String>> map = new HashMap<>();
        map.put("jdk.localedata", new ArrayList<>());
        map.put("java.base", new ArrayList<>());
        map.put(TEST_MODULE, new ArrayList<>());
        MODULES = Collections.unmodifiableMap(map);
    }

    public static boolean isImageBuild() {
        Path javaHome = Paths.get(System.getProperty("test.jdk"));
        Path jmods = javaHome.resolve("..").resolve("jmods");
        return Files.exists(jmods);
    }

    public AsmPluginTestBase() {
        try {
            List<String> classes = new ArrayList<>();
            List<String> resources = new ArrayList<>();

            pool = new ResourcePoolImpl(ByteOrder.nativeOrder());

            FileSystem fs = FileSystems.getFileSystem(URI.create("jrt:/"));
            Path root = fs.getPath("/modules");

            List<byte[]> moduleInfos = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(root)) {
                for (Iterator<Path> iterator = stream.iterator(); iterator.hasNext(); ) {
                    Path p = iterator.next();
                    if (Files.isRegularFile(p)) {
                        String module = p.toString().substring("/modules/".length());
                        module = module.substring(0, module.indexOf("/"));
                        if (MODULES.keySet().contains(module)) {
                            try {
                                boolean isModuleInfo = p.endsWith("module-info.class");
                                if (isModuleInfo) {
                                    moduleInfos.add(Files.readAllBytes(p));
                                }
                                byte[] content = Files.readAllBytes(p);
                                if (p.toString().endsWith(".class") && !isModuleInfo) {
                                    classes.add(toClassName(p));
                                } else if (!isModuleInfo) {
                                    MODULES.get(module).add(toResourceFile(p));
                                }
                                resources.add(toPath(p.toString()));
                                Resource res = new Resource(toPath(p.toString()), ByteBuffer.wrap(content));
                                pool.addResource(res);
                            } catch (Exception ex) {
                                throw new RuntimeException(ex);
                            }
                        }
                    }
                }
            }
            // There is more than 10 classes in java.base...
            if (classes.size() < 10 || pool.getResources().size() < 10) {
                throw new AssertionError("Not expected resource or class number");
            }

            //Add a fake resource file
            String content = "java.lang.Object";
            String path = "META-INF/services/com.foo.BarProvider";
            ResourcePool.Resource resFile = new ResourcePool.Resource("/" + TEST_MODULE + "/" +
                    path, ByteBuffer.wrap(content.getBytes()));
            pool.addResource(resFile);
            Resource fakeInfoFile = new Resource("/" + TEST_MODULE
                    + "/module-info.class", ByteBuffer.wrap(moduleInfos.get(0)));
            pool.addResource(fakeInfoFile);
            MODULES.get(TEST_MODULE).add(path);
            for(Map.Entry<String, List<String>> entry : MODULES.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    throw new AssertionError("No resource file for " + entry.getKey());
                }
            }
            this.classes = Collections.unmodifiableList(classes);
            this.resources = Collections.unmodifiableList(resources);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public List<String> getClasses() {
        return classes;
    }

    public List<String> getResources() {
        return resources;
    }

    public ResourcePool getPool() {
        return pool;
    }

    public abstract void test() throws Exception;

    public Collection<Resource> extractClasses(ResourcePool pool) {
        return pool.getResources().stream()
                .filter(isClass)
                .collect(Collectors.toSet());
    }

    public Collection<Resource> extractResources(ResourcePool pool) {
        return pool.getResources().stream()
                .filter(isClass.negate())
                .collect(Collectors.toSet());
    }

    public String getModule(String path) {
        int index = path.indexOf("/", 1);
        return path.substring(1, index);
    }

    public String removeModule(String path) {
        int index = path.indexOf("/", 1);
        return path.substring(index + 1);
    }

    private String toPath(String p) {
        return p.substring("/modules".length());
    }

    private String toClassName(Path p) {
        String path = p.toString();
        path = path.substring("/modules/".length());
        // remove module
        if (!path.endsWith("module-info.class")) {
            path = path.substring(path.indexOf("/") + 1);
        }
        path = path.substring(0, path.length() - ".class".length());

        return path;
    }

    private String toResourceFile(Path p) {
        String path = p.toString();
        path = path.substring("/modules/".length());
        // remove module
        path = path.substring(path.indexOf("/") + 1);

        return path;
    }

    public abstract class TestPlugin extends AsmPlugin {

        private AsmPools pools;

        public AsmPools getPools() {
            return pools;
        }

        public boolean isVisitCalled() {
            return pools != null;
        }

        public ResourcePool visit(ResourcePool inResources) throws IOException {
            try {
                ResourcePool outResources = new ResourcePoolImpl(inResources.getByteOrder());
                visit(inResources, outResources, new StringTable() {
                    @Override
                    public int addString(String str) {
                        return -1;
                    }

                    @Override
                    public String getString(int id) {
                        return null;
                    }
                });
                return outResources;
            } catch (Exception e) {
                throw new IOException(e);
            }
        }

        @Override
        public void visit(AsmPools pools, StringTable strings) throws IOException {
            if (isVisitCalled()) {
                throw new AssertionError("Visit was called twice");
            }
            this.pools = pools;
            visit();
        }

        public abstract void visit() throws IOException;
        public abstract void test(ResourcePool inResources, ResourcePool outResources) throws Exception;

        @Override
        public String getName() {
            return "test-plugin";
        }
    }
}
