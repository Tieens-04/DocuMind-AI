package com.documind.infrastructure.storage;

import com.documind.exception.DocumentTooLargeException;
import com.documind.exception.InvalidDocumentRequestException;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public class HashingBoundedInputStream extends FilterInputStream {

    public static final long MAX_FILE_SIZE_BYTES = 52_428_800L;

    private final MessageDigest digest;
    private final long maxSizeBytes;
    private long bytesRead;
    private String sha256Hex;

    public HashingBoundedInputStream(InputStream delegate, long maxSizeBytes) {
        this(Objects.requireNonNull(delegate, "delegate must not be null"), newSha256Digest(), maxSizeBytes);
    }

    private HashingBoundedInputStream(InputStream delegate, MessageDigest digest, long maxSizeBytes) {
        super(new DigestInputStream(delegate, digest));
        if (maxSizeBytes < 0) {
            throw new IllegalArgumentException("maxSizeBytes must be >= 0");
        }
        this.digest = digest;
        this.maxSizeBytes = maxSizeBytes;
    }

    @Override
    public int read() throws IOException {
        enforceSizeLimit();
        int b = in.read();
        if (b != -1) {
            bytesRead++;
            enforceSizeLimit();
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len == 0) {
            return 0;
        }
        enforceSizeLimit();
        long remaining = maxSizeBytes - bytesRead;
        int maxToRead = (remaining >= len) ? len : (int) (remaining + 1L);
        int n = in.read(b, off, maxToRead);
        if (n > 0) {
            bytesRead += n;
            enforceSizeLimit();
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        if (n <= 0) {
            return 0L;
        }
        byte[] scratch = new byte[(int) Math.min(n, 4096L)];
        long remaining = n;
        while (remaining > 0) {
            int nr = read(scratch, 0, (int) Math.min(remaining, scratch.length));
            if (nr < 0) {
                break;
            }
            remaining -= nr;
        }
        return n - remaining;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    @Override
    public void mark(int readlimit) {
        // Single-pass SHA-256 stream does not support mark/reset
    }

    @Override
    public void reset() throws IOException {
        throw new IOException("mark/reset not supported on HashingBoundedInputStream");
    }

    public long bytesRead() {
        return bytesRead;
    }

    public String sha256Hex() {
        if (bytesRead == 0) {
            throw new InvalidDocumentRequestException("Cannot compute SHA-256 hash of an empty (0-byte) stream");
        }
        if (sha256Hex == null) {
            sha256Hex = HexFormat.of().formatHex(digest.digest());
        }
        return sha256Hex;
    }

    private void enforceSizeLimit() {
        if (bytesRead > maxSizeBytes) {
            throw new DocumentTooLargeException(
                maxSizeBytes,
                "Document exceeds maximum allowed size of " + maxSizeBytes + " bytes"
            );
        }
    }

    private static MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 algorithm not available", ex);
        }
    }
}
