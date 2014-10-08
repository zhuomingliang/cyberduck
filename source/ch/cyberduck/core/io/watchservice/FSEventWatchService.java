package ch.cyberduck.core.io.watchservice;

import ch.cyberduck.core.threading.NamedThreadFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import ch.cyberduck.core.io.watchservice.jna.CFArrayRef;
import ch.cyberduck.core.io.watchservice.jna.CFIndex;
import ch.cyberduck.core.io.watchservice.jna.CFRunLoopRef;
import ch.cyberduck.core.io.watchservice.jna.CFStringRef;
import ch.cyberduck.core.io.watchservice.jna.CarbonAPI;
import ch.cyberduck.core.io.watchservice.jna.FSEventStreamRef;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;

/**
 * This class contains the bulk of my implementation of the Watch Service API.
 *
 * @author Steve McLeod
 */
public class FSEventWatchService extends AbstractWatchService {

    private CFRunLoop thread;

    private ThreadFactory threadFactory
            = new NamedThreadFactory("fsevent");

    @Override
    public WatchKey register(final WatchableFile file,
                             final WatchEvent.Kind<?>[] events, final WatchEvent.Modifier... modifiers)
            throws IOException {
        final Map<File, Long> lastModifiedMap = createLastModifiedMap(file.getFile());
        final Pointer[] values = {
                CFStringRef.toCFString(file.getFile().getCanonicalPath()).getPointer()
        };
        final CFArrayRef pathsToWatch = CarbonAPI.INSTANCE.CFArrayCreate(null, values, CFIndex.valueOf(1), null);
        final MacOSXWatchKey watchKey = new MacOSXWatchKey(this, events);

        final double latency = 1.0; // Latency in seconds

        final long kFSEventStreamEventIdSinceNow = -1; // This is 0xFFFFFFFFFFFFFFFF
        final int kFSEventStreamCreateFlagUseCFTypes = 0x00000001;
        final int kFSEventStreamCreateFlagNoDefer = 0x00000002;
        final int kFSEventStreamCreateFlagIgnoreSelf = 0x00000008;
        final CarbonAPI.FSEventStreamCallback callback = new Callback(watchKey, lastModifiedMap);
        final FSEventStreamRef stream = CarbonAPI.INSTANCE.FSEventStreamCreate(
                Pointer.NULL,
                callback,
                Pointer.NULL,
                pathsToWatch,
                kFSEventStreamEventIdSinceNow,
                latency,
                kFSEventStreamCreateFlagNoDefer);
        threadFactory.newThread(thread = new CFRunLoop(stream)).start();
        return watchKey;
    }

    private static final class CFRunLoop implements Runnable {

        private final FSEventStreamRef streamRef;
        private CFRunLoopRef runLoop;

        public CFRunLoop(final FSEventStreamRef streamRef) {
            this.streamRef = streamRef;
        }

        @Override
        public void run() {
            runLoop = CarbonAPI.INSTANCE.CFRunLoopGetCurrent();
            final CFStringRef runLoopMode = CFStringRef.toCFString("kCFRunLoopDefaultMode");
            CarbonAPI.INSTANCE.FSEventStreamScheduleWithRunLoop(streamRef, runLoop, runLoopMode);
            CarbonAPI.INSTANCE.FSEventStreamStart(streamRef);
            CarbonAPI.INSTANCE.CFRunLoopRun();
        }

        public CFRunLoopRef getRunLoop() {
            return runLoop;
        }

        public FSEventStreamRef getStreamRef() {
            return streamRef;
        }
    }

    private Map<File, Long> createLastModifiedMap(File folder) {
        Map<File, Long> lastModifiedMap = new ConcurrentHashMap<File, Long>();
        for(File file : recursiveListFiles(folder)) {
            lastModifiedMap.put(file, file.lastModified());
        }
        return lastModifiedMap;
    }

    private static Set<File> recursiveListFiles(File folder) {
        Set<File> files = new HashSet<File>();
        if(folder.isDirectory()) {
            final File[] children = folder.listFiles();
            if(null == children) {
                return files;
            }
            for(File file : children) {
                if(file.isDirectory()) {
                    files.addAll(recursiveListFiles(file));
                }
                else {
                    files.add(file);
                }
            }
        }
        return files;
    }

