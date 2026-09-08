package cgsense;

import cgsense.ast.GumTreeRunner;

public class Main {

  public static void main(String[] args) {

    GumTreeRunner runner = new GumTreeRunner();

    String before = """
        package com.example;
        public class A {
            public void a() { return; a(); }
        }
            """;
    String after = """
        package com.example;
        public class A {
            public void a() { return; }
        }
          """;

    try {
      var r = runner.diff(before, after);

      r.script().forEach(a -> System.out.printf("%s\n\n", a.toString()));

    } catch (Exception e) {
      e.printStackTrace();
    }

    System.out.println("Hello World!");
  }
}
