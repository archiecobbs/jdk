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

import com.sun.tools.javac.main.Option;
import com.sun.tools.javac.util.AbstractLog;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import com.sun.tools.javac.util.JCDiagnostic.LintWarning;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Options;

/**
 * A class for handling -Xlint suboptions and @SuppressWarnings.
 *
 * <p>
 * Simple instructions:
 * <ul>
 *  <li>To build an instance augmented with any new suppressions from @SuppressWarnings and/or
 *      @Deprecated annotations, use {@link #augment}. This creates a new symbol "scope".
 *  <li>Any category for which {@link #isActive} returns true must be checked; this is
 *      true even if {@link #isEnabled} returns false or {@link #isSuppressed} returns true.
 *      Use of {@link #isActive} is optional; it simply allows you to skip unnecessary work.
 *  <li>When a warnable condition is found, invoke {@link #logIfEnabled}. If the warning should
 *      be suppressed, it won't actually be logged, but the category will be validated as
 *      required for the SUPPRESSION and SUPPRESSION_OPTION categories. NOTE: All warnings that
 *      can possibly be generated must validate the corresponding category even if the warning
 *      is being suppressed.
 *  <li>You can also manually check whether a category {@link #isEnabled} or {@link #isSuppressed}.
 *      These methods allow you to validate any current suppression of the category as well;
 *      do that if a warning will actually be generated based on the method's return value.
 *  <li>You can also validate suppressions manually if needed via {@link #validateSuppression} or
 *      {@link LintSuppression#validate}.
 * </ul>
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class Lint {

    /** The context key for the root Lint object. */
    protected static final Context.Key<Lint> lintKey = new Context.Key<>();

    /** Get the root Lint instance. */
    public static Lint instance(Context context) {
        Lint instance = context.get(lintKey);
        if (instance == null)
            instance = new Lint(context);
        return instance;
    }

    /**
     * Obtain an instance with additional warning supression applied from any
     * @SuppressWarnings and/or @Deprecated annotations on the given symbol.
     *
     * <p>
     * The returned instance will be different from this instance if and only if
     * {@link LintSuppression#suppressionsFrom} returns a non-empty set.
     *
     * @param sym symbol
     * @return lint instance with new warning suppressions applied, or this instance if none
     */
    public Lint augment(Symbol sym) {
        EnumSet<LintCategory> suppressions = lintSuppression.suppressionsFrom(sym);
        if (!suppressions.isEmpty()) {
            Lint lint = new Lint(this, sym);
            lint.values.removeAll(suppressions);
            lint.suppressedValues.addAll(suppressions);
            return lint;
        }
        return this;
    }

    /**
     * Returns a new Lint that has the given LintCategorys enabled.
     * @param lc one or more categories to be enabled
     */
    public Lint enable(LintCategory... lc) {
        Lint l = new Lint(this, symbolInScope);
        l.values.addAll(Arrays.asList(lc));
        l.suppressedValues.removeAll(Arrays.asList(lc));
        return l;
    }

    /**
     * Returns a new Lint that has the given LintCategorys suppressed.
     * @param lc one or more categories to be suppressed
     */
    public Lint suppress(LintCategory... lc) {
        Lint l = new Lint(this, symbolInScope);
        l.values.removeAll(Arrays.asList(lc));
        l.suppressedValues.addAll(Arrays.asList(lc));
        return l;
    }

    /** Contains the categories suppressed via "-Xlint:-foo" command line flags. */
    final EnumSet<LintCategory> suppressedOptions;

    // Used to track the validation of suppressed warnings
    private final LintSuppression lintSuppression;

    // The current symbol in scope (having @SuppressWarnings or @Deprecated), or null for global scope
    private final Symbol symbolInScope;

    // Invariant: it's never the case that a category is in both "values" and "suppressedValues"
    private final EnumSet<LintCategory> values;
    private final EnumSet<LintCategory> suppressedValues;

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

        context.put(lintKey, this);

        lintSuppression = LintSuppression.instance(context);
    }

    protected Lint(Lint other, Symbol symbolInScope) {
        this.suppressedOptions = other.suppressedOptions;
        this.lintSuppression = other.lintSuppression;
        this.symbolInScope = symbolInScope;
        this.values = other.values.clone();
        this.suppressedValues = other.suppressedValues.clone();
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
         * Warn about issues related to classfile contents
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
         * Warn about issues relating to use of command line options
         */
        OPTIONS("options", false, false),

        /**
         * Warn when any output file is written to more than once.
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
         * Such warnings cannot be suppressed with the SuppressWarnings
         * annotation.
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
         * Warn about @SuppressWarnings values that don't actually suppress any warnings.
         */
        SUPPRESSION("suppression", true, false),

        /**
         * Warn about -Xlint:-key options that don't actually suppress any warnings (requires OPTIONS).
         */
        SUPPRESSION_OPTION("suppression-option", false, false),

        /**
         * Warn about synchronization attempts on instances of @ValueBased classes.
         */
        SYNCHRONIZATION("synchronization"),

        /**
         * Warn about issues relating to use of text blocks
         */
        TEXT_BLOCKS("text-blocks", false),

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

        public static EnumSet<LintCategory> newEmptySet() {
            return EnumSet.noneOf(LintCategory.class);
        }

        /** Get the string representing this category in @SuppressAnnotations and -Xlint options. */
        public final String option;

        /** Does this category support suppression via @SuppressAnnotations annotations, or only via -Xlint? */
        public final boolean annotationSuppression;

        /** Do the SUPPRESSION and SUPPRESSION_OPTION categories generate warnings about this category? */
        public final boolean suppressionTracking;
    }

    /**
     * Determine whether warnings in the given category should be calculated, either because
     * the category is enabled, or because one of SUPPRESSION or SUPPRESSION_OPTION is enabled,
     * the category is currently suppressed, and that suppression has not yet been validated.
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
     * the SuppressWarnings annotation.
     *
     * <p>
     * This method also optionally validates any warning suppressions currently in scope.
     * If you just want to know the configuration of this instance, set validate to false.
     * If you are using the result of this method to control whether a warning is actually
     * generated, then set validate to true to ensure any any suppression of the category
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
     * of the SuppressWarnings annotation, or, in the case of the deprecated
     * category, whether it has been implicitly suppressed by virtue of the
     * current entity being itself deprecated.
     *
     * <p>
     * This method also optionally validates any warning suppressions currently in scope.
     * If you just want to know the configuration of this instance, set validate to false.
     * If you are using the result of this method to control whether a warning is actually
     * generated, then set validate to true to ensure any any suppression of the category
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
     * Record that any suppression of the given category currently in scope is valid.
     *
     * <p>
     * Such a suppression will therefore <b>not</b> be declared as unnecessary by the
     * SUPPRESSION or SUPPRESSION_OPTION warnings.
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
     *  <li>It's subject to SUPPRESSION and SUPPRESSION_OPTION tracking
     *  <li>It's currently being suppressed by @SuppressWarnings or -Xlint:-foo
     *  <li>Either of SUPPRESSION or SUPPRESSION_OPTION is currently enabled
     * </ul>
     */
    private boolean needsSuppressionTracking(LintCategory lc) {
        return lc.suppressionTracking &&
            (suppressedValues.contains(lc) || suppressedOptions.contains(lc)) &&
            (values.contains(LintCategory.SUPPRESSION) || values.contains(LintCategory.SUPPRESSION_OPTION));
    }
}
