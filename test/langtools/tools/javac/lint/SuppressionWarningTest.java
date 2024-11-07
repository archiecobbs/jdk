/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/**
 * @test
 * @bug 9999999
 * @summary Test "suppressed" and "suppressed-option" lint warnings
 * @library /tools/lib
 * @modules
 *      jdk.compiler/com.sun.tools.javac.api
 *      jdk.compiler/com.sun.tools.javac.code
 *      jdk.compiler/com.sun.tools.javac.main
 * @build toolbox.ToolBox toolbox.JavacTask
 * @run main SuppressionWarningTest
 */

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.sun.tools.javac.code.Lint.LintCategory;

import toolbox.JavacTask;
import toolbox.Task.Mode;
import toolbox.Task;
import toolbox.TestRunner;
import toolbox.ToolBox;

import static com.sun.tools.javac.code.Lint.LintCategory.*;

public class SuppressionWarningTest extends TestRunner {

    // Test cases for testSuppressWarnings()
    public static final List<Object[]> SUPPRESS_WARNINGS_TEST_CASES = Stream.of(LintCategory.values())
      .filter(category -> category.suppressionTracking)
      .map(category -> {

        // Each String[] consists of:
        //  - the expected compiler warning key for the lint category
        //  - one or more Java source files with optional @OUTER@ and @INNER@ placeholders
        String[] array = switch (category) {
        case AUXILIARYCLASS -> new String[] {
            "compiler.warn.auxiliary.class.accessed.from.outside.of.its.source.file",
            """
            public class Class1 { }
            class AuxClass { }
            """,
            """
            @OUTER@
            public class Class2 {
                @INNER@
                public Object obj = new AuxClass();
            }
            """
        };

        case CAST -> new String[] {
            "compiler.warn.redundant.cast",
            """
            @OUTER@
            public class Test {
                @INNER@
                public Object obj = (Object)new Object();
            }
            """
        };

        case CLASSFILE -> null; // skip, too hard to simluate

        // Does not support @SupppressWarnings
        case DANGLING_DOC_COMMENTS -> new String[] {
            "compiler.warn.dangling.doc.comment",
            """
            /** Dangling comment */
            /** Javadoc comment */
            public class Test {
            }
            """
        };

        case DEPRECATION -> new String[] {
            "compiler.warn.has.been.deprecated",
            """
            public class Super {
                @Deprecated
                public void foo() { }
            }
            """,
            """
            @OUTER@
            public class Sub extends Super {
                @INNER@
                @Override
                public void foo() { }
            }
            """
        };

        case DEP_ANN -> new String[] {
            "compiler.warn.missing.deprecated.annotation",
            """
            @OUTER@
            public class Test {
                @INNER@
                public class TestSub {
                    /** @deprecated */
                    public void method() { }
                }
            }
            """
        };

        case DIVZERO -> new String[] {
            "compiler.warn.div.zero",
            """
            @OUTER@
            public class Test {
                @INNER@
                public int method() {
                    return 1/0;
                }
            }
            """
        };

        case EMPTY -> new String[] {
            "compiler.warn.empty.if",
            """
            @OUTER@
            public class Test {
                @INNER@
                public void method(boolean x) {
                    if (x);
                }
            }
            """
        };

        case EXPORTS -> new String[] {
            "compiler.warn.leaks.not.accessible",
            """
            module mod {
                exports pkg1;
            }
            """,
            """
            package pkg1;
            @OUTER@
            public class Class1 {
                @INNER@
                public pkg2.Class2 obj2;    // warning here
            }
            """,
            """
            package pkg2;
            public class Class2 {
            }
            """
        };

        case FALLTHROUGH -> new String[] {
            "compiler.warn.possible.fall-through.into.case",
            """
            @OUTER@
            public class Test {
                @INNER@
                public void method(int x) {
                    switch (x) {
                    case 1:
                        System.out.println(1);
                    default:
                        System.out.println(0);
                    }
                }
            }
            """
        };

        case FINALLY -> new String[] {
            "compiler.warn.finally.cannot.complete",
            """
            @OUTER@
            public class Test {
                @INNER@
                public void method(int x) {
                    try {
                        System.out.println(x);
                    } finally {
                        throw new RuntimeException();
                    }
                }
            }
            """
        };

        case INCUBATING -> null; // skip, too hard to simluate reliably over time

        case LOSSY_CONVERSIONS -> new String[] {
            "compiler.warn.possible.loss.of.precision",
            """
            @OUTER@
            public class Test {
                @INNER@
                public void method() {
                    long b = 1L;
                    b += 0.1 * 3L;
                }
            }
            """
        };

        case MISSING_EXPLICIT_CTOR -> new String[] {
            "compiler.warn.missing-explicit-ctor",
            """
            module mod {
                exports pkg1;
            }
            """,
            """
            package pkg1;
            @OUTER@
            public class Class1 {
                public Class1(int x) {
                }
                @INNER@
                public static class Sub {
                }
            }
            """
        };

        case MODULE -> new String[] {
            "compiler.warn.poor.choice.for.module.name",
            """
            @OUTER@
            module mod0 {
            }
            """
        };

        case OPENS -> new String[] {
            "compiler.warn.package.empty.or.not.found",
            """
            @OUTER@
            module mod {
                opens pkg1;
            }
            """
        };

        case OUTPUT_FILE_CLASH -> null; // skip, too hard to simluate

        case OVERLOADS -> new String[] {
            "compiler.warn.potentially.ambiguous.overload",
            """
            import java.util.function.*;
            @OUTER@
            public class Super {
                public void foo(IntConsumer c) {
                }
                @INNER@
                public void foo(Consumer<Integer> c) {
                }
            }
            """
        };

        case OVERRIDES -> new String[] {
            "compiler.warn.override.equals.but.not.hashcode",
            """
            @OUTER@
            public class Test {
                @INNER@
                public class Test2 {
                    public boolean equals(Object obj) {
                        return false;
                    }
                }
            }
            """
        };

        case PATH -> null; // skip, too hard to simluate

        case PROCESSING -> null;    // skip for now

        case RAW -> new String[] {
            "compiler.warn.raw.class.use",
            """
            @OUTER@
            public class Test {
                @INNER@
                public void foo() {
                    Iterable i = null;
                }
            }
            """
        };

        case REMOVAL -> new String[] {
            "compiler.warn.has.been.deprecated.for.removal",
            """
            public class Super {
                @Deprecated(forRemoval = true)
                public void foo() { }
            }
            """,
            """
            @OUTER@
            public class Sub extends Super {
                @INNER@
                @Override
                public void foo() { }
            }
            """
        };

        case REQUIRES_AUTOMATIC -> null;    // skip for now, too complicated

        case REQUIRES_TRANSITIVE_AUTOMATIC -> null; // skip for now, too complicated

        case SERIAL -> new String[] {
            "compiler.warn.missing.SVUID",
            """
            @OUTER@
            public class Test {
                @INNER@
                public static class Inner implements java.io.Serializable {
                    public int x;
                }
            }
            """
        };

        case STATIC -> new String[] {
            "compiler.warn.static.not.qualified.by.type",
            """
            @OUTER@
            public class Test {
                public static void foo() {
                }
                @INNER@
                public void bar() {
                    this.foo();
                }
            }
            """
        };

        case STRICTFP -> new String[] {
            "compiler.warn.strictfp",
            """
            @OUTER@
            public class Test {
                @INNER@
                public strictfp void foo() {
                }
            }
            """
        };

        case SYNCHRONIZATION -> new String[] {
            "compiler.warn.attempt.to.synchronize.on.instance.of.value.based.class",
            """
            @OUTER@
            public class Outer {
                @INNER@
                public void foo() {
                    Integer i = 42;
                    synchronized (i) {
                    }
                }
            }
            """
        };

        // Does not support @SupppressWarnings
        case TEXT_BLOCKS -> new String[] {
            "compiler.warn.trailing.white.space.will.be.removed",
            """
            public class Test {
                public void foo() {
                    String s =
                        \"\"\"
                        add trailing spaces here:
                        \"\"\";
                }
            }
            """.replaceAll("add trailing spaces here:", "$0    ")
        };

        case THIS_ESCAPE -> new String[] {
            "compiler.warn.possible.this.escape",
            """
            @OUTER@
            public class Outer {
                @INNER@
                public static class Inner {
                    public Inner() {
                        leak();
                    }
                    public void leak() { }
                }
            }
            """
        };

        case TRY -> new String[] {
            "compiler.warn.try.explicit.close.call",
            """
            import java.io.*;
            @OUTER@
            public class Outer {
                @INNER@
                public void foo() throws IOException {
                    try (InputStream in = new FileInputStream("x")) {
                        in.close();
                    }
                }
            }
            """
        };

        case UNCHECKED -> new String[] {
            "compiler.warn.prob.found.req: (compiler.misc.unchecked.cast.to.type)",
            """
            @OUTER@
            public class Test {
                public void foo() {
                    Iterable<?> c = null;
                    @INNER@
                    Iterable<Short> t = (Iterable<Short>)c, s = null;
                }
            }
            """
        };

        case VARARGS -> new String[] {
            "compiler.warn.varargs.unsafe.use.varargs.param",
            """
            @OUTER@
            public class Test {
                @INNER@
                @SafeVarargs
                public static <T> void bar(final T... barArgs) {
                    baz(barArgs);
                }
                public static <T> void baz(final T[] bazArgs) {
                }
            }
            """
        };

        case PREVIEW -> null;    // skip for now, too hard to test reliably

        case RESTRICTED -> new String[] {
            "compiler.warn.restricted.method",
            """
            @OUTER@
            public class Test {
                @INNER@
                public void foo() {
                    System.load("");
                }
            }
            """
        };

        default -> throw new AssertionError("missing test case for " + category);

        };

        // Skip unsupported categories
        if (array == null)
            return null;

        // Build parameter array
        List<String> strings = List.of(array);
        String categoryWarning = strings.get(0);
        strings = strings.subList(1, strings.size());
        return new Object[] { category, categoryWarning, strings };
      })
      .filter(Objects::nonNull)         // skip categories with no test case defined
      .collect(Collectors.toList());

