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
 * Tracks which @SuppressWarnings and -Xlint:-key warnings suppressions actually suppress something.
 *
 * <p>
 * This tracking is used to implement the SUPPRESSION and SUPPRESSION_OPTION lint warning categories.
 *
 * <p>
 * Lint instances are "augmented" via {@link Lint#augment} when a module, package, class, method, or variable
 * declaration is encountered, and any new warning suppressions gleaned from @SuppressWarnings and/or
 * @Deprecation annotations on the declared symbol are put into effect for the scope of that declaration.
 *
 * <p>
 * In order to know whether the suppression of a lint category actually suppresses any warnings, we need
 * to be notified if a warning that is currently suppressed would have been reported. This is termed the
 * "validation" of the suppression, and that notification happens via {@link #validate}.
 *
 * <p>
 * If a lint category is suppressed but the suppression is never validated, then the suppression is deemed
 * unnecessary and that can trigger a warning in the SUPPRESSION (for @SuppressWarnings) or SUPPRESSION_OPTION
 * (for -Xlint:-key) lint categories. Validation can happen within nested @SuppressWarning scopes, so each
 * validation is "propagated" upward in the AST tree until it hits the first corresponding suppression.
 *
 * <p>
 * After a source file has been fully lint-checked, {@link #reportUnnecessarySuppressWarnings} is
 * invoked to report any unnecessary @SuppressWarnings annotations in that file; these will be the annotations
 * that were never "hit" by any validation.
 *
 * <p>
 * Similarly, after all files have been fully lint-checked and {@link #reportUnnecessarySuppressWarnings}
 * invoked, {@link #reportUnnecessarySuppressOptions} is invoked to report on any unnecessary -Xlint:key flags.
 * These will be the categories for which zero validations "escaped" the per-file propagation process.
 *
 * <p>
 * Additional observations and corner cases:
 * <ul>
 *  <li>Lint warnings can be suppressed at a module, package, class, method, or variable declaration
 *      (via @SuppressWarnings), or globally (via -Xlint:-key).
 *  <li>Consequently, an unnecessary suppression warning can only be emitted at one of those declarations,
 *      or globally at the end of compilation.
 *  <li>Some categories (e.g., CLASSFILE) don't support suppression via @SuppressWarnings, so they can only
 *      generate warnings at the global level; any @SuppressWarnings annotation will be deemed unnecessary.
 *  <li>@SuppressWarnings("suppression") is valid and applies at that declaration: it means unnecessary
 *      suppression warnings will never be reported for any lint category suppressed by that annotation
 *      or any nested annotation within the scope of that annotation.
 *  <li>A few lint categories are not tracked/ignored: options, path, suppression, and suppression-option.
 * </ul>
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class LintSuppression {

    /** The context key for the LintSuppression object. */
    protected static final Context.Key<LintSuppression> lintSuppressionKey = new Context.Key<>();

    // Lint categories validated outside of any class, method, or variable declaration
    private final EnumSet<LintCategory> globalValidations = LintCategory.newEmptySet();

    // Maps @SuppressWarnings-annotated symbols to the lint categories validated in their scope
    private final HashMap<Symbol, EnumSet<LintCategory>> validationMap = new HashMap<>();

    private final Context context;
    private final DeclTreeBuilder declTreeBuilder;

    // These are initialized lazily to avoid dependency loops
    private Lint rootLint;
    private Symtab syms;
    private Names names;

    /** Get the LintSuppression instance. */
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
     * Note that the given lint category has been validated within the scope of the given symbol's declaration
     * (or globally if symbol is null).
     *
     * <p>
     * This means that a warning would have been generated were the category not being suppressed.
     *
     * @param symbol innermost @SuppressWarnings-annotated symbol in scope, or null for global scope
     * @param category lint category to validate
     */
    public void validate(Symbol symbol, LintCategory category) {
        validationsOf(symbol).add(category);
    }

    /**
     * Determine whether the given lint category has been validated within the scope of the given symbol's declaration.
     *
     * @param symbol innermost @SuppressWarnings-annotated symbol in scope, or null for global scope
     * @param category lint category
     * @return true if suppression scoped at the given symbol has been validated
     */
    public boolean isValid(Symbol symbol, LintCategory category) {
        return validationsOf(symbol).contains(category);
    }

    private EnumSet<LintCategory> validationsOf(Symbol symbol) {
        return Optional.ofNullable(symbol)
          .map(sym -> validationMap.computeIfAbsent(sym, sym2 -> LintCategory.newEmptySet()))
          .orElse(globalValidations);
    }

    /**
     * Report unnecessary @SuppressWarnings annotations within the given tree.
     */
    public void reportUnnecessaryAnnotations(Log log, JCTree tree) {
        Assert.check(tree != null);
        initializeIfNeeded();

        // Build a tree of the declarations that have a @SuppressWarnings annotation
        DeclNode rootNode = declTreeBuilder.build(tree);
        if (rootNode == null)
            return;

        // Copy over the validations we have observed to the corresponding tree nodes
        rootNode.stream().forEach(
          node -> Optional.of(node)
            .map(DeclNode::symbol)          // this step will omit the root node
            .map(validationMap::get)
            .ifPresent(node.valid()::addAll));

        // Propagate unsuppressed validations upward, with any leftovers going to the global set
        globalValidations.addAll(rootNode.propagateValidations());

        // Report unnecessary suppressions at each node where "suppression" itself is not suppressed
        if (rootLint.isActive(SUPPRESSION)) {
            rootNode.stream()
              .filter(DeclNode::shouldReport)
              .forEach(node -> reportUnnecessary(node.suppressed(), node.valid(), name -> "\"" + name + "\"",
                names -> log.warning(node.pos(), LintWarnings.UnnecessaryWarningSuppression(names))));
        }

        // Discard the declarations we just reported on from the validation map (no longer needed)
        rootNode.stream()
          .skip(1)                          // skip the root node
          .map(DeclNode::symbol)
          .forEach(validationMap::remove);
    }

    /**
     * Report about extraneous -Xlint:-foo flags.
     *
     * <p>
     * This step must be done last.
     */
    public void reportUnnecessaryOptions(Log log) {
        initializeIfNeeded();

        // For some categories we don't get per-file calls to reportUnnecessarySuppressWarnings(),
        // and so for those categories there can be leftover validations in the validationMap.
        // An example is DANGLING_DOC_COMMENTS. To handle these, we promote any validations
        // that haven't already been picked up to the global level.
        validationMap.values().forEach(globalValidations::addAll);

        // Clean up per-file validations
        validationMap.clear();

        // Report -Xlint:-key suppressions that were never validated
        if (rootLint.isActive(OPTIONS) && rootLint.isActive(SUPPRESSION_OPTION)) {
            reportUnnecessary(rootLint.suppressedOptions, globalValidations, name -> "-" + name,
              names -> log.warning(LintWarnings.UnnecessaryLintWarningSuppression(names)));
        }

        // Clean up global validations
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

        // Gather the corresponding option names and log a warning
        logger.accept(unnecessary.stream()
          .map(category -> category.option)
          .map(formatter)
          .collect(Collectors.joining(", ")));
    }

    private void initializeIfNeeded() {
        if (syms == null) {
            syms = Symtab.instance(context);
            names = Names.instance(context);
            rootLint = Lint.instance(context);
        }
    }

// DeclNode's

    // Holds warning suppression information for a module, package, class, method, or variable declaration
    record DeclNode(
        Symbol symbol,                          // the symbol for the thing declared by the declaration
        DiagnosticPosition pos,                 // location of the declaration's @SuppressWarnings annotation
        EnumSet<LintCategory> suppressed,       // categories suppressed by declaration's @SuppressWarnings
        EnumSet<LintCategory> valid,            // categories validated within the scope of the @SuppressWarnings
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

        // Is the SUPPRESSION category itself suppressed at this node?
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

    // Builds a tree of DeclNodes
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

            // We don't need to create a node here unless Lint.augment(symbol) would have created
            // a new Lint instance here (using symbol as the new "symbolInScope"). That happens when
            // the set of lint categories suppressed at the given symbol's declaration is non-empty.
            EnumSet<LintCategory> suppressed = rootLint.suppressionsFrom(symbol);
            if (suppressed.isEmpty()) {
                recursion.accept(tree);
                return;
            }

            // Get the lint categories explicitly suppressed at this symbol's declaration. These only
            // come from @SuppressedWarnings, i.e., we don't include an entry for @Deprecated even though
            // that annotation has the side effect of suppressing deprecation warnings, because it's never
            // "unnecessary". The annotation can be null here, which means the symbol has only @Deprecated.
            if (suppressed.contains(DEPRECATION)) {             // maybe came from @Deprecated, so need to rescan
                suppressed = Optional.ofNullable(annotation)
                                .map(rootLint::suppressionsFrom)
                                .orElseGet(LintCategory::newEmptySet);
            }

            // Initialize a set of validated categories for the suppressions that are defined at the symbol.
            // Symbols declared together (separated by commas) share annotations, so they must also share the
            // set of valid categories: i.e., a category is valid if *any* annotated variable validates it.
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
