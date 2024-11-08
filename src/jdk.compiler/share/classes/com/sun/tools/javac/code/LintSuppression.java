/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
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
import com.sun.tools.javac.resources.CompilerProperties.Warnings;
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
 * Lint instances are "augmented" via Lint.augment() when a module, package, class, method, or variable
 * declaration is encountered, and any new warning suppressions gleaned from @SuppressWarnings and/or
 * @Deprecation annotations on the declared symbol are put into effect for the scope of that declaration.
 *
 * <p>
 * In order to know whether the suppression of a lint category is actually suppressing anything, we need
 * to be notified if a warning that is currently suppressed would have been reported. This is termed
 * the "utilization" of the lint category, and the notification happens via setUtilized().
 *
 * <p>
 * If a lint category is suppressed but never utilized within the scope of that suppression, then the
 * suppression is unnecessary and that can generate a warning in the SUPPRESSION (for @SuppressWarnings)
 * or SUPPRESSION_OPTION (for -Xlint:-key) lint categories. Note that utilization can happen in nested
 * scopes, so each utilization must be "propagated" upward in the AST tree until it meets a corresponding
 * suppression.
 *
 * <p>
 * After a source file has been fully lint-checked, reportExtraneousSuppressWarnings() is invoked to report
 * any unnecessary @SuppressWarnings annotations in that file.
 *
 * <p>
 * Similarly, after all files have been fully lint-checked and reportExtraneousSuppressWarnings() invoked,
 * reportExtraneousLintSuppressions() is invoked to report on any unnecessary -Xlint:key suppressions: these
 * will be those categories for which no corresponding utilization "escaped" the per-file propagation process.
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
 *      suppression warnings will never be reported for any lint category listed in that annotation or any
 *      nested annotation within the scope of that declaration.
 *  <li>@SuppressWarnings("suppression-option") is useless and ignored (just like e.g. @SuppressWarnings("option")).
 * </ul>
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class LintSuppression {

    /** The context key for the LintSuppression object. */
    protected static final Context.Key<LintSuppression> lintSuppressionsKey = new Context.Key<>();

    // Lint categories actually utilized outside of any class, method, or variable declaration
    private final EnumSet<LintCategory> globalUtilizations = LintCategory.newEmptySet();

    // Maps @SuppressWarnings-annotated symbols to the lint categories actually utilized in their scope
    private final HashMap<Symbol, EnumSet<LintCategory>> utilizationMap = new HashMap<>();

    private final Context context;
    private final DeclTreeBuilder declTreeBuilder;

    // These are initialized lazily to avoid dependency loops
    private Lint rootLint;
    private Symtab syms;
    private Names names;

    /** Get the LintSuppression instance. */
    public static LintSuppression instance(Context context) {
        LintSuppression instance = context.get(lintSuppressionsKey);
        if (instance == null)
            instance = new LintSuppression(context);
        return instance;
    }

    private LintSuppression(Context context) {
        this.context = context;
        context.put(lintSuppressionsKey, this);
        declTreeBuilder = new DeclTreeBuilder();
    }

    /**
     * Obtain the set of lint warning categories suppressed at the given symbol's declaration.
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
            suppressions.add(DEPRECATION);
        return suppressions;
    }

    /**
     * Note that the given lint category has been utilized within the scope of the given symbol's declaration
     * (or globally if symbol is null).
     *
     * <p>
     * This means that either a warning has been generated, or it would have been generated were it not being
     * suppressed. The latter case is needed to implement the SUPPRESSION and SUPPRESSION_OPTION lint categories.
     *
     * @param symbol innermost @SuppressWarnings-annotated symbol in scope, or null for global scope
     * @param category lint category that was utilized
     */
    public void setUtilized(Symbol symbol, LintCategory category) {
        utilizationsOf(symbol).add(category);
    }

    /**
     * Determine whether the given lint category has been utilized within the scope of the given symbol's declaration.
     *
     * @param symbol innermost @SuppressWarnings-annotated symbol in scope, or null for global scope
     * @param category lint category that was utilized
     * @return true if {@link #setUtilized} has been called already
     */
    public boolean isUtilized(Symbol symbol, LintCategory category) {
        return utilizationsOf(symbol).contains(category);
    }

    private EnumSet<LintCategory> utilizationsOf(Symbol symbol) {
        return Optional.ofNullable(symbol)
          .map(sym -> utilizationMap.computeIfAbsent(sym, sym2 -> LintCategory.newEmptySet()))
          .orElse(globalUtilizations);
    }

    /**
     * Report unnecessary @SuppressWarnings annotations within the given tree.
     */
    public void reportExtraneousSuppressWarnings(Log log, JCTree tree) {
        Assert.check(tree != null);
        initializeIfNeeded();

        // Build a tree of the declarations that have a @SuppressWarnings annotation
        DeclNode rootNode = declTreeBuilder.build(tree);
        if (rootNode == null)
            return;

        // Copy over the utilizations we have observed to the corresponding tree nodes
        rootNode.stream().forEach(
          node -> Optional.of(node)
            .map(DeclNode::symbol)          // this step will omit the root node
            .map(utilizationMap::get)
            .ifPresent(node.utilized()::addAll));

        // Propagate unsuppressed utilizations upward, with any leftovers going to the global set
        globalUtilizations.addAll(rootNode.propagateUnsuppressedUtilizations());

        // Report unnecessary suppressions at each node where "suppression" itself is not suppressed
        if (rootLint.isActive(SUPPRESSION)) {
            rootNode.stream()
              .filter(DeclNode::shouldReport)
              .forEach(node -> reportUnnecessary(node.suppressed(), node.utilized(), name -> "\"" + name + "\"",
                names -> log.warning(SUPPRESSION, node.pos(), Warnings.UnnecessaryWarningSuppression(names))));
        }

        // Discard the declarations we just reported on from the utilizations map (no longer needed)
        rootNode.stream()
          .skip(1)                          // skip the root node
          .map(DeclNode::symbol)
          .forEach(utilizationMap::remove);
    }

    /**
     * Report about extraneous -Xlint:-foo flags.
     *
     * <p>
     * This step must be done last.
     */
    public void reportExtraneousLintSuppressions(Log log) {
        initializeIfNeeded();

        // For some categories we don't get calls to reportExtraneousSuppressWarnings(), and
        // so for those categories there can be leftover utilizations in the utilizationMap.
        // An example is DANGLING_DOC_COMMENTS. To handle these, we promote any utilizations
        // that haven't already been picked up to the global level.
        utilizationMap.values().forEach(globalUtilizations::addAll);

        // Clean up per-file utilizations
        utilizationMap.clear();

        // Report -Xlint:-key suppressions that were never utilized
        if (rootLint.isActive(OPTIONS) && rootLint.isActive(SUPPRESSION_OPTION)) {
            reportUnnecessary(rootLint.suppressedOptions, globalUtilizations, name -> "-" + name,
              names -> log.warning(SUPPRESSION_OPTION, Warnings.UnnecessaryLintWarningSuppression(names)));
        }

        // Clean up global utilizations
        globalUtilizations.clear();
    }

    private void reportUnnecessary(EnumSet<LintCategory> suppressed,
      EnumSet<LintCategory> utilized, Function<String, String> formatter, Consumer<String> logger) {

        // The unnecessary suppressions are the ones that are not utilized
        EnumSet<LintCategory> unnecessary = EnumSet.copyOf(suppressed);
        if (utilized != null)
            unnecessary.removeAll(utilized);

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

    /**
     * Retrieve the lint categories suppressed by the given @SuppressWarnings annotation.
     *
     * @param annotation @SuppressWarnings annotation, or null
     * @return set of lint categories, possibly empty but never null
     */
    private EnumSet<LintCategory> suppressionsFrom(JCAnnotation annotation) {
        initializeIfNeeded();
        if (annotation == null)
            return LintCategory.newEmptySet();
        Assert.check(annotation.attribute.type.tsym == syms.suppressWarningsType.tsym);
        return suppressionsFrom(Stream.of(annotation).map(anno -> anno.attribute));
    }

    // Find the @SuppressWarnings annotation in the attriubte stream and extract the suppressions
    private EnumSet<LintCategory> suppressionsFrom(Stream<Attribute.Compound> attributes) {
        initializeIfNeeded();
        return attributes
          .filter(attribute -> attribute.type.tsym == syms.suppressWarningsType.tsym)
          .map(attribute -> attribute.member(names.value))
          .flatMap(attribute -> Stream.of(((Attribute.Array)attribute).values))
          .map(Attribute.Constant.class::cast)
          .map(elem -> elem.value)
          .map(String.class::cast)
          .map(LintCategory::get)
          .filter(Objects::nonNull)
          .collect(Collectors.toCollection(LintCategory::newEmptySet));
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
        EnumSet<LintCategory> utilized,         // categories utilized within the scope of the @SuppressWarnings
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
          EnumSet<LintCategory> suppressed, EnumSet<LintCategory> utilized, DeclNode parent) {
            this(symbol, pos, suppressed, utilized, parent, new ArrayList<>());
            Assert.check(pos != null || suppressed.isEmpty());
            if (parent != null)
                parent.children().add(this);
        }

    // Methods

        // Is the SUPPRESSION category itself suppressed at this node?
        public boolean shouldReport() {
            return !suppressed().contains(SUPPRESSION) && (parent() == null || parent().shouldReport());
        }

        // Propagate the utilizations at each subtree node upward until they hit a matching suppression.
        // Return any utilizations that make it out of this node's subtree without hitting any.
        public EnumSet<LintCategory> propagateUnsuppressedUtilizations() {

            // Recurse on subtrees first
            children().stream()
              .map(DeclNode::propagateUnsuppressedUtilizations)
              .forEach(utilized()::addAll);

            // Process unsuppressed utilizations through this node
            EnumSet<LintCategory> unsuppressed = EnumSet.copyOf(utilized());
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

        private final HashMap<JCAnnotation, EnumSet<LintCategory>> utilizedMap = new HashMap<>();
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
            EnumSet<LintCategory> suppressed = suppressionsFrom(symbol);
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
                                .map(LintSuppression.this::suppressionsFrom)
                                .orElseGet(LintCategory::newEmptySet);
            }

            // Initialize a set of utilized categories for the suppressions that are defined at the symbol.
            // Symbols declared together (separated by commas) share annotations, so they must share the set
            // of utilized categories: i.e., a category is utilized if *either* variable utilizes it.
            EnumSet<LintCategory> utilized = Optional.ofNullable(annotation)
                                                .map(a -> utilizedMap.computeIfAbsent(a, a2 -> LintCategory.newEmptySet()))
                                                .orElseGet(LintCategory::newEmptySet);

            // Attach a new child node to the current parent node and recurse
            final DeclNode parentPrev = parent;
            parent = new DeclNode(symbol, annotation, suppressed, utilized, parent);
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
