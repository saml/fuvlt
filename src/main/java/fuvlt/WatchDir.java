package fuvlt;

//http://docs.oracle.com/javase/tutorial/essential/io/examples/WatchDir.java

import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.IOException;
import java.lang.InterruptedException;
import java.lang.Thread;
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
import java.util.Map;
import java.util.concurrent.*;

/**
 * Example to watch a directory (or tree) for changes to files.
 */

public class WatchDir {

    private final WatchService watcher;
    private final Map<WatchKey,Path> keys;
    private boolean trace = false;
    private final Path baseDir;
    private final Worker worker;

    private static final String[] IGNORES = {
        ".svn",
        ".vlt",
        "/svn-",
        "/vlt-",
        ".xml",
        ".git",
        //".swp",
        "__jb_"
    };
    

    private static final String endpoint = "http://localhost:4502/";


    private static class Pair<K,V> {
        private final K first;
        private final V second;
        public Pair(K first, V second) {
            this.first = first;
            this.second = second;
        }

        public K getFirst() {
            return first;
        }
        public V getSecond() {
            return second;
        }

        @Override
        public String toString() {
            return String.format("(%s,%s)", first, second);
        }
    }

    private static class Worker implements Runnable {

        private final BlockingQueue<Pair<Path, Kind<?>>> queue;
        private final BlockingQueue<Pair<Path, Kind<?>>> normalized;
        private final Path baseDir;
        private final String endpoint;

        public Worker(Path baseDir, String endpoint) {
            queue = new LinkedBlockingQueue<Pair<Path, Kind<?>>>();
            normalized = new LinkedBlockingQueue<Pair<Path, Kind<?>>>();
            this.baseDir = baseDir;
            this.endpoint = endpoint;
        }
        
        public void push(WatchEvent.Kind<?> kind, Path path) {
            queue.add(new Pair<Path, Kind<?>>(path, kind));
        }
        
        private void putFile(Path path) {
            path = baseDir.relativize(path);
            final String url = endpoint + path;

            final HttpPut put = new HttpPut(url);

            System.out.format("putFile: %s\n", url);
        }
        
        private void removeFile(Path path) {
            path = baseDir.relativize(path);
            final String url = endpoint + path;
            System.out.format("removeFile: %s\n", url);
        }
        
        @Override
        public void run() {
            while (true) {
                while (!queue.isEmpty()) {
                    try {
                        final Pair<Path, Kind<?>> pair = queue.take();
                        final Path path = pair.getFirst();
                        final Kind<?> kind = pair.getSecond();
                        if (ENTRY_DELETE.equals(kind)) {
                            removeFile(path);
                        } else {
                            putFile(path);
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
                    }
                }

                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException e) {

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
    WatchDir(Path dir) throws IOException {
        this.watcher = FileSystems.getDefault().newWatchService();
        this.keys = new HashMap<WatchKey,Path>();


        System.out.format("Scanning %s ...\n", dir);
        registerAll(dir);
        System.out.println("Done.");


        // enable trace after initial registration
        this.trace = true;
        
        baseDir = dir;
        worker = new Worker(baseDir, endpoint);
    }

    private static boolean shouldIgnore(Path path) {
        for (final String ignore : IGNORES) {
            if (path.toString().contains(ignore)) {
                return true;
            }
        }
        return false;
    }

    private static boolean shouldIgnore(Kind<?> kind) {
        return !(ENTRY_DELETE.equals(kind) || ENTRY_MODIFY.equals(kind));

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

                System.out.format("%s: %s\n", event.kind().name(), child);
                if (!shouldIgnore(child) && !shouldIgnore(kind)) {
                    // print out event
                    process(kind, child);

                }

                // if directory is created, and watching recursively, then
                // register it and its sub-directories
                if ((kind == ENTRY_CREATE)) {
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
        System.err.format("usage: java %s dir\n", WatchDir.class.getCanonicalName());
        System.exit(-1);
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            usage();
        }

        Path dir = Paths.get(args[0]);
        final WatchDir watchDir = new WatchDir(dir);
        Executors.newFixedThreadPool(1).execute(watchDir.getWorker());
        watchDir.processEvents();
    }
}
