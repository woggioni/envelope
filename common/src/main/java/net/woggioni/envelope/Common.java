package net.woggioni.envelope;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.AbstractMap;
import java.util.Map;
import java.util.MissingFormatArgumentException;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

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

    /**
     * @param template  Template text containing the variables to be replaced by this method. <br>
     *                  Variables follow the format ${variable_name}. <br>
     *                  Example: <br>
     *                  "This template was created by ${author}."
     * @param valuesMap A hashmap with the values of the variables to be replaced. <br>
     *                  The key is the variable name and the value is the value to be replaced in the template. <br>
     *                  Example: <br>
     *                  {"author" =&gt; "John Doe"}
     * @return The template text (String) with the variable names replaced by the values passed in the map. <br>
     * If any of the variable names is not contained in the map it will be replaced by an empty string. <br>
     * Example: <br>
     * "This template was created by John Doe."
     */
    public static String renderTemplate(String template, Map<String, Object> valuesMap) {
        return renderTemplate(template, valuesMap, null);
    }


    public static int indexOfWithEscape(String haystack, char needle, char escape, int begin, int end) {
        int result = -1;
        int cursor = begin;
        if (end == 0) {
            end = haystack.length();
        }
        int escapeCount = 0;
        while (cursor < end) {
            char c = haystack.charAt(cursor);
            if (escapeCount > 0) {
                --escapeCount;
                if (c == escape) {
                    result = -1;
                }
            } else if (escapeCount == 0) {
                if (c == escape) {
                    ++escapeCount;
                }
                if (c == needle) {
                    result = cursor;
                }
            }
            if (result >= 0 && escapeCount == 0) {
                break;
            }
            ++cursor;
        }
        return result;
    }

    public static String renderTemplate(
        String template,
        Map<String, Object> valuesMap,
        Map<String, Map<String, Object>> dictMap) {
        StringBuilder sb = new StringBuilder();
        Object absent = new Object();

        int cursor = 0;
        TokenScanner tokenScanner = new TokenScanner(template, '$', '$');
        while (cursor < template.length()) {
            tokenScanner.next();
            int nextPlaceHolder;
            switch (tokenScanner.getTokenType()) {
                case TOKEN: {
                    nextPlaceHolder = tokenScanner.getTokenIndex();
                    while (cursor < nextPlaceHolder) {
                        char ch = template.charAt(cursor++);
                        sb.append(ch);
                    }
                    if (cursor + 1 < template.length() && template.charAt(cursor + 1) == '{') {
                        String key;
                        String context = null;
                        String defaultValue = null;
                        Object value;
                        int end = template.indexOf('}', cursor + 1);
                        int colon;
                        if (dictMap == null)
                            colon = -1;
                        else {
                            colon = indexOfWithEscape(template, ':', '\\', cursor + 1, template.length());
                            if (colon >= end) colon = -1;
                        }
                        if (colon < 0) {
                            key = template.substring(cursor + 2, end);
                            value = valuesMap.getOrDefault(key, absent);
                        } else {
                            context = template.substring(cursor + 2, colon);
                            int secondColon = indexOfWithEscape(template, ':', '\\', colon + 1, end);
                            if (secondColon < 0) {
                                key = template.substring(colon + 1, end);
                            } else {
                                key = template.substring(colon + 1, secondColon);
                                defaultValue = template.substring(secondColon + 1, end);
                            }
                            value = Optional.ofNullable(dictMap.get(context))
                                .map(m -> m.get(key))
                                .orElse(absent);
                        }
                        if (value != absent) {
                            sb.append(value.toString());
                        } else {
                            if (defaultValue != null) {
                                sb.append(defaultValue);
                            } else {
                                throw new MissingFormatArgumentException(
                                    String.format("Missing value for placeholder '%s'",
                                        context == null ? key : context + ':' + key
                                    )
                                );
                            }
                        }
                        cursor = end + 1;
                    }
                    break;
                }
                case ESCAPE:
                    nextPlaceHolder = tokenScanner.getTokenIndex();
                    while (cursor < nextPlaceHolder) {
                        char ch = template.charAt(cursor++);
                        sb.append(ch);
                    }
                    cursor = nextPlaceHolder + 1;
                    sb.append(template.charAt(cursor++));
                    break;
                case END:
                default:
                    nextPlaceHolder = template.length();
                    while (cursor < nextPlaceHolder) {
                        char ch = template.charAt(cursor++);
                        sb.append(ch);
                    }
                    break;
            }
        }
        return sb.toString();
    }

    public static <T> Stream<T> opt2Stream(Optional<T> opt) {
        return opt.map(Stream::of).orElse(Stream.empty());
    }
}
