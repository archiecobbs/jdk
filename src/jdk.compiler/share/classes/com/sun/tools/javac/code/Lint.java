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

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import javax.tools.JavaFileObject;

import com.sun.tools.javac.code.Source.Feature;
import com.sun.tools.javac.main.Option;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.Assert;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JCDiagnostic;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import com.sun.tools.javac.util.JCDiagnostic.LintWarning;
import com.sun.tools.javac.util.JCDiagnostic.SimpleDiagnosticPosition;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Names;
import com.sun.tools.javac.util.Options;

/**
 * A class for handling -Xlint suboptions and @SuppressWarnings.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class Lint implements Lint.Logger {

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
     * A class representing a specific combination of enabled and suppressed {@link LintCategory}s.
     *
     * <p>
     * A {@link LintCategory} may be enabled, suppressed, or neither, but never both.
     *
     * <p>
     * Instances are immutable. New instances may be created by using the methods {@link #enable},
     * {@link #suppress}, and {@link #augment}. The "root" instance is configured based solely on
     * {@code -Xlint} flags and is available via {@link Lint#getRootConfig}.
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

        @Override
        public String toString() {
            return "Lint.Config[" + description + ",enable" + enabled + ",suppress" + suppressed + "]";
        }
    }

// Logger

    /**
     * An interface for creating a {@link LintWarning} at a particular source file location,
     * subject to the current {@link Config} in effect at that location.
     *
     * <p>
     * The actual logging of a warning may or may not occur synchronously; for example,
     * during parsing, before {@code @SuppressWarnings} annotations have been analyzed,
     * warnings must be deferred until it's known whether the corresponding category is
     * suppressed at the warning's location. In addition, all warnings that are logged
     * prior to the compiler's {@code warn()} phase are deferred until that happens.
     */
    public interface Logger {

        /**
         * Log a non-specific lint warning if its category is enabled in the root lint config.
         *
         * <p>
         * Non-specific lint warnings are not specific to any particular source file
         * and do not support suppression via {@code @SuppressWarnings}.
         *
         * @param pos source position at which to report the warning
         * @param warning key for the localized warning message; category should normally be non-specific
         */
        void logIfEnabled(LintWarning warning);

        /**
         * Log a non-specific lint warning if its category is not suppressed in the root lint config.
         *
         * <p>
         * Non-specific lint warnings are not specific to any particular source file
         * and do not support suppression via {@code @SuppressWarnings}.
         *
         * @param pos source position at which to report the warning
         * @param warning key for the localized warning message; category should normally be non-specific
         */
        void logIfNotSuppressed(LintWarning warning);

        /**
         * Log a lint warning if its category is {@linkplain Config#isEnabled enabled}
         * at the specified source file position.
         *
         * @param pos position in the current source file at which to report the warning
         * @param warning key for the localized warning message; category must be specific
         */
        default void logIfEnabled(int pos, LintWarning warning) {
            logIfEnabled(wrap(pos), warning);
        }

        /**
         * Log a lint warning if its category is {@linkplain Config#isEnabled enabled}
         * at the specified source file position.
         *
         * @param pos current source file position at which to report the warning, or null if unspecified
         * @param warning key for the localized warning message; category must be specific
         */
        void logIfEnabled(DiagnosticPosition pos, LintWarning warning);

        /**
         * Log a lint warning if its category is not {@linkplain Config#isSuppressed suppressed}
         * at the specified source file position.
         *
         * @param pos current source file position at which to report the warning
         * @param warning key for the localized warning message; category must be specific
         */
        default void logIfNotSuppressed(int pos, LintWarning warning) {
            logIfNotSuppressed(wrap(pos), warning);
        }

        /**
         * Log a lint warning if its category is not {@linkplain Config#isSuppressed suppressed}
         * at the specified source file position.
         *
         * @param pos current source file position at which to report the warning, or null if unspecified
         * @param warning key for the localized warning message; category must be specific
         */
        void logIfNotSuppressed(DiagnosticPosition pos, LintWarning warning);

        /**
         * Log a mandatory warning specific to a source file.
         *
         * @param pos current source file position at which to report the warning, or null if unspecified
         * @param warning key for the localized warning message; category must be specific
         */
        void logMandatoryWarning(DiagnosticPosition pos, Warning warning);

        /**
         * Log a non-specific mandatory warning.
         *
         * @param warning key for the localized warning message; category must be specific
         */
        void logMandatoryWarning(Warning warning);
    }

