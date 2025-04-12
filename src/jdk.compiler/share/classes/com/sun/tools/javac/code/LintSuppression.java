/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.sun.tools.javac.code.Lint.LintCategory;
import com.sun.tools.javac.resources.CompilerProperties.LintWarnings;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.Assert;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Names;

import static com.sun.tools.javac.code.Lint.LintCategory.DEPRECATION;
import static com.sun.tools.javac.code.Lint.LintCategory.OPTIONS;
import static com.sun.tools.javac.code.Lint.LintCategory.SUPPRESSION;
import static com.sun.tools.javac.code.Lint.LintCategory.SUPPRESSION_OPTION;

/**
 * Calculates which {@code @SuppressWarnings} and {@code -Xlint:-key} suppressions actually suppress something.
 *
 * <p>
 * This is used to implement the {@code suppression} and {@code suppression-option} lint warning categories.
 *
 * <p>
 * Lint instances are "augmented" via {@link Lint#augment} when a module, package, class, method, or variable
 * declaration is encountered, and any new warning suppressions gleaned from {@code @SuppressWarnings} and/or
 * {@code @Deprecation} annotations on the declared symbol are put into effect for the scope of that declaration.
 *
 * <p>
 * In order to know whether the suppression of a lint category actually suppresses any warnings, we need
 * to be notified if a warning that is currently suppressed would have been reported. This is termed the
 * "validation" of the suppression, and that notification happens via {@link #validate}.
 *
 * <p>
 * If a lint category is explicitly suppressed, but the suppression is never validated, then the suppression is
 * unnecessary, triggering a category {@code suppression} (for {@code @SuppressWarnings}) or {@code suppression-option}
 * (for {@code -Xlint:key}) lint warning. Because validation can occur within nested {@code @SuppressWarning} scopes,
 * each validation event is propagated upward in the AST tree until it hits the first corresponding suppression
 * (which is then validated), or else "escapes" the file.
 *
 * <p>
 * After a source file has been fully warned about, {@link #reportUnnecessaryAnnotations} is invoked to report any
 * unnecessary {@code @SuppressWarnings} annotations in that file; these will be the annotations that were never
 * "hit" by a validation event.
 *
 * <p>
 * After all files have had {@link #reportUnnecessaryAnnotations}, {@link #reportUnnecessaryOptions} is then invoked
 * to report on any unnecessary {@code -Xlint:key} flags. These will be the categories which were never "hit" by
 * a validation event that "escaped" the per-file propagation process.
 *
 * <p>
 * Additional observations and corner cases:
 * <ul>
 *  <li>Lint warnings can be suppressed at a module, package, class, method, or variable declaration
 *      (via {@code @SuppressWarnings}), or globally (via {@code -Xlint:-key}).
 *  <li>Consequently, an unnecessary suppression warning can only be emitted at one of those declarations, or globally
 *      at the end of compilation.
 *  <li>Some categories (e.g., {@code classfile}) don't support suppression via {@code @SuppressWarnings}, so they can
 *      only generate warnings at the global level; any {@code @SuppressWarnings} annotation is always deemed unnecessary.
 *  <li>Some categories are not tracked for suppression at all: {@code options}, {@code path}, {@code suppression},
 *      and {@code suppression-option}. Suppressions of these categories are never deemed unnecessary.
 *  <li>A self-referential annotation {@code @SuppressWarnings("suppression")} is perfectly valid. It means unnecessary
 *      suppression warnings will never be reported for any lint category suppressed by that same annotation, or by any
 *      nested {@code @SuppressWarnings} annotation within the scope of that declaration.
 * </ul>
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class LintSuppression {

    /** The context key for the {@link LintSuppression} singleton. */
    protected static final Context.Key<LintSuppression> lintSuppressionKey = new Context.Key<>();

    // Map from @SuppressWarnings-annotated symbol declaration to the lint categories validated in its scope
    private final HashMap<Symbol, EnumSet<LintCategory>> localValidations = new HashMap<>();

    // Lint categories validated outside of any matching @SuppressWarnings scope
    private final EnumSet<LintCategory> globalValidations = LintCategory.newEmptySet();

    private final Context context;
    private final DeclTreeBuilder declTreeBuilder;

    // These are initialized lazily to avoid dependency loops
    private Lint rootLint;
    private Symtab syms;
    private Names names;

    /** Get the {@link LintSuppression} singleton. */
    public static LintSuppression instance(Context context) {
        LintSuppression instance = context.get(lintSuppressionKey);
        if (instance == null)
            instance = new LintSuppression(context);
        return instance;
    }

    private LintSuppression(Context context) {
        this.context = context;
        context.put(lintSuppressionKey, this);
        declTreeBuilder = new DeclTreeBuilder();
    }

    /**
     * Validate the given lint category within the scope of the given symbol's declaration (or globally if symbol is null).
     *
     * <p>
     * This means that a warning would have been generated were the category not being suppressed.
     *
     * @param symbol innermost {@code @SuppressWarnings}-annotated symbol in scope, or null for global scope
     * @param category lint category to validate
     */
    public void validate(Symbol symbol, LintCategory category) {
        validationsOf(symbol).add(category);
    }

    /**
     * Determine whether the given lint category has been validated within the scope of the given symbol's declaration
     * (or globally if symbol is null).
     *
     * <p>
     * This means that any corresponding suppression is not unnecessary.
     *
     * @param symbol innermost {@code @SuppressWarnings}-annotated symbol in scope, or null for global scope
     * @param category lint category
     * @return true if suppression scoped at the given symbol has been validated
     */
    public boolean isValid(Symbol symbol, LintCategory category) {
        return validationsOf(symbol).contains(category);
    }

    private EnumSet<LintCategory> validationsOf(Symbol symbol) {
        return Optional.ofNullable(symbol)
          .map(sym -> localValidations.computeIfAbsent(sym, sym2 -> LintCategory.newEmptySet()))
          .orElse(globalValidations);
    }

    /**
     * Warn about unnecessary {@code @SuppressWarnings} suppressions within the given tree.
     *
     * <p>
     * This step must be done after the given source file has been warned about.
     *
     * @param log warning destination
     * @param tree source file
     */
    public void reportUnnecessaryAnnotations(Log log, JCTree tree) {
        Assert.check(tree != null);
        initializeIfNeeded();

        // Build a tree of the @SuppressWarnings-annotated declarations in the file
        DeclNode rootNode = declTreeBuilder.build(tree);
        if (rootNode == null)
            return;

        // Copy over the validations we have been notified about to the corresponding tree nodes
        rootNode.stream().forEach(
          node -> Optional.of(node)
            .map(DeclNode::symbol)          // this step will omit the root node
            .map(localValidations::get)
            .ifPresent(node.valid()::addAll));

        // Propagate validations upward, adding any that escape to the global set
        globalValidations.addAll(rootNode.propagateValidations());

        // Warn about the unnecessary suppressions (if any) at each node
        if (rootLint.isActive(SUPPRESSION)) {
            rootNode.stream()
              .filter(DeclNode::shouldReport)               // i.e., "suppression" itself is not suppressed
              .forEach(node -> reportUnnecessary(node.suppressed(), node.valid(), name -> "\"" + name + "\"",
                names -> log.warning(node.pos(), LintWarnings.UnnecessaryWarningSuppression(names))));
        }

        // Discard the declarations we just reported on from the validation map (no longer needed)
        rootNode.stream()
          .skip(1)                          // skip the root node
          .map(DeclNode::symbol)
          .forEach(localValidations::remove);
    }

    /**
     * Warn about unnecessary {@code -Xlint:-key} flags.
     *
     * <p>
     * This step must be done after all source files have been warned about.
     *
     * @param log warning destination
     */
    public void reportUnnecessaryOptions(Log log) {
        initializeIfNeeded();

        // If a file has errors, we may never get a call to reportUnnecessaryAnnotations(),
        // which means validations can get trapped in "localValidations" and never propagate
        // to the global level, causing bogus "suppression-option" warnings. To avoid that,
        // we promote any leftover validations to the global level here.
        localValidations.values().forEach(globalValidations::addAll);

        // Clean up per-file validations (no longer needed)
        localValidations.clear();

        // Report -Xlint:-key suppressions that were never validated (unless "suppression-option" is suppressed)
        if (rootLint.isActive(OPTIONS) && rootLint.isActive(SUPPRESSION_OPTION)) {
            reportUnnecessary(rootLint.getSuppressedOptions(), globalValidations, name -> "-" + name,
              names -> log.warning(LintWarnings.UnnecessaryLintWarningSuppression(names)));
        }

        // Clean up global validations (no longer needed)
        globalValidations.clear();
    }

    private void reportUnnecessary(EnumSet<LintCategory> suppressed,
      EnumSet<LintCategory> valid, Function<String, String> formatter, Consumer<String> logger) {

        // The unnecessary suppressions are the ones that are not validated
        EnumSet<LintCategory> unnecessary = EnumSet.copyOf(suppressed);
        if (valid != null)
            unnecessary.removeAll(valid);

        // Remove categories excluded from suppression checks
        unnecessary.removeIf(lc -> !lc.suppressionTracking);
        if (unnecessary.isEmpty())
            return;

        // Collect the corresponding option names and build and log a warning
        logger.accept(unnecessary.stream()
          .map(category -> category.option)
          .map(formatter)
          .collect(Collectors.joining(", ")));
    }

    // Lazy singleton initialization to avoid dependency loops
    private void initializeIfNeeded() {
        if (rootLint == null) {
            rootLint = Lint.instance(context);
            syms = Symtab.instance(context);
            names = Names.instance(context);
        }
    }

