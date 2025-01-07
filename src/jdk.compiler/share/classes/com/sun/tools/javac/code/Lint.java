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
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.sun.tools.javac.main.Option;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.util.Assert;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import com.sun.tools.javac.util.JCDiagnostic.LintWarning;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Names;
import com.sun.tools.javac.util.Options;

/**
 * A class for handling {@code -Xlint} suboptions and {@code @SuppressWarnings} annotations.
 *
 * <p>
 * Each lint category can be in one of three states: enabled, suppressed, or neither. The "neither"
 * state means it's effectively up to the code doing the check to determine the default behavior, by
 * warning when enabled (i.e., default suppressed) or warning when suppressed (i.e., default enabled).
 * Some categories default to enabled; most default to neither and warn when enabled.
 *
 * <p>
 * A lint category can be explicitly enabled via the command line flag {@code -Xlint:key}, or explicitly
 * disabled via the command line flag {@code -Xlint:-key}. Some lint categories warn at specific
 * locations in the code and can be suppressed within the scope of a symbol declaration via the
 * {@code @SuppressWarnings} annotation.
 *
 * <p>
 * The meta-categories {@code suppression-option} and {@code suppression} warn about unnecessary
 * {@code -Xlint:-key} flags and {@code @SuppressWarnings} annotations (respectively), i.e., they warn
 * about explicit suppressions that don't actually suppress anything. In order for this calculation
 * to be correct, <i>code that generates a warning must execute even when the corresponding category
 * is disabled or suppressed</i>.
 *
 * <p>
 * To ensure this happens, code should use {@link #isActive} to determine whether to bother performing
 * a warning calculation (if the calculation is non-trivial), and it should use {@link #logIfEnabled}
 * to actually log any warnings found. Even if the warning is suppressed, {@link #logIfEnabled} will note
 * that any suppression in effect is actually doing something useful. This is termed the <i>validation</i>
 * of the suppression.
 *
 * <p>
 * Further details:
 * <ul>
 *  <li>To build an instance augmented with any new suppressions from {@code @SuppressWarnings} and/or
 *      {@code @Deprecated} annotations on a symbol declaration, use {@link #augment} to establish a
 *      new symbol "scope".
 *  <li>Any category for which {@link #isActive} returns true must be checked; this is true even if
 *      {@link #isEnabled} returns false and/or {@link #isSuppressed} returns true. Use of {@link #isActive}
 *      is optional; it simply allows you to skip unnecessary work. For trivial checks, it's not needed.
 *  <li>When a warnable condition is found, invoke {@link #logIfEnabled}. If the warning is suppressed,
 *      it won't actually be logged, but the category will still be validated. All lint warnings that would
 *      have been generated but aren't because of suppression must still validate the corresponding category.
 *  <li>You can manually check whether a category {@link #isEnabled} or {@link #isSuppressed}. These methods
 *      include a boolean parameter to optionally also validate any suppression of the category; <i>always
 *      do so if a warning will actually be generated based on the method's return value</i>.
 *  <li>If needed, you can validate suppressions manually via {@link #validateSuppression}.
 * </ul>
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class Lint {

    /** The context key for the root {@link Lint} singleton. */
    protected static final Context.Key<Lint> lintKey = new Context.Key<>();

    /** Get the root {@link Lint} singleton. */
    public static Lint instance(Context context) {
        Lint instance = context.get(lintKey);
        if (instance == null)
            instance = new Lint(context);
        return instance;
    }

    /**
     * Obtain an instance with additional warning supression applied from any
     * {@code @SuppressWarnings} and/or {@code @Deprecated} annotations on the given symbol.
     *
     * <p>
     * The returned instance will be different from this instance if and only if
     * {@link #suppressionsFrom} returns a non-empty set.
     *
     * @param sym symbol
     * @return lint instance with new warning suppressions applied, or this instance if none
     */
    public Lint augment(Symbol sym) {
        EnumSet<LintCategory> suppressions = suppressionsFrom(sym);
        if (!suppressions.isEmpty()) {
            Lint lint = new Lint(this, sym);
            lint.values.removeAll(suppressions);
            lint.suppressedValues.addAll(suppressions);
            return lint;
        }
        return this;
    }

    /**
     * Returns a new Lint that has the given {@link LintCategory}s enabled.
     *
     * @param lc one or more categories to be enabled
     */
    public Lint enable(LintCategory... lc) {
        Lint l = new Lint(this, symbolInScope);
        l.values.addAll(Arrays.asList(lc));
        l.suppressedValues.removeAll(Arrays.asList(lc));
        return l;
    }

    /**
     * Returns a new Lint that has the given {@link LintCategory}s suppressed.
     *
     * @param lc one or more categories to be suppressed
     */
    public Lint suppress(LintCategory... lc) {
        Lint l = new Lint(this, symbolInScope);
        l.values.removeAll(Arrays.asList(lc));
        l.suppressedValues.addAll(Arrays.asList(lc));
        return l;
    }

    // Associated compiler context
    private final Context context;

    // The current symbol in scope (having @SuppressWarnings or @Deprecated), or null for global scope
    private final Symbol symbolInScope;

    // Used to track the validation of suppressed warnings
    private final LintSuppression lintSuppression;

    // These are initialized lazily to avoid dependency loops
    private Symtab syms;
    private Names names;

    // Invariant: it's never the case that a category is in both "values" and "suppressedValues"
    private final EnumSet<LintCategory> values;
    private final EnumSet<LintCategory> suppressedValues;

    /** Contains the categories suppressed via {@code -Xlint:-key} command line flags. */
    final EnumSet<LintCategory> suppressedOptions;

    private static final Map<String, LintCategory> map = new ConcurrentHashMap<>(20);

    @SuppressWarnings("this-escape")
    protected Lint(Context context) {
        // initialize values according to the lint options
        Options options = Options.instance(context);

        if (options.isSet(Option.XLINT) || options.isSet(Option.XLINT_CUSTOM, "all")) {
            // If -Xlint or -Xlint:all is given, enable all categories by default
            values = EnumSet.allOf(LintCategory.class);
        } else if (options.isSet(Option.XLINT_CUSTOM, "none")) {
            // if -Xlint:none is given, disable all categories by default
            values = LintCategory.newEmptySet();
        } else {
            // otherwise, enable on-by-default categories
            values = LintCategory.newEmptySet();

            Source source = Source.instance(context);
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
        suppressedOptions = LintCategory.newEmptySet();
        for (LintCategory lc : LintCategory.values()) {
            if (options.isSet(Option.XLINT_CUSTOM, lc.option)) {
                values.add(lc);
            } else if (options.isSet(Option.XLINT_CUSTOM, "-" + lc.option)) {
                suppressedOptions.add(lc);
                values.remove(lc);
            }
        }

        suppressedValues = LintCategory.newEmptySet();
        symbolInScope = null;

        this.context = context;
        context.put(lintKey, this);

        lintSuppression = LintSuppression.instance(context);
    }

    protected Lint(Lint other, Symbol symbolInScope) {
        this.context = other.context;
        this.syms = other.syms;
        this.names = other.names;
        this.lintSuppression = other.lintSuppression;
        this.symbolInScope = symbolInScope;
        this.values = other.values.clone();
        this.suppressedValues = other.suppressedValues.clone();
        this.suppressedOptions = other.suppressedOptions;
    }

    @Override
    public String toString() {
        return "Lint:[sym=" + symbolInScope + ",enable" + values + ",suppress" + suppressedValues + "]";
    }

    /**
     * Categories of warnings that can be generated by the compiler.
     */
    public enum LintCategory {
        /**
         * Warn when code refers to an auxiliary class that is hidden in a source file (i.e., the source file
         * name is different from the class name, and the type is not properly nested) and the referring code
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
         * This category is not supported by {@code @SuppressWarnings}.
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
         * comment, but which do not have the {@code @Deprecated} annotation.
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
         * This category is not supported by {@code @SuppressWarnings}
         * and is not tracked for unnecessary suppression.
         */
        OPTIONS("options", false, false),

        /**
         * Warn when any output file is written to more than once.
         *
         * <p>
         * This category is not supported by {@code @SuppressWarnings}.
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
         * This category is not supported by {@code @SuppressWarnings}
         * and is not tracked for unnecessary suppression.
         */
        PATH("path", false, false),

        /**
         * Warn about issues regarding annotation processing.
         */
        PROCESSING("processing"),

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
         * Warn about recognized {@code @SuppressWarnings} lint categories that don't actually suppress any warnings.
         *
         * <p>
         * This category is not tracked for unnecessary suppression.
         */
        SUPPRESSION("suppression", true, false),

        /**
         * Warn about {@code -Xlint:-key} options that don't actually suppress any warnings (requires {@link #OPTIONS}).
         *
         * <p>
         * This category is not supported by {@code @SuppressWarnings}
         * and is not tracked for unnecessary suppression.
         */
        SUPPRESSION_OPTION("suppression-option", false, false),

        /**
         * Warn about synchronization attempts on instances of @ValueBased classes.
         */
        SYNCHRONIZATION("synchronization"),

        /**
         * Warn about issues relating to use of text blocks.
         *
         * <p>
         * This category is not supported by {@code @SuppressWarnings}.
         */
        TEXT_BLOCKS("text-blocks", false),

        /**
         * Warn about possible 'this' escapes before subclass instance is fully initialized.
         */
        THIS_ESCAPE("this-escape"),

        /**
         * Warn about issues relating to use of try blocks (i.e., try-with-resources).
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
         *
         * <p>
         * This category is not supported by {@code @SuppressWarnings}.
         */
        PREVIEW("preview", false),

        /**
         * Warn about use of restricted methods.
         */
        RESTRICTED("restricted");

        LintCategory(String option) {
            this(option, true);
        }

        LintCategory(String option, boolean annotationSuppression) {
            this(option, annotationSuppression, true);
        }

        LintCategory(String option, boolean annotationSuppression, boolean suppressionTracking) {
            this.option = option;
            this.annotationSuppression = annotationSuppression;
            this.suppressionTracking = suppressionTracking;
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

        /**
         * Create a new, empty, mutable set of {@link LintCategory}.
         */
        public static EnumSet<LintCategory> newEmptySet() {
            return EnumSet.noneOf(LintCategory.class);
        }

        /** Get the string representing this category in {@code @SuppressWarnings} and {@code -Xlint:key} flags. */
        public final String option;

        /** Does this category support being suppressed by the {@code @SuppressWarnings} annotation? */
        public final boolean annotationSuppression;

        /** Do the {@code "suppression"} and {@code "suppression-option"} categories track suppressions in this category? */
        public final boolean suppressionTracking;
    }

    /**
     * Determine whether warnings in the given category should be calculated, because either
     * (a) the category is enabled, or (b) one of {@code "suppression"} or {@code "suppression-option"}
     * is enabled, the category is currently suppressed, and that suppression has not yet been validated.
     *
     * <p>
     * Use of this method is never required; it simply helps avoid potentially useless work.
     */
    public boolean isActive(LintCategory lc) {
        return values.contains(lc) ||
          (needsSuppressionTracking(lc) && !lintSuppression.isValid(symbolInScope, lc));
    }

    /**
     * Checks if a warning category is enabled. A warning category may be enabled
     * on the command line, or by default, and can be temporarily disabled with
     * the {@code @SuppressWarnings} annotation.
     *
     * <p>
     * This method also optionally validates any warning suppressions currently in scope.
     * If you just want to know the configuration of this instance, set {@code validate} to false.
     * If you are using the result of this method to control whether a warning is actually
     * generated, then set {@code validate} to true to ensure that any suppression of the category
     * in scope is validated (i.e., determined to actually be suppressing something).
     *
     * @param lc lint category
     * @param validateSuppression true to also validate any suppression of the category
     */
    public boolean isEnabled(LintCategory lc, boolean validateSuppression) {
        if (validateSuppression)
            validateSuppression(lc);
        return values.contains(lc);
    }

    /**
     * Checks is a warning category has been specifically suppressed, by means
     * of the {@code @SuppressWarnings} annotation, or, in the case of the deprecated
     * category, whether it has been implicitly suppressed by virtue of the
     * current entity being itself deprecated.
     *
     * <p>
     * This method also optionally validates any warning suppressions currently in scope.
     * If you just want to know the configuration of this instance, set {@code validate} to false.
     * If you are using the result of this method to control whether a warning is actually
     * generated, then set {@code validate} to true to ensure that any suppression of the category
     * in scope is validated (i.e., determined to actually be suppressing something).
     *
     * @param lc lint category
     * @param validateSuppression true to also validate any suppression of the category
     */
    public boolean isSuppressed(LintCategory lc, boolean validateSuppression) {
        if (validateSuppression)
            validateSuppression(lc);
        return suppressedValues.contains(lc);
    }

    /**
     * Helper method. Validate a lint warning and log it if its lint category is enabled.
     *
     * @param log warning destination
     * @param warning key for the localized warning message
     */
    public void logIfEnabled(Log log, LintWarning warning) {
        logIfEnabled(log, null, warning);
    }

    /**
     * Helper method. Validate a lint warning and log it if its lint category is enabled.
     *
     * @param log warning destination
     * @param pos source position at which to report the warning
     * @param warning key for the localized warning message
     */
    public void logIfEnabled(Log log, DiagnosticPosition pos, LintWarning warning) {
        if (isEnabled(warning.getLintCategory(), true)) {
            log.warning(pos, warning);
        }
    }

    /**
     * Obtain the set of recognized lint warning categories suppressed at the given symbol's declaration.
     *
     * <p>
     * This set can be non-empty only if the symbol is annotated with either
     * {@code @SuppressWarnings} or {@code @Deprecated}.
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
     * Retrieve the recognized lint categories suppressed by the given {@code @SuppressWarnings} annotation.
     *
     * @param annotation {@code @SuppressWarnings} annotation, or null
     * @return set of lint categories, possibly empty but never null
     */
    EnumSet<LintCategory> suppressionsFrom(JCAnnotation annotation) {
        initializeIfNeeded();
        if (annotation == null)
            return LintCategory.newEmptySet();
        Assert.check(annotation.attribute.type.tsym == syms.suppressWarningsType.tsym);
        return suppressionsFrom(Stream.of(annotation).map(anno -> anno.attribute));
    }

    // Find the @SuppressWarnings annotation in the given stream and extract the recognized suppressions
    private EnumSet<LintCategory> suppressionsFrom(Stream<Attribute.Compound> attributes) {
        initializeIfNeeded();
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

    /**
     * Record that any suppression of the given category currently in scope is valid.
     *
     * <p>
     * Such a suppression will therefore <b>not</b> be declared as unnecessary by the
     * {@code "suppression"} or {@code "suppression-option"} warnings.
     *
     * @param lc the lint category to be validated
     * @return this instance
     */
    public Lint validateSuppression(LintCategory lc) {
        if (needsSuppressionTracking(lc))
            lintSuppression.validate(symbolInScope, lc);
        return this;
    }

    /**
     * Determine whether we should bother tracking validation for the given lint category.
     *
     * <p>
     * We need to track the validation of a lint category if:
     * <ul>
     *  <li>It's supported by {@code "suppression"} and {@code "suppression-option"} suppression tracking
     *  <li>One or both of {@code "suppression"} or {@code "suppression-option"} is currently enabled
     *  <li>It's currently being suppressed by some {@code @SuppressWarnings} and/or {@code -Xlint:-key}
     * </ul>
     */
    private boolean needsSuppressionTracking(LintCategory lc) {
        return lc.suppressionTracking &&
            (suppressedValues.contains(lc) || suppressedOptions.contains(lc)) &&
            (values.contains(LintCategory.SUPPRESSION) || values.contains(LintCategory.SUPPRESSION_OPTION));
    }

    private void initializeIfNeeded() {
        if (syms == null) {
            syms = Symtab.instance(context);
            names = Names.instance(context);
        }
    }
}