// Reporter

    /**
     * A {@link Logger} that has an associated {@link Config} and is the conduit through which
     * an {@link Analyzer} reports the warnings it generates.
     *
     * <p>
     * The associated {@link Config} is initialized based on the analysis source file location.
     * During the analysis it may be modified/restored using {@link #modifyConfig} and
     * {@link #restoreConfig}.
     */
    public abstract class Reporter extends Logger {

        private final ArrayList<Config> configList = new ArrayList<>();
        private final ArrayList<Warning> warningList;
        private final LintCategory category;

        private boolean invalid;

        private Reporter(LintCategory category, Config config, ArrayList<Warning> warningList) {
            this.category = category;
            this.configList.add(config);
        }

        /**
         * Get the current configuration associated with this instance.
         *
         * @return current configuration
         */
        public Config getConfig() {
            return configList.get(configList.size() - 1);
        }

        /**
         * Modify the current configuration associated with this instance.
         *
         * <p>
         * Use {@link #restoreConfig} to un-do the modifications.
         *
         * @param modifier modifies the current config
         * @return previous configuration
         */
        public void modifyConfig(UnaryOperator<Config> modifier) {
            configList.add(modifier.apply(getConfig()));
        }

        /**
         * Restore the configuration in effect prior to the most recent call to {@link #modifyConfig}.
         *
         * @param modifier modifies the current config
         * @return previous configuration
         */
        public void restoreConfig() {
            int count = configList.size();
            Assert.check(count > 1);
            configList.remove(count - 1);
        }

    // Logger

        @Override
        public void logIfEnabled(LintWarning warning) {
            if (getRootConfig().isEnabled(warning.getLintCategory())) {
                log(warning);
            }
        }

        @Override
        public void logIfNotSuppressed(LintWarning warning) {
            if (!getRootConfig().isSuppressed(warning.getLintCategory())) {
                log(warning);
            }
        }

        @Override
        public void logIfEnabled(DiagnosticPosition pos, LintWarning warning) {
            if (config.isEnabled(warning.getLintCategory())) {
                log(pos, warning);
            }
        }

        @Override
        public void logIfNotSuppressed(DiagnosticPosition pos, LintWarning warning) {
            if (!config.isSuppressed(warning.getLintCategory())) {
                log(pos, warning);
            }
        }

        /**
         * Log a non-specific warning.
         *
         * @param warning key for the localized warning message; category must be non-specific
         */
        public void log(LintWarning warning) {
            LintWarning warningCategory = warning.getLintCategory();
            Assert.check(!warningCategory.isSpecific());
            Assert.check(warningCategory == category || category == null);
            Assert.check(!invalid);
            warningList.add(new Warning(null, warning));
        }

        /**
         * Log a specific warning.
         *
         * @param pos source position at which to report the warning
         * @param warning key for the localized warning message; category must be specific
         */
        public void log(DiagnosticPosition pos, LintWarning warning) {
            LintWarning warningCategory = warning.getLintCategory();
            Assert.check(warningCategory.isSpecific());
            Assert.check(warningCategory == category || category == null);
            Assert.check(!invalid);
            warningList.add(new Warning(pos, warning));
        }

        void invalidate() {
            this.invalid = true;
        }
    }

