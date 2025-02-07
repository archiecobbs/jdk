/*
 * Copyright (c) 2005, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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

package com.sun.tools.javac.code;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import javax.tools.JavaFileObject;

import com.sun.tools.javac.code.Source.Feature;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.main.Option;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.Assert;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JCDiagnostic;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import com.sun.tools.javac.util.JCDiagnostic.LintWarning;
import com.sun.tools.javac.util.JCDiagnostic.SimpleDiagnosticPosition;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Position;
import com.sun.tools.javac.util.Names;
import com.sun.tools.javac.util.Options;

/**
 * An API for reporting {@link LintWarning}s within the compiler.
 *
 * <p>
 * Lint warnings in all {@link Lint.LintCategory}s are subject to suppression via the {@link -Xlint:key}
 * command line flag, and warnings in {@linkplain Lint.LintCategory#isSpecific specific categories}
 * (i.e., occurring at specific source file locations) are also suppressable via {@code @SuppressWarnings}.
 * Lint warnings are reported through this interface to help ensure this suppression is handled properly.
 *
 * <p>
 * Lint warnings are not actually generated until the compiler's {@code warn()} phase. Therefore, warnings
 * and analysis callbacks passed through this API may or may not execute synchronously. It is an error
 * to report warnings after the {@code warn()} phase.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class Lint {

    /** The context key for the root Lint object. */
    protected static final Context.Key<Lint> lintKey = new Context.Key<>();

    /** Get the Lint singleton. */
    public static Lint instance(Context context) {
        Lint instance = context.get(lintKey);
        if (instance == null)
            instance = new Lint(context);
        return instance;
    }

// Config

    /**
     * A class representing a specific combination of enabled or suppressed {@link LintCategory}s.
     *
     * <p>
     * A {@link LintCategory} may be enabled, suppressed, or neither, but never both.
     *
     * <p>
     * Instances are immutable. New instances may be created by using the methods {@link #enable},
     * {@link #suppress}, and {@link #augment}. The "root" instance is automatically configured based
     * on {@code -Xlint} flags and available via {@link Lint#getRootConfig}.
     */
    public class Config {

        private final EnumSet<LintCategory> enabled;
        private final EnumSet<LintCategory> suppressed;
        private final String description;

        Config(EnumSet<LintCategory> enabled, EnumSet<LintCategory> suppressed, String description) {
            this.enabled = enabled;
            this.suppressed = suppressed;
            this.description = description;
        }

        private Config copy(String descriptionSuffix) {
            return new Config(EnumSet.copyOf(enabled), EnumSet.copyOf(suppressed), description + descriptionSuffix);
        }

        /**
         * Determine if the given category is enabled.
         *
         * @param category lint category
         * @return true if category is enabled (and therefore not suppressed), otherwise false
         */
        public boolean isEnabled(LintCategory category) {
            return enabled.contains(category);
        }

        /**
         * Determine if the given category is suppressed.
         *
         * @param category lint category
         * @return true if category is suppressed (and therefore not enabled), otherwise false
         */
        public boolean isSuppressed(LintCategory category) {
            return suppressed.contains(category);
        }

        /**
         * Create an instance with additional warning supression applied from any
         * {@code @SuppressWarnings} and/or {@code @Deprecated} annotations on the given symbol.
         *
         * <p>
         * The returned instance will be different from this instance if and only if
         * {@link #suppressionsFrom} returns a non-empty set.
         *
         * @param sym symbol
         * @return lint instance with new warning suppressions applied, or this instance if none
         */
        public Config augment(Symbol sym) {
            EnumSet<LintCategory> suppressions = suppressionsFrom(sym);
            if (!suppressions.isEmpty()) {
                Config config = copy("+" + sym);
                config.enabled.removeAll(suppressions);
                config.suppressed.addAll(suppressions);
                return config;
            }
            return this;
        }

        /**
         * Returns a new instance equivalent to this instance but
         * with the given {@code LintCategory}s enabled.
         *
         * @param category one or more categories to be enabled
         */
        public Config enable(LintCategory... categories) {
            List<LintCategory> categoryList = Arrays.asList(categories);
            Config config = copy("+" + categoryList);
            config.enabled.addAll(categoryList);
            config.suppressed.removeAll(categoryList);
            return config;
        }

        /**
         * Returns a new instance equivalent to this instance but
         * with the given {@code LintCategory}s suppressed.
         *
         * @param categories one or more categories to be suppressed
         */
        public Config suppress(LintCategory... categories) {
            List<LintCategory> categoryList = Arrays.asList(categories);
            Config config = copy("-" + categoryList);
            config.enabled.removeAll(categoryList);
            config.suppressed.addAll(categoryList);
            return config;
        }

        public String getDescription() {
            return this.description;
        }

        @Override
        public String toString() {
            return "Lint.Config[" + description + ",enable" + enabled + ",suppress" + suppressed + "]";
        }
    }

