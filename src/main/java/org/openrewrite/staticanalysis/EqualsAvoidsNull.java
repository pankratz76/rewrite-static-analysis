/*
 * Copyright 2024 the original author or authors.
 * <p>
 * Licensed under the Moderne Source Available License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://docs.moderne.io/licensing/moderne-source-available-license
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.staticanalysis;

import org.apache.commons.lang3.ObjectUtils;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.MethodMatcher;
import org.openrewrite.java.search.UsesMethod;
import org.openrewrite.java.style.Checkstyle;
import org.openrewrite.java.style.EqualsAvoidsNullStyle;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.Flag;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JLeftPadded;
import org.openrewrite.java.tree.JavaSourceFile;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.marker.Markers;

import java.time.Duration;
import java.util.Set;

import static java.time.Duration.ofMinutes;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;

public class EqualsAvoidsNull extends Recipe {

    @Override
    public String getDisplayName() {
        return "Equals avoids null";
    }

    @Override
    public String getDescription() {
        return """
                Checks that any combination of String literals is on the left side of an `equals()` comparison.
                Also checks for String literals assigned to some field (such as `someString.equals(anotherString = "text"))`.
                """;
    }

    @Override
    public Set<String> getTags() {
        return singleton("RSPEC-S1132");
    }

    @Override
    public Duration getEstimatedEffortPerOccurrence() {
        return ofMinutes(10);
    }

    private static final String JAVA_LANG_STRING = "java.lang.String";

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
                Preconditions.or(
                        new UsesMethod<>(JAVA_LANG_STRING + " contentEquals(..)"),
                        new UsesMethod<>(JAVA_LANG_STRING + " equals(..)"),
                        new UsesMethod<>(JAVA_LANG_STRING + " equalsIgnoreCase(..)")),
                new JavaIsoVisitor<ExecutionContext>() {

                    @Override
                    public J visit(@Nullable Tree tree, ExecutionContext ctx) {
                        if (tree instanceof JavaSourceFile) {
                            JavaSourceFile cu = (JavaSourceFile) requireNonNull(tree);
                            return new JavaVisitor<ExecutionContext>() {

                                private final MethodMatcher EQUALS =
                                        new MethodMatcher(JAVA_LANG_STRING + " equals(java.lang.Object)");
                                private final MethodMatcher EQUALS_IGNORE_CASE =
                                        new MethodMatcher(JAVA_LANG_STRING + " equalsIgnoreCase(" + JAVA_LANG_STRING + ")");
                                private final MethodMatcher CONTENT_EQUALS = new MethodMatcher(JAVA_LANG_STRING
                                        + " contentEquals(java.lang.CharSequence)");

                                @Override
                                public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext p) {
                                    J.MethodInvocation m = (J.MethodInvocation) super.visitMethodInvocation(method, p);
                                    if (m.getSelect() != null
                                            && !(m.getSelect() instanceof J.Literal)
                                            && isStringComparisonMethod(m)
                                            && hasCompatibleArgument(m)) {
                                        maybeHandleParentBinary(m, getCursor().getParentTreeCursor().getValue());
                                        Expression firstArgument = m.getArguments().get(0);
                                        return firstArgument.getType() == JavaType.Primitive.Null
                                                ? literalsFirstInComparisonsNull(m, firstArgument)
                                                : literalsFirstInComparisons(m, firstArgument);
                                    }
                                    return m;
                                }

                                private boolean hasCompatibleArgument(J.MethodInvocation m) {
                                    if (m.getArguments().isEmpty()) {
                                        return false;
                                    }
                                    Expression firstArgument = m.getArguments().get(0);
                                    if (firstArgument instanceof J.Literal) {
                                        return true;
                                    }
                                    if (firstArgument instanceof J.FieldAccess) {
                                        firstArgument = ((J.FieldAccess) firstArgument).getName();
                                    }
                                    if (firstArgument instanceof J.Identifier) {
                                        JavaType.Variable fieldType = ((J.Identifier) firstArgument).getFieldType();
                                        return fieldType != null && fieldType.hasFlags(Flag.Static, Flag.Final);
                                    }
                                    return false;
                                }

                                private boolean isStringComparisonMethod(J.MethodInvocation methodInvocation) {
                                    return EQUALS.matches(methodInvocation)
                                            || !ObjectUtils
                                            .defaultIfNull(cu.getStyle(EqualsAvoidsNullStyle.class),
                                                    Checkstyle.equalsAvoidsNull())
                                            .getIgnoreEqualsIgnoreCase()
                                            && EQUALS_IGNORE_CASE.matches(methodInvocation)
                                            || CONTENT_EQUALS.matches(methodInvocation);
                                }

                                private void maybeHandleParentBinary(J.MethodInvocation m, final Tree parent) {
                                    if (parent instanceof J.Binary) {
                                        if (((J.Binary) parent).getOperator() == J.Binary.Type.And
                                                && ((J.Binary) parent).getLeft() instanceof J.Binary) {
                                            J.Binary potentialNullCheck = (J.Binary) ((J.Binary) parent).getLeft();
                                            if (isNullLiteral(potentialNullCheck.getLeft())
                                                    && matchesSelect(potentialNullCheck.getRight(),
                                                    requireNonNull(m.getSelect()))
                                                    || isNullLiteral(potentialNullCheck.getRight())
                                                    && matchesSelect(potentialNullCheck.getLeft(),
                                                            requireNonNull(m.getSelect()))) {
                                                doAfterVisit(new JavaVisitor<ExecutionContext>() {

                                                    private final J.Binary scope = (J.Binary) parent;
                                                    private boolean done;

                                                    @Override
                                                    public @Nullable J visit(@Nullable Tree tree, ExecutionContext p) {
                                                        return done
                                                                ? (J) tree
                                                                : super.visit(tree, p);
                                                    }

                                                    @Override
                                                    public J visitBinary(J.Binary binary, ExecutionContext p) {
                                                        if (scope.isScope(binary)) {
                                                            done = true;
                                                            return binary.getRight().withPrefix(binary.getPrefix());
                                                        }
                                                        return super.visitBinary(binary, p);
                                                    }
                                                });
                                            }
                                        }
                                    }
                                }

                                private boolean isNullLiteral(Expression expression) {
                                    return expression instanceof J.Literal && ((J.Literal) expression).getType() == JavaType.Primitive.Null;
                                }

                                private boolean matchesSelect(Expression expression, Expression select) {
                                    return expression.printTrimmed(getCursor()).replaceAll("\\s", "")
                                            .equals(select.printTrimmed(getCursor()).replaceAll("\\s", ""));
                                }

                                private J.Binary literalsFirstInComparisonsNull(J.MethodInvocation m,
                                                                                Expression firstArgument) {
                                    return new J.Binary(Tree.randomId(),
                                            m.getPrefix(),
                                            Markers.EMPTY,
                                            requireNonNull(m.getSelect()),
                                            JLeftPadded.build(J.Binary.Type.Equal).withBefore(Space.SINGLE_SPACE),
                                            firstArgument.withPrefix(Space.SINGLE_SPACE),
                                            JavaType.Primitive.Boolean);
                                }

                                private J.MethodInvocation literalsFirstInComparisons(J.MethodInvocation m,
                                                                                      Expression firstArgument) {
                                    return m.withSelect(firstArgument.withPrefix(requireNonNull(m.getSelect()).getPrefix()))
                                            .withArguments(singletonList(m.getSelect().withPrefix(Space.EMPTY)));
                                }
                            }.visitNonNull(cu, ctx);
                        }
                        //noinspection DataFlowIssue
                        return (J) tree;
                    }
                }
        );
    }
}
