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
        Pattern append = Pattern.compile("\\.append\\(\"([^\"]+\\.ui)\"\\)");
        List<String> offenders = new ArrayList<>();
        int checked = 0;
        try (Stream<Path> java = Files.walk(JAVA_ROOT)) {
            for (Path file : java.filter(path -> path.toString().endsWith(".java")).toList()) {
                Matcher matcher = append.matcher(Files.readString(file));
                while (matcher.find()) {
                    checked++;
                    String appended = matcher.group(1);
                    if (!Files.isRegularFile(customRoot.resolve(appended))) {
                        offenders.add(file + " → append(\"" + appended + "\") has no file at "
                                + customRoot.resolve(appended));
                    }
                }
            }
        }
        assertTrue(checked > 0, "Expected to find append() calls to verify");
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
            Matcher matcher = id.matcher(Files.readString(file));
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
        Pattern texture = Pattern.compile("(?:TexturePath|BarTexturePath):\\s*\\\"(Assets/[^\\\"]+\\.png)\\\"");
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
        assertTrue(textureCount >= 8, "Expected painted document-relative textures");
        assertTrue(assetCount >= 10, "Expected asset-root-relative icons");
        assertTrue(missing.isEmpty(), "Missing referenced assets:\n - " + String.join("\n - ", missing));
    }

    @Test
    void hudDynamicIconsAndMetersUseSafeElementTypesAndFallbacks() throws IOException {
        String hud = Files.readString(UI_ROOT.resolve(HUD_DOCUMENT));
        String java = Files.readString(JAVA_ROOT.resolve("QuestHud.java"));
        for (int index = 0; index < 4; index++) {
            assertTrue(hud.matches("(?s).*AssetImage\\s+#HudObjectiveIcon" + index
                            + "\\s*\\{[^}]*FallbackTexturePath:\\s*\\$MQ\\.@MissingIcon;.*"),
                    "Dynamic HUD icon " + index + " must be an AssetImage with a visible fallback");
            assertTrue(hud.matches("(?s).*ObjectiveProgressBar\\s+#HudObjectiveMeter" + index + "\\s*\\{.*"),
                    "Dynamic HUD meter " + index + " must be a ProgressBar template instance");
        }
        assertTrue(java.contains(".AssetPath\""), "HUD must update dynamic icons through AssetPath");
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
        Matcher tracker = Pattern.compile("(?s)Group\\s+#TrackedQuest\\s*\\{(.*?)LayoutMode:\\s*Top;").matcher(hud);
        assertTrue(tracker.find(), "Missing #TrackedQuest container");
        assertTrue(tracker.group(1).matches("(?s).*Anchor:\\s*\\([^)]*Height:\\s*(?:1\\d\\d|2[0-7]\\d)[^)]*\\);.*"),
                "Right-layout children stretch on the cross axis, so the tracker needs a bounded explicit height");
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
        assertTrue(ui.contains("#BoardEmptyState") && ui.contains("#QuestBoardContent")
                        && ui.contains("#BoardEmptyBody"),
                "Quest board must declare separate empty and populated compositions");
        assertTrue(java.contains("#BoardEmptyState.Visible") && java.contains("#QuestBoardContent.Visible"),
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

    @Test
    void themePaletteMatchesPublishedPalette() throws IOException {
        Map<String, String> names = Map.ofEntries(
                Map.entry("background_deep", "BackgroundDeep"),
                Map.entry("background_soft", "BackgroundSoft"),
                Map.entry("panel", "Panel"),
                Map.entry("panel_raised", "PanelRaised"),
                Map.entry("brass", "BorderGold"),
                Map.entry("ember_gold", "AccentGold"),
                Map.entry("wayfinder", "AccentBlue"),
                Map.entry("success", "AccentGreen"),
                Map.entry("danger", "AccentRed"),
                Map.entry("text_primary", "TextPrimary"),
                Map.entry("text_secondary", "TextSecondary"),
                Map.entry("text_muted", "TextMuted"),
                Map.entry("focus_ring", "FocusRing"));
        String theme = Files.readString(UI_ROOT.resolve("Custom/mysticquests/Theme.ui"));
        Map<String, String> csv = new HashMap<>();
        for (String line : Files.readAllLines(PACK_ROOT.resolve("PALETTE.csv")).subList(1, PALETTE_ROWS())) {
            String[] fields = line.split(",", 3);
            csv.put(fields[0], fields[1]);
        }
        names.forEach((token, uiName) -> assertTrue(
                theme.contains("@" + uiName + " = " + csv.get(token) + ";"),
                "Theme.ui differs from PALETTE.csv for " + token));
    }

    private int PALETTE_ROWS() throws IOException {
        return Files.readAllLines(PACK_ROOT.resolve("PALETTE.csv")).size();
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