// Config Access

    /**
     * Get the root {@link Config}.
     *
     * <p>
     * The root configuration consists of the categories that are enabled by default
     * plus adjustments due to {@code -Xlint} command line flags.
     *
     * @return root lint configuration
     */
    public Config getRootConfig() {
        Assert.check(rootConfig != null);
        return rootConfig;
    }

    /**
     * Get the {@link Config} corresponding to the specified position in the source file
     * associated with the currently executing analysis.
     *
     * <p>
     * The current thread must be executing an analysis.
     *
     * @param pos source position at which to query the lint configuration
     * @return current analysis configuration
     * @throws AssertionError if the current thread is not executing an analysis
     */
    public Config configAt(int pos) {
        return currentAnalysis().configCalculator().configAt(pos);
    }

    /**
     * Get the {@link Config} corresponding to the specified position in the source file
     * associated with the currently executing analysis.
     *
     * <p>
     * The current thread must be executing an analysis.
     *
     * @param pos source position at which to query the lint configuration
     * @return current analysis configuration
     * @throws AssertionError if the current thread is not executing an analysis
     */
    public Config configAt(DiagnosticPosition pos) {
        return configAt(unwrap(pos));
    }

    /**
     * Modify the {@link Config} associated with the currently executing analysis
     * by "patching" a range of source file positions with a modified {@link Config}.
     *
     * <p>
     * Use {@link #restoreConfig} to un-do the most recent modification.
     *
     * <p>
     * The current thread must be executing an analysis.
     *
     * @param minPos minimum source file position (inclusive)
     * @param maxPos maximum source file position (inclusive)
     * @param modifier modifies the config in the range
     * @throws AssertionError if the current thread is not executing an analysis
     */
    public void modifyConfig(int minPos, int maxPos, UnaryOperator<Config> modifier) {
        currentAnalysis().configCalculator().push(minPos, maxPos, modifier);
    }

    /**
     * Modify the {@link Config} associated with the currently executing analysis
     * by "patching" a range of source file positions with a modified {@link Config}.
     *
     * <p>
     * Use {@link #restoreConfig} to un-do the most recent modification.
     *
     * <p>
     * The current thread must be executing an analysis.
     *
     * @param tree tree node corresponding to the range of positions to patch
     * @param modifier modifies the config in the range
     * @throws AssertionError if the current thread is not executing an analysis
     */
    public void modifyConfig(JCTree tree, UnaryOperator<Config> modifier) {
        int minPos = TreeInfo.getStartPos(tree);
        int maxPos = Math.max(TreeInfo.endPos(tree), minPos);   // avoid inverted ranges
        modifyConfig(minPos, maxPos, modifier);
    }

    /**
     * Restore the configuration in effect prior to the most recent modification by {@link #modifyConfig}.
     *
     * <p>
     * The current thread must be executing an analysis.
     *
     * @param modifier modifies the current config
     * @throws AssertionError if the current thread is not executing an analysis
     */
    public void restoreConfig() {
        currentAnalysis().configCalculator().pop();
    }

// Non-Specific Warnings

    /**
     * Log a warning in a {@linkplain LintCategory#isSpecific non-specific} lint category
     * if the category is enabled in the root lint config.
     *
     * @param pos source position at which to report the warning
     * @param warning key for the localized warning message; must be non-specific
     */
    public void logIfEnabled(LintWarning warning) {
        if (log.wouldDiscard(null, warning))
            return;
        ifEnabled(warning.getLintCategory(), () -> warningList.add(new Warning(warning)));
    }

    /**
     * Log a warning in a {@linkplain LintCategory#isSpecific non-specific} lint category
     * if the category is not suppressed in the root lint config.
     *
     * @param pos source position at which to report the warning
     * @param warning key for the localized warning message; must be non-specific
     */
    public void logIfNotSuppressed(LintWarning warning) {
        if (log.wouldDiscard(null, warning))
            return;
        ifNotSuppressed(warning.getLintCategory(), () -> warningList.add(new Warning(warning)));
    }

    /**
     * Perform an analysis that generates warning(s) in a {@linkplain LintCategory#isSpecific non-specific}
     * lint category if the category is enabled in the root config.
     *
     * <p>
     * <b>All warnings generated by {@code analyzer} must be in the given {@code category}</b>.
     *
     * <p>
     * If {@code category} is not enabled in the root config, then {@code analyzer} will not be invoked.
     * In the case of a non-trivial warning analysis, this method can be used to avoid unnecessary work.
     *
     * @param category category for generated warnings; must be non-specific
     * @param pos analysis starting source file position
     * @param analyzer callback for doing the analysis
     */
    public void ifEnabled(LintCategory category, Runnable analyzer) {
        analyze(category, true, true, () -> {
            if (configAt(Position.NOPOS).isEnabled(category)) {
                analyzer.run();
            }
        });
    }

    /**
     * Perform an analysis that generates warning(s) in a {@linkplain LintCategory#isSpecific non-specific}
     * lint category if the category is not suppressed in the root config.
     *
     * <p>
     * <b>All warnings generated by {@code analyzer} must be in the given {@code category}</b>.
     *
     * <p>
     * If {@code category} is suppressed in the root config, then {@code analyzer} will not be invoked.
     * In the case of a non-trivial warning analysis, this method can be used to avoid unnecessary work.
     *
     * @param category category for generated warnings; must be non-specific
     * @param pos analysis starting source file position
     * @param analyzer callback for doing the analysis
     */
    public void ifNotSuppressed(LintCategory category, Runnable analyzer) {
        analyze(category, true, true, () -> {
            if (!configAt(Position.NOPOS).isSuppressed(category)) {
                analyzer.run();
            }
        });
    }

