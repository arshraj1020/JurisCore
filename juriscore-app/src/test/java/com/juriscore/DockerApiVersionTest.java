package com.juriscore;

import com.juriscore.DockerApiVersion.DaemonVersions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the part of the Docker API-version negotiation that can be decided without a
 * daemon: reading a {@code /version} response, and choosing a version from the bounds it
 * reports.
 *
 * <p>The payloads below are real shapes, not minimal ones — in particular the Engine
 * entry inside {@code Components} repeats {@code ApiVersion} and {@code MinAPIVersion}
 * before the top-level fields appear, which is exactly the sort of thing a pattern reads
 * wrong.
 */
class DockerApiVersionTest {

    /** Docker Desktop on Engine 29: the configuration that produced the HTTP 400. */
    private static final String ENGINE_29 =
            """
            {"Platform":{"Name":"Docker Desktop 4.50.0 (210000)"},\
            "Components":[{"Name":"Engine","Version":"29.0.1",\
            "Details":{"ApiVersion":"1.52","Arch":"arm64","MinAPIVersion":"1.44","Os":"linux"}},\
            {"Name":"containerd","Version":"2.1.4"}],\
            "Version":"29.0.1","ApiVersion":"1.52","MinAPIVersion":"1.44",\
            "Os":"linux","Arch":"arm64","KernelVersion":"6.10.14-linuxkit"}\
            """;

    @Nested
    @DisplayName("reading /version")
    class Parsing {

        @Test
        void readsBothBoundsFromARealEngine29Payload() {
            Optional<DaemonVersions> parsed = DockerApiVersion.parseVersions(ENGINE_29);

            assertThat(parsed).contains(new DaemonVersions("1.52", "1.44"));
        }

        @Test
        @DisplayName("does not read MinAPIVersion as ApiVersion")
        void distinguishesTheTwoSimilarlyNamedFields() {
            // Docker spells them with different capitalisation (ApiVersion, MinAPIVersion),
            // and this is the pair a loose pattern conflates.
            String onlyMin = "{\"MinAPIVersion\":\"1.44\"}";

            assertThat(DockerApiVersion.parseVersions(onlyMin)).isEmpty();
        }

        @Test
        void toleratesADaemonThatOmitsTheFloor() {
            String noFloor = "{\"Version\":\"20.10.7\",\"ApiVersion\":\"1.41\"}";

            assertThat(DockerApiVersion.parseVersions(noFloor))
                    .contains(new DaemonVersions("1.41", null));
        }

        @Test
        void returnsNothingForAPayloadWithoutAVersion() {
            assertThat(DockerApiVersion.parseVersions("{\"message\":\"not found\"}")).isEmpty();
            assertThat(DockerApiVersion.parseVersions("")).isEmpty();
            assertThat(DockerApiVersion.parseVersions(null)).isEmpty();
        }
    }

    @Nested
    @DisplayName("reading the HTTP response")
    class Http {

        @Test
        void readsABodyDeclaredByContentLength() {
            byte[] response = response(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 20\r\n",
                    "{\"ApiVersion\":\"1.52\"}");

            assertThat(DockerApiVersion.httpBody(response)).contains("{\"ApiVersion\":\"1.52\"}");
        }

        @Test
        void decodesAChunkedBody() {
            String body = "{\"ApiVersion\":\"1.52\",\"MinAPIVersion\":\"1.44\"}";
            String chunked = Integer.toHexString(10) + "\r\n" + body.substring(0, 10) + "\r\n"
                    + Integer.toHexString(body.length() - 10) + "\r\n" + body.substring(10) + "\r\n"
                    + "0\r\n\r\n";
            byte[] response = response("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n", chunked);

            assertThat(DockerApiVersion.httpBody(response)).contains(body);
        }

        @Test
        @DisplayName("keeps multi-byte characters intact across chunk boundaries")
        void decodesChunksByByteCountNotCharacterCount() {
            // Chunk sizes count bytes. A platform name with a non-ASCII character is
            // enough to make character-indexed slicing produce a truncated body.
            String body = "{\"Platform\":{\"Name\":\"Docker Desktop – Ärm64\"},\"ApiVersion\":\"1.52\"}";
            byte[] raw = body.getBytes(StandardCharsets.UTF_8);
            String chunked = Integer.toHexString(raw.length) + "\r\n" + body + "\r\n0\r\n\r\n";
            byte[] response = response("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n", chunked);

            assertThat(DockerApiVersion.httpBody(response)).contains(body);
        }

        @Test
        void rejectsANonSuccessResponse() {
            byte[] response = response(
                    "HTTP/1.1 400 Bad Request\r\nContent-Type: application/json\r\n",
                    "{\"message\":\"client version 1.32 is too old\"}");

            assertThat(DockerApiVersion.httpBody(response)).isEmpty();
        }

