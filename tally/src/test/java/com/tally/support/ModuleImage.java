package com.tally.support;

import java.nio.file.Path;
import org.testcontainers.images.ParsedDockerfile;
import org.testcontainers.images.RemoteDockerImage;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

/**
 * A test image built from a module's own Dockerfile and the jar the reactor built for it (FR-020): the fake
 * provider's today, Access's from Slice 2 on.
 *
 * <p>The Dockerfile's base images are pulled first, by Testcontainers, and the build only uses them. On Docker 28
 * with the classic image store (overlay2), a build that pulls its own base image fails once with "failed to
 * export image: failed to create image: failed to get layer … layer does not exist", and the next build succeeds.
 * That failed every nightly load run from 9 to 27 October 2026 (Slice 2 step 0b; the evidence is on PR-0).
 */
public final class ModuleImage {

    private ModuleImage() {
    }

    /**
     * The image {@code name}, from {@code module}'s Dockerfile, with {@code jar} at {@code jarInContext}: the path
     * the Dockerfile copies from. The build context holds only these two files.
     */
    public static ImageFromDockerfile of(String name, Path module, String jarInContext, Path jar) {
        Path dockerfile = module.resolve("Dockerfile");
        return new ImageFromDockerfile(name, false)
                .withFileFromPath("Dockerfile", dockerfile)
                .withFileFromPath(jarInContext, jar)
                // Runs when the image is resolved, just before the build: the first test that needs the image
                // pays for the pull, and a test that never needs it pulls nothing.
                .withBuildImageCmdModifier(build -> pullBaseImages(dockerfile));
    }

    static void pullBaseImages(Path dockerfile) {
        for (String image : new ParsedDockerfile(dockerfile).getDependencyImageNames()) {
            new RemoteDockerImage(DockerImageName.parse(image)).get();
        }
    }
}
