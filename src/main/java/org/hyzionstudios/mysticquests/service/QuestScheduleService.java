package org.hyzionstudios.mysticquests.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hypixel.hytale.logger.HytaleLogger;
import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.model.EventDefinition;
import org.hyzionstudios.mysticquests.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Runs authored real-time schedules without requiring server restarts after a content reload. */
public final class QuestScheduleService implements AutoCloseable {
    private final Supplier<LoadedContent> contentSupplier;
    private final PlayerQuestService questService;
    private final PlayerSessionService sessions;
    private final HytaleLogger logger;
    private final ScheduledExecutorService executor;
    private final Set<String> firedSlots = new HashSet<>();
    private final Path stateFile;
    private volatile Instant lastScan;

    public QuestScheduleService(
            Supplier<LoadedContent> contentSupplier,
            PlayerQuestService questService,
            PlayerSessionService sessions,
            HytaleLogger logger) {
        this(contentSupplier, questService, sessions, logger, null);
    }

    public QuestScheduleService(
            Supplier<LoadedContent> contentSupplier,
            PlayerQuestService questService,
            PlayerSessionService sessions,
            HytaleLogger logger,
            Path stateFile) {
        this.contentSupplier = contentSupplier;
        this.questService = questService;
        this.sessions = sessions;
        this.logger = logger;
        this.stateFile = stateFile;
        this.lastScan = loadLastScan();
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MysticQuests-Schedules");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void start() {
        long delay = 60 - (Instant.now().getEpochSecond() % 60);
        executor.scheduleAtFixedRate(this::tickSafely, delay, 60, TimeUnit.SECONDS);
    }

    /** Keeps the current minute's de-duplication keys while dropping stale reload history. */
    public synchronized void reload() {
        String current = ZonedDateTime.now().truncatedTo(ChronoUnit.MINUTES).toInstant().toString();
        firedSlots.removeIf(value -> !value.endsWith(current));
    }

