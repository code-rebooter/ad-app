package com.smart.android.ad_app.google.ima.local.envelope;

import java.security.MessageDigest;
import com.smart.android.ad_app.google.ima.local.portable.Base64Compat;
import java.util.Objects;

/**
 * Host-only framing and serialization with caller-supplied padding and block
 * transformation. This class does not collect inputs or generate random bytes.
 */
public final class EnvelopeFormat {
    public static final int MAX_CHUNK_PAYLOAD_BYTES = 255;
    public static final int TRANSFORMED_BLOCK_BYTES = 256;
    private static final int DIGEST_BYTES = 16;
    private static final int SERIALIZED_BLOCK_BYTES = 259;
    private static final int SERIALIZED_DIGEST_BYTES = 18;

    private EnvelopeFormat() {}

    @FunctionalInterface
    public interface BlockTransform {
        /** Receives a fresh 256-byte frame and must return exactly 256 bytes. */
        byte[] transform(byte[] framedBlock) throws Exception;
    }

    /**
     * Returns URL-safe Base64 without padding or line wrapping.
     *
     * Each paddingByChunk entry must contain exactly 255 minus that chunk's
     * payload length bytes. A full chunk therefore requires an explicit empty
     * padding array. Empty payloads are unsupported, with no fallback message.
     */
    public static String encode(
            byte[] payload,
            byte[][] paddingByChunk,
            BlockTransform blockTransform) throws Exception {
        return Base64Compat.getUrlEncoder().withoutPadding().encodeToString(
                encodeBytes(payload, paddingByChunk, blockTransform));
    }

    /**
     * Serializes field 1 as repeated transformed 256-byte blocks and field 2 as
     * the 16-byte MD5 digest of the complete, untransformed payload.
     */
    public static byte[] encodeBytes(
            byte[] payload,
            byte[][] paddingByChunk,
            BlockTransform blockTransform) throws Exception {
        Objects.requireNonNull(payload, "payload");
        if (payload.length == 0) {
            throw new UnsupportedOperationException("Empty payload is unsupported");
        }
        Objects.requireNonNull(paddingByChunk, "paddingByChunk");
        Objects.requireNonNull(blockTransform, "blockTransform");

        byte[] payloadSnapshot = payload.clone();
        int chunkCount = (payloadSnapshot.length - 1) / MAX_CHUNK_PAYLOAD_BYTES + 1;
        if (paddingByChunk.length != chunkCount) {
            throw new IllegalArgumentException(
                    "Expected " + chunkCount + " explicit padding entries, got "
                            + paddingByChunk.length);
        }
        long serializedLength = (long) SERIALIZED_BLOCK_BYTES * chunkCount
                + SERIALIZED_DIGEST_BYTES;
        if (serializedLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Serialized envelope exceeds Java array size");
        }

        // Snapshot and validate every explicit input before invoking caller code.
        byte[][] paddingSnapshot = new byte[chunkCount][];
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            int offset = chunkIndex * MAX_CHUNK_PAYLOAD_BYTES;
            int chunkLength = Math.min(
                    MAX_CHUNK_PAYLOAD_BYTES, payloadSnapshot.length - offset);
            int expectedPaddingLength = MAX_CHUNK_PAYLOAD_BYTES - chunkLength;
            byte[] padding = Objects.requireNonNull(
                    paddingByChunk[chunkIndex], "paddingByChunk[" + chunkIndex + "]");
            if (padding.length != expectedPaddingLength) {
                throw new IllegalArgumentException(
                        "Chunk " + chunkIndex + " requires " + expectedPaddingLength
                                + " padding bytes, got " + padding.length);
            }
            paddingSnapshot[chunkIndex] = padding.clone();
        }

        byte[] digest = MessageDigest.getInstance("MD5").digest(payloadSnapshot);
        if (digest.length != DIGEST_BYTES) {
            throw new IllegalStateException("MD5 provider returned an unexpected digest length");
        }
        byte[] serialized = new byte[(int) serializedLength];
        int position = 0;
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            int offset = chunkIndex * MAX_CHUNK_PAYLOAD_BYTES;
            int chunkLength = Math.min(
                    MAX_CHUNK_PAYLOAD_BYTES, payloadSnapshot.length - offset);
            byte[] frame = new byte[TRANSFORMED_BLOCK_BYTES];
            frame[0] = (byte) chunkLength;
            System.arraycopy(payloadSnapshot, offset, frame, 1, chunkLength);
            System.arraycopy(paddingSnapshot[chunkIndex], 0, frame, 1 + chunkLength,
                    paddingSnapshot[chunkIndex].length);
            byte[] transformed = blockTransform.transform(frame);
            if (transformed == null || transformed.length != TRANSFORMED_BLOCK_BYTES) {
                throw new IllegalStateException(
                        "Block transform must return exactly 256 bytes for chunk " + chunkIndex);
            }

            serialized[position++] = 0x0a; // Field 1, length-delimited.
            serialized[position++] = (byte) 0x80; // Unsigned varint 256.
            serialized[position++] = 0x02;
            System.arraycopy(transformed, 0, serialized, position, TRANSFORMED_BLOCK_BYTES);
            position += TRANSFORMED_BLOCK_BYTES;
        }
        serialized[position++] = 0x12; // Field 2, length-delimited.
        serialized[position++] = DIGEST_BYTES;
        System.arraycopy(digest, 0, serialized, position, DIGEST_BYTES);
        position += DIGEST_BYTES;
        if (position != serialized.length) {
            throw new AssertionError("Unexpected serialized envelope length");
        }
        return serialized;
    }
}
