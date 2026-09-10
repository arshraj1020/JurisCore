package com.juriscore.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Dockerfile's module list has to match the reactor's.
 *
 * <h2>Why this is a test and not a comment</h2>
 *
 * <p>The build stage copies each module explicitly. That is deliberate — it keeps the build
 * context small — but it means the list is a hand-maintained duplicate of {@code <modules>}
 * in the root POM, and it drifted: the file was written when there were four modules, six
 * more were added across later phases, and none of them was ever added here. The result was
 * an image that could not be built at all, because Maven resolves the whole reactor and
 * stops at the first {@code <module>} it cannot find on disk.
 *
 * <p>Nothing caught it. {@code mvn verify} does not read the Dockerfile, and CI built the
 * jar directly rather than through the image, so the only signal was a {@code docker build}
 * that nobody ran until a deployment needed one. A drifted list is exactly the kind of
 * mistake a test should catch in a second, so this compares the two lists directly.
 *
 * <p>It is deliberately narrow: it asserts that every reactor module is copied, not how, so
 * reordering, reformatting or switching to a single {@code COPY . .} all keep it green.
 */
class DockerfileModulesTest {

    private static final Pattern MODULE = Pattern.compile("<module>([^<]+)</module>");
    /** Matches both `COPY juriscore-x/pom.xml …` and `COPY juriscore-x/src …`. */
    private static final Pattern COPIED = Pattern.compile("^COPY\\s+(?:--\\S+\\s+)*(\\S+)");

    /**
     * The repository root, found by walking up from the module the test runs in.
     *
     * <p>Surefire sets the working directory to the module's basedir, but that is one
     * assumption; walking up until a POM declaring {@code <modules>} appears works from
     * anywhere, including an IDE that runs tests from the repository root.
     */
    private static Path repositoryRoot() throws IOException {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null) {
            Path pom = candidate.resolve("pom.xml");
            if (Files.exists(pom) && Files.readString(pom).contains("<modules>")) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not locate the reactor root from "
                + Paths.get("").toAbsolutePath());
    }

    private static List<String> reactorModules(Path root) throws IOException {
        Matcher matcher = MODULE.matcher(Files.readString(root.resolve("pom.xml")));
        return matcher.results().map(result -> result.group(1).trim()).toList();
    }

    private static String dockerfile(Path root) throws IOException {
        return Files.readString(root.resolve("Dockerfile"));
    }

    @Test
    @DisplayName("every module in the reactor is copied into the image build")
    void everyReactorModuleIsCopied() throws IOException {
        Path root = repositoryRoot();
        List<String> modules = reactorModules(root);
        String dockerfile = dockerfile(root);

        // A sanity check on the parsing itself: if this ever reads zero modules, the
        // assertions below would pass against anything.
        assertThat(modules)
                .as("the reactor should declare its modules in pom.xml")
                .isNotEmpty()
                .contains("juriscore-app", "juriscore-common");

        // `COPY . .` copies the lot, and is a perfectly good answer to this problem.
        boolean copiesEverything = dockerfile.lines()
                .map(String::trim)
                .anyMatch(line -> line.matches("COPY\\s+\\.\\s+\\.?/?"));
        if (copiesEverything) {
            return;
        }

        List<String> copied = dockerfile.lines()
                .map(String::trim)
                .map(COPIED::matcher)
                .filter(Matcher::find)
                .map(matcher -> matcher.group(1))
                .toList();

        for (String module : modules) {
            assertThat(copied)
                    .as("Dockerfile does not copy '%s'. The build stage copies each module "
                            + "explicitly, so a module missing here fails `docker build` "
                            + "outright — Maven stops at the first <module> it cannot find. "
                            + "Add it, or replace the list with `COPY . .`.", module)
                    .anyMatch(path -> path.equals(module + "/pom.xml"));
            assertThat(copied)
                    .as("Dockerfile copies '%s/pom.xml' but not its sources", module)
                    .anyMatch(path -> path.equals(module + "/src"));
        }
    }

    @Test
    @DisplayName("the image does not default to a developer profile")
    void imageDefaultsToASafeProfile() throws IOException {
        String dockerfile = dockerfile(repositoryRoot());

        String profile = dockerfile.lines()
                .map(String::trim)
                .filter(line -> line.startsWith("ENV SPRING_PROFILES_ACTIVE"))
                .map(line -> line.substring(line.indexOf('=') + 1).trim())
                .findFirst()
                .orElse("");

        // The image is the artefact that gets deployed, so its default has to be the safe
        // one. `docker` and `local` enable Swagger, publish full health details and point
        // S3 at a LocalStack host that does not exist outside Compose — a deployment that
        // forgot to override this would be a config-disclosure incident. Compose sets the
        // profile it wants explicitly, so nothing local depends on the default.
        assertThat(profile)
                .as("the image's default Spring profile must not be a developer profile")
                .isNotIn("docker", "local", "dev", "test");
    }
}
