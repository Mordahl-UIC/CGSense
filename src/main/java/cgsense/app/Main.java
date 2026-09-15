package cgsense.app;

import org.kohsuke.github.GHRepository;
import cgsense.astdiff.GumTreeRunner;
import cgsense.discovery.GithubClientFactory;
import cgsense.discovery.RepositoryDiscovery;
import cgsense.reader.ResourceReader;

public class Main {

  public static void main(String[] args) {
    try {
      GumTreeRunner runner = new GumTreeRunner();
      String before = ResourceReader.read("test_files/TestBefore1.java");
      String after = ResourceReader.read("test_files/TestAfter1.java");

      var r = runner.diff(before, after);
      r.script().forEach(a -> System.out.printf("%s\n\n", a.toString()));

      /*
       * RepositoryDiscovery discovery = new
       * RepositoryDiscovery(GithubClientFactory.create());
       * 
       * for (GHRepository repo : discovery.topJava(10)) {
       * System.out.printf("%s\n", repo.getFullName());
       * }
       */

    } catch (Exception e) {
      e.printStackTrace();
    }
  }
}
