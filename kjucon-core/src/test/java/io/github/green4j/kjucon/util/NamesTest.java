/*
 * Copyright (c) 2026 Anatoly Gudkov and others.
 *
 * Licensed under the MIT License.
 * See the LICENSE file in the project root for details.
 */

package io.github.green4j.kjucon.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamesTest {
    private static final String LONG = "x".repeat(100);

    @ParameterizedTest
    @CsvSource(value = {"app, web, app-web", "app, null, app"}, nullValues = "null")
    void validNamesAreKeptAsTheyAre(final String parent,
                                    final String child,
                                    final String expected) {
        assertEquals(expected, Names.child(parent, child));
    }

    // texts which differ only in what sanitizing or truncating loses
    static Stream<Arguments> textsWhichMustStayApart() {
        return Stream.of(
                Arguments.of("Stream.One", "stream_one"),
                Arguments.of(LONG, LONG + "y"),
                Arguments.of("---", "..."));
    }

    @ParameterizedTest
    @MethodSource("textsWhichMustStayApart")
    void changedNamesAreValidStableAndDistinct(final String text,
                                               final String other) {
        final String name = Names.child("parent", text);
        assertTrue(Names.isDns1123Label(name), name);
        assertEquals(name, Names.child("parent", text), "the same on every call");
        assertNotEquals(name, Names.child("parent", other));
    }

    @ParameterizedTest
    @CsvSource(value = {"'', true", "My_Value.1-2, true", "-x, false", "a/b, false"})
    void labelValues(final String value,
                     final boolean valid) {
        assertEquals(valid, Names.isLabelValue(value));
    }
}