// Specific Warnings

    /**
     * Log a warning in a {@linkplain LintCategory#isSpecific specific} lint category
     * if the category is enabled at the given source file position.
     *
     * @param pos position in the current source file at which to report the warning
     * @param warning key for the localized warning message; category must be specific
     */
    public void logIfEnabled(int pos, LintWarning warning) {
        logIfEnabled(wrap(pos), warning);
    }

    /**
     * Log a warning in a {@linkplain LintCategory#isSpecific specific} lint category
     * if the category is enabled at the given source file position.
     *
     * @param pos current source file position at which to report the warning, or null if unspecified
     * @param warning key for the localized warning message; category must be specific
     */
    public void logIfEnabled(DiagnosticPosition pos, LintWarning warning) {
        if (log.wouldDiscard(pos, warning))
            return;
        LintCategory category = warning.getLintCategory();

/*
System.out.println("logIfEnabled():"
+"\n  category="+category
+"\n  pos=["+pos+"]"
+"\n  warning="+warning
);
*/

        analyze(category, true, true, () -> {
            if (configAt(pos).isEnabled(category)) {
                warningList.add(new Warning(pos, warning));
            }
        });
    }

    /**
     * Log a warning in a {@linkplain LintCategory#isSpecific specific} lint category
     * if the category is not suppressed at the given source file position.
     *
     * @param pos current source file position at which to report the warning
     * @param warning key for the localized warning message; category must be specific
     */
    public void logIfNotSuppressed(int pos, LintWarning warning) {
        logIfNotSuppressed(wrap(pos), warning);
    }

    /**
     * Log a warning in a {@linkplain LintCategory#isSpecific specific} lint category
     * if the category is not suppressed at the given source file position.
     *
     * @param pos current source file position at which to report the warning, or null if unspecified
     * @param warning key for the localized warning message; category must be specific
     */
    public void logIfNotSuppressed(DiagnosticPosition pos, LintWarning warning) {
        if (log.wouldDiscard(pos, warning))
            return;
        LintCategory category = warning.getLintCategory();
        analyze(category, true, true, () -> {
            if (!configAt(pos).isSuppressed(category)) {
                warningList.add(new Warning(pos, warning));
            }
        });
    }

    /**
     * Perform an analysis that generates warning(s) in a {@linkplain LintCategory#isSpecific specific}
     * lint category if the category is enabled at the given source file position.
     *
     * <p>
     * <b>All warnings generated by {@code analyzer} must be in the given {@code category}</b>.
     *
     * <p>
     * If {@code category} is not enabled at {@code pos}, then {@code analyzer} will not be invoked.
     * In the case of a non-trivial warning analysis, this method can be used to avoid unnecessary work.
     *
     * @param category category for generated warnings; must be specific
     * @param pos analysis starting source file position
     * @param analyzer callback for doing the analysis
     */
    public void ifEnabled(LintCategory category, int pos, Runnable analyzer) {
        ifEnabled(category, wrap(pos), analyzer);
    }

    /**
     * Perform an analysis that generates warning(s) in a {@linkplain LintCategory#isSpecific specific}
     * lint category if the category is enabled at the given source file position.
     *
     * <p>
     * <b>All warnings generated by {@code analyzer} must be in the given {@code category}</b>.
     *
     * <p>
     * If {@code category} is not enabled at {@code pos}, then {@code analyzer} will not be invoked.
     * In the case of a non-trivial warning analysis, this method can be used to avoid unnecessary work.
     *
     * @param category category for generated warnings; must be specific
     * @param pos analysis starting source file position
     * @param analyzer callback for doing the analysis
     */
    public void ifEnabled(LintCategory category, DiagnosticPosition pos, Runnable analyzer) {
        analyze(category, true, false, () -> {
            if (configAt(pos).isEnabled(category)) {
                analyzer.run();
            }
        });
    }

    /**
     * Perform an analysis that generates warning(s) in a {@linkplain LintCategory#isSpecific specific}
     * lint category if the category is not suppressed at the given source file position.
     *
     * <p>
     * <b>All warnings generated by {@code analyzer} must be in the given {@code category}</b>.
     *
     * <p>
     * If {@code category} is suppressed at {@code pos}, then {@code analyzer} will not be invoked.
     * In the case of a non-trivial warning analysis, this method can be used to avoid unnecessary work.
     *
     * @param category category for generated warnings; must be specific
     * @param pos analysis starting source file position
     * @param analyzer callback for doing the analysis
     */
    public void ifNotSuppressed(LintCategory category, int pos, Runnable analyzer) {
        ifNotSuppressed(category, wrap(pos), analyzer);
    }

    /**
     * Perform an analysis that generates warning(s) in a {@linkplain LintCategory#isSpecific specific}
     * lint category if the category is not suppressed at the given source file position.
     *
     * <p>
     * <b>All warnings generated by {@code analyzer} must be in the given {@code category}</b>.
     *
     * <p>
     * If {@code category} is suppressed at {@code pos}, then {@code analyzer} will not be invoked.
     * In the case of a non-trivial warning analysis, this method can be used to avoid unnecessary work.
     *
     * @param category category for generated warnings; must be specific
     * @param pos analysis starting source file position
     * @param analyzer callback for doing the analysis
     */
    public void ifNotSuppressed(LintCategory category, DiagnosticPosition pos, Runnable analyzer) {
        analyze(category, true, false, () -> {
            if (!configAt(pos).isSuppressed(category)) {
                analyzer.run();
            }
        });
    }

    /**
     * Perform an analysis using an initial {@link Config} appropriate for the given source file position.
     *
     * <p>
     * <b>All warnings generated by {@code analyzer} must be in the given {@code category} (if not null)</b>.
     *
     * @param category category for generated warnings (must be specific) or null for none
     * @param analyzer callback for doing the analysis
     */
    public void analyze(LintCategory category, Runnable analyzer) {
        analyze(category, false, false, analyzer);
    }

    /**
     * Handle a new analysis request.
     *
     * <p>
     * For non-specific lint categories, and for {@code logIfXxx()} specific lint category requests,
     * we always execute the analysis synchonously. Otherwise, we add the analysis to the queue for
     * the current source file. Reentrant requests must have a consistent {@code category}.
     *
     * @param category the expected lint category for reported warnings, or null for not restriction
     * @param requireCategory true if {@code category} should not be null
     * @param reentrantOK if analysis already running, execute {@code analyzer} instead of enqueuing it
     * @param analyzer the analysis to run
     */
    private void analyze(LintCategory category, boolean requireCategory, boolean reentrantOK, Runnable analyzer) {

        // Sanity check analysis category and compare to the current analysis (if any)
        if (category == null) {
            Assert.check(!requireCategory);
        } else {
            Assert.check(currentAnalysis == null || currentAnalysis.category() == category);
        }

        // Create the analysis
        Analysis analysis = new Analysis(category, new ConfigCalculator(), analyzer);

        // Analyses with "reentrantOK" can execute immediately within an already-running analysis
        if (currentAnalysis != null && reentrantOK) {
            execute(analysis);
            return;
        }

        // Get the current source file (specific lint categories only)
        JavaFileObject source = category.isSpecific() ? log.currentSourceFile() : null;

        // If there is no current source file with a specific lint category, we're in a weird
        // situation where warnings are being ignored anyway (e.g., doclint running) so bail out.
        if (source == null && category.isSpecific())
            return;

        // Enqueue the analysis
        analysisMap.computeIfAbsent(source, s -> new SourceInfo()).getAnalyses().addLast(analysis);
    }

    // Execute an analysis
    private void execute(Analysis analysis) {
        Analysis previousAnalysis = currentAnalysis;
        currentAnalysis = analysis;
        try {
            analysis.task().run();
        } finally {
            currentAnalysis = previousAnalysis;
        }
    }

    /**
     * Execute all enqueued analyses for the given compilation unit and emit any resulting warnings.
     *
     * @param tree source file to analyze, or null to execute non-specific analyses
     */
    public void analyzeAndEmitWarnings(Env<AttrContext> env) {

        // Apply sanity checks
        JavaFileObject source = env != null ? env.toplevel.sourcefile : null;
        Assert.check(Objects.equals(source, log.currentSourceFile()));
        Assert.check(currentAnalysis == null, "reentrant invocation");

boolean debug = source != null && source.toString().contains("/java/util/ImmutableCollections.java");

        // Find the analysis queue for the source file, if any
        SourceInfo sourceInfo = analysisMap.get(source);
        if (sourceInfo == null) {
            return;
        }

if (debug) {
System.out.println("ANALYZING: " + source + " with " + (int)env.toplevel.defs.stream().filter(JCClassDecl.class::isInstance).count() + " class decl's");
}

        // Scan this top-level class, but stop here if any others remain
        if (env != null && !sourceInfo.scanClass(env.toplevel, env.enclClass)) {

if (debug) {
System.out.println(" -> NOT DONE");
}

            return;
        }
        final Deque<Analysis> analyses = sourceInfo.getAnalyses();

if (debug) {
System.out.println(" -> DONE");
}

        // Execute the analyses for the source file and collect any generated warnings
        try {
            warningList.clear();
            while (!analyses.isEmpty()) {
                Analysis analysis = analyses.peekFirst();
                analysis.configCalculator().copyFrom(sourceInfo.getConfigCalculator());
                try {
                    execute(analysis);
                } finally {
                    analyses.removeFirst();
                }
            }
        } finally {
System.out.println("ANALYZED " + source);
if (source == null)
    new Throwable("HERE").printStackTrace(System.out);
            analysisMap.remove(source);
        }

        // Sort and emit the generated warnings
        warningList.sort(Comparator.comparingInt(Warning::sortKey));
        warningList.forEach(warning -> warning.warn(log));
        warningList.clear();
    }

