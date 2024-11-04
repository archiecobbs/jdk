/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
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

package toolbox;

import java.io.PrintStream;
import java.lang.annotation.Annotation;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Utility class to manage and execute sub-tests within a test.
 *
 * This class does the following:
 * <ul>
 * <li>  invokes those test methods annotated with @Test
 * <li>  keeps track of successful and failed tests
 * <li>  throws an Exception if any test fails.
 * <li>  provides a test summary at the end of the run.
 * </ul>

 * Tests must extend this class, annotate the test methods
 * with @Test and call one of the runTests method.
 */
public abstract class TestRunner {
   /** Marker annotation for test cases. */
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Test { }

    int testCount = 0;
    int errorCount = 0;

    public String testName = null;

    protected PrintStream out;

    /**
     * Constructs the Object.
     * @param out the PrintStream to print output to.
     */
    public TestRunner(PrintStream out) {
        this.out = out;
    }

    /**
     * Invoke all methods annotated with @Test.
     * @throws java.lang.Exception if any errors occur
     */
    protected void runTests() throws Exception {
        runTests(f -> new Object[0]);
    }

    /**
     * Invoke all methods annotated with @Test one time each.
     * @param f a lambda expression to specify arguments for the test method
     * @throws java.lang.Exception if any errors occur
     */
    protected void runTests(Function<Method, Object[]> f) throws Exception {
        runTestsMulti(f.andThen(Stream::<Object[]>of));
    }

    /**
     * Invoke all methods annotated with @Test once for each set of parameters in the given iteration.
     * @param f function returning an iteration of test method parameter sets
     * @throws java.lang.Exception if any errors occur
     */
    protected void runTestsMulti(Function<Method, ? extends Stream<? extends Object[]>> f) throws Exception {
        String testQuery = System.getProperty("test.query");
        for (Method m : getClass().getDeclaredMethods()) {
            Annotation a = m.getAnnotation(Test.class);
            if (a != null) {
                testName = m.getName();
                if (testQuery == null || testQuery.equals(testName)) {
                    AtomicInteger iteration = new AtomicInteger();
                    for (Iterator<? extends Object[]> i = f.apply(m).iterator(); i.hasNext(); ) {
                        Object[] params = i.next();
                        try {
                            testCount++;
                            out.println(String.format("test: %s#%d", testName, iteration.incrementAndGet()));
                            m.invoke(this, params);
                        } catch (InvocationTargetException e) {
                            errorCount++;
                            Throwable cause = e.getCause();
                            String paramsDesc = Stream.of(params)
                              .map(String::valueOf)
                              .collect(Collectors.joining(", "));
                            out.println(String.format(
                                "Exception running test %s(%s): %s", testName, paramsDesc, e.getCause()));
                            cause.printStackTrace(out);
                        }
                        out.println();
                    }
                }
            }
        }

        if (testCount == 0) {
            throw new Error("no tests found");
        }

        StringBuilder summary = new StringBuilder();
        if (testCount != 1) {
            summary.append(testCount).append(" tests");
        }
        if (errorCount > 0) {
            if (summary.length() > 0) {
                summary.append(", ");
            }
            summary.append(errorCount).append(" errors");
        }
        out.println(summary);
        if (errorCount > 0) {
            throw new Exception(errorCount + " errors found");
        }
    }

    protected void error(String message) {
        out.println("Error: " + message);
        errorCount++;
    }
}
