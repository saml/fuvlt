package fuvlt;

//http://docs.oracle.com/javase/tutorial/essential/io/examples/WatchDir.java

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchEvent.Kind;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;

/**
 * Example to watch a directory (or tree) for changes to files.
 */

public class WatchDir {

    private final WatchService watcher;
    private final Map<WatchKey,Path> keys;
    private final boolean recursive;
    private boolean trace = false;
    private final Path baseDir;
    private final Worker worker;

    private static final String[] IGNORES = {
        ".svn",
        ".vlt",
        "/svn-",
        "/vlt-",
        ".xml"
    };
    

    private static final String endpoint = "http://localhost:4502";

    
    private static class Worker implements Runnable {
        private final ConcurrentMap<Path, Kind<?>> map;
        public Worker() {
            map = new ConcurrentHashMap<Path, Kind<?>>();
        }
        
        public void push(WatchEvent.Kind<?> kind, Path path) {
            map.put(path, kind);
            System.out.format("push: %s\n", map);
        }
        
        private void putFile(Path path) {
            System.out.format("putFile: %s\n", path);
        }
        
        private void removeFile(Path path) {
            System.out.format("removeFile: %s\n", path);
        }
        
        @Override
        public void run() {
            while (true) {
                if (map.isEmpty()) {
                    try {
                        Thread.sleep(2000L);
                    } catch (InterruptedException e) {
                    }
                } else {
                    final Iterator<Entry<Path, Kind<?>>> iter = map.entrySet().iterator();
                    while (iter.hasNext()) {
                        final Entry<Path, Kind<?>> entry = iter.next();
                        final Path path = entry.getKey();
                        final Kind<?> kind = entry.getValue();
                        if (ENTRY_DELETE.equals(kind)) {
                            removeFile(path);
                        } else {
                            putFile(path);
                        }
                        iter.remove();
                    }
                    System.out.format("%s\n", map);
                }
            }
        }
        
    }
    
    Worker getWorker() {
        return worker;
    }
    
    @SuppressWarnings("unchecked")
    static <T> WatchEvent<T> cast(WatchEvent<?> event) {
        return (WatchEvent<T>)event;
    }

    /**
     * Register the given directory with the WatchService
     */
    private void register(Path dir) throws IOException {
        WatchKey key = dir.register(watcher, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
        if (trace) {
            Path prev = keys.get(key);
            if (prev == null) {
                System.out.format("register: %s\n", dir);
            } else {
                if (!dir.equals(prev)) {
                    System.out.format("update: %s -> %s\n", prev, dir);
                }
            }
        }
        keys.put(key, dir);
    }

    /**
     * Register the given directory, and all its sub-directories, with the
     * WatchService.
     */
    private void registerAll(final Path start) throws IOException {
        // register directory and sub-directories
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                throws IOException
            {
                if (!shouldIgnore(dir)) {
                    register(dir);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Creates a WatchService and registers the given directory
     */
    WatchDir(Path dir, boolean recursive) throws IOException {
        this.watcher = FileSystems.getDefault().newWatchService();
        this.keys = new HashMap<WatchKey,Path>();
        this.recursive = recursive;

        if (recursive) {
            System.out.format("Scanning %s ...\n", dir);
            registerAll(dir);
            System.out.println("Done.");
        } else {
            register(dir);
        }

        // enable trace after initial registration
        this.trace = true;
        
        baseDir = dir;
        worker = new Worker();
    }

    private static boolean shouldIgnore(Path path) {
        for (final String ignore : IGNORES) {
            if (path.toString().contains(ignore)) {
                return true;
            }
        }
        return false;
    }
    
    private void process(Kind<?> kind, Path path) {
        if (ENTRY_DELETE.equals(kind) || Files.isRegularFile(path, NOFOLLOW_LINKS)) {
            worker.push(kind, path);
        }
    }
    
    /**
     * Process all events for keys queued to the watcher
     */
    void processEvents() {
        for (;;) {

            // wait for key to be signalled
            WatchKey key;
            try {
                key = watcher.take();
            } catch (InterruptedException x) {
                return;
            }

            Path dir = keys.get(key);
            if (dir == null) {
                System.err.println("WatchKey not recognized!!");
                continue;
            }

            for (WatchEvent<?> event: key.pollEvents()) {
                final Kind<?> kind = event.kind();

                // TBD - provide example of how OVERFLOW event is handled
                if (kind == OVERFLOW) {
                    continue;
                }

                // Context for directory entry event is the file name of entry
                final WatchEvent<Path> ev = cast(event);
                final Path name = ev.context();
                final Path child = dir.resolve(name);

                if (!shouldIgnore(child)) {
                    // print out event
                    process(kind, child);
                    System.out.format("%s: %s\n", event.kind().name(), child);
                }

                // if directory is created, and watching recursively, then
                // register it and its sub-directories
                if (recursive && (kind == ENTRY_CREATE)) {
                    try {
                        if (Files.isDirectory(child, NOFOLLOW_LINKS)) {
                            registerAll(child);
                        }
                    } catch (IOException x) {
                        // ignore to keep sample readbale
                    }
                }
            }

            // reset key and remove from set if directory no longer accessible
            boolean valid = key.reset();
            if (!valid) {
                keys.remove(key);

                // all directories are inaccessible
                if (keys.isEmpty()) {
                    break;
                }
            }
        }
    }

    static void usage() {
        System.err.println("usage: java WatchDir [-r] dir");
        System.exit(-1);
    }

    public static void main(String[] args) throws IOException {
        // parse arguments
        if (args.length == 0 || args.length > 2)
            usage();
        boolean recursive = false;
        int dirArg = 0;
        if (args[0].equals("-r")) {
            if (args.length < 2)
                usage();
            recursive = true;
            dirArg++;
        }

        // register directory and process its events
        Path dir = Paths.get(args[dirArg]);
        final WatchDir watchDir = new WatchDir(dir, recursive);
        Executors.newFixedThreadPool(1).execute(watchDir.getWorker());
        watchDir.processEvents();
    }
}