// SourceInfo

    private class SourceInfo {

        private final ConfigCalculator configCalculator = new ConfigCalculator();
        private final Deque<Analysis> analyses = new ArrayDeque<>();

        private int remainingClassDefs = -1;

        ConfigCalculator getConfigCalculator() {
            return configCalculator;
        }

        Deque<Analysis> getAnalyses() {
            return analyses;
        }

        boolean scanClass(JCCompilationUnit top, JCClassDecl decl) {

            // First time, scan from the top down to top level classes but no further
            if (remainingClassDefs == -1) {
                remainingClassDefs = (int)top.defs.stream().filter(JCClassDecl.class::isInstance).count();
                configCalculator.process(top, true);
            }

            // Scan the specified top level class
            configCalculator.process(decl, false);
            return --remainingClassDefs == 0;
        }
    }

    // Get the current analysis (it must exist)
    private Analysis currentAnalysis() {
        Assert.check(currentAnalysis != null, "there is no current lint analysis executing");
        return currentAnalysis;
    }

    static DiagnosticPosition wrap(int pos) {
        return pos != Position.NOPOS ? new SimpleDiagnosticPosition(pos) : null;
    }

    static int unwrap(DiagnosticPosition pos) {
        return Optional.ofNullable(pos)
          .map(DiagnosticPosition::getPreferredPosition)
          .orElse(Position.NOPOS);
    }