// Analyzer

    /**
     * Callback interface for doing lint warning analysis, starting from a specific source file location.
     */
    @FunctionalInterface
    public interface Analyzer {

        /**
         * Analyze for warnings and report any found via the given {@link Reporter}.
         *
         * @param reporter a {@link Reporter} pre-configured based on the analysis location
         */
        void analyze(Reporter reporter);
    }

// Public API

    /**
     * Get the root {@link Config}.
     *
     * <p>
     * The root configuration consists of the categories that are enabled by default,
     * with any adjustments specified by {@code -Xlint} command line flags applied.
     *
     * @return root lint configuration
     */
    public Config getRootConfig() {
        Assert.check(rootConfig != null);
        return rootConfig;
    }

    /**
     * Get the {@link Reporter} associated with the analysis executing in the current thread, if any.
     *
     * @return current thread's {@link Reporter}, or null if no analysis is executing
     */
    public Reporter currentReporter() {
        return currentReporter.get();
    }

// Logger Methods

    @Override
    public void logIfEnabled(LintWarning warning) {
        Assert.check(!category.isSpecific());
        if (getRootConfig().isEnabled(warning.getLintCategory()))
            log.warning(warning);
    }

    @Override
    public void logIfNotSuppressed(LintWarning warning) {
        Assert.check(!category.isSpecific());
        if (!getRootConfig().isSuppressed(warning.getLintCategory()))
            log.warning(warning);
    }

    @Override
    public void logIfEnabled(DiagnosticPosition pos, LintWarning warning) {
        Assert.check(warningCategory.isSpecific());
        nowOrLater(warning.getLintCategory(), pos, reporter -> reporter.logIfEnabled(pos, warning));
    }

    @Override
    public void logIfNotSuppressed(DiagnosticPosition pos, LintWarning warning) {
        Assert.check(warningCategory.isSpecific());
        nowOrLater(warning.getLintCategory(), pos, reporter -> reporter.logIfNotSuppressed(pos, warning));
    }

    private void nowOrLater(LintCategory category, DiagnosticPosition pos, Analyzer analyzer) {
        Reporter currentReporter = currentReporter();
        if (currentReporter != null) {
            analyzer.accept(currentReporter);       // we are already doing an analysis
        } else {
            analyze(category, pos, analyzer);       // enqueue the analysis for later
        }
    }

    /**
     * Enqueue a warning analysis task for generating warning(s) in a specific {@link LintCategory},
     * to be executed when the compiler reaches the warning phase for the current source file,
     * but only if the category is enabled at the specified source file position.
     *
     * <p>
     * The {@code analyzer} will be given a {@link Reporter} that is configured based on the specified
     * source file location. <b>The {@link Reporter} should be used to report all lint warnings generated
     * and all such warnings should be in the given {@code category}</b>.
     *
     * <p>
     * If {@code category} is not active at {@code pos}, then the analysis will be skipped entirely.
     *
     * @param category category for generated warnings
     * @param pos analysis starting source file position
     * @param analyzer callback object for doing the analysis
     */
    public void ifEnabled(LintCategory category, int pos, Analyzer analyzer) {
        ifEnabled(category, wrap(pos), analyzer);
    }

    /**
     * Enqueue a warning analysis task for generating warning(s) in a specific {@link LintCategory},
     * to be executed when the compiler reaches the warning phase for the current source file,
     * but only if the category is enabled at the specified source file position.
     *
     * <p>
     * The {@code analyzer} will be given a {@link Reporter} that is configured based on the specified
     * source file location. <b>The {@link Reporter} should be used to report all lint warnings generated
     * and all such warnings should be in the given {@code category}</b>.
     *
     * <p>
     * If {@code category} is not active at {@code pos}, then the analysis will be skipped entirely.
     *
     * @param category category for generated warnings
     * @param pos analysis starting source file position
     * @param analyzer callback object for doing the analysis
     */
    public void ifEnabled(LintCategory category, DiagnosticPosition pos, Analyzer analyzer) {
        Assert.check(category != null);
        analyze(category, pos, reporter -> {
            if (reporter.getConfig().isEnabled(category)) {
                analyzer.analyze(reporter);
            }
        });
    }

    /**
     * Enqueue a warning analysis task for generating warning(s) in a specific {@link LintCategory},
     * to be executed when the compiler reaches the warning phase for the current source file,
     * but only if the category is not suppressed at the specified source file position.
     *
     * <p>
     * The {@code analyzer} will be given a {@link Reporter} that is configured based on the specified
     * source file location. <b>The {@link Reporter} should be used to report all lint warnings generated
     * and all such warnings should be in the given {@code category}</b>.
     *
     * <p>
     * If {@code category} is not active at {@code pos}, then the analysis will be skipped entirely.
     *
     * @param category category for generated warnings
     * @param pos analysis starting source file position
     * @param analyzer callback object for doing the analysis
     */
    public void ifNotSuppressed(LintCategory category, int pos, Analyzer analyzer) {
        ifNotSuppressed(category, wrap(pos), analyzer);
    }

    /**
     * Enqueue a warning analysis task for generating warning(s) in a specific {@link LintCategory},
     * to be executed when the compiler reaches the warning phase for the current source file,
     * but only if the category is not suppressed at the specified source file position.
     *
     * <p>
     * The {@code analyzer} will be given a {@link Reporter} that is configured based on the specified
     * source file location. <b>The {@link Reporter} should be used to report all lint warnings generated
     * and all such warnings should be in the given {@code category}</b>.
     *
     * <p>
     * If {@code category} is not active at {@code pos}, then the analysis will be skipped entirely.
     *
     * @param category category for generated warnings
     * @param pos analysis starting source file position
     * @param analyzer callback object for doing the analysis
     */
    public void ifNotSuppressed(LintCategory category, DiagnosticPosition pos, Analyzer analyzer) {
        Assert.check(category != null);
        analyze(category, pos, reporter -> {
            if (!reporter.getConfig().isSuppressed(category)) {
                analyzer.analyze(reporter);
            }
        });
    }

    /**
     * Enqueue a warning analysis task for generating warning(s) in a specific {@link LintCategory},
     * to be executed when the compiler reaches the warning phase for the current source file.
     *
     * <p>
     * The {@code analyzer} will be given a {@link Reporter} that is configured based on the specified
     * source file location. <b>The {@link Reporter} should be used to report all lint warnings generated
     * and all such warnings should be in the given {@code category} (if not null)</b>.
     *
     * @param category category for generated warnings, or null for unspecified
     * @param pos analysis starting source file position
     * @param analyzer callback object for doing the analysis
     */
    public void analyze(LintCategory category, DiagnosticPosition pos, Analyzer analyzer) {

        // Get the analysis list for the current source file
        Assert.check(analyzer != null);
        JavaFileObject source = currentSource();
        List<Analysis> analysisList = analysisMap.get(source);
        if (analysisList == null) {                     // a null list means warnings have already happened
            Assert.check(!analysisMap.containsKey(source), "too late for analysis of source");
            analysisList = new ArrayList<>();
            analysisMap.put(source, analysisList);
        }

        // Enqueue this analysis
        analysisList.add(new Analysis(category, pos, analyzer));
    }

    /**
     * Execute all enqueued analyses for the given compilation unit.
     *
     * @param tree attributed compliation unit
     */
    public void executeAnalyses(JCCompilationUnit tree) {
        Assert.check(currentReporter() == null, "reentrant invocation");

        // Get the associated source file
        JavaSourceFile source = tree.sourcefile;
        Assert.check(source.equals(currentSource()));

        // Do we have any enqueued analyses for this source file?
        if (!analysisMap.containsKey(source)) {
            analysisMap.put(source, null);          // put in a tombstone marker
            return;
        }

        // Get the analysis list replace with a tombstone marker
        List<Analysis> analysisList = analysisMap.replace(source, null);
        Assert.check(analysisList != null, "executeWarnings() invoked twice for the same source");

        // Compute the appropriate initial Config for each analysis
        List<Config> configList = new ConfigAssigner(analysisList).assign(tree);

        // Run the analyses and gather the resulting warnings
        ArrayList<Warning> warningList = new ArrayList<>();
        for (int i = 0; i < analysisList.size(); i++) {
            Analysis analysis = analysisList.get(i);
            LintCategory analysisCategory = analysis.category();
            Config config = configList.get(i);
            Reporter reporter = new Reporter(config) {
                @Override
                protected void log(DiagnosticPosition pos, LintWarning warning) {
                    LintWarning warningCategory = warning.getLintCategory();
                    Assert.check(analysisCategory == null || warningCategory == analysisCategory);
                    warningList.add(new Warning(pos, warning));
                }
            };
            currentReporter.set(reporter);
            try {
                analysis.analyzer().analyze(reporter);
            } finally {
                currentReporter.set(null);
            }
        }

        // Sort and emit the warnings, puting the non-specific warnings aside
        warningList.sort(Comparator.comparingInt(Warning::sortKey));
        warningList.forEach(warning -> {
            if (warning.isSpecific())
                log.warning(warning.pos(), warning.warning());
            else
                nonSpecificWarnings.add(warning);
        });
    }

    /**
     * Emit all remaining non-specific warnings.
     */
    public void emitNonSpecificWarnings() {
        nonSpecificWarnings.stream()
          .map(Warning::warning)
          .forEach(log::warning);
    }

    // Get the "current" source file
    private JavaFileObject currentSource() {
        Optional<JavaFileObject> opt = Optional.of(log)
          .map(Log::currentSource)
          .map(DiagnosticSource::getFile);
        Assert.check(opt.isPresent(), "no current source file");
        return opt.get();
    }

    static DiagnosticPosition wrap(int pos) {
        return pos != Position.NOPOS ? new SimpleDiagnosticPosition(pos) : null;
    }

    static int unwrap(DiagnosticPosition pos) {
        return Optional.ofNullable(pos)
          .mapToInt(DiagnosticPosition::getPreferredPosition)
          .orElse(Position.NOPOS);
    }

