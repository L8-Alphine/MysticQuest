package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.MysticQuestsRuntime;
import org.hyzionstudios.mysticquests.narrative.NarrativeRuntime;
import org.hyzionstudios.mysticquests.narrative.media.QuestMediaService;
import org.hyzionstudios.mysticquests.narrative.session.QuestSession;
import org.hyzionstudios.mysticquests.narrative.session.SessionOwner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@link JournalSources} over the running server: active story sessions and milestones from the
 * narrative runtime, settings from the HUD, the saved UI preferences and the story audio settings.
 */
final class RuntimeJournalSources implements JournalSources {
    private final MysticQuestsRuntime runtime;

    RuntimeJournalSources(MysticQuestsRuntime runtime) {
        this.runtime = runtime;
    }

    private NarrativeRuntime narrative() {
        return runtime.narrative() == null ? null : runtime.narrative().runtime();
    }

    @Override
    public List<Story> stories(UUID player) {
        NarrativeRuntime narrative = narrative();
        if (narrative == null) {
            return List.of();
        }
        List<NarrativeRuntime.Milestone> milestones;
        try {
            milestones = narrative.milestones(player);
        } catch (IOException unreadable) {
            milestones = List.of();
        }
        List<Story> stories = new ArrayList<>();
        for (SessionOwner owner : narrative.audiences().owners(player)) {
            for (QuestSession session : narrative.sessions().load(owner)) {
                if (!session.active()) {
                    continue;
                }
                // A milestone belongs to a story when its tag shares the story's namespace.
                String namespace = session.storyKey().contains(":") ? session.storyKey().substring(0, session.storyKey().indexOf(':')) : "";
                List<Milestone> reached = milestones.stream()
                        .filter(milestone -> milestone.tag().namespace().equals(namespace))
                        .map(milestone -> new Milestone(milestone.text(), milestone.reached()))
                        .toList();
                stories.add(new Story(session.storyKey(), name(session.storyKey()), owner.kind() == SessionOwner.Kind.PARTY,
                        session.createdAt(), reached));
            }
        }
        stories.sort(Comparator.comparing(Story::since).reversed());
        return stories;
    }

    /** {@code grove:sealed_grove} reads as "Sealed Grove". */
    static String name(String storyKey) {
        String path = storyKey.substring(storyKey.indexOf(':') + 1);
        path = path.substring(path.lastIndexOf('.') + 1).replace('_', ' ').replace('-', ' ').trim();
        StringBuilder name = new StringBuilder();
        for (String word : path.split("\\s+")) {
            if (!word.isEmpty()) {
                name.append(name.isEmpty() ? "" : " ").append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
            }
        }
        return name.isEmpty() ? storyKey : name.toString();
    }

    @Override
    public Settings settings(UUID player) {
        NarrativeRuntime narrative = narrative();
        QuestMediaService.Preferences audio = narrative == null
                ? new QuestMediaService.Preferences(null, true) : narrative.media().preferences(player);
        PlayerUiPreferences saved = runtime.uiPreferences();
        QuestTransitionService transitions = runtime.transitionService();
        return new Settings(runtime.hudService() == null ? QuestHudCoordinator.Preference.AUTOMATIC : runtime.hudService().preference(player),
                audio.subtitles(), audio.voiceLocale(),
                saved == null || saved.popups(player),
                transitions != null && transitions.enabled());
    }

    @Override
    public void setTracker(UUID player, QuestHudCoordinator.Preference preference) {
        if (runtime.hudService() != null) {
            runtime.hudService().setPreference(player, preference);
        }
    }

    @Override
    public void setSubtitles(UUID player, boolean on) {
        NarrativeRuntime narrative = narrative();
        if (narrative != null) {
            narrative.media().setSubtitles(player, on);
        }
    }

    @Override
    public void followGameLanguage(UUID player) {
        NarrativeRuntime narrative = narrative();
        if (narrative != null) {
            narrative.media().setVoiceLocale(player, null);
        }
    }

    @Override
    public void setPopups(UUID player, boolean on) {
        PlayerUiPreferences saved = runtime.uiPreferences();
        if (saved != null) {
            saved.setPopups(player, on);
        }
    }
}
