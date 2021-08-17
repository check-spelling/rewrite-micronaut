/*
 * Copyright 2021 the original author or authors.
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
package org.openrewrite.java.micronaut;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;

import java.util.*;

public class TypeRequiresIntrospection extends Recipe {
    private static final Collection<String> typesRequiringIntrospection = Arrays.asList("io.micronaut.http.annotation.Controller", "io.micronaut.http.client.annotation.Client");

    @Override
    public String getDisplayName() {
        return "Add `@Introspected` to classes requiring a map representation";
    }

    @Override
    public String getDescription() {
        return "In Micronaut 2.x a reflection-based strategy was used to retrieve that information if the class was not annotated with `@Introspected`. As of Micronaut 3.x it is required to annotate classes with `@Introspected` that are used in this way.";
    }

    private boolean parentRequiresIntrospection(@Nullable JavaType.FullyQualified type) {
        if (type == null) {
            return false;
        }
        for (JavaType.FullyQualified fullyQualified : type.getAnnotations()) {
            if (typesRequiringIntrospection.contains(fullyQualified.getFullyQualifiedName())) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected List<SourceFile> visit(List<SourceFile> before, ExecutionContext ctx) {
        // look for classes requiring Introspected types
        Set<JavaType.FullyQualified> typesFromSources = new HashSet<>();
        for (SourceFile sourceFile : before) {
            if (sourceFile instanceof J.CompilationUnit) {
                J.CompilationUnit cu = (J.CompilationUnit) sourceFile;
                for (J.ClassDeclaration classDeclaration : cu.getClasses()) {
                    typesFromSources.add(classDeclaration.getType());
                }
            }
        }

        Set<JavaType.FullyQualified> introspectableTypes = new HashSet<>();

        FindParamsAndReturnTypes findParamsAndReturnTypes = new FindParamsAndReturnTypes();
        for (SourceFile sourceFile : before) {
            if (sourceFile instanceof J.CompilationUnit) {
                J.CompilationUnit cu = (J.CompilationUnit) sourceFile;
                for (J.ClassDeclaration classDeclaration : cu.getClasses()) {
                    if (parentRequiresIntrospection(classDeclaration.getType())) {
                        Set<JavaType.FullyQualified> paramAndReturnTypes = new HashSet<>();
                        findParamsAndReturnTypes.visit(classDeclaration, paramAndReturnTypes);
                        for (JavaType.FullyQualified paramOrReturnType : paramAndReturnTypes) {
                            if (typesFromSources.contains(paramOrReturnType) && !parentRequiresIntrospection(paramOrReturnType)) {
                                introspectableTypes.add(paramOrReturnType);
                            }
                        }
                    }
                }
            }
        }

        return ListUtils.map(before, sourceFile -> {
            if (sourceFile instanceof J.CompilationUnit) {
                J.CompilationUnit cu = (J.CompilationUnit) sourceFile;
                for (J.ClassDeclaration aClass : cu.getClasses()) {
                    if (introspectableTypes.contains(aClass.getType())) {
                        return (SourceFile) new AddIntrospectionAnnotationVisitor().visit(cu, introspectableTypes);
                    }
                }
            }
            return sourceFile;
        });
    }

    @Override
    protected JavaIsoVisitor<ExecutionContext> getApplicableTest() {
        return new UsesType<>("io.micronaut.*");
    }

    private static class FindParamsAndReturnTypes extends JavaIsoVisitor<Set<JavaType.FullyQualified>> {
        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, Set<JavaType.FullyQualified> fullyQualifieds) {
            if (method.isConstructor()) {
                return method;
            }

            // method parameters need introspection
            for (Statement param : method.getParameters()) {
                if (param instanceof J.VariableDeclarations) {
                    J.VariableDeclarations variableDeclarations = (J.VariableDeclarations) param;
                    for (J.VariableDeclarations.NamedVariable namedVariable : variableDeclarations.getVariables()) {
                        fullyQualifieds.add(TypeUtils.asFullyQualified(namedVariable.getType()));
                    }
                }
            }
            // return type needs introspection
            if (method.getReturnTypeExpression() instanceof J.ParameterizedType) {
                J.ParameterizedType parameterizedType = (J.ParameterizedType) method.getReturnTypeExpression();
                if (parameterizedType.getTypeParameters() != null) {
                    for (Expression typeParam : parameterizedType.getTypeParameters()) {
                        fullyQualifieds.add(TypeUtils.asFullyQualified(typeParam.getType()));
                    }
                }
            } else if (method.getReturnTypeExpression() != null && method.getReturnTypeExpression().getType() != null) {
                fullyQualifieds.add(TypeUtils.asFullyQualified(method.getReturnTypeExpression().getType()));
            }
            return method;
        }
    }

    private static class AddIntrospectionAnnotationVisitor extends JavaIsoVisitor<Set<JavaType.FullyQualified>> {
        final String introspectedAnnotationFqn = "io.micronaut.core.annotation.Introspected";
        final AnnotationMatcher INTROSPECTION_ANNOTATION_MATCHER = new AnnotationMatcher("@" + introspectedAnnotationFqn);
        final JavaTemplate templ = JavaTemplate.builder(this::getCursor, "@Introspected")
                .imports(introspectedAnnotationFqn)
                .javaParser(() -> JavaParser.fromJavaVersion().dependsOn("package io.micronaut.core.annotation; public @interface Introspected {}").build())
                .build();

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, Set<JavaType.FullyQualified> introspectableTypes) {
            if (!introspectableTypes.contains(TypeUtils.asFullyQualified(classDecl.getType()))) {
                return classDecl;
            }

            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, introspectableTypes);
            if (cd.getLeadingAnnotations().stream().noneMatch(INTROSPECTION_ANNOTATION_MATCHER::matches)) {
                cd = cd.withTemplate(templ, cd.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName)));
                maybeAddImport(introspectedAnnotationFqn);
            }
            return cd;
        }
    }
}