    @Override
    void implClose() throws IOException {
        CarbonAPI.INSTANCE.CFRunLoopStop(thread.getRunLoop());
        CarbonAPI.INSTANCE.FSEventStreamStop(thread.getStreamRef());
        CarbonAPI.INSTANCE.FSEventStreamInvalidate(thread.getStreamRef());
        CarbonAPI.INSTANCE.FSEventStreamRelease(thread.getStreamRef());
    }


    private static final class MacOSXWatchKey extends AbstractWatchKey {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final boolean reportCreateEvents;
        private final boolean reportModifyEvents;
        private final boolean reportDeleteEvents;

        public MacOSXWatchKey(FSEventWatchService FSEventWatchService, WatchEvent.Kind<?>[] events) {
            super(FSEventWatchService);
            boolean reportCreateEvents = false;
            boolean reportModifyEvents = false;
            boolean reportDeleteEvents = false;

            for(WatchEvent.Kind<?> event : events) {
                if(event == StandardWatchEventKind.ENTRY_CREATE) {
                    reportCreateEvents = true;
                }
                else if(event == StandardWatchEventKind.ENTRY_MODIFY) {
                    reportModifyEvents = true;
                }
                else if(event == StandardWatchEventKind.ENTRY_DELETE) {
                    reportDeleteEvents = true;
                }
            }
            this.reportCreateEvents = reportCreateEvents;
            this.reportDeleteEvents = reportDeleteEvents;
            this.reportModifyEvents = reportModifyEvents;
        }

        @Override
        public boolean isValid() {
            return !cancelled.get() && watcher().isOpen();
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        public boolean isReportCreateEvents() {
            return reportCreateEvents;
        }

        public boolean isReportModifyEvents() {
            return reportModifyEvents;
        }

        public boolean isReportDeleteEvents() {
            return reportDeleteEvents;
        }
    }

    private static final class Callback implements CarbonAPI.FSEventStreamCallback {
        private final MacOSXWatchKey watchKey;
        private final Map<File, Long> lastModifiedMap;

        private Callback(MacOSXWatchKey watchKey, Map<File, Long> lastModifiedMap) {
            this.watchKey = watchKey;
            this.lastModifiedMap = lastModifiedMap;
        }

        public void invoke(FSEventStreamRef streamRef, Pointer clientCallBackInfo, NativeLong numEvents,
                           Pointer eventPaths, Pointer /* array of unsigned int */ eventFlags, /* array of unsigned long */ Pointer eventIds) {
            final int length = numEvents.intValue();
            for(String folderName : eventPaths.getStringArray(0, length)) {
                final Set<File> filesOnDisk = recursiveListFiles(new File(folderName));
                for(File file : findCreatedFiles(filesOnDisk)) {
                    if(watchKey.isReportCreateEvents()) {
                        watchKey.signalEvent(StandardWatchEventKind.ENTRY_CREATE, file);
                    }
                    lastModifiedMap.put(file, file.lastModified());
                }

                for(File file : findModifiedFiles(filesOnDisk)) {
                    if(watchKey.isReportModifyEvents()) {
                        watchKey.signalEvent(StandardWatchEventKind.ENTRY_MODIFY, file);
                    }
                    lastModifiedMap.put(file, file.lastModified());
                }

                for(File file : findDeletedFiles(folderName, filesOnDisk)) {
                    if(watchKey.isReportDeleteEvents()) {
                        watchKey.signalEvent(StandardWatchEventKind.ENTRY_DELETE, file);
                    }
                    lastModifiedMap.remove(file);
                }
            }
        }

        private List<File> findModifiedFiles(Set<File> filesOnDisk) {
            List<File> modifiedFileList = new ArrayList<File>();
            for(File file : filesOnDisk) {
                final Long lastModified = lastModifiedMap.get(file);
                if(lastModified != null && lastModified != file.lastModified()) {
                    modifiedFileList.add(file);
                }
            }
            return modifiedFileList;
        }

        private List<File> findCreatedFiles(Set<File> filesOnDisk) {
            List<File> createdFileList = new ArrayList<File>();
            for(File file : filesOnDisk) {
                if(!lastModifiedMap.containsKey(file)) {
                    createdFileList.add(file);
                }
            }
            return createdFileList;
        }

        private List<File> findDeletedFiles(String folderName, Set<File> filesOnDisk) {
            List<File> deletedFileList = new ArrayList<File>();
            for(File file : lastModifiedMap.keySet()) {
                if(file.getAbsolutePath().startsWith(folderName) && !filesOnDisk.contains(file)) {
                    deletedFileList.add(file);
                }
            }
            return deletedFileList;
        }
    }
}
