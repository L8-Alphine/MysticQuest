package org.hyzionstudios.mysticquests.narrative;

import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.state.ScopeSupport;
import org.hyzionstudios.mysticquests.narrative.state.VariableScope;

import com.fasterxml.jackson.databind.JsonNode;

import javax.annotation.Nullable;

/**
 * Shared readers for authored parameters. They are used by condition and action compilation and by
 * the runtime handlers, so a field means the same thing, and fails with the same message,
 * wherever it appears.
 */
public final class ContentParams {
    private ContentParams() {
    }

    /** Reads a required namespaced id, reporting a missing or malformed one. Null when invalid. */
    @Nullable
    public static NamespacedId id(JsonNode parameters, String field, String path, DiagnosticReport report) {
        String raw = parameters.path(field).asText("");
        String problem = NamespacedId.describeProblem(raw);
        if (problem != null) {
            report.error(DiagnosticCode.INVALID_ID, path, "\"" + field + "\": " + problem);
            return null;
        }
        return NamespacedId.parse(raw);
    }

    /** Reads an id at runtime, after validation has already accepted it. */
    public static NamespacedId id(JsonNode parameters, String field) {
        return NamespacedId.parse(parameters.path(field).asText(""));
    }

    /** Reads an optional scope at runtime; null when absent, meaning "the declared scope". */
    @Nullable
    public static VariableScope scope(JsonNode parameters) {
        JsonNode node = parameters.get("scope");
        return node == null || node.isNull() ? null : VariableScope.parse(node.asText());
    }

    /**
     * Validates an optional {@code scope}: it must be known, match the declaration, and be usable in
     * this deployment. Reports problems and returns false when the field is unusable.
     */
    public static boolean checkScope(JsonNode parameters, @Nullable VariableScope declared, String path,
                                     CompileContext context, DiagnosticReport report) {
        VariableScope scope = declared;
        if (parameters.hasNonNull("scope")) {
            String raw = parameters.get("scope").asText();
            scope = VariableScope.parse(raw);
            if (scope == null) {
                report.error(DiagnosticCode.INVALID_SCOPE, path, "unknown scope '" + raw + "'");
                return false;
            }
            if (declared != null && declared != scope) {
                report.error(DiagnosticCode.INVALID_SCOPE, path,
                        "declared in " + declared.id() + " scope but used in " + scope.id() + " scope");
                return false;
            }
        }
        if (scope == null) {
            return true;
        }
        ScopeSupport.Status status = context.scopes().status(scope);
        switch (status.level()) {
            case UNSUPPORTED -> {
                report.error(DiagnosticCode.UNSUPPORTED_SCOPE, path, scope.id() + " scope is unsupported: " + status.reason());
                return false;
            }
            case DEGRADED -> report.warning(DiagnosticCode.MISSING_INTEGRATION, path, scope.id() + " scope: " + status.reason());
            default -> {
            }
        }
        return true;
    }
}
