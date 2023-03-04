package net.woggioni.gradle.envelope;

import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import javax.annotation.Nonnull;

import lombok.Getter;
import lombok.SneakyThrows;
import org.gradle.api.resources.ReadableResource;
import org.gradle.api.resources.ResourceException;

final class LauncherResource implements ReadableResource {
    static final LauncherResource instance = new LauncherResource();

    private final URL url;
    @Getter
    private final byte[] hash;

    private LauncherResource() {
        url = getClass().getResource(String.format("/LIB-INF/%s", getDisplayName()));
        hash = computeHash();
    }

    @Override
    @Nonnull
    @SneakyThrows
    public InputStream read() throws ResourceException {
        return url.openStream();
    }

    @Override
    public String getDisplayName() {
        return getBaseName() + ".tar";
    }

    @Override
    @SneakyThrows
    public URI getURI() {
        return url.toURI();
    }

    @Override
    public String getBaseName() {
        return "launcher";
    }

    @SneakyThrows
    private byte[] computeHash() {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try(InputStream inputStream = new DigestInputStream(read(), md)) {
            byte[] buffer = new byte[0x10000];
            while(true) {
                int read = inputStream.read(buffer);
                if(read < 0) break;
            }
        }
        return md.digest();
    }
}