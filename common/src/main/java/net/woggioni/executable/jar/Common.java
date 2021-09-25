package net.woggioni.executable.jar;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import java.io.IOException;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class Common {
    final private static char[] hexArray = "0123456789ABCDEF".toCharArray();

    public static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    @SneakyThrows
    public static byte[] computeSHA256Digest(Supplier<InputStream> streamSupplier) {
        byte[] buffer = new byte[Constants.BUFFER_SIZE];
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return computeDigest(streamSupplier, md, buffer);
    }

    @SneakyThrows
    public static byte[] computeDigest(Supplier<InputStream> streamSupplier, MessageDigest md, byte[] buffer) {
        try(InputStream stream = new DigestInputStream(streamSupplier.get(), md)) {
            while(stream.read(buffer) >= 0) {}
        }
        return md.digest();
    }

    @SneakyThrows
    public static void computeSizeAndCrc32(
            ZipEntry zipEntry,
            InputStream inputStream,
            byte[] buffer) {
        CRC32 crc32 = new CRC32();
        long sz = 0L;
        while (true) {
            int read = inputStream.read(buffer);
            if (read < 0) break;
            sz += read;
            crc32.update(buffer, 0, read);
        }
        zipEntry.setSize(sz);
        zipEntry.setCompressedSize(sz);
        zipEntry.setCrc(crc32.getValue());
    }

    @SneakyThrows
    public static void write2Stream(InputStream inputStream, OutputStream os,
                                    byte[] buffer) {
        while (true) {
            int read = inputStream.read(buffer);
            if (read < 0) break;
            os.write(buffer, 0, read);
        }
    }

    public static void write2Stream(InputStream inputStream, OutputStream os) {
        write2Stream(inputStream, os, new byte[Constants.BUFFER_SIZE]);
    }

    public static Optional<Map.Entry<String, String>> splitExtension(String fileName) {
        int index = fileName.lastIndexOf('.');
        if (index == -1) {
            return Optional.empty();
        } else {
            return Optional.of(
                    new AbstractMap.SimpleEntry<>(fileName.substring(0, index), fileName.substring(index)));
        }
    }

    /**
     * Helper method to create an {@link InputStream} from a file without having to catch the possibly
     * thrown {@link IOException}, use {@link FileInputStream#FileInputStream(File)} if you need to catch it.
     * @param file the {@link File} to be opened
     * @return an open {@link InputStream} instance reading from the file
     */
    @SneakyThrows
    public static InputStream read(File file, boolean buffered) {
        InputStream result = new FileInputStream(file);
        return buffered ? new BufferedInputStream(result) : result;
    }

    /**
     * Helper method to create an {@link OutputStream} from a file without having to catch the possibly
     * thrown {@link IOException}, use {@link FileOutputStream#FileOutputStream(File)} if you need to catch it.
     * @param file the {@link File} to be opened
     * @return an open {@link OutputStream} instance writing to the file
     */
    @SneakyThrows
    public static OutputStream write(File file, boolean buffered) {
        OutputStream result = new FileOutputStream(file);
        return buffered ? new BufferedOutputStream(result) : result;
    }
}
