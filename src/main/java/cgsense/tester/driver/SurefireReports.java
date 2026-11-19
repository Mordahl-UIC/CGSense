package cgsense.tester.driver;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/*SurefireReports finds and reads the test-result files that Maven (Surefire/Failsafe) or Gradle write. It turns them into a simple list: for each test, its status (passed,
failed, error or skipped) and failure details. GraphBuilder attaches those statuses to the test contexts in callgraph.json.*/

/** Reading per-test outcomes from Surefire/Failsafe XML reports. */
final class SurefireReports {
  private static final Set<String> REPORT_DIR_NAMES =
      Set.of("surefire-reports", "failsafe-reports");

  /**
   * Worst outcome wins when several invocations (parameterized cases, retries) share one method.
   */
  private static final Map<String, Integer> STATUS_RANK =
      Map.of("error", 4, "failed", 3, "passed", 2, "skipped", 1);

  private static final int MAX_TRACE_LINES = 25;

  private SurefireReports() {}

  /// TestInvocation is a small data container that holds the outcome of one test method invocation.
  // It has no logic, only fields and a constructor.
  static final class TestInvocation {
    /** "&lt;class&gt;#&lt;method&gt;" at method granularity. */
    final String testId;

    final String reportedName;
    final String status;

    /** kind, type, message, trace; null when the invocation passed or was skipped. */
    final Map<String, Object> failure;

    TestInvocation(String testId, String reportedName, String status, Map<String, Object> failure) {
      this.testId = testId;
      this.reportedName = reportedName;
      this.status = status;
      this.failure = failure;
    }
  }

  /** Reduce a reported test name such as 'parse(String)[2]' to its method name. */
  static String methodKey(String className, String reportedName) {
    String base = reportedName.split("[\\[(]", 2)[0].strip();
    return className + "#" + (base.isEmpty() ? reportedName : base);
  }

  /** XML report directories written by Maven Surefire/Failsafe or Gradle test tasks. */
  static List<Path> reportDirs(Path projectDir, BuildTool buildTool) throws IOException {
    return buildTool == BuildTool.GRADLE
        ? findGradleReportDirs(projectDir)
        : findReportDirs(projectDir, true);
  }

  /** Report directories inside a copy made by {@link TestRunner} (no {@code target} parent). */
  static List<Path> copiedReportDirs(Path copyRoot) throws IOException {
    return Files.isDirectory(copyRoot) ? findXmlReportDirs(copyRoot) : List.of();
  }

