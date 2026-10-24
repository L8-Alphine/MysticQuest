package org.hyzionstudios.mysticquests.service.trial.network;

import java.util.Locale;
import java.util.Optional;

/**
 * The SQL dialects the shared trial store speaks (R-1). Only column types and driver loading differ;
 * every statement is portable SQL (no upsert syntax, no partial indexes).
 */
public enum TrialDialect {
    H2("org.h2.Driver", "CHARACTER LARGE OBJECT"),
    MARIADB("org.mariadb.jdbc.Driver", "MEDIUMTEXT"),
    /** MySQL through MariaDB Connector/J, which needs {@code permitMysqlScheme} for a jdbc:mysql: URL. */
    MYSQL("org.mariadb.jdbc.Driver", "MEDIUMTEXT"),
    POSTGRESQL("org.postgresql.Driver", "TEXT");

    private final String driverClass;
    private final String textType;

    TrialDialect(String driverClass, String textType) {
        this.driverClass = driverClass;
        this.textType = textType;
    }

    /** The dialect of a JDBC URL; empty for an unsupported one. */
    public static Optional<TrialDialect> of(String jdbcUrl) {
        String url = jdbcUrl == null ? "" : jdbcUrl.toLowerCase(Locale.ROOT);
        if (url.startsWith("jdbc:h2:")) {
            return Optional.of(H2);
        }
        if (url.startsWith("jdbc:mariadb:")) {
            return Optional.of(MARIADB);
        }
        if (url.startsWith("jdbc:mysql:")) {
            return Optional.of(MYSQL);
        }
        if (url.startsWith("jdbc:postgresql:")) {
            return Optional.of(POSTGRESQL);
        }
        return Optional.empty();
    }

    public String driverClass() {
        return driverClass;
    }

    /** Unbounded text: progress snapshots, metrics and reasons. */
    public String textType() {
        return textType;
    }

    /** The URL as the driver must see it. */
    public String connectUrl(String jdbcUrl) {
        if (this == MYSQL && !jdbcUrl.toLowerCase(Locale.ROOT).contains("permitmysqlscheme")) {
            return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "permitMysqlScheme";
        }
        return jdbcUrl;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
