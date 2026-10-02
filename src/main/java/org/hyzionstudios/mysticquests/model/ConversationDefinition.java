package org.hyzionstudios.mysticquests.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.ArrayList;
import java.util.List;

public final class ConversationDefinition {
    private String id;
    private String packageId;
    private String speaker;

    /**
     * The nodes this conversation may open on, most specific first.
     *
     * <p>A list rather than a single id because an NPC has to be able to greet a player differently
     * once something has changed — quest taken, quest finished, tag set. With one entry point the
     * only thing conditions on it could do was refuse to open the conversation at all, so every
     * conversation opened on the same line forever.
     *
     * <p>{@code "start": "hello"} and {@code "start": ["quest_done", "quest_active", "hello"]} both
     * parse; the single-string form is the same content it always was.
     */
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private List<String> start = new ArrayList<>();

    private ConversationEntityBinding entity;
    private List<ConversationNode> nodes = new ArrayList<>();

    public String id() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String packageId() {
        return packageId;
    }

    public void setPackageId(String packageId) {
        this.packageId = packageId;
    }

    public String speaker() {
        return speaker == null || speaker.isBlank() ? id : speaker;
    }

    public void setSpeaker(String speaker) {
        this.speaker = speaker;
    }

    /**
     * The candidate opening nodes, in the order they should be tried.
     *
     * <p>Never empty for a conversation that has nodes: a definition with no {@code start} opens on
     * its first node, which is what it has always done.
     */
    public List<String> startCandidates() {
        List<String> named = start.stream()
                .filter(id -> id != null && !id.isBlank())
                .toList();
        if (!named.isEmpty()) {
            return named;
        }
        return nodes.isEmpty() ? List.of() : List.of(nodes.getFirst().id());
    }

    public void setStart(List<String> start) {
        this.start = start == null ? new ArrayList<>() : new ArrayList<>(start);
    }

    public ConversationEntityBinding entity() {
        return entity;
    }

    public void setEntity(ConversationEntityBinding entity) {
        this.entity = entity;
    }

    public List<ConversationNode> nodes() {
        return nodes;
    }

    public void setNodes(List<ConversationNode> nodes) {
        this.nodes = nodes == null ? new ArrayList<>() : nodes;
    }
}