    protected final ToolBox tb;

    public SuppressionWarningTest() {
        super(System.err);
        tb = new ToolBox();
    }

    public static void main(String... args) throws Exception {
        SuppressionWarningTest test = new SuppressionWarningTest();

        // Run parameterized tests
        test.runTestsMulti(m -> switch (m.getName()) {
          case "testSuppressWarnings" ->        SUPPRESS_WARNINGS_TEST_CASES.stream();
          case "testUselessAnnotation" ->       Stream.of(LintCategory.values())
                                                  .filter(category -> category.suppressionTracking)
                                                  .map(category -> new Object[] { category });
          case "testUselessLintFlag" ->         Stream.of(LintCategory.values())
                                                  .filter(category -> category.suppressionTracking)
                                                  .map(category -> new Object[] { category });
          case "testSelfSuppression" ->         Stream.of(RAW, SUPPRESSION)
                                                  .map(category -> new Object[] { category });
          default -> throw new AssertionError("missing params for " + m);
        });
    }

    // We are testing all combinations of nested @SuppressWarning annotations and lint flags
    @Test
    public void testSuppressWarnings(LintCategory category,
      String categoryWarning, List<String> sourceTemplates) throws Exception {

        // Setup source & destination diretories
        Path base = Paths.get("testSuppressWarnings");
        Path sourceDir = base.resolve("sources." + category.option);
        Path classDir = base.resolve("classes." + category.option);
        Files.createDirectories(sourceDir);
        Files.createDirectories(classDir);

        // Detect if a module is being compiled; if so we need to create an extra directory level
        Pattern moduleDecl = Pattern.compile("module\\s+(\\S*).*");
        String[] moduleNames = sourceTemplates.stream()
          .flatMap(source -> Stream.of(source.split("\\n")))
          .map(moduleDecl::matcher)
          .filter(Matcher::matches)
          .map(matcher -> matcher.group(1))
          .toArray(String[]::new);
        if (moduleNames.length > 1)
            throw new AssertionError("invalid multi-module test case");
        Path topDir = moduleNames.length > 0 ? sourceDir.resolve(moduleNames[0]) : sourceDir;

        // Create a @SuppressWarnings annotation
        String annotation = String.format("@SuppressWarnings(\"%s\")", category.option);

        // Certain lint categories support limited or no @SuppressWarnings annotations
        boolean suportsOuterAnnotation = sourceTemplates.stream().anyMatch(source -> source.contains("@OUTER@"));
        boolean suportsInnerAnnotation = sourceTemplates.stream().anyMatch(source -> source.contains("@INNER@"));

        // Try all combinations of inner and outer @SuppressWarnings
        boolean[] booleans = new boolean[] { false, true };
        for (boolean outerAnnotation : booleans) {
            for (boolean innerAnnotation : booleans) {

                // Skip this scenario if not supported by test case
                if ((outerAnnotation && !suportsOuterAnnotation) || (innerAnnotation && !suportsInnerAnnotation))
                    continue;

                // Insert (or not) annotations into source templates and write them out
                String[] sources = sourceTemplates.stream()
                  .map(source -> source.replace("@OUTER@",
                    String.format("%s@SuppressWarnings(\"%s\")", outerAnnotation ? "" : "//", category.option)))
                  .map(source -> source.replace("@INNER@",
                    String.format("%s@SuppressWarnings(\"%s\")", innerAnnotation ? "" : "//", category.option)))
                  .toArray(String[]::new);
                tb.writeJavaFiles(topDir, sources);

                // Try all combinations of lint flags
                for (boolean enableCategory : booleans) {                       // category/-category
                    for (boolean enableSuppression : booleans) {                // suppression/-suppression
                        for (boolean enableSuppressionOption : booleans) {      // suppression-option/-suppression-option

                            // Which warning should we expect?
                            boolean expectCategoryWarning = enableCategory &&
                              !outerAnnotation && !innerAnnotation;
                            boolean expectSuppressionWarning = enableSuppression &&
                              outerAnnotation && innerAnnotation;
                            boolean expectSuppressionOptionWarning = enableSuppressionOption && !enableCategory &&
                              (outerAnnotation || innerAnnotation);
                            boolean expectWarning = expectCategoryWarning ||
                              expectSuppressionWarning || expectSuppressionOptionWarning;

                            String lintOption = String.format("-Xlint:%s%s,%s%s,%s%s,%s%s",
                              enableCategory ? "" : "-", category.option,
                              enableSuppression ? "" : "-", SUPPRESSION.option,
                              enableSuppressionOption ? "" : "-", OPTIONS.option,
                              enableSuppressionOption ? "" : "-", SUPPRESSION_OPTION.option);

                            String description = String.format(
                              "[%s] outer %s inner %s %s", category, outerAnnotation, innerAnnotation, lintOption);
                            out.println(String.format(">>> Test  START: %s", description));
                            if (false) {
                                out.println(String.format("   expectCategoryWarning=%s", expectCategoryWarning));
                                out.println(String.format("   expectSuppressionWarning=%s", expectSuppressionWarning));
                                out.println(String.format("   expectSuppressionOptionWarning=%s", expectSuppressionOptionWarning));
                                Stream.of(sources).forEach(out::println);
                            }

                            // Compile sources and get log output
                            List<String> log = compile(sourceDir, classDir, lintOption,
                              expectWarning ? Task.Expect.FAIL : Task.Expect.SUCCESS);

                            // Scrub insignificant log output
                            log.removeIf(line -> line.matches("[0-9]+ (error|warning)s?"));
                            log.removeIf(line -> line.contains("compiler.err.warnings.and.werror"));
                            log.removeIf(line -> line.matches("- compiler\\.note\\..*"));   // mandatory warning "recompile" etc.

                            // Verify expected warning output
                            boolean foundSuppressionWarning = log.removeIf(
                              line -> line.contains("compiler.warn.unnecessary.warning.suppression"));
                            boolean foundSuppressionOptionWarning = log.removeIf(
                              line -> line.contains("compiler.warn.unnecessary.lint.warning.suppression"));
                            boolean foundCategoryWarning = log.removeIf(
                              line -> line.contains(categoryWarning));

                            // Check expectations
                            if (foundCategoryWarning != expectCategoryWarning) {
                                throw new AssertionError(String.format(
                                  "%s: category warning: found=%s but expected=%s",
                                  description, foundCategoryWarning, expectCategoryWarning));
                            }
                            if (foundSuppressionWarning != expectSuppressionWarning) {
                                throw new AssertionError(String.format(
                                  "%s: suppression warning: found=%s but expected=%s",
                                  description, foundSuppressionWarning, expectSuppressionWarning));
                            }
                            if (foundSuppressionOptionWarning != expectSuppressionOptionWarning) {
                                throw new AssertionError(String.format(
                                  "%s: suppression-option warning: found=%s but expected=%s",
                                  description, foundSuppressionOptionWarning, expectSuppressionOptionWarning));
                            }

                            // There shouldn't be any other warnings
                            if (!log.isEmpty()) {
                                throw new AssertionError(String.format(
                                  "%s: %d unexpected warning(s): %s", description, log.size(), log));
                            }

                            // Done
                            out.println(String.format("<<< Test PASSED: %s", description));
                        }
                    }
                }
            }
        }
    }

