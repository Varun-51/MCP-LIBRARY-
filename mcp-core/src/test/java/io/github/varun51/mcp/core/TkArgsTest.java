package io.github.varun51.mcp.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TkArgsTest {

    private final TkArgs tkArgs = new TkArgs(Map.of(
            "text", "hi",
            "count", 3,
            "wholeDouble", 4.0,
            "ratio", 0.5,
            "flag", true,
            "words", List.of("a", "b"),
            "numbers", List.of(1, 2.5),
            "ints", List.of(1L, 2),
            "filter", Map.of("k", "v")));

    @Test
    void mtkReadsEveryTypedValue() {
        assertEquals("hi", tkArgs.mtkString("text"));
        assertEquals(3, tkArgs.mtkInt("count"));
        assertEquals(4, tkArgs.mtkInt("wholeDouble"));
        assertEquals(0.5, tkArgs.mtkDouble("ratio"));
        assertTrue(tkArgs.mtkBoolean("flag", false));
        assertEquals(List.of("a", "b"), tkArgs.mtkStringList("words"));
        assertArrayEquals(new double[] {1, 2.5}, tkArgs.mtkDoubleArray("numbers"));
        assertEquals(List.of(1, 2), tkArgs.mtkIntList("ints"));
        assertEquals(Map.of("k", "v"), tkArgs.mtkMap("filter"));
    }

    @Test
    void mtkFallsBackToDefaultsWhenAbsent() {
        assertEquals("x", tkArgs.mtkString("absent", "x"));
        assertEquals(7, tkArgs.mtkInt("absent", 7));
        assertEquals(1.5, tkArgs.mtkDouble("absent", 1.5));
        assertFalse(tkArgs.mtkBoolean("absent", false));
        assertEquals(Map.of(), tkArgs.mtkMap("absent", Map.of()));
    }

    @Test
    void mtkTreatsNullAsAbsent() {
        Map<String, Object> tkValues = new HashMap<>();
        tkValues.put("text", null);
        TkArgs tkNullArgs = new TkArgs(tkValues);
        assertFalse(tkNullArgs.mtkHas("text"));
        assertEquals("missing argument: text", assertThrows(TkToolException.class, () -> tkNullArgs.mtkString("text")).getMessage());
    }

    @Test
    void mtkRejectsMissingAndMistypedValues() {
        assertEquals("missing argument: absent", assertThrows(TkToolException.class, () -> tkArgs.mtkString("absent")).getMessage());
        assertEquals("text must be number", assertThrows(TkToolException.class, () -> tkArgs.mtkInt("text")).getMessage());
        assertEquals("ratio must be an integer", assertThrows(TkToolException.class, () -> tkArgs.mtkInt("ratio")).getMessage());
        assertEquals("numbers must be string", assertThrows(TkToolException.class, () -> tkArgs.mtkStringList("numbers")).getMessage());
        assertEquals("numbers must be an integer", assertThrows(TkToolException.class, () -> tkArgs.mtkIntList("numbers")).getMessage());
    }

    @Test
    void mtkRejectsIntegerOverflow() {
        TkArgs tkHuge = new TkArgs(Map.of("n", 1L << 40));
        assertThrows(TkToolException.class, () -> tkHuge.mtkInt("n"));
    }

    @Test
    void mtkAcceptsNullArgumentMap() {
        assertFalse(new TkArgs(null).mtkHas("anything"));
    }
}
