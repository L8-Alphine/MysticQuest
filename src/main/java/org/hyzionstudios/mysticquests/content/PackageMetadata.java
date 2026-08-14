package org.hyzionstudios.mysticquests.content;

import java.nio.file.Path;
import java.util.List;

/** Resolved package identity and template ancestry. */
public record PackageMetadata(
        String id,
        Path directory,
        boolean enabled,
        String version,
        List<String> templates) {
    public PackageMetadata {
        templates = templates == null ? List.of() : List.copyOf(templates);
    }
}