    // Test a @SuppressWarning annotation that suppresses nothing
    @Test
    public void testUselessAnnotation(LintCategory category) throws Exception {
        compileAndExpectWarning(
          String.format("-Xlint:%s", SUPPRESSION.option),
          String.format("@SuppressWarnings(\"%s\") public class Test { }", category.option),
          "compiler.warn.unnecessary.warning.suppression");
    }

    // Test a -Xlint:-foo flag that suppresses no nothing
    @Test
    public void testUselessLintFlag(LintCategory category) throws Exception {
        compileAndExpectWarning(
          String.format("-Xlint:%s,%s,-%s", OPTIONS.option, SUPPRESSION_OPTION.option, category.option),
          "public class Test { }",
          "compiler.warn.unnecessary.lint.warning.suppression");
    }

    // Test the suppression of SUPPRESSION itself, which should always work,
    // even when the same annotation uselessly suppresses some other category.
    @Test
    public void testSelfSuppression(LintCategory otherCategory) throws Exception {
        compileAndExpectSuccess(
          String.format("-Xlint:%s", SUPPRESSION.option),
          String.format("@SuppressWarnings({ \"%s\", \"%s\" }) public class Test { }",
            otherCategory.option,   // this is actually a useless suppression
            SUPPRESSION.option));   // but this prevents us from reporting it
    }