// DeclNode's

    // Holds warning suppression information for a module, package, class, method, or variable declaration
    record DeclNode(
        Symbol symbol,                          // the symbol for the thing declared by the declaration
        DiagnosticPosition pos,                 // location of the declaration's @SuppressWarnings annotation
        EnumSet<LintCategory> suppressed,       // categories suppressed by @SuppressWarnings and @Deprecated
        EnumSet<LintCategory> valid,            // categories validated within the scope of this declaration
        DeclNode parent,                        // this node's parent node
        List<DeclNode> children)                // this node's child nodes
    {

    // Constructors

        // Construct a root node
        public DeclNode() {
            this(null, null, LintCategory.newEmptySet(), LintCategory.newEmptySet(), null);
        }

        // Construct a non-root node
        public DeclNode(Symbol symbol, DiagnosticPosition pos,
          EnumSet<LintCategory> suppressed, EnumSet<LintCategory> valid, DeclNode parent) {
            this(symbol, pos, suppressed, valid, parent, new ArrayList<>());
            Assert.check(pos != null || suppressed.isEmpty());
            if (parent != null)
                parent.children().add(this);
        }

    // Methods

        // Is the "suppression" category itself suppressed at this node or a parent node?
        public boolean shouldReport() {
            return !suppressed().contains(SUPPRESSION) && (parent() == null || parent().shouldReport());
        }

        // Propagate the validations at each subtree node upward until they hit a matching suppression.
        // Return any validations that make it out of this node's subtree without hitting a suppression.
        public EnumSet<LintCategory> propagateValidations() {

            // Recurse on subtrees first
            children().stream()
              .map(DeclNode::propagateValidations)
              .forEach(valid()::addAll);

            // Process unsuppressed validations through this node
            EnumSet<LintCategory> unsuppressed = EnumSet.copyOf(valid());
            unsuppressed.removeAll(suppressed());
            return unsuppressed;
        }

        // Stream this node and all descendents via pre-order recursive descent
        public Stream<DeclNode> stream() {
            return Stream.concat(Stream.of(this), children.stream().flatMap(DeclNode::stream));
        }
    }

    // This builds a tree of DeclNodes. This tree is a subset of the AST that only contains those module,
    // package, class, method, and variable declarations that change the set of suppressed categories.
    class DeclTreeBuilder extends TreeScanner {

        private final HashMap<JCAnnotation, EnumSet<LintCategory>> validMap = new HashMap<>();
        private DeclNode parent;

        DeclNode build(JCTree treeRoot) {
            parent = new DeclNode();
            scan(treeRoot);
            return parent;
        }

    // TreeScanner methods

        @Override
        public void visitModuleDef(JCModuleDecl tree) {
            scanDecl(tree, tree.sym, findSuppressWarnings(tree.mods), super::visitModuleDef);
        }

        @Override
        public void visitPackageDef(JCPackageDecl tree) {
            scanDecl(tree, tree.packge, findSuppressWarnings(tree.annotations), super::visitPackageDef);
        }

        @Override
        public void visitClassDef(JCClassDecl tree) {
            scanDecl(tree, tree.sym, findSuppressWarnings(tree.mods), super::visitClassDef);
        }

        @Override
        public void visitMethodDef(JCMethodDecl tree) {
            scanDecl(tree, tree.sym, findSuppressWarnings(tree.mods), super::visitMethodDef);
        }

        @Override
        public void visitVarDef(JCVariableDecl tree) {
            scanDecl(tree, tree.sym, findSuppressWarnings(tree.mods), super::visitVarDef);
        }

        // Visit a tree node declaring some symbol possibly annotated with @SuppressWarnings and/or @Deprecated
        private <T extends JCTree> void scanDecl(T tree, Symbol symbol, JCAnnotation annotation, Consumer<? super T> recursion) {

            // We only need to create a node here if the set of lint categories suppressed at the given
            // symbol's declaration is non-empty. For this calculation, we also include categories that don't
            // support @SuppressedWarnings because those unnecessary suppressions should be reported as well.
            EnumSet<LintCategory> suppressed = rootLint.suppressionsFrom(symbol, true);
            if (suppressed.isEmpty()) {
                recursion.accept(tree);
                return;
            }

            // Get the lint categories explicitly suppressed at this symbol's declaration by @SuppressedWarnings.
            // We exclude the implicit suppression of DEPRECATION due to @Deprecated because that suppression is
            // never unnecessary. So if we see DEPRECATION, rescan to distinguish which annotation it came from.
            // Note "annotation" can be null here when the symbol has @Deprecated but not @SuppressedWarnings.
            if (suppressed.contains(DEPRECATION)) {             // might have come from @Deprecated, so we need to rescan
                suppressed = Optional.ofNullable(annotation)
                                .map(anno -> rootLint.suppressionsFrom(anno, true))
                                .orElseGet(LintCategory::newEmptySet);
            }

            // Initialize the set of validated categories for the suppressions that are applied at the symbol.
            // Note symbols declared together (separated by commas) share annotations, so they must also share
            // the set of valid categories: i.e., a category is valid if *any* annotated variable validates it.
            EnumSet<LintCategory> valid = Optional.ofNullable(annotation)
                                                .map(a -> validMap.computeIfAbsent(a, a2 -> LintCategory.newEmptySet()))
                                                .orElseGet(LintCategory::newEmptySet);

            // Attach a new child node to the current parent node and recurse
            final DeclNode parentPrev = parent;
            parent = new DeclNode(symbol, annotation, suppressed, valid, parent);
            try {
                recursion.accept(tree);
            } finally {
                parent = parentPrev;
            }
        }

    // Helper methods

        // Retrieve the @SuppressWarnings annotation, if any, from the given modifiers.
        private JCAnnotation findSuppressWarnings(JCModifiers mods) {
            if (mods == null)
                return null;
            return findSuppressWarnings(mods.annotations);
        }

        // Retrieve the @SuppressWarnings annotation, if any, from the given list of annotations.
        private JCAnnotation findSuppressWarnings(com.sun.tools.javac.util.List<JCAnnotation> annotations) {
            if (annotations == null)
                return null;
            return annotations.stream()
              .filter(annotation -> annotation.attribute.type.tsym == syms.suppressWarningsType.tsym)
              .findFirst()
              .orElse(null);
        }
    }
}
