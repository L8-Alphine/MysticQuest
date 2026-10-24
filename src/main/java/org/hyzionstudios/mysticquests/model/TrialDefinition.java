package org.hyzionstudios.mysticquests.model;

/**
 * Marks a quest as a trial another mod can start through {@code MysticQuestsApi.trials()}: a class
 * advancement trial for MysticRPG, for example.
 *
 * <pre>
 * trial:
 *   id: mysticrpg:trial/warrior     # the id the other mod asks for
 *   timeLimitSeconds: 900           # optional: the trial fails when it runs longer
 *   maxDeaths: 3                    # optional: the trial fails on the death after this many
 *   ranking:                        # optional (MysticRPG Q11): without it the result is unranked
 *     parTimeSeconds: 300
 *     timeWeight: 0.3
 *     deathPenalty: 0.1
 *     optionalWeight: 0.2
 *   signOff: PENDING                # optional: content sign-off state, e.g. PENDING or APPROVED
 * </pre>
 *
 * <p>Trials resolve exactly once per run, as completed, failed or abandoned, and a player who leaves
 * the server during one abandons it. Field visibility binds this from package content.
 */
public final class TrialDefinition {
    private String id;
    private Integer timeLimitSeconds;
    private Integer maxDeaths;
    private Ranking ranking;
    /** Content sign-off state as authored (MysticRPG PO addendum: provisional content says PENDING). */
    private String signOff;

    public TrialDefinition() {
    }

    public TrialDefinition(String id, Integer timeLimitSeconds, Integer maxDeaths, Ranking ranking) {
        this.id = id;
        this.timeLimitSeconds = timeLimitSeconds;
        this.maxDeaths = maxDeaths;
        this.ranking = ranking;
    }

    /** The trial id other mods use, e.g. {@code mysticrpg:trial/warrior}. */
    public String id() {
        return id == null ? "" : id.strip();
    }

    /** Seconds the trial may run before it fails; 0 when unlimited. */
    public int timeLimitSeconds() {
        return timeLimitSeconds == null ? 0 : Math.max(0, timeLimitSeconds);
    }

    /** Deaths allowed during the trial; -1 when unlimited. */
    public int maxDeaths() {
        return maxDeaths == null ? -1 : maxDeaths;
    }

    /** The scoring rules, or null for an unranked trial. */
    public Ranking ranking() {
        return ranking;
    }

    /**
     * The authored content sign-off state, upper case ({@code PENDING}, {@code APPROVED}, ...); empty
     * when the content does not say. A release gate treats anything but {@code APPROVED} as unsigned.
     */
    public String signOff() {
        return signOff == null ? "" : signOff.strip().toUpperCase(java.util.Locale.ROOT);
    }

    /** Problems with this definition, for the content loader; empty when valid. */
    public java.util.List<String> problems() {
        java.util.List<String> problems = new java.util.ArrayList<>();
        if (id().isEmpty() || id().indexOf(':') <= 0 || id().indexOf(':') == id().length() - 1) {
            problems.add("trial.id must be a namespaced id such as mysticrpg:trial/warrior");
        }
        if (timeLimitSeconds != null && timeLimitSeconds < 0) {
            problems.add("trial.timeLimitSeconds must not be negative");
        }
        if (maxDeaths != null && maxDeaths < 0) {
            problems.add("trial.maxDeaths must not be negative");
        }
        if (ranking != null) {
            problems.addAll(ranking.problems());
        }
        return problems;
    }

    /**
     * How a completed run is scored into {@code [0, 1]}:
     * {@code 1 - optionalWeight - timeWeight * overPar - deathPenalty * deaths + optionalWeight * optionalShare},
     * clamped, where {@code overPar} is how far past the par time the run went, as a fraction of par
     * (at most 1), and {@code optionalShare} the fraction of optional objectives completed.
     */
    public static final class Ranking {
        private Integer parTimeSeconds;
        private Double timeWeight;
        private Double deathPenalty;
        private Double optionalWeight;

        public Ranking() {
        }

        public Ranking(Integer parTimeSeconds, Double timeWeight, Double deathPenalty, Double optionalWeight) {
            this.parTimeSeconds = parTimeSeconds;
            this.timeWeight = timeWeight;
            this.deathPenalty = deathPenalty;
            this.optionalWeight = optionalWeight;
        }

        public int parTimeSeconds() {
            return parTimeSeconds == null ? 0 : parTimeSeconds;
        }

        public double timeWeight() {
            return timeWeight == null ? 0.0d : timeWeight;
        }

        public double deathPenalty() {
            return deathPenalty == null ? 0.0d : deathPenalty;
        }

        public double optionalWeight() {
            return optionalWeight == null ? 0.0d : optionalWeight;
        }

        java.util.List<String> problems() {
            java.util.List<String> problems = new java.util.ArrayList<>();
            if (parTimeSeconds() <= 0 && timeWeight() > 0.0d) {
                problems.add("trial.ranking.parTimeSeconds must be positive when timeWeight is set");
            }
            for (double weight : new double[] {timeWeight(), deathPenalty(), optionalWeight()}) {
                if (!Double.isFinite(weight) || weight < 0.0d || weight > 1.0d) {
                    problems.add("trial.ranking weights must be between 0 and 1");
                    break;
                }
            }
            return problems;
        }

        /** The score of a completed run. */
        public double score(long elapsedSeconds, int deaths, int optionalTotal, int optionalDone) {
            double overPar = parTimeSeconds() <= 0 ? 0.0d
                    : Math.clamp((elapsedSeconds - parTimeSeconds()) / (double) parTimeSeconds(), 0.0d, 1.0d);
            double optionalShare = optionalTotal <= 0 ? 1.0d : optionalDone / (double) optionalTotal;
            double score = 1.0d - optionalWeight() - timeWeight() * overPar - deathPenalty() * Math.max(0, deaths)
                    + optionalWeight() * optionalShare;
            return Math.clamp(score, 0.0d, 1.0d);
        }
    }
}