    public void compileAndExpectWarning(String lintOption, String source, String errorKey) throws Exception {

        // Setup source & destination diretories
        Path base = Paths.get("compileAndExpectWarning");
        Path sourceDir = base.resolve("sources");
        Path classDir = base.resolve("classes");
        Files.createDirectories(sourceDir);
        Files.createDirectories(classDir);

        // Write source file
        tb.writeJavaFiles(sourceDir, source);

        // Compile sources and verify we got the warning
        List<String> log = compile(sourceDir, classDir, lintOption, Task.Expect.FAIL);
        if (log.stream().noneMatch(line -> line.contains(errorKey))) {
            throw new AssertionError(String.format(
              "did not find \"%s\" in log output:%n  %s",
              errorKey, log.stream().collect(Collectors.joining("\n  "))));
        }
    }

    public void compileAndExpectSuccess(String lintOption, String source) throws Exception {

        // Setup source & destination diretories
        Path base = Paths.get("compileAndExpectSuccess");
        Path sourceDir = base.resolve("sources");
        Path classDir = base.resolve("classes");
        Files.createDirectories(sourceDir);
        Files.createDirectories(classDir);

        // Write source file
        tb.writeJavaFiles(sourceDir, source);

        // Compile sources and verify there is no log output
        List<String> log = compile(sourceDir, classDir, lintOption, Task.Expect.SUCCESS);
        if (!log.isEmpty()) {
            throw new AssertionError(String.format(
              "non-empty log output:%n  %s", log.stream().collect(Collectors.joining("\n  "))));
        }
    }

    private List<String> compile(Path sourceDir, Path classDir, String lintOption, Task.Expect expectation) throws Exception {
        ArrayList<String> options = new ArrayList<>();
        options.add("-XDrawDiagnostics");
        options.add("-Werror");
        if (lintOption != null)
            options.add(lintOption);
        List<String> log;
        try {
            log = new JavacTask(tb, Mode.CMDLINE)
              .options(options.toArray(new String[0]))
              .outdir(classDir)
              .files(tb.findJavaFiles(sourceDir))
              .run(expectation)
              .writeAll()
              .getOutputLines(Task.OutputKind.DIRECT);
        } catch (Task.TaskError e) {
            throw new AssertionError(String.format("compile in %s failed: %s", sourceDir, e.getMessage()), e);
        }
        log.removeIf(line -> line.trim().isEmpty());
        return log;
    }
}
