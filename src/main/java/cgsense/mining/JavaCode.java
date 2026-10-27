package cgsense.mining;

public class JavaCode {

  public static boolean sameCode(String s1, String s2) {
    return strip(s1).equals(strip(s2));
  }

  public static String strip(String s) {
    StringBuilder result = new StringBuilder(s.length());
    int n = s.length();
    int i = 0;

    while (i < n) {
      char c = s.charAt(i);

      if (Character.isWhitespace(c)) {
        i++;
      } else if (s.startsWith("//", i)) {
        while (i < n && s.charAt(i) != '\n')
          i++;
      } else if (s.startsWith("/*", i)) {
        int end = s.indexOf("*/", i + 2);
        i = end < 0 ? n : end + 2;
      } else if (s.startsWith("\"\"\"", i)) {
        int end = s.indexOf("\"\"\"", i + 3);
        int j = end < 0 ? n : end + 3;
        result.append(s, i, j);
        i = j;
      } else if (c == '"' || c == '\'') {
        int j = i + 1;
        while (i < n && s.charAt(j) != c && s.charAt(j) != '\n') {
          if (s.charAt(j) == '\\') {
            j++;
          }
          j++;
        }
        j = Math.min(n, j + 1);
        result.append(s, i, j);
        i = j;
      } else {
        result.append(c);
        i++;
      }
    }
    return result.toString();
  }
}
