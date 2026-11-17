package cgsense.mining;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JavaCodeTest {
  @Test
  void ignoresWhitespaceAndFormatting() {
    assertTrue(
        JavaCode.sameCode(
            "class A { int f() { return 1; } }",
            "class A {\n  int f() {\n    return 1;\n  }\n}\n"));
  }

  @Test
  void ignoresLineAndBlockComments() {
    assertTrue(
        JavaCode.sameCode(
            "class A { int f() { return 1; } }",
            "// header\nclass A { /** doc */ int f() { return 1; /* inline */ } } // trailing"));
  }

  @Test
  void keepsStringAndCharLiteralContents() {
    assertEquals("s=\"// not a comment\";", JavaCode.strip("s = \"// not a comment\";"));
    assertEquals("s=\"/* nor this */\";", JavaCode.strip("s = \"/* nor this */\";"));
    assertFalse(JavaCode.sameCode("s = \"a  b\";", "s = \"a b\";"));
    assertFalse(JavaCode.sameCode("c = ' ';", "c = 'x';"));
  }

  @Test
  void handlesEscapedQuotesInLiterals() {
    assertEquals("s=\"a\\\" // b\";", JavaCode.strip("s = \"a\\\" // b\";"));
    assertEquals("c='\\'';", JavaCode.strip("c = '\\'';"));
  }

  @Test
  void keepsTextBlockContents() {
    assertFalse(
        JavaCode.sameCode("s = \"\"\"\n  a // b\n  \"\"\";", "s = \"\"\"\n  a  // b\n  \"\"\";"));
  }

  @Test
  void detectsRealCodeChange() {
    assertFalse(
        JavaCode.sameCode(
            "class A { int f() { return 1; } }", "class A { int f() { return 2; } }"));
  }

  @Test
  void unterminatedLiteralAtEndOfInputDoesNotThrow() {
    assertDoesNotThrow(() -> JavaCode.strip("s = \"abc"));
    assertDoesNotThrow(() -> JavaCode.strip("c = 'x"));
    assertDoesNotThrow(() -> JavaCode.strip("s = \"abc\\"));
    assertDoesNotThrow(() -> JavaCode.strip("s = \"\"\"\nabc"));
    assertDoesNotThrow(() -> JavaCode.strip("/* unterminated"));
  }
}
