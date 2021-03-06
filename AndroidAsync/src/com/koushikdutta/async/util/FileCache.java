package com.koushikdutta.async.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Random;

/**
 * Created by koush on 4/12/14.
 */
public class FileCache {
    class CacheEntry {
        final long size;
        public CacheEntry(File file) {
            size = file.length();
        }
    }

    public static class Snapshot {
        FileInputStream[] fins;
        long[] lens;
        Snapshot(FileInputStream[] fins, long[] lens) {
            this.fins = fins;
            this.lens = lens;
        }

        public long getLength(int index) {
            return lens[index];
        }

        public void close() {
            StreamUtility.closeQuietly(fins);
        }
    }

    public static String toKeyString(Object... parts) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            for (Object part: parts) {
                messageDigest.update(part.toString().getBytes());
            }
            byte[] md5bytes = messageDigest.digest();
            return new BigInteger(1, md5bytes).toString(16);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    Random random = new Random();
    public File getTempFile() {
        File f;
        while ((f = new File(directory, new BigInteger(128, random).toString(16))).exists());
        return f;
    }

    public File[] getTempFiles(int count) {
        File[] ret = new File[count];
        for (int i = 0; i < count; i++) {
            ret[i] = getTempFile();
        }
        return ret;
    }

    public static void removeFiles(File... files) {
        if (files == null)
            return;
        for (File file: files) {
            file.delete();
        }
    }

    public void remove(String key) {
        int i = 0;
        while (cache.remove(getPartName(key, i)) != null) {
            i++;
        }
        removePartFiles(key);
    }

    public boolean exists(String key, int part) {
        return getPartFile(key, part).exists();
    }

    public boolean exists(String key) {
        return getPartFile(key, 0).exists();
    }

    public File touch(File file) {
        cache.get(file.getName());
        file.setLastModified(System.currentTimeMillis());
        return file;
    }

    public FileInputStream get(String key) throws IOException {
        return new FileInputStream(touch(getPartFile(key, 0)));
    }

    public File getFile(String key) {
        return touch(getPartFile(key, 0));
    }

    public FileInputStream[] get(String key, int count) throws IOException {
        FileInputStream[] ret = new FileInputStream[count];
        try {
            for (int i = 0; i < count; i++) {
                ret[i] = new FileInputStream(touch(getPartFile(key, i)));
            }
        }
        catch (IOException e) {
            // if we can't get all the parts, delete everything
            for (FileInputStream fin: ret) {
                StreamUtility.closeQuietly(fin);
            }
            remove(key);
            throw e;
        }

        return ret;
    }

    String getPartName(String key, int part) {
        return key + "." + part;
    }

    public void commitTempFiles(String key, File... tempFiles) {
        removePartFiles(key);

        // try to rename everything
        for (int i = 0; i < tempFiles.length; i++) {
            File tmp = tempFiles[i];
            File partFile = getPartFile(key, i);
            if (!tmp.renameTo(partFile)) {
                // if any rename fails, delete everything
                removeFiles(tempFiles);
                remove(key);
                return;
            }
            remove(tmp.getName());
            cache.put(getPartName(key, i), new CacheEntry(partFile));
        }
    }

    void removePartFiles(String key) {
        int i = 0;
        File f;
        while ((f = getPartFile(key, i)).exists()) {
            f.delete();
            i++;
        }
    }

    File getPartFile(String key, int part) {
        return new File(directory, getPartName(key, part));
    }

    class InternalCache extends LruCache<String, CacheEntry> {
        public InternalCache() {
            super(size);
        }

        @Override
        protected long sizeOf(String key, CacheEntry value) {
            return value.size;
        }

        @Override
        protected void entryRemoved(boolean evicted, String key, CacheEntry oldValue, CacheEntry newValue) {
            super.entryRemoved(evicted, key, oldValue, newValue);
            if (newValue != null)
                return;
            new File(directory, key).delete();
        }
    }

    InternalCache cache;
    File directory;
    long size;

    Comparator<File> dateCompare = new Comparator<File>() {
        @Override
        public int compare(File lhs, File rhs) {
            long l = lhs.lastModified();
            long r = rhs.lastModified();
            if (l < r)
                return -1;
            if (r > l)
                return 1;
            return 0;
        }
    };

    void load() {
        File[] files = directory.listFiles();
        if (files == null)
            return;
        ArrayList<File> list = new ArrayList<File>();
        Collections.addAll(list, files);
        Collections.sort(list, dateCompare);

        for (File file: list) {
            String name = file.getName();
            CacheEntry entry = new CacheEntry(file);
            cache.put(name, entry);
            cache.get(name);
        }
    }

    public FileCache(File directory, long size, boolean loadAsync) {
        this.directory = directory;
        this.size = size;
        cache = new InternalCache();

        directory.mkdirs();

        if (loadAsync) {
            new Thread() {
                @Override
                public void run() {
                    load();
                }
            }.start();
        }
        else {
            load();
        }
    }

    public long size() {
        return cache.size();
    }

    public void clear() {
        removeFiles(directory.listFiles());
        cache.evictAll();
    }

    public void setMaxSize(long maxSize) {
        cache.setMaxSize(maxSize);
    }
}
