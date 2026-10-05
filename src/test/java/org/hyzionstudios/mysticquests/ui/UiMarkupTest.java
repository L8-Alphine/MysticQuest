package org.hyzionstudios.mysticquests.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the two markup mistakes that only surface as a runtime "Custom UI — Markup Error" dialog:
 * a flat {@code Style: (Background: …)} on a button, and using {@code TextButton} as a container.
 *
 * <p>{@code ButtonStyle} and {@code TextButtonStyle} are per-state maps ({@code Default},
 * {@code Hovered}, {@code Pressed}, {@code Disabled}); neither has a top-level {@code Background} or
 * {@code TextColor} field. {@code TextButton} renders its own {@code Text} through a per-state
 * {@code LabelStyle} and must not hold child elements — use {@code Button} for clickable containers.
 *
 * <p>Only a button's own body is inspected. A nested {@code Label} legitimately carries a flat
 * {@code Style: (FontSize: …, TextColor: …)}, because {@code LabelStyle} really does have those
 * fields.
 */
final class UiMarkupTest {
    private static final Path UI_ROOT = Path.of("src/main/resources/Common/UI");
    private static final Path JAVA_ROOT = Path.of("src/main/java/org/hyzionstudios/mysticquests/ui");
    private static final Path RESOURCE_ROOT = Path.of("src/main/resources/Common");
    private static final Path PACK_ROOT = Path.of("artifacts/MysticQuests_Assets_v1");
    /** HUD documents sit in the shared custom-HUD root, beside the base game's own. */
    private static final String HUD_DOCUMENT = "Custom/Hud/MysticQuestsQuestHud.ui";
    private static final String PUZZLE_HUD_DOCUMENT = "Custom/Hud/MysticQuestsPuzzleHud.ui";

    private static final Pattern BUTTON_OPEN = Pattern.compile("\\b(TextButton|Button)\\s+#\\S+?\\s*\\{");
    /** A Style tuple whose first field is a leaf property rather than a button state. */
    private static final Pattern FLAT_STYLE =
            Pattern.compile("Style:\\s*\\(\\s*(Background|TextColor|FontSize|HorizontalAlignment)\\s*:");
    private static final Pattern CHILD_ELEMENT =
            Pattern.compile("\\b(Label|Group|Button|TextButton|Image)\\b[^;{}]*\\{");