// Analysis

    private record Analysis(LintCategory category, ConfigCalculator configCalculator, Runnable task) { }

// Warning

    private record Warning(boolean specific, DiagnosticPosition pos, LintWarning warning) {

        // For non-specific lint categories
        Warning(LintWarning warning) {
            this(false, null, warning);
            Assert.check(warning.getLintCategory().isSpecific());
        }

        // For specific lint categories
        Warning(DiagnosticPosition pos, LintWarning warning) {
            this(true, pos, warning);
            Assert.check(warning.getLintCategory().isSpecific());
        }

        void warn(Log log) {
            if (specific())
                log.warning(pos(), warning());
            else
                log.warning(warning());
        }

        int sortKey() {
            return specific() ? unwrap(pos()) : Integer.MAX_VALUE;
        }
    }

// ConfigCalculator

    /**
     * Scans a source file and calculates the lint configuration that applies at each character offset.
     *
     * <p>
     * Also supports temporary "patches".
     */
    private class ConfigCalculator extends TreeScanner {

        // Config ranges and any "patches" applied
        private final List<Range> ranges = new ArrayList<>();
        private final List<Patch> patches = new ArrayList<>();

        private boolean stopAtClassDecl;

        // Use during scanning
        private Range parentRange;

private boolean debug;

        ConfigCalculator() {
            ranges.add(new Range(Integer.MIN_VALUE, Integer.MAX_VALUE, getRootConfig()));
        }

        void copyFrom(ConfigCalculator that) {
            this.ranges.clear();
            this.ranges.addAll(that.ranges);
            this.patches.clear();
            this.patches.addAll(that.patches);
        }

        void process(JCTree tree, boolean stopAtClassDecl) {
            this.stopAtClassDecl = stopAtClassDecl;

if (tree instanceof JCCompilationUnit cu) {
this.debug = cu.sourcefile != null && cu.sourcefile.toString().contains("/java/util/ImmutableCollections.java");
}

            // Scan file to generate config ranges within tree
            parentRange = ranges.get(0);
            try {
                scan(tree);
            } finally {
                parentRange = null;
            }

if (debug) {
String s = tree.toString();
s = s.substring(0, Math.min(200, s.length()));
s = s.replaceAll("\\s+", " ");
System.out.println("process("+(stopAtClassDecl?"TOP":((JCClassDecl)tree).name)+"):"
+"\n  tree="+s
+"\n  ranges:"+ranges.stream().map(Range::toString).collect(java.util.stream.Collectors.joining("\n    "))
);
}

        }

        /**
         * Obtain the {@link Config} that applies at the given position.
         *
         * @param pos character offset into source file
         * @return the lint configuration in effect at that position
         */
        Config configAt(int pos) {

            // Find the smallest range containing pos
            Range range = null;
            for (Range candidate : ranges) {
                if (candidate.contains(pos) && (range == null || candidate.size() < range.size())) {
                    range = candidate;
                }
            }
            Assert.check(range != null);

if (debug && pos >= 0xdc40 && pos < 0xdc60) {
System.out.println("configAt():"
+"\n  pos="+String.format("0x%08x", pos)
+"\n  range="+range
);
}

            // Apply any applicable patches
            Config config = range.config();
            for (Patch patch : patches) {
                if (patch.contains(pos)) {
                    config = patch.mods().apply(config);
                }
            }

            // Done
            return config;
        }

    // Patching

        /**
         * Add a patch to this instance.
         */
        void push(int minPos, int maxPos, UnaryOperator<Config> mods) {
            patches.add(new Patch(minPos, maxPos, mods));
        }

        /**
         * Remove the previously added patch from this instance.
         */
        void pop() {
            Assert.check(!patches.isEmpty(), "too many pops");
            patches.remove(patches.size() - 1);
        }

    // TreeScanner methods

        @Override
        public void visitModuleDef(JCModuleDecl decl) {
            visitDeclaration(decl, decl.sym, super::visitModuleDef);
        }

        @Override
        public void visitPackageDef(JCPackageDecl decl) {
            visitDeclaration(decl, decl.packge, super::visitPackageDef);
        }

        @Override
        public void visitClassDef(JCClassDecl decl) {
            if (stopAtClassDecl)
                return;
            visitDeclaration(decl, decl.sym, super::visitClassDef);
        }

        @Override
        public void visitMethodDef(JCMethodDecl decl) {
            visitDeclaration(decl, decl.sym, super::visitMethodDef);
        }

        @Override
        public void visitVarDef(JCVariableDecl decl) {
            visitDeclaration(decl, decl.sym, super::visitVarDef);
        }

        private <T extends JCTree> void visitDeclaration(T decl, Symbol sym, Consumer<? super T> recursion) {

if (debug) {
String s = decl.toString();
s = s.substring(0, Math.min(200, s.length()));
s = s.replaceAll("\\s+", " ");
System.out.println("visitDeclaration():"
+"\n  decl=["+s+"]"+(decl instanceof JCClassDecl ? " (" + ((JCClassDecl)decl).defs.size() + " defs)" : "")
+"\n  sym="+sym
+"\n  range="+String.format("[0x%08x,0x%08x]", TreeInfo.getStartPos(decl), TreeInfo.endPos(decl))
);
}

            visitTree(decl, config -> config.augment(sym), recursion);  // apply @SuppressWarnings and @Deprecated
        }

        @Override
        public void visitImport(JCImport tree) {
            initializeSymbolsIfNeeded();

            // Proceed normally unless special import suppression logic applies
            JCFieldAccess imp = tree.qualid;
            Name name = TreeInfo.name(imp);
            if (Feature.DEPRECATION_ON_IMPORT.allowedInSource(source)
              || name == names.asterisk
              || tree.staticImport) {
                super.visitImport(tree);
                return;
            }

            // Apply some automatic suppression here where we're only importing (and not using) the symbol
            visitTree(tree,
              config -> config.suppress(LintCategory.DEPRECATION, LintCategory.REMOVAL, LintCategory.PREVIEW),
              super::visitImport);
        }

        private <T extends JCTree> void visitTree(T tree, UnaryOperator<Config> mods, Consumer<? super T> recursion) {

            // Determine the lexical extent of the given tree node
            int minPos = TreeInfo.getStartPos(tree);
            int maxPos = Math.max(TreeInfo.endPos(tree), minPos);   // avoid inverted ranges

            // Update the current config
            Config oldConfig = parentRange.config();
            Config newConfig = mods.apply(oldConfig);

            // Add a new range, but not if we can determine its not needed
            Range childRange = new Range(minPos, maxPos, newConfig);
            if (!newConfig.equals(oldConfig) || !parentRange.contains(childRange)) {
                ranges.add(childRange);
            }

            // Recurse
            Range prevParentRange = parentRange;
            parentRange = childRange;
            try {
                recursion.accept(tree);
            } finally {
                parentRange = prevParentRange;
            }
        }

        // Represents a range of character offsets and the corresponding lint config that applies there.
        // The end of each range is inferred from the minPos() of the next range in the list.
        private record Range(int minPos, int maxPos, Config config) {

            long size() {
                return (long)maxPos() - (long)minPos() + 1;
            }

            boolean contains(int pos) {
                return pos >= minPos() && pos <= maxPos();
            }

            boolean contains(Range that) {
                return that.minPos() >= minPos() && that.maxPos() <= maxPos();
            }

@Override
public String toString() {
    return String.format("Range[0x%08x-0x%08x|%s]", minPos(), maxPos(), config().getDescription());
}

        }

        // Represents a "patch" to this instance for the given range
        private record Patch(int minPos, int maxPos, UnaryOperator<Config> mods) {

            boolean contains(int pos) {
                return pos >= minPos() && pos <= maxPos();
            }

            boolean contains(Range that) {
                return that.minPos() >= minPos() && that.maxPos() <= maxPos();
            }
        }
    }

