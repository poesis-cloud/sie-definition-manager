package com.sif.sie.definitionmanager.util;

import java.security.SecureRandom;
import java.util.UUID;

/** RFC 9562 UUIDv7 generator: Unix epoch milliseconds + cryptographic random. */
public final class UuidV7GeneratorUtil {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7GeneratorUtil() {}

    public static UUID generate() {
        long timestamp = System.currentTimeMillis();
        byte[] random = new byte[10];
        RANDOM.nextBytes(random);

        long msb = (timestamp << 16) | 0x7000L | ((random[0] & 0xFF) << 4) | ((random[1] & 0xF0) >>> 4);
        long lsb =
                (0x80L | (random[1] & 0x0FL)) << 56
                        | (long) (random[2] & 0xFF) << 48
                        | (long) (random[3] & 0xFF) << 40
                        | (long) (random[4] & 0xFF) << 32
                        | (long) (random[5] & 0xFF) << 24
                        | (long) (random[6] & 0xFF) << 16
                        | (long) (random[7] & 0xFF) << 8
                        | (long) (random[8] & 0xFF);

        return new UUID(msb, lsb);
    }
}
