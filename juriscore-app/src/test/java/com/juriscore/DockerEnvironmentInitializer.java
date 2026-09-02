package com.juriscore;

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

/**
 * Settles how the build will talk to Docker before JUnit discovers a single test class:
 * which endpoint ({@link DockerEnvironment}) and which API version
 * ({@link DockerApiVersion}).
 *
 * <h2>Why a launcher listener rather than a static initializer</h2>
 *
 * <p>The resolver was previously called from {@code AbstractIntegrationTest}'s static
 * block. That is early enough <em>only</em> if nothing else in the JVM has already asked
 * Testcontainers where Docker is: {@code DockerClientFactory} resolves a strategy once,
 * on first use, and caches it for the life of the JVM. Anything that touches it first —
 * an extension evaluating whether Docker is available, another test class, a library
 * initialising eagerly — fixes the answer before our class is ever initialised, and no
 * property set afterwards can move it. Relying on class-initialisation order across
 * JUnit, Spring and Testcontainers is a race that happens to be won, not a guarantee.
 *
 * <p>{@link LauncherSessionListener} is the earliest extension point the JUnit Platform
 * offers. It is discovered through {@code ServiceLoader} and
 * {@link #launcherSessionOpened} runs when the launcher session opens — before discovery,
 * before any test class is loaded, and therefore before anything can have initialised
 * Testcontainers. That turns "early enough in practice" into "first, by construction".
 *
 * <p>Registered in {@code META-INF/services/org.junit.platform.launcher.LauncherSessionListener}.
 * The static block in {@code AbstractIntegrationTest} is kept as an idempotent backstop
 * for runners that bypass the launcher, such as some IDE test actions.
 */
public class DockerEnvironmentInitializer implements LauncherSessionListener {

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        DockerEnvironment.ensureConfigured();
        // Read back through Testcontainers' own API. Publishing without verifying is how a
        // value that never landed came to look exactly like one that did.
        DockerEnvironment.reportTestcontainersView();
        // Order matters: the version is negotiated against the endpoint chosen above, and
        // must be published before Testcontainers builds its first client — that client is
        // pinned to Docker API 1.32 unless docker-java has already resolved a version, and
        // 1.32 is below the floor of Docker Engine 29.
        DockerApiVersion.ensureNegotiated();
    }
}
