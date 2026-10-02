package org.hyzionstudios.mysticquests.narrative.value;

import org.hyzionstudios.mysticquests.narrative.value.QuestValue.BoolValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.DurationValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.EntityRefValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.IntValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.LocationValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.LongValue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §26.1: typed variable coercion and rejection. */
final class ValueCodecTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    private static Coercion coerce(String type, String json) throws Exception {
        return ValueCodec.coerce(TypeSpec.parse(type, List.of("idle", "awake")), json(json));
    }

    @Test
    void acceptsTheSpellingsMigratedV1ValuesArriveIn() throws Exception {
        assertEquals(new IntValue(12), coerce("integer", "\"12\"").value(), "v1 stored every variable as text");
        assertEquals(new IntValue(12), coerce("integer", "12").value());
        assertEquals(new LongValue(9_000_000_000L), coerce("long", "9000000000").value());
        assertEquals(new BoolValue(true), coerce("boolean", "\"TRUE\"").value());
        assertEquals(new DurationValue(Duration.ofSeconds(15)), coerce("duration", "\"15s\"").value());
        assertEquals(new DurationValue(Duration.ofMinutes(2)), coerce("duration", "\"PT2M\"").value());
        assertEquals(new DurationValue(Duration.ofMillis(500)), coerce("duration", "500").value());
        assertEquals(new LocationValue("avalon", 1, 64, -3), coerce("location", "\"avalon:1:64:-3\"").value());
        assertEquals(new EntityRefValue("generation", "hyzion:guard"), coerce("entity", "\"generation:hyzion:guard\"").value());
        assertEquals("uuid", ((EntityRefValue) coerce("entity", "\"9a3f1c2e-0000-4000-8000-000000000001\"").value()).kind());
    }

    /** Refusing is what stops a typo becoming silent zero, as it did in v1. */
    @Test
    void refusesValuesThatWouldChangeMeaning() throws Exception {
        assertFalse(coerce("integer", "4.5").accepted(), "truncation would change the story");
        assertFalse(coerce("integer", "\"four\"").accepted());
        assertFalse(coerce("integer", "3000000000").accepted(), "out of int range");
        assertFalse(coerce("boolean", "\"yes\"").accepted(), "only true and false");
        assertFalse(coerce("boolean", "1").accepted());
        assertFalse(coerce("double", "\"NaN\"").accepted());
        assertFalse(coerce("duration", "\"-5s\"").accepted());
        assertFalse(coerce("uuid", "\"not-a-uuid\"").accepted());
        assertFalse(coerce("enum", "\"asleep\"").accepted(), "enum values are a closed set");
        assertFalse(coerce("string", "{\"a\":1}").accepted(), "objects are not strings");
        assertFalse(coerce("integer", "null").accepted());

        Coercion element = coerce("list<integer>", "[1, \"x\", 3]");
        assertFalse(element.accepted());
        assertTrue(element.problem().contains("element 1"), "points at the bad element: " + element.problem());
    }

    @Test
    void everyTypeRoundTripsThroughItsPersistedForm() throws Exception {
        String[][] cases = {
                {"boolean", "true"}, {"integer", "7"}, {"long", "70000000000"}, {"double", "2.5"},
                {"string", "\"hi\""}, {"uuid", "\"9a3f1c2e-0000-4000-8000-000000000001\""},
                {"duration", "\"PT1H\""}, {"timestamp", "\"2026-10-02T12:00:00Z\""},
                {"location", "{\"world\":\"avalon\",\"x\":1.0,\"y\":2.0,\"z\":3.0}"},
                {"entity", "\"generation:abc\""}, {"list<string>", "[\"a\",\"b\"]"},
                {"set<integer>", "[1,2,3]"}, {"map<long>", "{\"a\":1,\"b\":2}"}, {"enum", "\"awake\""},
                {"list<map<boolean>>", "[{\"x\":true}]"}};
        for (String[] testCase : cases) {
            QuestValue value = coerce(testCase[0], testCase[1]).orThrow();
            QuestValue back = ValueCodec.coerce(TypeSpec.parse(testCase[0], List.of("idle", "awake")), ValueCodec.toJson(value)).orThrow();
            assertEquals(value, back, testCase[0]);
            assertTrue(ValueCodec.conforms(TypeSpec.parse(testCase[0], List.of("idle", "awake")), value), testCase[0]);
        }
    }

    @Test
    void setsDropDuplicatesAndKeepOrder() throws Exception {
        QuestValue.SetValue set = (QuestValue.SetValue) coerce("set<integer>", "[3,1,3,2]").orThrow();
        assertEquals(List.of(new IntValue(3), new IntValue(1), new IntValue(2)), List.copyOf(set.values()));
    }

    @Test
    void typeTextIsValidated() {
        assertEquals("map<list<long>>", TypeSpec.parse("map<list<long>>", null).toString());
        assertThrows(IllegalArgumentException.class, () -> TypeSpec.parse("list", null), "container without element");
        assertThrows(IllegalArgumentException.class, () -> TypeSpec.parse("integer<string>", null));
        assertThrows(IllegalArgumentException.class, () -> TypeSpec.parse("enum", List.of()), "enum without values");
        assertThrows(IllegalArgumentException.class, () -> TypeSpec.parse("float", null));
    }

    @Test
    void comparisonsAreTypedAndNumericAcrossWidths() {
        assertTrue(CompareOperator.EQ.test(new IntValue(4), new QuestValue.DoubleValue(4.0)));
        assertTrue(CompareOperator.GTE.test(new LongValue(4), new IntValue(4)));
        assertFalse(CompareOperator.GT.test(new IntValue(4), new IntValue(4)));
        assertTrue(CompareOperator.LT.test(new DurationValue(Duration.ofSeconds(1)), new DurationValue(Duration.ofSeconds(2))));
        assertFalse(CompareOperator.GT.test(new QuestValue.StringValue("b"), new QuestValue.StringValue("a")),
                "strings have no order here");
        assertTrue(CompareOperator.CONTAINS.test(
                new QuestValue.ListValue(List.of(new IntValue(1), new IntValue(2))), new LongValue(2)));
        assertTrue(CompareOperator.NE.test(null, new IntValue(1)), "unset is not equal to anything");
        assertFalse(CompareOperator.EQ.test(null, new IntValue(1)));
        assertEquals(CompareOperator.GTE, CompareOperator.parse(">="));
        assertEquals(CompareOperator.NE, CompareOperator.parse("ne"));
    }
}
