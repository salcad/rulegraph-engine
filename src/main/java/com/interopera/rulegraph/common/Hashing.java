package com.interopera.rulegraph.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Deterministic hashing helpers. Stable chunk ids are required for reproducible citations. */
public final class Hashing {

    private Hashing() {
    }

    /** SHA-256 of the input, returned as lowercase hex. */
    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Short, stable chunk id of the form {@code chunk_9c1a} (matches the brief's example shape). */
    public static String chunkId(int page, String text) {
        return "chunk_" + sha256Hex(page + "|" + text).substring(0, 4);
    }
}