// Analysis

    private record Analysis(LintCategory category, int pos, Analyzer analyzer) {

        /**
         * Compare the position of this analysis to the given declaration range.
         *
         * <p>
         * This instance's position is either before, contained by, or after the declaration.
         *
         * @return -1 if before, 0 if contained, 1 if after
         */
        int compareToRange(int minPos, int maxPos) {
            if (pos() < minPos)
                return -1;
            if (pos() == minPos || (pos() > minPos && pos() < maxPos))
                return 0;
            return 1;
        }
    }

// Warning

    private record Warning(DiagnosticPosition pos, JCDiagnostic.Warning warning, boolean specific) {

        Warning(JCDiagnostic.Warning warning) {
            this(null, warning, false);
        }

        Warning(DiagnosticPosition pos, JCDiagnostic.Warning warning) {
            this(pos, warning, true);
        }

        int sortKey() {
            return pos() != null ? pos().getPreferredPosition() : Integer.MAX_VALUE;
        }
    }

// ConfigAssigner

    /**
     * This scans a source file and identifies, for each {@link Analysis} starting position,
     * the innermost declaration that contains it, and assigns a corresponding {@link Config}.
     */
    private class ConfigAssigner extends TreeScanner {

        private final List<Analysis> analysisList;
        private final List<Config> configList;

        private Config config = rootConfig;
        private int currentAnalysis;

        ConfigAssigner(List<Analysis> analysisList) {
            this.analysisList = analysisList;
            Config[] configArray = new Config[analysisList.size()];
            Arrays.fill(configArray, rootConfig);
            configList = Arrays.asList(configArray);
        }

        List<Config> assign(JCCompilationUnit tree) {
            analysisList.sort(Comparator.comparingInt(Analysis::pos));
            try {
                scan(tree);
            } catch (ShortCircuitException e) {
                // got done early
            }
            return configList;
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

        @Override
        public void visitImport(JCImport tree) {
            initializeSymbolsIfNeeded();

            // Proceed normally unless the special suppression logic applies here
            JCFieldAccess imp = tree.qualid;
            Name name = TreeInfo.name(imp);
            if (Feature.DEPRECATION_ON_IMPORT.allowedInSource(source)
              || name == names.asterisk
              || tree.staticImport) {
                super.visitImport(tree);
                return;
            }

            // Suppress warnings here because we're only importing (and not using) the symbol
            visitTree(tree,
              config -> config.suppress(LintCategory.DEPRECATION, LintCategory.REMOVAL, LintCategory.PREVIEW),
              super::visitImport);
        }

        private void visitDeclaration(JCTree decl, Symbol sym, Consumer<? super T> recursion) {
            visitTree(decl, config -> config.augment(sym), recursion);  // apply @SuppressWarnings and @Deprecated
        }

        private void visitTree(JCTree tree, UnaryOperator<Config> mods, Consumer<? super T> recursion) {

            // Determine the lexical extent of the given AST node
            int minPos = TreeInfo.getStartPos(tree);
            int maxPos = TreeInfo.endPos(tree);

            // Update the current Config and assign to matching analyses
            Config prevConfig = config;
            config = mods.apply(config);
            try {
                assignCurrentConfig(minPos, maxPos);
                recursion.accept(tree);
            } finally {
                config = prevConfig;
            }
        }

        private void assignCurrentConfig(int minPos, int maxPos) {

            // Find analyses contained in the range and assign them the current config
            int numMatches = 0;
            while (currentAnalysis + numMatches < analysisList.size()) {

                // Where is the range relative to the current analysis?
                Analysis analysis = analysisList.get(currentAnalysis + numMatches);
                int relativePosition = analysis.compareToRange(minPos, maxPos);

                // If range is before the current analysis, then it overlaps nothing else in the list
                if (relativePosition > 0) {
                    break;
                }

                // If range is after the current analysis, advance to the next analysis and try again
                if (relativePosition < 0) {
                    Assert.check(numMatches == 0);
                    currentAnalysis++;
                    continue;
                }

                // The current analysis is contained within this declaration, so assign to it the
                // config associated with the declaration, and continue doing so for all immediately
                // following analyses that also match, but don't advance the current analysis just yet:
                // this declaration may not be the innermost containing declaration, and if not,
                // we want the narrower declaration(s) that follow to assign their config instead.
                configList.set(currentAnalysis + numMatches, config);
                numMatches++;
            }

            // If we have assigned all of the analyses, we can stop now
            if (currentAnalysis >= analysisList.size()) {
                throw new ShortCircuitException();
            }
        }
    }

// ShortCircuitException

    @SuppressWarnings("serial")
    private static class ShortCircuitException extends RuntimeException {
    }

// Internal State

    private final Context context;
    private final Log log;
    private final Source source;

    // These are initialized lazily to avoid dependency loops
    private Symtab syms;
    private Names names;

    // The root configuration
    private Config rootConfig;

    // The source file we are currently parsing, or null if not parsing
    private JavaFileObject parsingFile;

    // Maps source file to enqueued analyses, or null if already analyzed
    private final Map<JavaFileObject, List<Analysis>> analysisMap = new HashMap<>();

    // Collects non-specific warnings
    private final List<Warning> nonSpecificWarnings = new ArrayList<>();

    // Is the current thread executing a call to analyze()?
    private static ThreadLocal<Lint.Reporter> currentReporter = new ThreadLocal<>();

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
         */
        INCUBATING("incubating"),

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