  // Find Gradle report directories, skipping .git and .gradle subtrees. Only accept directories
  // that are below build/test-results. Sort the result by path.
  private static List<Path> findGradleReportDirs(Path root) throws IOException {
    List<Path> found = new ArrayList<>();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Path name = dir.getFileName();
            if (!dir.equals(root)
                && name != null
                && (name.toString().equals(".git") || name.toString().equals(".gradle"))) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            if (containsTestXml(dir) && isBelowTestResults(root, dir)) {
              found.add(dir);
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException error) {
            return FileVisitResult.CONTINUE;
          }
        });
    Collections.sort(found);
    return found;
  }

  // Check if the given directory is below a build/test-results path relative to the root. This is
  // used to filter out Gradle report directories that are not in the expected location.
  private static boolean isBelowTestResults(Path root, Path dir) {
    Path relative = root.relativize(dir);
    for (int i = 0; i + 1 < relative.getNameCount(); i++) {
      if (relative.getName(i).toString().equals("build")
          && relative.getName(i + 1).toString().equals("test-results")) {
        return true;
      }
    }
    return false;
  }

  // Find report directories that are named surefire-reports or failsafe-reports, optionally
  // requiring that their parent directory is named target. Sort the result by path.
  private static List<Path> findXmlReportDirs(Path root) throws IOException {
    List<Path> found = new ArrayList<>();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            if (containsTestXml(dir)) {
              found.add(dir);
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }
        });
    Collections.sort(found);
    return found;
  }

  // Check if the given directory contains any TEST-*.xml files, which indicates that it is a test
  // report directory. This is used to identify Surefire/Failsafe or Gradle report directories.
  private static boolean containsTestXml(Path dir) throws IOException {
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "TEST-*.xml")) {
      return stream.iterator().hasNext();
    }
  }

  // Find report directories that are named surefire-reports or failsafe-reports, optionally
  // requiring that their parent directory is named target. Sort the result by path.
  private static List<Path> findReportDirs(Path root, boolean requireTargetParent)
      throws IOException {
    List<Path> found = new ArrayList<>();
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            Path name = dir.getFileName();
            Path parent = dir.getParent();
            if (!dir.equals(root)
                && name != null
                && REPORT_DIR_NAMES.contains(name.toString())
                && (!requireTargetParent
                    || (parent != null
                        && parent.getFileName() != null
                        && parent.getFileName().toString().equals("target")))) {
              found.add(dir);
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException error) {
            return FileVisitResult.CONTINUE;
          }
        });
    Collections.sort(found);
    return found;
  }

  // Parse the XML report files in the given directories and return a list of TestInvocation objects
  // representing the outcome of each test method invocation. This method handles both
  // Surefire/Failsafe and Gradle report formats.
  static List<TestInvocation> parse(List<Path> directories) throws IOException {
    DocumentBuilder builder = newBuilder();
    List<TestInvocation> invocations = new ArrayList<>();
    for (Path directory : directories) {
      List<Path> files = new ArrayList<>();
      try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "TEST-*.xml")) {
        stream.forEach(files::add);
      }
      Collections.sort(files);
      for (Path file : files) {
        Element root;
        try {
          root = builder.parse(file.toFile()).getDocumentElement();
        } catch (SAXException | IOException e) {
          continue;
        }
        List<Element> suites =
            root.getTagName().equals("testsuite") ? List.of(root) : children(root, "testsuite");
        for (Element suite : suites) {
          NodeList cases = suite.getElementsByTagName("testcase");
          for (int i = 0; i < cases.getLength(); i++) {
            invocations.add(invocation((Element) cases.item(i), suite.getAttribute("name")));
          }
        }
      }
    }
    return invocations;
  }

  // Create a TestInvocation object from a <testcase> XML element, extracting the class name, method
  // name, status, and failure details. This method handles both passed and failed test cases, as
  // well as skipped tests.
  private static TestInvocation invocation(Element testCase, String suiteName) {
    String className =
        testCase.hasAttribute("classname") && !testCase.getAttribute("classname").isEmpty()
            ? testCase.getAttribute("classname")
            : suiteName;
    String name = testCase.getAttribute("name");
    String status = "passed";
    Map<String, Object> failure = null;
    for (String[] tagAndOutcome : new String[][] {{"error", "error"}, {"failure", "failed"}}) {
      Element element = firstChild(testCase, tagAndOutcome[0]);
      if (element != null) {
        status = tagAndOutcome[1];
        String text = element.getTextContent() == null ? "" : element.getTextContent().strip();
        List<String> trace = text.isEmpty() ? List.of() : Arrays.asList(text.split("\\R"));
        failure = new LinkedHashMap<>();
        failure.put("kind", status);
        failure.put("type", attributeOrNull(element, "type"));
        failure.put("message", attributeOrNull(element, "message"));
        failure.put(
            "trace", new ArrayList<>(trace.subList(0, Math.min(MAX_TRACE_LINES, trace.size()))));
        break;
      }
    }
    if (failure == null && firstChild(testCase, "skipped") != null) {
      status = "skipped";
    }
    return new TestInvocation(methodKey(className, name), name, status, failure);
  }

  /**
   * Outcomes written by dyncg's Gradle test listener, which keeps the method of parameterized cases
   * that Gradle's XML reports name only by their display name.
   */
  @SuppressWarnings("unchecked")
  /* Parse the Gradle test log file and return a list of TestInvocation objects representing the outcome of each test method invocation. This method reads the JSON lines in the log file and extracts the class name, method name, status, and failure details for each test case. */
  static List<TestInvocation> parseGradleTestLog(Path log) throws IOException {
    List<TestInvocation> invocations = new ArrayList<>();
    for (String line : Files.readAllLines(log)) {
      if (line.isBlank()) {
        continue;
      }
      Map<String, Object> row = Json.MAPPER.readValue(line, Map.class);
      String className = (String) row.get("class");
      String method = (String) row.get("method");
      if (className == null || method == null) {
        continue;
      }
      invocations.add(
          new TestInvocation(
              methodKey(className, method),
              String.valueOf(row.get("name")),
              (String) row.get("status"),
              (Map<String, Object>) row.get("failure")));
    }
    return invocations;
  }

  //
  static String worst(List<String> statuses) {
    String worst = statuses.get(0);
    for (String status : statuses) {
      if (STATUS_RANK.getOrDefault(status, 0) > STATUS_RANK.getOrDefault(worst, 0)) {
        worst = status;
      }
    }
    return worst;
  }

  // Create a new DocumentBuilder instance with secure processing features enabled. This method
  // configures the XML parser to prevent external entity attacks and other security vulnerabilities
  // when parsing XML report files.
  private static String attributeOrNull(Element element, String name) {
    return element.hasAttribute(name) ? element.getAttribute(name) : null;
  }

  // Get the first child element of the given parent element that has the specified tag name. If no
  // such child exists, return null. This method is used to navigate the XML DOM tree when parsing
  // test report files.
  private static Element firstChild(Element parent, String tag) {
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element && ((Element) node).getTagName().equals(tag)) {
        return (Element) node;
      }
    }
    return null;
  }

  // Get all child elements of the given parent element that have the specified tag name. If no such
  // children exist, return an empty list. This method is used to navigate the XML DOM tree when
  // parsing test report files.
  private static List<Element> children(Element parent, String tag) {
    List<Element> result = new ArrayList<>();
    for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
      if (node instanceof Element && ((Element) node).getTagName().equals(tag)) {
        result.add((Element) node);
      }
    }
    return result;
  }

  /* Create a new DocumentBuilder instance with secure processing features enabled. This method configures the XML parser to prevent external entity attacks and other security vulnerabilities when parsing XML report files.*/
  private static DocumentBuilder newBuilder() {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setExpandEntityReferences(false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      builder.setErrorHandler(new DefaultHandler()); // skip broken files silently
      return builder;
    } catch (ParserConfigurationException e) {
      throw new IllegalStateException(e);
    }
  }
}