        @Test
        void rejectsATruncatedResponse() {
            assertThat(DockerApiVersion.httpBody("HTTP/1.1 200 OK\r\nContent-Len".getBytes(StandardCharsets.UTF_8)))
                    .isEmpty();
            assertThat(DockerApiVersion.httpBody(null)).isEmpty();
        }

        private byte[] response(String head, String body) {
            return (head + "\r\n" + body).getBytes(StandardCharsets.UTF_8);
        }
    }

    @Nested
    @DisplayName("choosing a version")
    class Choosing {

        @Test
        @DisplayName("Engine 29 (1.44..1.52): asks for 1.44, because 1.32 is refused")
        void raisesTheVersionWhenTheDefaultIsBelowTheDaemonFloor() {
            assertThat(DockerApiVersion.chooseApiVersion(new DaemonVersions("1.52", "1.44")))
                    .contains("1.44");
        }

        @Test
        @DisplayName("Engine 28 (1.24..1.48): changes nothing, because 1.32 works")
        void leavesTheDefaultAloneWhenItIsInsideTheDaemonRange() {
            assertThat(DockerApiVersion.chooseApiVersion(new DaemonVersions("1.48", "1.24"))).isEmpty();
        }

        @Test
        @DisplayName("a daemon whose ceiling is below 1.32 negotiates down to that ceiling")
        void lowersTheVersionWhenTheDefaultIsAboveTheDaemonCeiling() {
            // Docker rejects "too new" as firmly as "too old", with the same 400. The
            // answer is the ceiling, not the floor: it is the nearest accepted version.
            assertThat(DockerApiVersion.chooseApiVersion(new DaemonVersions("1.30", "1.24")))
                    .contains("1.30");
        }

        @Test
        void picksTheFloorRatherThanTheCeilingWhenTheDefaultIsTooOld() {
            // The daemon's newest dialect may postdate this docker-java entirely; the
            // oldest it accepts is the one the client was written against.
            assertThat(DockerApiVersion.chooseApiVersion(new DaemonVersions("1.99", "1.44")))
                    .contains("1.44");
        }

        @Test
        @DisplayName("a one-version daemon leaves no choice")
        void handlesAWindowOfASingleVersion() {
            assertThat(DockerApiVersion.chooseApiVersion(new DaemonVersions("1.44", "1.44")))
                    .contains("1.44");
        }

        @Test
        void declinesToGuessWhenTheDaemonStatesNoFloor() {
            assertThat(DockerApiVersion.chooseApiVersion(new DaemonVersions("1.41", null))).isEmpty();
        }

        @Test
        void declinesToGuessWhenTheDaemonStatesNoCeiling() {
            assertThat(DockerApiVersion.chooseApiVersion(new DaemonVersions(null, "1.44"))).isEmpty();
        }

        @Test
        void declinesToGuessWhenTheDaemonContradictsItself() {
            assertThat(DockerApiVersion.chooseApiVersion(new DaemonVersions("1.40", "1.44"))).isEmpty();
        }

        @Test
        void declinesToGuessWhenTheDaemonSaysNothingUsable() {
            assertThat(DockerApiVersion.chooseApiVersion(null)).isEmpty();
            assertThat(DockerApiVersion.chooseApiVersion(new DaemonVersions("latest", "1.44"))).isEmpty();
            assertThat(DockerApiVersion.chooseApiVersion(new DaemonVersions("1.52", "one.point.four"))).isEmpty();
        }

        @Test
        @DisplayName("normalises what it returns: 'v1.44' and ' 1.44 ' both come back as 1.44")
        void normalisesTheVersionItPublishes() {
            // The value becomes a system property that docker-java parses; a stray 'v' or
            // a stray space there would be a second, quieter failure.
            assertThat(DockerApiVersion.chooseApiVersion(new DaemonVersions("v1.52", "v1.44")))
                    .contains("1.44");
            assertThat(DockerApiVersion.chooseApiVersion(new DaemonVersions(" 1.52 ", " 1.44 ")))
                    .contains("1.44");
        }
    }

    @Nested
    @DisplayName("comparing versions")
    class Comparing {

        @Test
        @DisplayName("1.9 is older than 1.10, which string ordering gets backwards")
        void comparesMinorVersionsNumerically() {
            assertThat(DockerApiVersion.compareVersions("1.9", "1.10")).isNegative();
            assertThat(DockerApiVersion.compareVersions("1.10", "1.9")).isPositive();
            assertThat("1.9".compareTo("1.10")).isPositive();
        }

        @Test
        void comparesMajorVersionsFirst() {
            assertThat(DockerApiVersion.compareVersions("2.0", "1.99")).isPositive();
        }

        @Test
        void treatsEqualVersionsAsEqual() {
            assertThat(DockerApiVersion.compareVersions("1.44", "v1.44")).isZero();
            assertThat(DockerApiVersion.compareVersions(" 1.44 ", "1.44")).isZero();
        }

        @Test
        void refusesSomethingThatIsNotAVersion() {
            assertThatThrownBy(() -> DockerApiVersion.compareVersions("1.44", "unknown"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