// Internal State

    private final Context context;
    private final Source source;
    private final Log log;

    // These are initialized lazily to avoid dependency loops
    private Symtab syms;
    private Names names;

    // The root configuration
    private Config rootConfig;

    // The analysis currently executing (null if none)
    private Analysis currentAnalysis;

    // Warnings are collected here during analyses
    private final List<Warning> warningList = new ArrayList<>();

    // Maps source file to pending analyses, if any
    private final Map<JavaFileObject, SourceInfo> analysisMap = new HashMap<>();

    // Maps category name to category
    private static final Map<String, LintCategory> map = new ConcurrentHashMap<>(20);

    @SuppressWarnings("this-escape")
    protected Lint(Context context) {
        this.context = context;
        context.put(lintKey, this);
        log = Log.instance(context);
        source = Source.instance(context);
        Options.instance(context).whenReady(this::initializeRootConfig);
    }

    // Process command line options on demand to allow use of root Lint early during startup
    private void initializeRootConfig(Options options) {

        // Initialize enabled categories based on "-Xlint" flags
        EnumSet<LintCategory> values;
        if (options.isSet(Option.XLINT) || options.isSet(Option.XLINT_CUSTOM, "all")) {
            // If -Xlint or -Xlint:all is given, enable all categories by default
            values = EnumSet.allOf(LintCategory.class);
        } else if (options.isSet(Option.XLINT_CUSTOM, "none")) {
            // if -Xlint:none is given, disable all categories by default
            values = LintCategory.newEmptySet();
        } else {
            // otherwise, enable on-by-default categories
            values = LintCategory.newEmptySet();

            if (source.compareTo(Source.JDK9) >= 0) {
                values.add(LintCategory.DEP_ANN);
            }
            if (Source.Feature.REDUNDANT_STRICTFP.allowedInSource(source)) {
                values.add(LintCategory.STRICTFP);
            }
            values.add(LintCategory.REQUIRES_TRANSITIVE_AUTOMATIC);
            values.add(LintCategory.OPENS);
            values.add(LintCategory.MODULE);
            values.add(LintCategory.REMOVAL);
            if (!options.isSet(Option.PREVIEW)) {
                values.add(LintCategory.PREVIEW);
            }
            values.add(LintCategory.SYNCHRONIZATION);
            values.add(LintCategory.INCUBATING);
        }

        // Look for specific overrides
        for (LintCategory lc : LintCategory.values()) {
            if (options.isSet(Option.XLINT_CUSTOM, lc.option)) {
                values.add(lc);
            } else if (options.isSet(Option.XLINT_CUSTOM, "-" + lc.option)) {
                values.remove(lc);
            }
        }

        // Create root config
        rootConfig = new Config(values, LintCategory.newEmptySet(), "ROOT");
    }

    /**
     * Categories of warnings that can be generated by the compiler.
     */
    public enum LintCategory {
        /**
         * Warn when code refers to a auxiliary class that is hidden in a source file (ie source file name is
         * different from the class name, and the type is not properly nested) and the referring code
         * is not located in the same source file.
         */
        AUXILIARYCLASS("auxiliaryclass"),

        /**
         * Warn about use of unnecessary casts.
         */
        CAST("cast"),

        /**
         * Warn about issues related to classfile contents.
         *
         * <p>
         * This category is non-specific (e.g., not supported by {@code @SuppressWarnings}).
         */
        CLASSFILE("classfile", false),

        /**
         * Warn about "dangling" documentation comments,
         * not attached to any declaration.
         */
        DANGLING_DOC_COMMENTS("dangling-doc-comments"),

        /**
         * Warn about use of deprecated items.
         */
        DEPRECATION("deprecation"),

        /**
         * Warn about items which are documented with an {@code @deprecated} JavaDoc
         * comment, but which do not have {@code @Deprecated} annotation.
         */
        DEP_ANN("dep-ann"),

        /**
         * Warn about division by constant integer 0.
         */
        DIVZERO("divzero"),

        /**
         * Warn about empty statement after if.
         */
        EMPTY("empty"),

        /**
         * Warn about issues regarding module exports.
         */
        EXPORTS("exports"),

        /**
         * Warn about falling through from one case of a switch statement to the next.
         */
        FALLTHROUGH("fallthrough"),

        /**
         * Warn about finally clauses that do not terminate normally.
         */
        FINALLY("finally"),

        /**
         * Warn about use of incubating modules.
         *
         * <p>
         * This category is non-specific (e.g., not supported by {@code @SuppressWarnings}).
         */
        INCUBATING("incubating", false),

        /**
          * Warn about compiler possible lossy conversions.
          */
        LOSSY_CONVERSIONS("lossy-conversions"),

        /**
          * Warn about compiler generation of a default constructor.
          */
        MISSING_EXPLICIT_CTOR("missing-explicit-ctor"),

        /**
         * Warn about module system related issues.
         */
        MODULE("module"),

        /**
         * Warn about issues regarding module opens.
         */
        OPENS("opens"),

        /**
         * Warn about issues relating to use of command line options.
         *
         * <p>
         * This category is non-specific (e.g., not supported by {@code @SuppressWarnings}).
         */
        OPTIONS("options", false),

        /**
         * Warn when any output file is written to more than once.
         *
         * <p>
         * This category is non-specific (e.g., not supported by {@code @SuppressWarnings}).
         */
        OUTPUT_FILE_CLASH("output-file-clash", false),

        /**
         * Warn about issues regarding method overloads.
         */
        OVERLOADS("overloads"),

        /**
         * Warn about issues regarding method overrides.
         */
        OVERRIDES("overrides"),

        /**
         * Warn about invalid path elements on the command line.
         *
         * <p>
         * This category is non-specific (e.g., not supported by {@code @SuppressWarnings}).
         */
        PATH("path", false),

        /**
         * Warn about issues regarding annotation processing.
         *
         * <p>
         * This category is non-specific (e.g., not supported by {@code @SuppressWarnings}).
         */
        PROCESSING("processing", false),

        /**
         * Warn about unchecked operations on raw types.
         */
        RAW("rawtypes"),

        /**
         * Warn about use of deprecated-for-removal items.
         */
        REMOVAL("removal"),

        /**
         * Warn about use of automatic modules in the requires clauses.
         */
        REQUIRES_AUTOMATIC("requires-automatic"),

        /**
         * Warn about automatic modules in requires transitive.
         */
        REQUIRES_TRANSITIVE_AUTOMATIC("requires-transitive-automatic"),

        /**
         * Warn about Serializable classes that do not provide a serial version ID.
         */
        SERIAL("serial"),

        /**
         * Warn about issues relating to use of statics
         */
        STATIC("static"),

        /**
         * Warn about unnecessary uses of the strictfp modifier
         */
        STRICTFP("strictfp"),

        /**
         * Warn about synchronization attempts on instances of @ValueBased classes.
         */
        SYNCHRONIZATION("synchronization"),

        /**
         * Warn about issues relating to use of text blocks
         */
        TEXT_BLOCKS("text-blocks"),

        /**
         * Warn about possible 'this' escapes before subclass instance is fully initialized.
         */
        THIS_ESCAPE("this-escape"),

        /**
         * Warn about issues relating to use of try blocks (i.e. try-with-resources)
         */
        TRY("try"),

        /**
         * Warn about unchecked operations on raw types.
         */
        UNCHECKED("unchecked"),

        /**
         * Warn about potentially unsafe vararg methods
         */
        VARARGS("varargs"),

        /**
         * Warn about use of preview features.
         */
        PREVIEW("preview"),

        /**
         * Warn about use of restricted methods.
         */
        RESTRICTED("restricted");

        LintCategory(String option) {
            this(option, true);
        }

        LintCategory(String option, boolean specific) {
            this.option = option;
            this.specific = specific;
            map.put(option, this);
        }

        /**
         * Get the {@link LintCategory} having the given command line option.
         *
         * @param option lint category option string
         * @return corresponding {@link LintCategory}, or empty if none exists
         */
        public static Optional<LintCategory> get(String option) {
            return Optional.ofNullable(map.get(option));
        }

        public static EnumSet<LintCategory> newEmptySet() {
            return EnumSet.noneOf(LintCategory.class);
        }

        /**
         * Determine whether this category is specific or non-specific.
         *
         * <p>
         * Specific lint category warnings always occur at a specific location in a source file.
         * (for example {@link #UNCHECKED}). Non-specific lint categories apply generally to the
         * entire compliation process, for example {@link #OUTPUT_FILE_CLASH}.
         */
        public boolean isSpecific() {
            return specific;
        }

        /** Get the string representing this category in @SuppressAnnotations and -Xlint options. */
        public final String option;

        private final boolean specific;
    }

    /**
     * Obtain the set of recognized lint warning categories suppressed at the given symbol's declaration.
     *
     * <p>
     * This set can be non-empty only if the symbol is annotated with either
     * @SuppressWarnings or @Deprecated.
     *
     * @param symbol symbol corresponding to a possibly-annotated declaration
     * @return new warning suppressions applied to sym
     */
    public EnumSet<LintCategory> suppressionsFrom(Symbol symbol) {
        EnumSet<LintCategory> suppressions = suppressionsFrom(symbol.getDeclarationAttributes().stream());
        if (symbol.isDeprecated() && symbol.isDeprecatableViaAnnotation())
            suppressions.add(LintCategory.DEPRECATION);
        return suppressions;
    }

    /**
     * Retrieve the recognized lint categories suppressed by the given @SuppressWarnings annotation.
     *
     * @param annotation @SuppressWarnings annotation, or null
     * @return set of lint categories, possibly empty but never null
     */
    private EnumSet<LintCategory> suppressionsFrom(JCAnnotation annotation) {
        initializeSymbolsIfNeeded();
        if (annotation == null)
            return LintCategory.newEmptySet();
        Assert.check(annotation.attribute.type.tsym == syms.suppressWarningsType.tsym);
        return suppressionsFrom(Stream.of(annotation).map(anno -> anno.attribute));
    }

    // Find the @SuppressWarnings annotation in the given stream and extract the recognized suppressions
    private EnumSet<LintCategory> suppressionsFrom(Stream<Attribute.Compound> attributes) {
        initializeSymbolsIfNeeded();
        EnumSet<LintCategory> result = LintCategory.newEmptySet();
        attributes
          .filter(attribute -> attribute.type.tsym == syms.suppressWarningsType.tsym)
          .map(this::suppressionsFrom)
          .forEach(result::addAll);
        return result;
    }

    // Given a @SuppressWarnings annotation, extract the recognized suppressions
    private EnumSet<LintCategory> suppressionsFrom(Attribute.Compound suppressWarnings) {
        EnumSet<LintCategory> result = LintCategory.newEmptySet();
        Attribute.Array values = (Attribute.Array)suppressWarnings.member(names.value);
        for (Attribute value : values.values) {
            Optional.of((String)((Attribute.Constant)value).value)
              .flatMap(LintCategory::get)
              .ifPresent(result::add);
        }
        return result;
    }

    private void initializeSymbolsIfNeeded() {
        if (syms == null) {
            syms = Symtab.instance(context);
            names = Names.instance(context);
        }
    }
}
