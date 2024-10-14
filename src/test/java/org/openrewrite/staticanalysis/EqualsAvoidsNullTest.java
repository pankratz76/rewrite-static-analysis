/*
 * Copyright 2020 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.staticanalysis;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

@SuppressWarnings({"ClassInitializerMayBeStatic", "StatementWithEmptyBody", "ConstantConditions"})
class EqualsAvoidsNullTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new EqualsAvoidsNull());
    }

    @DocumentExample
    @Test
    void invertConditional() {
        rewriteRun(
          //language=java
          java(
            """
                    public class A {
                        {
                            String s = "literalsfirstincomparisons";
                            System.out.println(s.compareTo("literalsfirstincomparisons"));
                            System.out.println(s.compareToIgnoreCase("literalsfirstincomparisons"));
                            System.out.println(s.contentEquals("literalsfirstincomparisons"));
                            System.out.println(s.equals("literalsfirstincomparisons"));
                            System.out.println(s.equalsIgnoreCase("literalsfirstincomparisons"));
                        }
                    }
              """,
            """
              public class A {
                  {
                      String s = "literalsfirstincomparisons";
                      System.out.println("literalsfirstincomparisons".compareTo(s));
                      System.out.println("literalsfirstincomparisons".compareToIgnoreCase(s));
                      System.out.println("literalsfirstincomparisons".contentEquals(s));
                      System.out.println("literalsfirstincomparisons".equals(s));
                      System.out.println("literalsfirstincomparisons".equalsIgnoreCase(s));
                  }
              }
              """
          )
        );
    }

    @Test
    void removeUnnecessaryNullCheck() {
        rewriteRun(
          //language=java
          java(
            """
              public class A {
                  {
                      String s = null;
                      if(s != null && s.equals("literalsfirstincomparisons")) {}
                      if(null != s && s.equals("literalsfirstincomparisons")) {}
                  }
              }
              """,
            """
              public class A {
                  {
                      String s = null;
                      if("literalsfirstincomparisons".equals(s)) {}
                      if("literalsfirstincomparisons".equals(s)) {}
                  }
              }
              """
          )
        );
    }

    @Test
    void nullLiteral() {
        rewriteRun(
          //language=java
          java("""
              public class A {
                    void foo(String s) {
                        if(s.equals(null)) {
                        }
                    }
                }
              """,
            """
              
              public class A {
                    void foo(String s) {
                        if(s == null) {
                        }
                    }
                }
              """)
        );
    }

}
