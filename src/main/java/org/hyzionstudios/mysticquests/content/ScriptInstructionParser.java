package org.hyzionstudios.mysticquests.content;

import org.hyzionstudios.mysticquests.model.ConditionDefinition;
import org.hyzionstudios.mysticquests.model.EventDefinition;
import org.hyzionstudios.mysticquests.model.ObjectiveDefinition;
import org.hyzionstudios.mysticquests.model.TypedConfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Quote-aware parser for BetonQuest-style instruction strings. */
public final class ScriptInstructionParser {
    private final ObjectMapper mapper;

    public ScriptInstructionParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ConditionDefinition condition(String instruction) throws IOException {
        ConditionDefinition definition = new ConditionDefinition();
        populate(definition, parse(instruction), Kind.CONDITION);
        return definition;
    }

    public EventDefinition event(String instruction) throws IOException {
        EventDefinition definition = new EventDefinition();
        populate(definition, parse(instruction), Kind.EVENT);
        return definition;
    }

    public ObjectiveDefinition objective(String id, String instruction) throws IOException {
        ObjectiveDefinition definition = new ObjectiveDefinition();
        definition.setId(id);
        populate(definition, parse(instruction), Kind.OBJECTIVE);
        definition.setDisplayName(definition.text("name", id));
        return definition;
    }

    public ParsedInstruction parse(String instruction) throws IOException {
        List<String> tokens = tokenize(instruction);
        if (tokens.isEmpty()) {
            throw new IOException("Instruction is empty.");
        }
        String type = normalizeType(tokens.removeFirst());
        List<String> positional = new ArrayList<>();
        Map<String, String> named = new LinkedHashMap<>();
        Set<String> flags = new LinkedHashSet<>();
        for (String token : tokens) {
            int separator = token.indexOf(':');
            if (separator > 0) {
                named.put(token.substring(0, separator), token.substring(separator + 1));
            } else if (token.equals("persistent") || token.equals("global") || token.equals("notify")) {
                flags.add(token);
            } else {
                positional.add(token);
            }
        }
        return new ParsedInstruction(type, List.copyOf(positional), Map.copyOf(named), Set.copyOf(flags));
    }

    public List<String> tokenize(String instruction) throws IOException {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaping = false;
        for (int index = 0; index < (instruction == null ? 0 : instruction.length()); index++) {
            char value = instruction.charAt(index);
            if (escaping) {
                current.append(switch (value) {
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> value;
                });
                escaping = false;
            } else if (value == '\\') {
                escaping = true;
            } else if (quote != 0) {
                if (value == quote) {
                    quote = 0;
                } else {
                    current.append(value);
                }
            } else if (value == '\'' || value == '"') {
                quote = value;
            } else if (Character.isWhitespace(value)) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(value);
            }
        }
        if (escaping) {
            current.append('\\');
        }
        if (quote != 0) {
            throw new IOException("Instruction contains an unclosed quote.");
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private void populate(TypedConfig target, ParsedInstruction instruction, Kind kind) {
        target.setType(instruction.type());
        for (Map.Entry<String, String> entry : instruction.named().entrySet()) {
            target.put(entry.getKey(), scalar(entry.getValue()));
        }
        if (!instruction.flags().isEmpty()) {
            ArrayNode flags = mapper.createArrayNode();
            instruction.flags().forEach(flags::add);
            target.put("flags", flags);
        }
        List<String> values = instruction.positional();
        switch (kind) {
            case CONDITION -> conditionArguments(target, instruction.type(), values);
            case EVENT -> eventArguments(target, instruction.type(), values);
            case OBJECTIVE -> objectiveArguments(target, instruction.type(), values);
        }
        ArrayNode arguments = mapper.createArrayNode();
        values.forEach(arguments::add);
        target.put("arguments", arguments);
    }

    private void conditionArguments(TypedConfig target, String type, List<String> values) {
        switch (type) {
            case "tag", "notTag", "globalTag", "entityTag", "blockTag", "volumeTag" -> put(target, "tag", values, 0);
            case "questCompleted", "questActive" -> put(target, "quest", values, 0);
            case "permission" -> put(target, "permission", values, 0);
            case "economy" -> put(target, "amount", values, 0);
            case "variable", "globalVariable", "entityVariable", "blockVariable", "volumeVariable" -> {
                put(target, "key", values, 0);
                put(target, "value", values, 1);
            }
            case "ref" -> put(target, "id", values, 0);
            default -> { }
        }
    }

    private void eventArguments(TypedConfig target, String type, List<String> values) {
        switch (type) {
            case "giveItem", "removeItem" -> {
                put(target, "item", values, 0);
                put(target, "amount", values, 1);
            }
            case "runCommand" -> target.put("command", TextNode.valueOf(String.join(" ", values)));
            case "sendMessage" -> target.put("message", TextNode.valueOf(String.join(" ", values)));
            case "notification" -> target.put("body", TextNode.valueOf(String.join(" ", values)));
            case "startQuest", "completeQuest" -> put(target, "quest", values, 0);
            case "cancelQuest" -> put(target, "canceler", values, 0);
            case "addTag", "removeTag", "globalTag", "entityTag", "blockTag", "volumeTag" -> put(target, "tag", values, 0);
            case "setVariable", "globalVariable", "entityVariable", "blockVariable", "volumeVariable" -> {
                put(target, "key", values, 0);
                put(target, "value", values, 1);
            }
            case "removeVariable" -> put(target, "key", values, 0);
            case "incrementVariable", "modifyMoney" -> {
                put(target, type.equals("modifyMoney") ? "amount" : "key", values, 0);
                if (type.equals("incrementVariable")) {
                    put(target, "amount", values, 1);
                }
            }
            case "ref" -> put(target, "id", values, 0);
            default -> { }
        }
    }

    private void objectiveArguments(TypedConfig target, String type, List<String> values) {
        if (!values.isEmpty()) {
            target.put("target", scalar(values.getFirst()));
        }
        if (values.size() > 1) {
            target.put("amount", scalar(values.get(1)));
        }
    }

    private void put(TypedConfig target, String key, List<String> values, int index) {
        if (index < values.size()) {
            target.put(key, scalar(values.get(index)));
        }
    }

    private JsonNode scalar(String value) {
        if (value != null && value.matches("-?\\d+")) {
            try {
                return mapper.getNodeFactory().numberNode(Long.parseLong(value));
            } catch (NumberFormatException ignored) {
                // Keep huge numeric-looking values as text rather than losing data.
            }
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return mapper.getNodeFactory().booleanNode(Boolean.parseBoolean(value));
        }
        return TextNode.valueOf(value == null ? "" : value);
    }

    private String normalizeType(String raw) {
        return switch (raw.toLowerCase()) {
            case "notify" -> "notification";
            case "command" -> "runCommand";
            case "message" -> "sendMessage";
            case "itemgive", "give" -> "giveItem";
            case "itemtake", "take" -> "removeItem";
            case "questcomplete" -> "completeQuest";
            case "queststart" -> "startQuest";
            case "cancel" -> "cancelQuest";
            case "mobkill" -> "kill";
            case "interact" -> "interactEntity";
            default -> raw;
        };
    }

    private enum Kind { CONDITION, EVENT, OBJECTIVE }

    public record ParsedInstruction(
            String type,
            List<String> positional,
            Map<String, String> named,
            Set<String> flags) {
    }
}
