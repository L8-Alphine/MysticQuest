package org.hyzionstudios.mysticquests.narrative.condition;

import org.hyzionstudios.mysticquests.narrative.CompileContext;
import org.hyzionstudios.mysticquests.narrative.NarrativeTestKit;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.AtLeast;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.AtMost;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.Constant;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.Exactly;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.state.ScopeContext;
import org.hyzionstudios.mysticquests.narrative.state.ScopeSupport;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.IntValue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.id;
import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §4.3 and §26.1: condition truth tables and static analysis. */
final class ConditionTreeTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");

    private NarrativeTestKit kit;

    @BeforeEach
    void setUp() {
        kit = new NarrativeTestKit();
        kit.load("""
                {
                  "tagSchemas": [
                    { "id": "hyzion:druid_temple.entered", "scope": "player" },
                    { "id": "hyzion:druid_temple.completed", "scope": "player" }
                  ],
                  "variableSchemas": [
                    { "id": "hyzion:druid_temple.keys_found", "type": "integer", "scope": "player", "default": 0 },
                    { "id": "hyzion:inventory", "type": "list<string>", "scope": "player" }
                  ]
                }
                """);
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    private boolean test(Condition condition) {
        return kit.runtime().conditions().test(condition, ScopeContext.player(ALICE));
    }

    private Condition compile(String json, DiagnosticReport report) {
        CompileContext context = new CompileContext(kit.runtime().content().schemas(), ScopeSupport.standard(true),
                kit.runtime().conditionTypes(), kit.runtime().actionTypes(), "test");
        return ConditionCompiler.compile(parse(json), "cond", context, report);
    }

    private Condition compileClean(String json) {
        DiagnosticReport report = new DiagnosticReport();
        Condition condition = compile(json, report);
        assertTrue(report.isEmpty(), report.format());
        return condition;
    }

    /** Every combinator against every assignment of three leaves, checked against a reference. */
    @Test
    void combinatorTruthTables() {
        record Case(String name, Function<List<Condition>, Condition> build, Function<boolean[], Boolean> reference) {
        }
        List<Case> cases = List.of(
                new Case("all", Condition.All::new, values -> values[0] && values[1] && values[2]),
                new Case("any", Condition.Any::new, values -> values[0] || values[1] || values[2]),
                new Case("none", Condition.None::new, values -> !values[0] && !values[1] && !values[2]),
                new Case("at_least 2", children -> new AtLeast(2, children), values -> count(values) >= 2),
                new Case("at_most 1", children -> new AtMost(1, children), values -> count(values) <= 1),
                new Case("exactly 2", children -> new Exactly(2, children), values -> count(values) == 2),
                new Case("xor(a, not b)", children -> new Condition.Xor(children.get(0), new Condition.Not(children.get(1))),
                        values -> values[0] ^ !values[1]));
        for (Case testCase : cases) {
            for (int mask = 0; mask < 8; mask++) {
                boolean[] values = {(mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0};
                List<Condition> leaves = new ArrayList<>();
                for (boolean value : values) {
                    leaves.add(new Constant(value));
                }
                assertEquals(testCase.reference().apply(values), test(testCase.build().apply(leaves)),
                        testCase.name() + " with " + java.util.Arrays.toString(values));
            }
        }
    }

    private static int count(boolean[] values) {
        int count = 0;
        for (boolean value : values) {
            count += value ? 1 : 0;
        }
        return count;
    }

    /** The specification's own example tree, in the keyed spelling. */
    @Test
    void specificationExampleEvaluatesAgainstLiveState() {
        Condition gate = compileClean("""
                { "all": [
                    { "type": "tag", "tag": "hyzion:druid_temple.entered" },
                    { "type": "variable", "variable": "hyzion:druid_temple.keys_found", "op": ">=", "value": 4 },
                    { "not": { "type": "tag", "tag": "hyzion:druid_temple.completed" } }
                ] }
                """);
        ScopeContext alice = ScopeContext.player(ALICE);
        assertFalse(test(gate));
        kit.runtime().tags().add(alice, null, id("hyzion:druid_temple.entered"), null, "t");
        kit.runtime().variables().set(alice, null, id("hyzion:druid_temple.keys_found"), new IntValue(4));
        assertTrue(test(gate));
        kit.runtime().tags().add(alice, null, id("hyzion:druid_temple.completed"), null, "t");
        assertFalse(test(gate));
    }

    @Test
    void impossibleAndContradictoryTreesFailTheReload() {
        DiagnosticReport report = new DiagnosticReport();
        compile("""
                { "type": "at_least", "count": 3, "conditions": [
                    { "type": "tag", "tag": "hyzion:druid_temple.entered" } ] }
                """, report);
        assertTrue(report.has(DiagnosticCode.IMPOSSIBLE_CONDITION), report.format());

        report = new DiagnosticReport();
        compile("""
                { "all": [
                    { "type": "variable", "variable": "hyzion:druid_temple.keys_found", "op": ">=", "value": 5 },
                    { "type": "variable", "variable": "hyzion:druid_temple.keys_found", "op": "<", "value": 3 } ] }
                """, report);
        assertTrue(report.has(DiagnosticCode.CONTRADICTORY_CONDITION), "bounds with no overlap: " + report.format());

        report = new DiagnosticReport();
        compile("""
                { "all": [
                    { "type": "tag", "tag": "hyzion:druid_temple.entered" },
                    { "not": { "type": "tag", "tag": "hyzion:druid_temple.entered" } } ] }
                """, report);
        assertTrue(report.has(DiagnosticCode.CONTRADICTORY_CONDITION), report.format());

        report = new DiagnosticReport();
        compile("{ \"type\": \"any\", \"conditions\": [] }", report);
        assertTrue(report.has(DiagnosticCode.IMPOSSIBLE_CONDITION));

        report = new DiagnosticReport();
        compile("""
                { "type": "xor", "conditions": [ { "type": "tag", "tag": "hyzion:druid_temple.entered" },
                  { "type": "tag", "tag": "hyzion:druid_temple.completed" },
                  { "type": "tag", "tag": "hyzion:druid_temple.completed" } ] }
                """, report);
        assertTrue(report.hasErrors(), "n-ary xor is ambiguous and refused");
    }

    @Test
    void unknownReferencesAndBadOperatorsAreReported() {
        DiagnosticReport report = new DiagnosticReport();
        Condition condition = compile("""
                { "all": [
                    { "type": "tag", "tag": "hyzion:not.declared" },
                    { "type": "variable", "variable": "hyzion:druid_temple.keys_found", "op": ">=", "value": "lots" },
                    { "type": "variable", "variable": "hyzion:inventory", "op": ">", "value": ["x"] },
                    { "type": "tag", "tag": "hyzion:druid_temple.entered", "scope": "server" },
                    { "type": "mymod:not_registered" },
                    { "type": "questCompleted", "quest": "x" }
                ] }
                """, report);
        assertTrue(report.has(DiagnosticCode.UNKNOWN_TAG));
        assertTrue(report.has(DiagnosticCode.INVALID_VARIABLE_TYPE));
        assertTrue(report.has(DiagnosticCode.INVALID_PARAMETER), "ordering on a list");
        assertTrue(report.has(DiagnosticCode.INVALID_SCOPE));
        assertTrue(report.has(DiagnosticCode.UNKNOWN_CONDITION), "unregistered type, and v1 type without the bridge");
        assertFalse(test(condition), "a tree with errors fails closed");
    }

    @Test
    void containsAndExistsWork() {
        Condition holds = compileClean("""
                { "type": "variable", "variable": "hyzion:inventory", "op": "contains", "value": "key_3" }
                """);
        Condition exists = compileClean("""
                { "type": "variable", "variable": "hyzion:inventory", "op": "exists" }
                """);
        assertFalse(test(exists));
        kit.runtime().variables().setRaw(ScopeContext.player(ALICE), null, id("hyzion:inventory"), parse("[\"key_1\",\"key_3\"]"));
        assertTrue(test(holds));
        assertTrue(test(exists));
    }

    @Test
    void customHandlersAreContainedWhenTheyThrow() {
        kit.runtime().conditionTypes().register(id("mymod:explodes"), (context, parameters) -> {
            throw new IllegalStateException("boom");
        });
        Condition condition = compileClean("{ \"type\": \"mymod:explodes\" }");
        assertInstanceOf(Condition.Custom.class, condition);
        assertFalse(test(condition), "a failing handler denies");
        assertTrue(kit.problems.stream().anyMatch(problem -> problem.contains("CONDITION_FAILED")), kit.problems.toString());
        assertFalse(kit.runtime().conditionTypes().register(id("mymod:explodes"), (context, parameters) -> true),
                "the first registration wins");
        assertFalse(kit.runtime().conditionTypes().register(id("mysticquests:anything"), (context, parameters) -> true),
                "the built-in namespace is reserved");
    }
}
