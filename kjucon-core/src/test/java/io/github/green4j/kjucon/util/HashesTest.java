/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HashesTest {
    // FIPS 180-2, appendix B.1
    private static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @ParameterizedTest
    @ValueSource(ints = {Hashes.MAX_LENGTH, 8, 7})
    void hashIsTheBeginningOfTheSha256(final int length) {
        assertEquals(ABC.substring(0, length), Hashes.sha256("abc", length));
    }
}
