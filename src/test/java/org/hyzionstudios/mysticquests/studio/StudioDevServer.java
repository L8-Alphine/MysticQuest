package org.hyzionstudios.mysticquests.studio;

import org.hyzionstudios.mysticquests.integration.studio.StudioIntegration;
import org.hyzionstudios.mysticquests.studio.web.StudioHttpServer;
import org.hyzionstudios.mysticquests.util.Json;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Runs the web Studio without a game server, over a copy of {@code examples/packages} in
 * {@code build/studio-dev}, for working on the Studio's pages: {@code ./gradlew studioDev}.
 * Every permission is granted, a publish copies files without reloading anything, and validation
 * runs the real content loader (story content is not compiled, as there is no narrative runtime).
 */
public final class StudioDevServer {
    private StudioDevServer() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8766;
        Path base = Path.of("build", "studio-dev");
        Path live = base.resolve("packages");
        if (!Files.isDirectory(live)) {
            for (String folder : new String[] {"packages", "templates"}) {
                Path examples = Path.of("examples", folder);
                try (Stream<Path> files = Files.walk(examples)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        Path target = base.resolve(folder).resolve(examples.relativize(file).toString());
                        Files.createDirectories(target.getParent());
                        Files.copy(file, target);
                    }
                }
            }
        }
        ObjectMapper json = new ObjectMapper();
        ObjectMapper mapper = Json.createMapper();
        Clock clock = Clock.systemUTC();
        Path root = base.resolve("studio");
        StudioWorkspace workspace = new StudioWorkspace(ContentRoot.live(live), root, json, Json.createYamlMapper());
        StudioValidation.Validator validator = draft -> StudioIntegration.validate(draft, mapper, null);
        StudioReleases releases = new StudioReleases(root, workspace, validator, () -> { }, clock, json);
        StudioService studio = new StudioService(new StudioAuth(clock), workspace, releases,
                new StudioAudit(root.resolve("audit.jsonl"), clock, json, System.out::println),
                (player, permission) -> true, validator, "development", new StudioDemoLive(), clock);
        studio.audio(new StudioAudio(root, json, clock), base.resolve(StudioAudio.PACK_FOLDER), "*");
        StudioHttpServer server = new StudioHttpServer(studio, "127.0.0.1", port, "", System.err::println);
        server.start();
        String code = studio.auth().issueCode(UUID.fromString("00000000-0000-4000-8000-0000000000de"), "Developer");
        System.out.println("Studio dev server: " + server.publicUrl() + "#code=" + code);
        Thread.currentThread().join();
    }
}
