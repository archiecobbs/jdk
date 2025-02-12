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
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
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
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticFlag;
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
     * Represents a specific combination of enabled or suppressed {@link LintCategory}s.
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

        private Config copy(String descriptionSuffix) {
            return new Config(EnumSet.copyOf(enabled), EnumSet.copyOf(suppressed), description + descriptionSuffix);
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
     * plus any adjustments due to {@code -Xlint} command line flags.
     *
     * @return root lint configuration
     */
    public Config getRootConfig() {
        if (rootConfig == null)
            initializeRootConfig(options);
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
        return currentSourceInfo().getConfigCalculator().configAt(pos);
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
        currentSourceInfo().getConfigCalculator().push(minPos, maxPos, modifier);
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
        currentSourceInfo().getConfigCalculator().pop();
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
        LintCategory category = warning.getLintCategory();
        Assert.check(!category.isSpecific());
        if (!log.wouldDiscard(null, warning)) {
            ifEnabled(category, () -> addWarning(new Warning(warning)));
        }
    }

    /**
     * Log a warning in a {@linkplain LintCategory#isSpecific non-specific} lint category
     * if the category is not suppressed in the root lint config.
     *
     * @param pos source position at which to report the warning
     * @param warning key for the localized warning message; must be non-specific
     */
    public void logIfNotSuppressed(LintWarning warning) {
        LintCategory category = warning.getLintCategory();
        Assert.check(!category.isSpecific());
        if (!log.wouldDiscard(null, warning)) {
            ifNotSuppressed(category, () -> addWarning(new Warning(warning)));
        }
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
        Assert.check(category != null);
        Assert.check(!category.isSpecific());
        analyze(category, null, () -> {
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
        Assert.check(category != null);
        Assert.check(!category.isSpecific());
        analyze(category, null, () -> {
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

boolean debug = false;

    /**
     * Log a warning in a {@linkplain LintCategory#isSpecific specific} lint category
     * if the category is enabled at the given source file position.
     *
     * @param pos current source file position at which to report the warning, or null if unspecified
     * @param warning key for the localized warning message; category must be specific
     */
    public void logIfEnabled(DiagnosticPosition pos, LintWarning warning) {
        LintCategory category = warning.getLintCategory();
        Assert.check(category.isSpecific());

if (debug || (warning.key().contains("automatic") && log.currentSourceFile().toString().contains("RequiresTransitiveAutomatic"))) {
//if (debug) {
System.out.println("logIfEnabled():"
+"\n  sourceFile="+log.currentSourceFile()
+"\n  category="+category
+"\n  pos=["+pos+"]"
+"\n  warning="+warning
+"\n  wouldDiscard="+log.wouldDiscard(pos, warning)
);
//new Throwable("HERE").printStackTrace(System.out);
}

        if (!log.wouldDiscard(pos, warning)) {

            analyze(category, pos, () -> {

if (debug || (warning.key().contains("automatic") && log.currentSourceFile().toString().contains("RequiresTransitiveAutomatic"))) {
//if (debug) {
System.out.println("logIfEnabled(): ANALYSIS:"
+"\n  category="+category
+"\n  pos=["+pos.getPreferredPosition()+"]"
+"\n  warning="+warning
+"\n  configAt(pos)="+configAt(pos)
);
}

                if (configAt(pos).isEnabled(category)) {
                    addWarning(new Warning(pos, warning));
                }
            });
        }
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
        LintCategory category = warning.getLintCategory();
        Assert.check(category.isSpecific());
        if (!log.wouldDiscard(pos, warning)) {
            analyze(category, pos, () -> {
                if (!configAt(pos).isSuppressed(category)) {
                    addWarning(new Warning(pos, warning));
                }
            });
        }
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
        Assert.check(category != null);
        Assert.check(category.isSpecific());
        analyze(category, pos, () -> {
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
        Assert.check(category != null);
        Assert.check(category.isSpecific());
        analyze(category, pos, () -> {
            if (!configAt(pos).isSuppressed(category)) {
                analyzer.run();
            }
        });
    }

// Analyze

    /**
     * Perform a general purpose lint analysis.
     *
     * <p>
     * For non-specific lint categories, the analysis is always executed synchonously. Otherwise, the
     * analysis executes as soon as possible after the current source file has been mapped.
     *
     * <p>
     * This is a low level API; prefer the {@code logIfXxx()} and {@code ifXxx()} methods.
     *
     * <p>
     * <b>All warnings generated by {@code analyzer} must be in the given {@code category} (if not null)</b>.
     *
     * @param category specific category for generated warnings, or null for no restriction
     * @param pos analysis starting source file position, or null if category is null or not specific
     * @param analyzer callback for doing the analysis
     */
    public void analyze(LintCategory category, DiagnosticPosition pos, Runnable analyzer) {

        // Sanity check analysis category and compare to the current analysis (if any)
        boolean specific = category != null && category.isSpecific();
        Assert.check(category == null ||
            currentSourceInfo == null ||
            category == currentSourceInfo.currentAnalysis().category());
        Assert.check(pos != null || !specific);

        // Get the current source file (specific lint categories only)
        JavaFileObject source = specific ? log.currentSourceFile() : null;

        // If there is no current source file with a specific lint category, we're in a weird
        // situation where warnings are being ignored anyway (e.g., doclint running) so bail out.
        if (source == null && specific) {
            return;
        }

        // Create the analysis
        int position = unwrap(pos);
        Analysis analysis = new Analysis(category, position, analyzer);

        // Execute the analysis now or enqueue for later if the source file isn't mapped yet.
        // For non-specific lint categories, we always execute synchronously.
        SourceInfo sourceInfo = sourceMap.computeIfAbsent(source, s -> new SourceInfo());
        boolean executeNow = (category != null && !category.isSpecific()) || sourceInfo.isReady(position);
        if (executeNow) {
            sourceInfo.getReadyAnalyses().push(analysis);
            executeNextAnalysis(sourceInfo);
        } else {
            sourceInfo.getWaitingAnalyses().addLast(analysis);
        }
    }

    /**
     * Directly enqueue a warning.
     *
     * <p>
     * The current thread must be executing an analysis.
     *
     * <p>
     * This is a low level API; prefer the {@code logIfXxx()} and {@code ifXxx()} methods.
     *
     * @param pos warning location; null iff lint category is non-specific
     * @param warning the lint warning to enqueue
     * @param flags diagnostic flags, if any
     * @throws AssertionError if the current thread is not executing an analysis
     */
    public void addWarning(DiagnosticPosition pos, LintWarning warning, DiagnosticFlag... flags) {
        addWarning(new Warning(pos, warning, flags));
    }

    /**
     * Get the total number of enqueued warnings.
     */
    public int getNumEnqueuedWarnings() {
        return sourceMap.values().stream().map(SourceInfo::getWarnings).mapToInt(List::size).sum();
    }

    // Execute the next enqueued analysis for the given source and remove it from the queue
    private void executeNextAnalysis(SourceInfo sourceInfo) {
        SourceInfo previousSourceInfo = currentSourceInfo;
        currentSourceInfo = sourceInfo;
        try {

if (false && debug) {
System.out.println("executeNextAnalysis(): BEFORE"
+"\n  next analysis="+sourceInfo.currentAnalysis()
+"\n  sourceMap="+sourceMap
);
}
            sourceInfo.currentAnalysis().task().run();
        } finally {
            sourceInfo.getReadyAnalyses().removeFirst();
            currentSourceInfo = previousSourceInfo;


if (false && debug) {
System.out.println("executeNextAnalysis(): AFTER"
+"\n  sourceMap="+sourceMap
);
}

        }
    }

    private void addWarning(Warning warning) {

if (false && debug) {
System.out.println("addWarning():"
+"\n  warning="+warning
+"\n  currentSourceInfo()="+currentSourceInfo()
);
}

        currentSourceInfo().getWarnings().add(warning);

if (false && debug) {
System.out.println("addWarning2():"
+"\n  currentSourceInfo()="+currentSourceInfo()
);
}

    }

// Compiler Events

    /**
     * Notify that (specific) analyses that are scoped to the given tree may now execute.
     */
    public void readyForAnalysis(Env<AttrContext> env) {
        Assert.check(env.tree.getTag() == Tag.MODULEDEF
                  || env.tree.getTag() == Tag.PACKAGEDEF
                  || env.tree.getTag() == Tag.CLASSDEF);

        // Get corresponding source
        JavaFileObject source = env.toplevel.sourcefile;
        Assert.check(Objects.equals(source, log.currentSourceFile()));

if (debug) {
String s = env.tree.toString();
s = s.substring(0, Math.min(200, s.length()));
s = s.replaceAll("\\s+", " ");
System.out.println("READY-FOR-ANALYSIS: " +(env != null ? ((JCClassDecl)env.tree).sym : "NULL")
+"\n  source="+source
+"\n  env.tree="+s
);
}

        // Acquire the info for this source file
        SourceInfo sourceInfo = sourceMap.computeIfAbsent(source, s -> new SourceInfo());

        // Mark tree as ready
        sourceInfo.setReady(env);

        // Execute ready analyses
        while (!sourceInfo.getReadyAnalyses().isEmpty()) {
            executeNextAnalysis(sourceInfo);
        }
    }

    /**
     * Emit all enqueued warnings for the given top-level declaration.
     *
     * @param env The attribution environment of an outermost declaration, or null to emit non-specific warnings
     */
    public void emitWarnings(Env<AttrContext> env) {

// TODO: what about modules & packages?

if (debug) {
System.out.println("EMIT-WARNINGS:"
+"\n  sym="+(env != null ? TreeInfo.symbolFor(env.tree) : "NULL")
+"\n  source="+(env != null ? log.currentSourceFile() : "N/A")
+"\n  #warn="+(env != null && sourceMap.get(log.currentSourceFile()) != null ? sourceMap.get(log.currentSourceFile()).getWarnings().size() : "N/A")
);
}

        // Flush warnings for specified class, or all remaining warnings if env is null
        if (env != null) {
            JavaFileObject sourceFile = log.currentSourceFile();
            SourceInfo sourceInfo = sourceMap.get(sourceFile);
            if (sourceInfo == null) {
                return;
            }
            emitWarnings(env.toplevel.sourcefile, sourceInfo);
        } else {
            sourceMap.forEach(this::emitWarnings);
        }
    }

    private void emitWarnings(JavaFileObject sourceFile, SourceInfo sourceInfo) {
        JavaFileObject prevSource = log.useSource(sourceFile);
        try {
            List<Warning> warnings = sourceInfo.getWarnings();
            //warnings.sort(Comparator.comparingInt(Warning::sortKey));

if (debug) {
System.out.println("WARNING LIST: size " + warnings.size()+ " for source " +sourceFile);
for (int i = 0; i < warnings.size(); i++)
    System.out.println(String.format("[%02d] %s", i, warnings.get(i)));
}

            warnings.forEach(warning -> warning.warn(log));
            warnings.clear();
        } finally {
            log.useSource(prevSource);
        }
    }

    /**
     * Reset this instance for a new compilation task.
     */
    public void newRound() {
        rootConfig = null;
        currentSourceInfo = null;
        sourceMap.clear();
    }

// SourceInfo

    // Information about a single source file:
    //  - @SuppressWarnings config calculator
    //  - Which analyses are ready for execution
    //  - Warnings waiting to be emitted
    private class SourceInfo {

        private final ConfigCalculator configCalculator = new ConfigCalculator();
        private final Deque<Analysis> waitingAnalyses = new ArrayDeque<>();
        private final Deque<Analysis> readyAnalyses = new ArrayDeque<>();
        private final List<Warning> warnings = new ArrayList<>();

        private List<Range> waitingRanges;

        ConfigCalculator getConfigCalculator() {
            return configCalculator;
        }

        Deque<Analysis> getWaitingAnalyses() {
            return waitingAnalyses;
        }

        Deque<Analysis> getReadyAnalyses() {
            return readyAnalyses;
        }

        List<Warning> getWarnings() {
            return warnings;
        }

        void setReady(Env<AttrContext> env) {

            // First time, initialize our waiting ranges
            if (waitingRanges == null) {
                waitingRanges = new ArrayList<>();
                env.toplevel.defs.stream()
                  .filter(def -> def.getTag() == Tag.MODULEDEF ||
                                 def.getTag() == Tag.PACKAGEDEF ||
                                 def.getTag() == Tag.CLASSDEF)
                  .map(Range::new)
                  .forEach(waitingRanges::add);
            }

            // This range is no longer waiting
            waitingRanges.remove(new Range(env.tree));

            // Map config ranges
            configCalculator.process(env);

            // Move newly ready analyses from the waiting queue to the ready queue
            for (Iterator<Analysis> i = waitingAnalyses.iterator(); i.hasNext(); ) {
                Analysis analysis = i.next();
                if (isReady(analysis.pos())) {
                    i.remove();
                    readyAnalyses.add(analysis);
                }
            }
        }

        boolean isReady(int pos) {
            return waitingRanges != null && waitingRanges.stream().noneMatch(range -> range.contains(pos));
        }

        Analysis currentAnalysis() {
            Assert.check(!readyAnalyses.isEmpty());
            return readyAnalyses.getFirst();
        }

        @Override
        public String toString() {
            return "SourceInfo"
              + "[calculator=" + configCalculator
              + ",waiting=" + waitingAnalyses
              + ",ready=" + readyAnalyses
              + ",warnings=" + warnings
              + "]";
        }
    }

    // Get the SourceInfo corresponding to the currently executing analysis
    private SourceInfo currentSourceInfo() {
        Assert.check(currentSourceInfo != null, "there is no current lint analysis executing");
        return currentSourceInfo;
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

    private record Analysis(LintCategory category, int pos, Runnable task) {

        @Override
        public String toString() {
            return "Analysis"
              + "[" + category()
              + "@" + pos()
              + ":" + task.getClass().getName()
              + "]";
        }
    }

// Warning

    private record Warning(DiagnosticPosition pos, LintWarning warning, DiagnosticFlag... flags) {

        Warning {
            Assert.check((pos != null) == warning.getLintCategory().isSpecific());
            Assert.check(Stream.of(flags).allMatch(DiagnosticFlag.MANDATORY::equals));  // we only support the one flag
        }

        // For non-specific lint categories
        Warning(LintWarning warning, DiagnosticFlag... flags) {
            this(null, warning, flags);
        }

        void warn(Log log) {

if (false) {
System.out.println("Warning: LOGGING"
+"\n  log="+log
+"\n  isSpecific()="+isSpecific()
+"\n  isMandatory()="+isMandatory()
+"\n  warning="+warning
);
}

            if (isSpecific()) {
                if (isMandatory()) {
                    log.mandatoryWarning(pos(), warning());
                } else {
                    log.warning(pos(), warning());
                }
            }
            else {
                if (isMandatory()) {
                    log.mandatoryWarning(null, warning());
                } else {
                    log.warning(warning());
                }
            }
        }

        boolean isSpecific() {
            return warning().getLintCategory().isSpecific();
        }

        boolean isMandatory() {
            return Stream.of(flags()).anyMatch(DiagnosticFlag.MANDATORY::equals);
        }

//        int sortKey() {
//            return warning.getLintCategory().isSpecific() ? unwrap(pos()) : Integer.MAX_VALUE;
//        }
    }

// Range

    private record Range(int minPos, int maxPos) {

        Range {
            Assert.check(maxPos() >= minPos());
        }

        Range(JCTree tree) {
            this(TreeInfo.getStartPos(tree), TreeInfo.endPos(tree));
        }

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
            return String.format("Range[0x%08x-0x%08x]", minPos(), maxPos());
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
        private final List<ConfigRange> ranges = new ArrayList<>();
        private final List<ConfigPatch> patches = new ArrayList<>();

        // Have we processed everything outside of the top-level declaration(s)?
        private boolean processedTopLevel;

        // Use during scanning
        private ConfigRange parentRange;

private boolean calcDebug = false;

        ConfigCalculator() {
            ranges.add(new ConfigRange(Integer.MIN_VALUE, Integer.MAX_VALUE, getRootConfig()));
        }

        void copyFrom(ConfigCalculator that) {
            this.ranges.clear();
            this.ranges.addAll(that.ranges);
            this.patches.clear();
            this.patches.addAll(that.patches);
        }

        boolean process(Env<AttrContext> env) {
            boolean firstTime = !processedTopLevel;
            if (firstTime) {
                process(env.toplevel);
                processedTopLevel = true;
            }
            process(env.tree);
            return firstTime;
        }

        private void process(JCTree tree) {

            // Scan file to generate config ranges within tree
            parentRange = ranges.get(0);
            try {
                scan(tree);
            } finally {
                parentRange = null;
            }

if (calcDebug) {
String s = tree.toString();
s = s.substring(0, Math.min(200, s.length()));
s = s.replaceAll("\\s+", " ");
System.out.println("process("+(!processedTopLevel?"TOP":((JCClassDecl)tree).name)+"):"
+"\n  tree="+s
+"\n  ranges:"+ranges.stream().map(ConfigRange::toString).collect(java.util.stream.Collectors.joining("\n    "))
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
            ConfigRange configRange = null;
            for (ConfigRange candidate : ranges) {
                if (candidate.contains(pos) && (configRange == null || candidate.size() < configRange.size())) {
                    configRange = candidate;
                }
            }
            Assert.check(configRange != null);

            // Apply any applicable patches
            Config config = configRange.config();
            for (ConfigPatch patch : patches) {
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
            patches.add(new ConfigPatch(minPos, maxPos, mods));
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
            if (!processedTopLevel)
                return;
            visitDeclaration(decl, decl.sym, super::visitModuleDef);
        }

        @Override
        public void visitPackageDef(JCPackageDecl decl) {
            if (!processedTopLevel)
                return;
            visitDeclaration(decl, decl.packge, super::visitPackageDef);
        }

        @Override
        public void visitClassDef(JCClassDecl decl) {
            if (!processedTopLevel)
                return;
            visitDeclaration(decl, decl.sym, super::visitClassDef);
        }

        @Override
        public void visitMethodDef(JCMethodDecl decl) {
            Assert.check(processedTopLevel);
            visitDeclaration(decl, decl.sym, super::visitMethodDef);
        }

        @Override
        public void visitVarDef(JCVariableDecl decl) {
            Assert.check(processedTopLevel);
            visitDeclaration(decl, decl.sym, super::visitVarDef);
        }

        private <T extends JCTree> void visitDeclaration(T decl, Symbol sym, Consumer<? super T> recursion) {

if (calcDebug) {
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
            int maxPos = TreeInfo.endPos(tree);

            // Update the current config
            Config oldConfig = parentRange.config();
            Config newConfig = mods.apply(oldConfig);

            // Add a new range, but not if we can determine its not needed
            ConfigRange childRange = new ConfigRange(minPos, maxPos, newConfig);
            if (!newConfig.equals(oldConfig) || !parentRange.contains(childRange)) {
                ranges.add(childRange);
            }

            // Recurse
            ConfigRange prevParentRange = parentRange;
            parentRange = childRange;
            try {
                recursion.accept(tree);
            } finally {
                parentRange = prevParentRange;
            }
        }

        @Override
        public String toString() {
            return "ConfigCalculator"
              + "[ranges=" + ranges
              + ",patches=" + patches
              + "]";
        }

        // Represents a range of character offsets and the corresponding lint config that applies there.
        // The end of each range is inferred from the minPos() of the next range in the list.
        private record ConfigRange(Range range, Config config) {

            ConfigRange(int minPos, int maxPos, Config config) {
                this(new Range(minPos, maxPos), config);
            }

            long size() {
                return range().size();
            }

            boolean contains(int pos) {
                return range().contains(pos);
            }

            boolean contains(ConfigRange that) {
                return range().contains(that.range());
            }

            @Override
            public String toString() {
                return String.format("ConfigRange[0x%08x-0x%08x|%s]",
                    range().minPos(), range().maxPos(), config().getDescription());
            }
        }

        // Represents a "patch" to this instance for the given range
        private record ConfigPatch(Range range, UnaryOperator<Config> mods) {

            boolean contains(int pos) {
                return range().contains(pos);
            }

            ConfigPatch(int minPos, int maxPos, UnaryOperator<Config> mods) {
                this(new Range(minPos, maxPos), mods);
            }
        }
    }

// Internal State

    private final Context context;
    private final Options options;
    private final Source source;
    private final Log log;

    // These are initialized lazily to avoid dependency loops
    private Symtab syms;
    private Names names;

    // The root configuration
    private Config rootConfig;

    // The SourceInfo of the currently executing analysis (null if none)
    private SourceInfo currentSourceInfo;

    // Maps source file to enqueued analyses and warnings, if any
    private final Map<JavaFileObject, SourceInfo> sourceMap = new LinkedHashMap<>();

    // Maps category name to category
    private static final Map<String, LintCategory> map = new ConcurrentHashMap<>(20);

    @SuppressWarnings("this-escape")
    protected Lint(Context context) {
        this.context = context;
        context.put(lintKey, this);
        log = Log.instance(context);
        source = Source.instance(context);
        options = Options.instance(context);
        options.whenReady(this::initializeRootConfig);
    }

    // Process command line options to build the root Lint.Config instance.
    // We do this "on demand" to allow use of the Lint singleton early during startup.
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
