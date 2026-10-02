package org.hyzionstudios.mysticquests.narrative;

import org.hyzionstudios.mysticquests.narrative.entity.StoryEntityRegistry;
import org.hyzionstudios.mysticquests.narrative.overlay.OverlayDefinition;
import org.hyzionstudios.mysticquests.narrative.overlay.WorldOverlayRegistry;
import org.hyzionstudios.mysticquests.narrative.puzzle.QuestPuzzleService.PuzzleView;
import org.hyzionstudios.mysticquests.narrative.session.QuestSession;
import org.hyzionstudios.mysticquests.narrative.session.SessionOwner;
import org.hyzionstudios.mysticquests.narrative.state.OwnerState;
import org.hyzionstudios.mysticquests.narrative.state.ScopeOwner;
import org.hyzionstudios.mysticquests.narrative.state.TagRecord;
import org.hyzionstudios.mysticquests.narrative.state.VariableScope;
import org.hyzionstudios.mysticquests.narrative.trigger.TriggerActivationService.Decision;
import org.hyzionstudios.mysticquests.narrative.value.ValueCodec;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * §21 live debugger: everything the narrative runtime holds about one player, read without
 * changing it. Tags, variables and overrides are listed only for owners already loaded; resolving
 * music may read stored state, but inspecting never writes any.
 */
public final class NarrativeDebug {
    private final NarrativeRuntime narrative;
    private final String serverId;

    NarrativeDebug(NarrativeRuntime narrative, String serverId) {
        this.narrative = narrative;
        this.serverId = serverId;
    }

    /** Sections in display order; each line is one fact. Also the shape of an exported snapshot. */
    public Map<String, List<String>> snapshot(UUID player) {
        Instant now = Instant.now();
        Map<String, List<String>> sections = new LinkedHashMap<>();
        Optional<String> party = narrative.audiences().party(player);

        List<QuestSession> active = new ArrayList<>();
        List<String> sessionLines = new ArrayList<>();
        for (SessionOwner owner : narrative.audiences().owners(player)) {
            for (QuestSession session : narrative.sessions().load(owner)) {
                sessionLines.add(session.id() + "  " + session.storyKey() + "  " + session.status().name().toLowerCase(Locale.ROOT)
                        + "  owner " + session.owner() + ", node " + session.currentNode() + ", content " + session.contentVersion());
                if (session.active()) {
                    active.add(session);
                }
            }
        }
        sections.put("Sessions", sessionLines);

        Map<String, OwnerState> owners = new LinkedHashMap<>();
        narrative.store().loaded(ScopeOwner.player(player)).ifPresent(state -> owners.put("player", state));
        party.flatMap(id -> narrative.store().loaded(new ScopeOwner(VariableScope.PARTY, id)))
                .ifPresent(state -> owners.put("party " + party.get(), state));
        for (QuestSession session : active) {
            OwnerState state = narrative.sessions().sessionState(session.id());
            if (state != null) {
                owners.put("session " + session.id(), state);
            }
        }
        narrative.store().loaded(new ScopeOwner(VariableScope.SERVER, serverId)).ifPresent(state -> owners.put("server", state));

        List<String> tags = new ArrayList<>();
        List<String> variables = new ArrayList<>();
        List<String> volumes = new ArrayList<>();
        owners.forEach((label, state) -> {
            for (TagRecord tag : state.liveTags(now)) {
                tags.add(label + ": " + tag.id() + "  (" + tag.source() + (tag.expiresAt() == null ? "" : ", expires " + tag.expiresAt()) + ")");
            }
            state.variables().forEach((id, value) -> variables.add(label + ": " + id + " = " + ValueCodec.toText(value)));
            state.triggerOverrides().forEach((key, enabled) -> {
                if (!key.startsWith("#overlay/")) {
                    volumes.add(label + ": " + key + " " + (enabled ? "enabled" : "disabled"));
                }
            });
        });
        sections.put("Tags", tags);
        sections.put("Variables", variables);
        sections.put("Trigger overrides", volumes);

        List<String> puzzles = new ArrayList<>();
        for (var id : narrative.content().puzzles().keySet()) {
            narrative.puzzles().view(player, id).ifPresent(view -> puzzles.add(describe(view)));
        }
        sections.put("Puzzles", puzzles);

        Set<String> sessionIds = active.stream().map(QuestSession::id).collect(Collectors.toSet());
        List<String> entities = new ArrayList<>();
        for (StoryEntityRegistry.Claim claim : narrative.storyEntities().claims()) {
            if (sessionIds.contains(claim.sessionId())) {
                entities.add(claim.key() + "  (" + claim.story() + ", session " + claim.sessionId() + ")");
            }
        }
        sections.put("Story entities", entities);

        List<String> overlays = new ArrayList<>();
        for (OverlayDefinition overlay : narrative.content().overlays().values()) {
            Decision decision = narrative.activation().decide(WorldOverlayRegistry.key(overlay.id()), player);
            overlays.add(overlay.id() + ": " + (narrative.overlays().presentFor(player, overlay) ? "present" : "absent")
                    + (decision.decidedBy() == null ? " (default)" : " (" + decision.decidedBy().name().toLowerCase(Locale.ROOT)
                    + " " + decision.owner() + ")"));
        }
        sections.put("World overlays", overlays);
        sections.put("Media", narrative.media().describe(player));
        sections.put("Cutscene", narrative.cutscenes().describe(player));
        return sections;
    }

    private static String describe(PuzzleView view) {
        return view.puzzle() + " in " + view.sessionId() + ": active " + view.activeInputs() + ", activated "
                + view.activated().keySet()
                + (view.machineState() == null ? "" : ", state " + view.machineState())
                + (view.completed() ? (view.outputsComplete() ? ", solved" : ", solved, outputs pending") : "");
    }
}