    private void tickSafely() {
        try {
            tick(ZonedDateTime.now());
        } catch (RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception).log("MysticQuests schedule tick failed.");
        }
    }

    void tick(ZonedDateTime now) {
        LoadedContent content = contentSupplier.get();
        ZonedDateTime currentMinute = now.truncatedTo(ChronoUnit.MINUTES);
        String minuteSlot = currentMinute.toInstant().toString();
        synchronized (this) {
            firedSlots.removeIf(value -> !value.endsWith(minuteSlot) && firedSlots.size() > 4096);
        }
        for (Map.Entry<String, JsonNode> entry : content.schedules().entrySet()) {
            Schedule schedule = parse(entry.getKey(), entry.getValue(), now.getZone());
            if (schedule == null) {
                continue;
            }

            List<ZonedDateTime> missed = matchingMinutes(schedule, currentMinute);
            if (schedule.catchup().equals("one") && !missed.isEmpty()) {
                fire(content, entry.getKey(), schedule, missed.getLast().toInstant().toString());
            } else if (schedule.catchup().equals("all")) {
                for (ZonedDateTime occurrence : missed) {
                    fire(content, entry.getKey(), schedule, occurrence.toInstant().toString());
                }
            }
            if (schedule.matches(currentMinute)) {
                fire(content, entry.getKey(), schedule, minuteSlot);
            }
        }
        lastScan = currentMinute.toInstant();
        saveLastScan();
    }

    private List<ZonedDateTime> matchingMinutes(Schedule schedule, ZonedDateTime currentMinute) {
        Instant previous = lastScan;
        if (previous == null || !previous.isBefore(currentMinute.toInstant()) || schedule.catchup().equals("none")) {
            return List.of();
        }
        ZonedDateTime cursor = previous.atZone(currentMinute.getZone()).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        List<ZonedDateTime> result = new ArrayList<>();
        int scanned = 0;
        while (cursor.isBefore(currentMinute) && scanned++ < 100_000) {
            if (schedule.matches(cursor)) result.add(cursor);
            cursor = cursor.plusMinutes(1);
        }
        return result;
    }

    private void fire(LoadedContent content, String scheduleId, Schedule schedule, String slot) {
        String executionKey = scheduleId + "@" + slot;
        synchronized (this) {
            if (!firedSlots.add(executionKey)) return;
        }
        run(content, scheduleId, schedule);
    }

    private void run(LoadedContent content, String scheduleId, Schedule schedule) {
        String packageId = packageOf(scheduleId);
        List<EventDefinition> events = new ArrayList<>();
        for (String raw : schedule.events()) {
            EventDefinition reference = new EventDefinition();
            reference.setType("ref");
            reference.put("id", com.fasterxml.jackson.databind.node.TextNode.valueOf(raw));
            events.add(reference);
        }
        if (events.isEmpty()) {
            return;
        }
        int players = 0;
        for (UUID playerId : sessions.onlinePlayerIds()) {
            questService.executeEvents(playerId, packageId, events);
            players++;
        }
        logger.at(Level.INFO).log("Ran quest schedule " + scheduleId + " for " + players + " online players.");
    }

    private Schedule parse(String id, JsonNode node, ZoneId defaultZone) {
        if (node == null || !node.isObject()) {
            logger.at(Level.WARNING).log("Ignored quest schedule " + id + ": expected an object.");
            return null;
        }
        String type = text(node, "type", "realtime-cron").toLowerCase();
        ZoneId zone;
        try {
            zone = ZoneId.of(text(node, "timezone", defaultZone.getId()));
        } catch (RuntimeException exception) {
            logger.at(Level.WARNING).log("Ignored quest schedule " + id + ": invalid timezone.");
            return null;
        }
        try {
            String expression = type.equals("realtime-daily")
                    ? dailyExpression(text(node, "time", "00:00"))
                    : text(node, "cron", text(node, "time", "@daily"));
            String catchup = text(node, "catchup", "none").toLowerCase();
            if (!Set.of("none", "one", "all").contains(catchup)) {
                throw new IllegalArgumentException("catchup must be none, one, or all");
            }
            return new Schedule(CronExpression.parse(expression), zone, strings(node.get("events")), catchup);
        } catch (IllegalArgumentException exception) {
            logger.at(Level.WARNING).withCause(exception).log("Ignored quest schedule " + id + ": invalid expression.");
            return null;
        }
    }

    private static String dailyExpression(String time) {
        String[] parts = time.split(":", -1);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Daily time must use HH:mm");
        }
        return Integer.parseInt(parts[1]) + " " + Integer.parseInt(parts[0]) + " * * *";
    }

    private static String text(JsonNode node, String key, String fallback) {
        JsonNode value = node.get(key);
        return value == null || value.isNull() ? fallback : value.asText(fallback);
    }

    private static List<String> strings(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isTextual()) {
            return List.of(node.asText());
        }
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(value -> values.add(value.asText()));
        }
        return List.copyOf(values);
    }

    private static String packageOf(String id) {
        int separator = id.lastIndexOf(':');
        return separator < 0 ? "" : id.substring(0, separator);
    }

    private Instant loadLastScan() {
        if (stateFile == null || !Files.isRegularFile(stateFile)) return null;
        try {
            JsonNode root = Json.createMapper().readTree(stateFile.toFile());
            String value = root.path("lastScan").asText("");
            return value.isBlank() ? null : Instant.parse(value);
        } catch (IOException | RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception).log("Could not read quest schedule catch-up state.");
            return null;
        }
    }

    private void saveLastScan() {
        if (stateFile == null || lastScan == null) return;
        try {
            Files.createDirectories(stateFile.getParent());
            var root = Json.createMapper().createObjectNode();
            root.put("lastScan", lastScan.toString());
            Json.createMapper().writerWithDefaultPrettyPrinter().writeValue(stateFile.toFile(), root);
        } catch (IOException exception) {
            logger.at(Level.WARNING).withCause(exception).log("Could not persist quest schedule catch-up state.");
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private record Schedule(CronExpression expression, ZoneId zone, List<String> events, String catchup) {
        boolean matches(ZonedDateTime time) {
            return expression.matches(time.withZoneSameInstant(zone));
        }
    }

    /** Five-field minute/hour/day/month/day-of-week cron with lists, ranges, and steps. */
    static final class CronExpression {
        private final Field minute;
        private final Field hour;
        private final Field day;
        private final Field month;
        private final Field weekDay;

        private CronExpression(Field minute, Field hour, Field day, Field month, Field weekDay) {
            this.minute = minute;
            this.hour = hour;
            this.day = day;
            this.month = month;
            this.weekDay = weekDay;
        }

        static CronExpression parse(String raw) {
            String expression = switch (raw.trim().toLowerCase()) {
                case "@hourly" -> "0 * * * *";
                case "@daily", "@midnight" -> "0 0 * * *";
                case "@weekly" -> "0 0 * * 0";
                case "@monthly" -> "0 0 1 * *";
                case "@yearly", "@annually" -> "0 0 1 1 *";
                default -> raw.trim();
            };
            String[] parts = expression.split("\\s+");
            if (parts.length != 5) {
                throw new IllegalArgumentException("Cron must have five fields");
            }
            return new CronExpression(
                    Field.parse(parts[0], 0, 59),
                    Field.parse(parts[1], 0, 23),
                    Field.parse(parts[2], 1, 31),
                    Field.parse(parts[3], 1, 12),
                    Field.parse(parts[4], 0, 7));
        }

        boolean matches(ZonedDateTime value) {
            int dow = value.getDayOfWeek() == DayOfWeek.SUNDAY ? 0 : value.getDayOfWeek().getValue();
            return minute.contains(value.getMinute())
                    && hour.contains(value.getHour())
                    && day.contains(value.getDayOfMonth())
                    && month.contains(value.getMonthValue())
                    && (weekDay.contains(dow) || (dow == 0 && weekDay.contains(7)));
        }

        private record Field(Set<Integer> values) {
            static Field parse(String source, int min, int max) {
                Set<Integer> result = new HashSet<>();
                for (String item : source.split(",")) {
                    String[] stepParts = item.split("/", -1);
                    int step = stepParts.length == 2 ? Integer.parseInt(stepParts[1]) : 1;
                    if (step <= 0 || stepParts.length > 2) {
                        throw new IllegalArgumentException("Invalid cron step");
                    }
                    String range = stepParts[0];
                    int start;
                    int end;
                    if (range.equals("*")) {
                        start = min;
                        end = max;
                    } else if (range.contains("-")) {
                        String[] bounds = range.split("-", -1);
                        if (bounds.length != 2) {
                            throw new IllegalArgumentException("Invalid cron range");
                        }
                        start = Integer.parseInt(bounds[0]);
                        end = Integer.parseInt(bounds[1]);
                    } else {
                        start = Integer.parseInt(range);
                        end = start;
                    }
                    if (start < min || end > max || start > end) {
                        throw new IllegalArgumentException("Cron value outside range");
                    }
                    for (int value = start; value <= end; value += step) {
                        result.add(value);
                    }
                }
                return new Field(Set.copyOf(result));
            }

            boolean contains(int value) {
                return values.contains(value);
            }
        }
    }
}
