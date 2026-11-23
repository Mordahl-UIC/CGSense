package cgsense.exec;

import java.nio.file.Path;
import java.util.List;

/** The containers cgsense runs. Callers add their own setup before {@code start()}. */
public final class Containers {
  private static final String BUILD_IMAGE = "cgsense-build";
  private static final String M2_VOLUME = "cgsense-m2";
  private static final String GRADLE_VOLUME = "cgsense-gradle";
  public static final String REPO_MIRROR = "/mirror";
  public static final List<Integer> JDKS = List.of(26, 25, 21, 17, 11, 8);

  private Containers() {}

  /**
   * A repository's build environment
   *
   * @param slug repository slug, used for the container name
   * @param mirror host path of the repository's bare clone
   */
  public static Container.Builder repo(String slug, Path mirror) {
    return Container.named("cgsense-" + slug)
        .image(BUILD_IMAGE)
        .volume(M2_VOLUME, "/root/.m2")
        .volume(GRADLE_VOLUME, "/root/.gradle")
        .copyIn(mirror, REPO_MIRROR);
  }

  /** JAVA_HOME of {@code jdk} in the build image */
  public static String jdkHome(int jdk) {
    return "/opt/jdk/" + jdk;
  }
}