    /**
     * Every {@code UICommandBuilder.append("…")} path must resolve to a document that actually
     * ships. The client resolves these against {@code Common/UI/Custom/}; a path outside that root
     * — or a typo — is not a build error, it disconnects the player with
     * "Could not find document … for Custom UI Append command".
     */
    @Test
    void everyAppendedDocumentExists() throws IOException {
        Path customRoot = UI_ROOT.resolve("Custom");
        // Pages name their documents in constants (DOCUMENT, RAIL_ROW, ...) and append those, so
        // every document path written in the UI sources is checked, not only literal append calls.
        Pattern document = Pattern.compile("\"((?:mysticquests|Hud)/[^\"]+\\.ui)\"");
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> java = Files.walk(JAVA_ROOT)) {
            for (Path file : java.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = document.matcher(Files.readString(file));
                while (matcher.find()) {
                    checked++;
                    String appended = matcher.group(1);
                    if (!Files.isRegularFile(customRoot.resolve(appended))) {
                        offenders.add(file + " → \"" + appended + "\" has no file at "
                                + customRoot.resolve(appended));
                    }
                }
            }
        }
        assertTrue(checked > 10, "Expected to find the pages' document paths to verify");
        assertTrue(offenders.isEmpty(), "Appended UI documents that do not exist:\n - "
                + String.join("\n - ", offenders));
    }

    /**
     * A HUD append only resolves from the shared {@code Custom/Hud/} root — the layout
     * MysticRPG's working HUD uses. Filing the document under {@code Custom/mysticquests/}
     * instead disconnects the player with "Could not find document …".
     */
    @Test
    void questHudLivesInTheSharedHudRoot() throws IOException {
        String source = Files.readString(JAVA_ROOT.resolve("QuestHud.java"));
        assertTrue(source.contains("DOCUMENT = \"Hud/MysticQuestsQuestHud.ui\""));
        assertTrue(source.contains("builder.append(DOCUMENT)"));
        assertTrue(Files.isRegularFile(UI_ROOT.resolve(HUD_DOCUMENT)));
        assertTrue(Files.isRegularFile(UI_ROOT.resolve(PUZZLE_HUD_DOCUMENT)));
    }

    /**
     * The markup dialect has no {@code \n} escape. The base game spells multi-line strings with real
     * line breaks and escapes only {@code \"}; across 135 base-game documents and 619 shipped by
     * other mods on the same server, a literal backslash-n appears exactly zero times. Ours had one,
     * in a {@code MultilineTextField} default that Java overwrites on open anyway.
     */
    @Test
    void noDocumentUsesABackslashEscapeTheDialectLacks() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path document : uiDocuments()) {
            Matcher escapes = Pattern.compile("\\\\(?!\")(.)").matcher(Files.readString(document));
            while (escapes.find()) {
                offenders.add(document + " uses \\" + escapes.group(1)
                        + "; only \\\" is a supported escape");
            }
        }
        assertTrue(offenders.isEmpty(), "Unsupported escapes in UI markup:\n - "
                + String.join("\n - ", offenders));
    }

    /**
     * No shipped path may nest another {@code UI} segment inside {@code Common/UI/}. Of every pack
     * loaded on the live server — the base game, MysticRPG, MysticEssentials, BetterLootBox and the
     * rest — MysticQuests was the only one whose asset paths read
     * {@code UI/Custom/mysticquests/Assets/UI/buttons/…}, and the client is the only thing that has
     * to make sense of that. Textures live at {@code Assets/panels}, {@code Assets/buttons},
     * {@code Assets/meters} instead.
     */
    @Test
    void noShippedPathNestsASecondUiSegment() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(UI_ROOT)) {
            paths.filter(Files::isRegularFile).forEach(path -> {
                String relative = UI_ROOT.relativize(path).toString().replace('\\', '/');
                if (relative.matches(".*(^|/)UI(/|$).*")) {
                    offenders.add(relative);
                }
            });
        }
        assertTrue(offenders.isEmpty(), "Paths nesting a second UI segment:\n - "
                + String.join("\n - ", offenders));
    }

    /**
     * Alignment is {@code Start} / {@code Center} / {@code End} — there is no {@code Right} or
     * {@code Left}. Across the base game and every other mod on the live server, those three values
     * account for all 1,340 uses and nothing else appears.
     *
     * <p>This is not a cosmetic rule. A single {@code HorizontalAlignment: Right} in
     * {@code QuestStudioPage.ui} stopped the client registering custom UI documents *for every pack
     * on the server*, so joins died on whichever mod's HUD was appended first — MysticRPG's, for two
     * days, while this document sat there parsing-but-invalid. An unknown enum value does not
     * degrade; it takes the whole custom-UI load with it.
     */
    @Test
    void alignmentValuesAreStartCenterOrEnd() throws IOException {
        List<String> offenders = new ArrayList<>();
        Pattern alignment = Pattern.compile("(Horizontal|Vertical)Alignment:\\s*(\\w+)");
        for (Path source : sources()) {
            Matcher values = alignment.matcher(Files.readString(source));
            while (values.find()) {
                String value = values.group(2);
                if (!value.equals("Start") && !value.equals("Center") && !value.equals("End")) {
                    offenders.add(source + " → " + values.group(1) + "Alignment: " + value);
                }
            }
        }
        assertTrue(offenders.isEmpty(), "Alignment must be Start, Center or End:\n - "
                + String.join("\n - ", offenders));
    }

    /** Documents outside {@code Common/UI/Custom/} can never be resolved by an append. */
    @Test
    void everyUiDocumentLivesUnderTheCustomRoot() throws IOException {
        Path customRoot = UI_ROOT.resolve("Custom");
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> ui = Files.walk(UI_ROOT)) {
            ui.filter(path -> path.toString().endsWith(".ui"))
                    .filter(path -> !path.toAbsolutePath().startsWith(customRoot.toAbsolutePath()))
                    .forEach(path -> offenders.add(path + " is outside " + customRoot));
        }
        assertTrue(offenders.isEmpty(), "UI documents outside the Custom root:\n - "
                + String.join("\n - ", offenders));
    }

    @Test
    void noButtonUsesAFlatStyleTuple() throws IOException {
        List<String> offenders = new ArrayList<>();
        forEachButton((file, kind, ownBody) -> {
            if (FLAT_STYLE.matcher(ownBody).find()) {
                offenders.add(file + " → " + kind
                        + " has a flat Style tuple; use Default:/Hovered:/Pressed: states");
            }
        });
        assertTrue(offenders.isEmpty(), "Flat button Style tuples:\n - " + String.join("\n - ", offenders));
    }

    @Test
    void noTextButtonIsUsedAsAContainer() throws IOException {
        List<String> offenders = new ArrayList<>();
        forEachButton((file, kind, ownBody) -> {
            if (kind.equals("TextButton") && CHILD_ELEMENT.matcher(ownBody).find()) {
                offenders.add(file + " → TextButton holds child elements; use Button instead");
            }
        });
        assertTrue(offenders.isEmpty(), "TextButton used as a container:\n - " + String.join("\n - ", offenders));
    }

    @Test
    void manifestEnablesAssetPack() throws IOException {
        String manifest = Files.readString(Path.of("src/main/resources/manifest.json"));
        assertTrue(manifest.matches("(?s).*\\\"IncludesAssetPack\\\"\\s*:\\s*true.*"),
                "manifest.json must enable IncludesAssetPack");
    }

    @Test
    void localImportsAndDelimitersResolve() throws IOException {
        Pattern imported = Pattern.compile("\\$[A-Za-z][A-Za-z0-9_]*\\s*=\\s*\\\"([^\\\"]+\\.ui)\\\"");
        List<String> unresolved = new ArrayList<>();
        List<String> unbalanced = new ArrayList<>();
        for (Path file : uiDocuments()) {
            String content = Files.readString(file);
            if (!balanced(content)) {
                unbalanced.add(file.toString());
            }
            Matcher matcher = imported.matcher(content);
            while (matcher.find()) {
                Path resolved = file.getParent().resolve(matcher.group(1)).normalize();
                // Platform Common.ui is supplied by Assets.zip. Every mod-local import must ship.
                if (resolved.startsWith(UI_ROOT.resolve("Custom/mysticquests").normalize())
                        && !Files.isRegularFile(resolved)) {
                    unresolved.add(file + " -> " + matcher.group(1));
                }
            }
        }
        assertTrue(unbalanced.isEmpty(), "Unbalanced UI delimiters:\n - " + String.join("\n - ", unbalanced));
        assertTrue(unresolved.isEmpty(), "Unresolved mod-local imports:\n - " + String.join("\n - ", unresolved));
    }

    @Test
    void everyStaticIdIsUniqueWithinItsDocument() throws IOException {
        Pattern id = Pattern.compile("#([A-Za-z_][A-Za-z0-9_]*)");
        List<String> duplicates = new ArrayList<>();
        for (Path file : uiDocuments()) {
            Map<String, Integer> counts = new HashMap<>();
            Matcher matcher = id.matcher(withoutComments(Files.readString(file)));
            while (matcher.find()) {
                String candidate = matcher.group(1);
                if (candidate.matches("(?i)[0-9a-f]{3,8}")) {
                    continue;
                }
                counts.merge(candidate, 1, Integer::sum);
            }
            counts.forEach((name, count) -> {
                if (count > 1) {
                    duplicates.add(file + " -> #" + name + " occurs " + count + " times");
                }
            });
        }
        assertTrue(duplicates.isEmpty(), "Duplicate literal UI ids:\n - " + String.join("\n - ", duplicates));
    }

    @Test
    void textureAndAssetPathsUseTheirSeparateConventions() throws IOException {
        Pattern texture = Pattern.compile(
                "(?:TexturePath|BarTexturePath|Background):\\s*\\\"((?:\\.\\./)*(?:mysticquests/)?Assets/[^\\\"]+\\.png)\\\"");
        Pattern asset = Pattern.compile("\\\"(UI/Custom/mysticquests/Assets/[^\\\"]+\\.png)\\\"");
        List<String> missing = new ArrayList<>();
        int textureCount = 0;
        for (Path file : uiDocuments()) {
            Matcher matcher = texture.matcher(Files.readString(file));
            while (matcher.find()) {
                textureCount++;
                Path resolved = file.getParent().resolve(matcher.group(1)).normalize();
                if (!Files.isRegularFile(resolved)) {
                    missing.add(file + " texture -> " + matcher.group(1));
                }
            }
        }
        int assetCount = 0;
        try (Stream<Path> sources = Stream.concat(uiDocuments().stream(), javaSources().stream())) {
            for (Path file : sources.toList()) {
                Matcher matcher = asset.matcher(Files.readString(file));
                while (matcher.find()) {
                    assetCount++;
                    Path resolved = RESOURCE_ROOT.resolve(matcher.group(1)).normalize();
                    if (!Files.isRegularFile(resolved)) {
                        missing.add(file + " asset -> " + matcher.group(1));
                    }
                }
            }
        }
        assertTrue(textureCount >= 40, "Expected painted document-relative textures");
        assertTrue(assetCount >= 5, "Expected asset-root-relative icons");
        assertTrue(missing.isEmpty(), "Missing referenced assets:\n - " + String.join("\n - ", missing));
    }

    @Test
    void compactHudUsesSemanticFieldsAndSafeMeterUpdates() throws IOException {
        String hud = Files.readString(UI_ROOT.resolve(HUD_DOCUMENT));
        String java = Files.readString(JAVA_ROOT.resolve("QuestHud.java"));
        for (String id : List.of("#TrackedCategory", "#TrackedState", "#TrackedQuestName",
                "#TrackedContext", "#TrackedObjectiveText", "#TrackedObjectiveProgress",
                "#TrackedObjectiveMeter", "#TrackedGuidance", "#TrackedQuestProgress")) {
            assertTrue(hud.contains(id), "Compact tracker document is missing " + id);
            assertTrue(java.contains(id + "."), "QuestHud never writes to " + id);
        }
        assertTrue(hud.matches("(?s).*ProgressBar\\s+#TrackedObjectiveMeter\\s*\\{.*"),
                "Compact HUD progress must use Hytale's native ProgressBar element");
        assertTrue(java.contains("QuestHudViewModel model"),
                "QuestHud must render a semantic view model instead of deriving state in markup");
        assertTrue(Files.readString(JAVA_ROOT.resolve("QuestHudService.java")).contains("QuestHudViewModel.from(entry)"),
                "The HUD service must build the tracker from the journal's semantic projection");
        assertTrue(java.contains(".Value\""), "HUD must update progress through Value");
        assertFalse(java.contains(".Anchor\""), "Runtime code must not mutate Anchor");
        assertFalse(java.contains(".Background.TexturePath\""),
                "Runtime code must not mutate Background.TexturePath");
    }

    @Test
    void trackerHudUsesRightOriginAndBoundedHeight() throws IOException {
        String hud = Files.readString(UI_ROOT.resolve(HUD_DOCUMENT));
        assertTrue(hud.matches("(?s).*Group\\s*\\{\\s*LayoutMode:\\s*Right;\\s*FlexWeight:\\s*1;.*"),
                "Quest tracker needs a Right layout wrapper; Anchor.Right alone starts from the left origin");
        Matcher tracker = Pattern.compile("(?s)Group\\s+#CompactTracker\\s*\\{(.*?)Group\\s+#TrackedQuest").matcher(hud);
        assertTrue(tracker.find(), "Missing #CompactTracker container");
        assertTrue(tracker.group(1).matches("(?s).*Anchor:\\s*\\([^)]*Height:\\s*2[0-4]\\d[^)]*\\);.*"),
                "The compact tracker must remain a bounded 200-249px persistent surface");
    }

    @Test
    void expandedTrackerSharesTheHudAndStaysWithinItsObjectiveBudget() throws IOException {
        String hud = Files.readString(UI_ROOT.resolve(HUD_DOCUMENT));
        String java = Files.readString(JAVA_ROOT.resolve("QuestHud.java"));
        assertTrue(hud.contains("#CompactTracker") && hud.contains("#ExpandedTracker"),
                "Both tracker compositions must share one keyed HUD document");
        assertTrue(java.contains("#CompactTracker.Visible") && java.contains("#ExpandedTracker.Visible"),
                "Mode changes must patch composition visibility without replacing the HUD");
        for (int index = 0; index < 3; index++) {
            assertTrue(hud.contains("#ExpandedRow" + index),
                    "Expanded tracker is missing supporting row " + index);
            assertTrue(java.contains("#ExpandedRow\" + index"),
                    "Expanded tracker rows must be populated from the semantic model");
        }
        assertFalse(hud.contains("#ExpandedRow3"),
                "Expanded tracker may show one primary plus at most three supporting rows");
        assertTrue(hud.matches("(?s).*ProgressBar\\s+#ExpandedPrimaryMeter\\s*\\{.*"),
                "Expanded tracker progress must use Hytale's native ProgressBar");
        assertTrue(hud.contains("#ExpandedActionHint") && java.contains("model.actionHint()"),
                "Expanded tracker must expose the server-authored Journal action descriptor");
    }

    /**
     * The puzzle card sits bottom-centre and the tracker top-right. One layout tree cannot place
     * blocks in two corners — Anchor offsets are relative to where the layout puts an element — so
     * the card is its own HUD layer with its own document, the way MysticRPG keeps its experience
     * bar apart from its vitals.
     */
    @Test
    void puzzleCardIsItsOwnBottomAnchoredHudLayer() throws IOException {
        String tracker = Files.readString(UI_ROOT.resolve(HUD_DOCUMENT));
        String card = Files.readString(UI_ROOT.resolve(PUZZLE_HUD_DOCUMENT));
        String java = Files.readString(JAVA_ROOT.resolve("QuestPuzzleHud.java"));
        assertFalse(tracker.contains("#Puzzle"),
                "The tracker document must not carry the puzzle card; it cannot be placed bottom-centre from there");
        assertTrue(java.contains("DOCUMENT = \"Hud/MysticQuestsPuzzleHud.ui\"") && java.contains("builder.append(DOCUMENT)"),
                "The puzzle card must append its own document from the shared Hud/ root");
        assertTrue(card.matches("(?s).*Group\\s*\\{\\s*LayoutMode:\\s*Bottom;\\s*FlexWeight:\\s*1;.*"),
                "The card's root must take the full height and lay out from the bottom");
        Matcher bottom = Pattern.compile("Anchor:\\s*\\(Bottom:\\s*(\\d+),").matcher(card);
        assertTrue(bottom.find(), "The card row needs a Bottom offset");
        assertTrue(Integer.parseInt(bottom.group(1)) >= 232,
                "The card must clear the hotbar stack and MysticRPG's experience bar (Bottom 176, Height 56)");
        for (String id : List.of("#PuzzleTitle", "#PuzzleHint", "#PuzzleStatus",
                "#PuzzleSession", "#PuzzleState", "#PuzzleFeedback")) {
            assertTrue(card.contains(id), "Puzzle card document is missing " + id);
            assertTrue(java.contains(id + "."), "QuestPuzzleHud never writes to " + id);
        }
        assertFalse(card.contains("#PuzzleSlot") || card.contains("#PuzzleMeter"),
                "A general puzzle information card must not imply every puzzle is slot/count based");
        assertFalse(tracker.contains("#FocusTracker"),
                "Navigation must use Hytale's native world map, not a second centre card");
        String state = Files.readString(JAVA_ROOT.resolve("QuestPuzzleHudState.java"));
        assertFalse(state.contains("candidate") && state.contains("String candidate"),
                "Client puzzle state must not carry hidden candidate ids");
        assertFalse(state.contains("seed()") || state.contains("solutionOrder"),
                "Client puzzle state must not carry session seed or solution order");
    }

    /**
     * Theme icons are asset-root paths ({@code UI/Custom/mysticquests/Assets/…}) and only
     * {@code AssetImage.AssetPath} reads them that way. Used as a {@code Background}, the same string
     * is a document-relative texture path that resolves to nothing — and an unresolvable document
     * reference is exactly the kind of fault that has taken the whole custom-UI load down before.
     */
    @Test
    void themeIconsAreOnlyUsedAsAssetPaths() throws IOException {
        Pattern iconUse = Pattern.compile("(\\w+):\\s*\\$MQ\\.@Icon\\w+");
        List<String> offenders = new ArrayList<>();
        for (Path document : uiDocuments()) {
            Matcher matcher = iconUse.matcher(Files.readString(document));
            while (matcher.find()) {
                if (!matcher.group(1).equals("AssetPath")) {
                    offenders.add(document + " uses a theme icon as " + matcher.group(1));
                }
            }
        }
        assertTrue(offenders.isEmpty(), "Theme icons outside AssetPath:\n - " + String.join("\n - ", offenders));
    }

    @Test
    void cinematicDialogueUsesOneBottomBandWithScrollableChoicesAndTranscript() throws IOException {
        String ui = Files.readString(UI_ROOT.resolve("Custom/mysticquests/Pages/ConversationPage.ui"));
        String java = Files.readString(JAVA_ROOT.resolve("ConversationPage.java"));
        assertTrue(ui.contains("#SpeakerPortrait") && ui.contains("#DialoguePanel"),
                "Dialogue needs a distinct portrait rail and cinematic subtitle panel");
        assertTrue(ui.contains("LayoutMode: TopScrolling") && ui.contains("#ChoiceList"),
                "All visible server choices must remain reachable without growing the HUD band");
        for (String id : List.of("#VoiceBadge", "#DialogueText", "#TranscriptToggle",
                "#TranscriptPanel", "#TranscriptText")) {
            assertTrue(ui.contains(id), "Cinematic dialogue is missing " + id);
            assertTrue(java.contains(id), "ConversationPage never controls " + id);
        }
    }

    /**
     * The tracker shows one step of the quest, so it must also say which step that is and how much
     * of the whole quest is done — otherwise a thirteen-objective quest looks like a five-objective
     * one. Both halves have to exist: the ids in the document, and the Java that fills them.
     */
    @Test
    void trackerHudShowsOneImmediateObjectiveAndKeepsQuestContext() throws IOException {
        String hud = Files.readString(UI_ROOT.resolve(HUD_DOCUMENT));
        String java = Files.readString(JAVA_ROOT.resolve("QuestHud.java"));
        for (String id : List.of("#TrackedContext", "#TrackedObjectiveText",
                "#TrackedObjectiveProgress", "#TrackedQuestProgress", "#TrackedGuidance")) {
            assertTrue(hud.contains(id), "Tracker document is missing " + id);
            assertTrue(java.contains(id + "."), "QuestHud never writes to " + id);
        }
        assertFalse(hud.contains("#HudObjective0"),
                "The compact persistent HUD must not expand into a multi-objective quest log");
        assertTrue(Files.readString(JAVA_ROOT.resolve("QuestHudViewModel.java")).contains("currentStage()"),
                "The semantic HUD model must still select the current authored step");
    }

    /**
     * A quest can carry a dozen objectives and a long recap; the Journal's detail has to scroll
     * inside its panel instead of running underneath the Track and Abandon buttons, which stay put.
     */
    @Test
    void journalDetailScrollsAboveFixedActions() throws IOException {
        String journal = Files.readString(UI_ROOT.resolve("Custom/mysticquests/Pages/JournalPage.ui"));
        Matcher scroll = Pattern.compile("(?s)Group\\s+#DetailScroll\\s*\\{(.*?)Group\\s+#ObjectiveBlock").matcher(journal);
        assertTrue(scroll.find(), "Missing #DetailScroll container");
        String body = scroll.group(1);
        assertTrue(body.contains("LayoutMode: TopScrolling"), "#DetailScroll must scroll");
        assertTrue(body.contains("ScrollbarStyle:"), "#DetailScroll must show a scrollbar");
        assertTrue(body.contains("FlexWeight: 1"),
                "#DetailScroll needs a bounded height to scroll within; it fills what the panel leaves");
        assertTrue(journal.indexOf("#ObjectiveList") > journal.indexOf("#DetailScroll")
                        && journal.indexOf("#Actions") > journal.indexOf("#TimelineList"),
                "Objectives scroll inside the detail; the action row sits below the scroll");
    }

    @Test
    void journalAppendInlineMarkupDoesNotUseRelativeTextures() throws IOException {
        String java = Files.readString(JAVA_ROOT.resolve("MysticQuestJournalPage.java"));
        assertFalse(java.contains("BarTexturePath:"),
                "AppendInline fragments cannot reliably resolve document-relative progress textures");
        assertFalse(java.contains("Background: \"../Assets/"),
                "AppendInline fragments must not use document-relative texture backgrounds");
    }

    @Test
    void questBoardUsesOnePurposefulEmptyState() throws IOException {
        String ui = Files.readString(UI_ROOT.resolve("Custom/mysticquests/Pages/QuestMenuPage.ui"));
        String java = Files.readString(JAVA_ROOT.resolve("QuestMenuPage.java"));
        assertTrue(ui.contains("#BoardEmpty") && ui.contains("#CardRows") && ui.contains("#EmptyBody"),
                "Quest board must declare separate empty and populated compositions");
        assertTrue(java.contains("#BoardEmpty.Visible") && java.contains("#CardRows.Visible"),
                "Quest board runtime must swap the two compositions");
        assertFalse(java.contains("appendInline(\"#QuestCardList\", emptyState"),
                "Empty state must not be duplicated as both a quest card and preview");
    }

    @Test
    void appendInlineTargetsExist() throws IOException {
        String allUi = "";
        for (Path ui : uiDocuments()) {
            allUi += Files.readString(ui) + "\n";
        }
        Pattern appendInline = Pattern.compile("appendInline\\(\\\"#([A-Za-z0-9_]+)\\\"");
        List<String> missing = new ArrayList<>();
        for (Path source : javaSources()) {
            Matcher matcher = appendInline.matcher(Files.readString(source));
            while (matcher.find()) {
                if (!allUi.contains("#" + matcher.group(1))) {
                    missing.add(source + " -> #" + matcher.group(1));
                }
            }
        }
        assertTrue(missing.isEmpty(), "appendInline selectors without a declared target:\n - "
                + String.join("\n - ", missing));
    }

    @Test
    void productionPngsMatchManifestAndAreRgba() throws IOException {
        Path manifest = PACK_ROOT.resolve("ASSET_MANIFEST.csv");
        assertTrue(Files.isRegularFile(manifest), "Missing asset manifest");
        List<String> lines = Files.readAllLines(manifest);
        assertTrue(lines.size() > 50, "Expected a production-oriented asset library");
        Set<String> seen = new HashSet<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split(",", 4);
            assertEquals(4, fields.length, "Malformed asset manifest row: " + line);
            Path relative = Path.of(fields[0]);
            assertFalse(relative.isAbsolute() || fields[0].contains(".."), "Asset escaped pack root: " + fields[0]);
            Path png = PACK_ROOT.resolve(relative).normalize();
            assertTrue(Files.isRegularFile(png), "Manifest path does not exist: " + png);
            BufferedImage image = ImageIO.read(png.toFile());
            assertNotNull(image, "Unreadable PNG: " + png);
            assertEquals(Integer.parseInt(fields[1]), image.getWidth(), "Width mismatch for " + png);
            assertEquals(Integer.parseInt(fields[2]), image.getHeight(), "Height mismatch for " + png);
            assertEquals("RGBA", fields[3], "Production PNG is not recorded as RGBA: " + png);
            assertTrue(image.getColorModel().hasAlpha(), "Production PNG has no alpha channel: " + png);
            assertTrue(seen.add(fields[0]), "Duplicate manifest path: " + fields[0]);
        }
    }

    @Test
    void meterFrameAndFillDimensionsMatch() throws IOException {
        for (String suffix : List.of("", "@2x")) {
            BufferedImage frame = ImageIO.read(PACK_ROOT.resolve("ui/meters/objective_frame" + suffix + ".png").toFile());
            BufferedImage fill = ImageIO.read(PACK_ROOT.resolve("ui/meters/objective_fill" + suffix + ".png").toFile());
            assertEquals(frame.getWidth(), fill.getWidth());
            assertEquals(frame.getHeight(), fill.getHeight());
        }
    }

    /**
     * Theme.ui carries the Redesign Bible's palette (§4.1), and the server's copy in {@link UiText}
     * — which colours TextSpans, since the server cannot read the client's stylesheet — matches it.
     */
    @Test
    void themePaletteMatchesTheBibleAndTheServerCopy() throws IOException {
        String theme = Files.readString(UI_ROOT.resolve("Custom/mysticquests/Theme.ui"));
        Map<String, String> bible = Map.of(
                "Void", "#0A0D13", "Raised", "#161B26", "Purple", "#7E5DD3", "Gold", "#CCAA58",
                "Cyan", "#46C6C4", "Green", "#66D395", "Red", "#EF6969", "Muted", "#9AA4B7");
        bible.forEach((token, colour) -> assertTrue(theme.contains("@" + token + " = " + colour + ";"),
                "Theme.ui @" + token + " is not the Bible's " + colour));
        Map<String, String> server = Map.of(
                "Text", UiText.TEXT, "TextSoft", UiText.TEXT_SOFT, "Muted", UiText.MUTED, "Dim", UiText.DIM,
                "Purple", UiText.PURPLE, "PurpleText", UiText.PURPLE_TEXT, "Gold", UiText.GOLD, "Cyan", UiText.CYAN,
                "Green", UiText.GREEN, "Red", UiText.RED);
        server.forEach((token, colour) -> assertTrue(theme.contains("@" + token + " = " + colour + ";"),
                "UiText's copy of @" + token + " (" + colour + ") differs from Theme.ui"));
    }

    /**
     * A field's value reaches the server only when the binding's key starts with {@code @}: the
     * client resolves {@code "@Name": "#Field.Value"} to the field's text and sends any other key's
     * value as written. The in-game studio's forms sent the literal selector for every field — its
     * player tools all answered "Unknown player" — until this rule was found.
     */
    @Test
    void valueBindingsUseAtKeys() throws IOException {
        Pattern literalValue = Pattern.compile("\\.append\\(\"([^\"]*)\",\\s*\"#[^\"]*\\.Value\"\\)");
        List<String> offenders = new ArrayList<>();
        for (Path source : javaSources()) {
            Matcher matcher = literalValue.matcher(Files.readString(source));
            while (matcher.find()) {
                if (!matcher.group(1).startsWith("@")) {
                    offenders.add(source + " -> \"" + matcher.group(1) + "\" reads a field without an @ key");
                }
            }
        }
        assertTrue(offenders.isEmpty(), "Field bindings that would send the selector, not the value:\n - "
                + String.join("\n - ", offenders));
    }

    /**
     * The client refuses most runtime property changes, and refuses them by disconnecting the player
     * ("CustomUI is not allowed to change this property"). These are the properties the engine's own
     * pages set, which MysticRPG confirmed in game; nothing else may be written after build.
     */
    @Test
    void runtimeWritesUseOnlyPropertiesTheClientAllows() throws IOException {
        Set<String> allowed = Set.of("Value", "Visible", "Text", "TextSpans", "Entries", "Disabled", "Style",
                "TooltipText", "TooltipTextSpans", "Background", "ItemId", "AssetPath", "Color", "PlaceholderText");
        Pattern write = Pattern.compile("\\.set(?:Object)?\\(([^;]*?)\"[^\"]*\\.([A-Za-z]+)\"\\s*,");
        List<String> offenders = new ArrayList<>();
        int writes = 0;
        for (Path source : javaSources()) {
            Matcher matcher = write.matcher(Files.readString(source));
            while (matcher.find()) {
                writes++;
                if (!allowed.contains(matcher.group(2))) {
                    offenders.add(source + " writes ." + matcher.group(2));
                }
            }
        }
        assertTrue(writes > 100, "Expected to find the pages' runtime writes, found " + writes);
        assertTrue(offenders.isEmpty(), "Runtime writes the client refuses:\n - " + String.join("\n - ", offenders));
    }

    /** Every style a page swaps in by reference is defined in Theme.ui, under the name it asks for. */
    @Test
    void styleReferencesExistInTheTheme() throws IOException {
        String theme = Files.readString(UI_ROOT.resolve("Custom/mysticquests/Theme.ui"));
        String styles = withoutJavaComments(Files.readString(JAVA_ROOT.resolve("UiStyles.java")));
        Matcher names = Pattern.compile("\"([A-Z][A-Za-z]+)\"").matcher(styles);
        List<String> missing = new ArrayList<>();
        int checked = 0;
        while (names.find()) {
            checked++;
            if (!theme.matches("(?s).*\\n@" + names.group(1) + " = .*")) {
                missing.add(names.group(1));
            }
        }
        assertTrue(checked > 20, "Expected the style names in UiStyles");
        assertTrue(missing.isEmpty(), "Styles referenced but not defined in Theme.ui: " + missing);
    }

    /**
     * Every element id a page or HUD writes to or binds is declared in one of our documents. Setting
     * a selector the client cannot find addresses nothing, and the page looks dead.
     */
    @Test
    void everySelectorTheJavaUsesIsDeclared() throws IOException {
        Set<String> declared = new HashSet<>();
        Pattern declaration = Pattern.compile("#([A-Za-z][A-Za-z0-9]*)\\s*\\{");
        for (Path document : uiDocuments()) {
            Matcher matcher = declaration.matcher(withoutComments(Files.readString(document)));
            while (matcher.find()) {
                declared.add(matcher.group(1));
            }
        }
        Pattern used = Pattern.compile("#([A-Za-z][A-Za-z0-9]*)");
        Pattern literal = Pattern.compile("\"([^\"]*)\"");
        List<String> missing = new ArrayList<>();
        for (Path source : javaSources()) {
            Matcher strings = literal.matcher(withoutJavaComments(Files.readString(source)));
            while (strings.find()) {
                Matcher ids = used.matcher(strings.group(1));
                while (ids.find()) {
                    String id = ids.group(1);
                    if (id.matches("[0-9A-Fa-f]{6}|[0-9A-Fa-f]{3}|[0-9A-Fa-f]{8}")) {
                        continue; // a colour, not an element
                    }
                    if (!declared.contains(id) && !missing.contains(source.getFileName() + " #" + id)) {
                        missing.add(source.getFileName() + " #" + id);
                    }
                }
            }
        }
        // Ids composed at runtime (#Objective1Id, #ExpandedRow0) are declared literally in the document.
        missing.removeIf(entry -> entry.matches(".* #(Objective|ExpandedRow|Setting|Choice)[A-Za-z]*"));
        assertTrue(missing.isEmpty(), "Selectors used by the Java but declared in no document:\n - "
                + String.join("\n - ", missing));
    }

    /**
     * A HUD is appended while a player joins, and a fault anywhere in its import graph disconnects
     * them. The two HUD documents therefore import nothing of ours; they inline their colours.
     */
    @Test
    void hudDocumentsImportNothingOfOurs() throws IOException {
        for (String hud : List.of(HUD_DOCUMENT, PUZZLE_HUD_DOCUMENT)) {
            String document = withoutComments(Files.readString(UI_ROOT.resolve(hud)));
            assertFalse(document.contains("$"), hud + " must not import or reference another document");
        }
    }

    /**
     * Every {@code $MQ.@Name} a document uses is defined in Theme.ui. One unresolved reference in any
     * shipped document — even one no page opens, since the client registers them all — can stop
     * custom UI loading for every pack on the server.
     */
    @Test
    void themeReferencesResolve() throws IOException {
        String theme = Files.readString(UI_ROOT.resolve("Custom/mysticquests/Theme.ui"));
        Set<String> defined = new HashSet<>();
        Matcher definitions = Pattern.compile("(?m)^@([A-Za-z][A-Za-z0-9]*)\\s*=").matcher(theme);
        while (definitions.find()) {
            defined.add(definitions.group(1));
        }
        List<String> missing = new ArrayList<>();
        for (Path document : uiDocuments()) {
            Matcher references = Pattern.compile("\\$MQ\\.@([A-Za-z][A-Za-z0-9]*)").matcher(withoutComments(Files.readString(document)));
            while (references.find()) {
                if (!defined.contains(references.group(1))) {
                    missing.add(document.getFileName() + " -> $MQ.@" + references.group(1));
                }
            }
        }
        assertTrue(missing.isEmpty(), "Theme references with no definition:\n - " + String.join("\n - ", missing));
    }

    /**
     * A label the server colours through TextSpans declares no Text of its own: the two are one
     * label's content in two forms, and setting both is undefined.
     */
    @Test
    void spanLabelsDeclareNoText() throws IOException {
        Set<String> spanIds = new HashSet<>();
        Pattern spans = Pattern.compile("#([A-Za-z][A-Za-z0-9]*)\\.TextSpans\"");
        for (Path source : javaSources()) {
            Matcher matcher = spans.matcher(Files.readString(source));
            while (matcher.find()) {
                spanIds.add(matcher.group(1));
            }
        }
        assertFalse(spanIds.isEmpty(), "Expected labels written as TextSpans");
        List<String> offenders = new ArrayList<>();
        for (Path document : uiDocuments()) {
            String content = withoutComments(Files.readString(document));
            for (String id : spanIds) {
                Matcher label = Pattern.compile("Label\\s+#" + id + "\\s*\\{").matcher(content);
                while (label.find()) {
                    int end = matchingBrace(content, label.end());
                    String own = stripNestedBlocks(content.substring(label.end(), end));
                    if (own.matches("(?s).*\\bText\\s*:.*")) {
                        offenders.add(document.getFileName() + " #" + id);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "Labels written as TextSpans that also declare Text:\n - "
                + String.join("\n - ", offenders));
    }

    private static String withoutComments(String text) {
        return text.replaceAll("//[^\\n]*", "");
    }

    /** Java source without its comments, so examples in Javadoc are not read as code. */
    private static String withoutJavaComments(String source) {
        return withoutComments(source.replaceAll("(?s)/\\*.*?\\*/", ""));
    }

    private boolean balanced(String text) {
        StringBuilder stack = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '"' && (index == 0 || text.charAt(index - 1) != '\\')) {
                quoted = !quoted;
                continue;
            }
            if (quoted) {
                continue;
            }
            if (current == '/' && index + 1 < text.length() && text.charAt(index + 1) == '/') {
                index = text.indexOf('\n', index);
                if (index < 0) {
                    break;
                }
                continue;
            }
            if (current == '(' || current == '{' || current == '[') {
                stack.append(current);
            } else if (current == ')' || current == '}' || current == ']') {
                if (stack.isEmpty()) {
                    return false;
                }
                char open = stack.charAt(stack.length() - 1);
                if ((current == ')' && open != '(') || (current == '}' && open != '{')
                        || (current == ']' && open != '[')) {
                    return false;
                }
                stack.deleteCharAt(stack.length() - 1);
            }
        }
        return !quoted && stack.isEmpty();
    }

    /** Our documents: the mod folder plus the HUD document that has to live in the shared root. */
    private List<Path> uiDocuments() throws IOException {
        List<Path> documents = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(UI_ROOT.resolve("Custom/mysticquests"))) {
            paths.filter(path -> path.toString().endsWith(".ui")).forEach(documents::add);
        }
        documents.add(UI_ROOT.resolve(HUD_DOCUMENT));
        documents.add(UI_ROOT.resolve(PUZZLE_HUD_DOCUMENT));
        return documents;
    }

    private List<Path> javaSources() throws IOException {
        try (Stream<Path> paths = Files.walk(JAVA_ROOT)) {
            return paths.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    /**
     * Visits every button declaration with its own body: the block contents with nested element
     * blocks replaced by a marker, so child styling is not mistaken for the button's own.
     */
    private void forEachButton(ButtonVisitor visitor) throws IOException {
        for (Path file : sources()) {
            String content = Files.readString(file);
            Matcher opens = BUTTON_OPEN.matcher(content);
            while (opens.find()) {
                int bodyStart = opens.end();
                int bodyEnd = matchingBrace(content, bodyStart);
                if (bodyEnd < 0) {
                    continue;
                }
                String body = content.substring(bodyStart, bodyEnd);
                visitor.visit(file, opens.group(1), stripNestedBlocks(body));
            }
        }
    }

    /** Index of the brace closing the block that opened just before {@code from}, or -1. */
    private int matchingBrace(String content, int from) {
        int depth = 1;
        for (int index = from; index < content.length(); index++) {
            char character = content.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    /** Replaces each nested `{ … }` block with a marker so only the element's own fields remain. */
    private String stripNestedBlocks(String body) {
        StringBuilder own = new StringBuilder();
        int depth = 0;
        for (int index = 0; index < body.length(); index++) {
            char character = body.charAt(index);
            if (character == '{') {
                if (depth == 0) {
                    own.append('{');
                }
                depth++;
            } else if (character == '}') {
                depth--;
                if (depth == 0) {
                    own.append('}');
                }
            } else if (depth == 0) {
                own.append(character);
            }
        }
        return own.toString();
    }

    /** Every `.ui` document plus the Java files that emit markup through `appendInline`. */
    private List<Path> sources() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> ui = Files.walk(UI_ROOT)) {
            ui.filter(path -> path.toString().endsWith(".ui")).forEach(files::add);
        }
        try (Stream<Path> java = Files.walk(JAVA_ROOT)) {
            java.filter(path -> path.toString().endsWith(".java")).forEach(files::add);
        }
        assertTrue(files.size() > 5, "Expected to scan the UI sources, found " + files.size());
        return files;
    }

    @FunctionalInterface
    private interface ButtonVisitor {
        void visit(Path file, String kind, String ownBody);
    }
}
