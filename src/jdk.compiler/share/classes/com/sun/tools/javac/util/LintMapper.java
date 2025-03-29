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

package com.sun.tools.javac.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;

import com.sun.tools.javac.code.Lint;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;

/**
 * Maps source code positions to the applicable {@link Lint} instance based on {@link -Xlint}
 * command line flags and {@code @SuppressWarnings} annotations on containing declarations.
 *
 * <p>
 * This mapping can't be cannot be calculated until after attribution. As each top-level
 * declaration (class, package, or module) is attributed, this singleton is notified by
 * Attr and the {@link Lint}s contained in that declaration are calculated.
 *
 * <p><b>This is NOT part of any supported API.
 * If you write code that depends on this, you do so at your own risk.
 * This code and its internal interfaces are subject to change or
 * deletion without notice.</b>
 */
public class LintMapper {

    // The key for the context singleton
    private static final Context.Key<LintMapper> CONTEXT_KEY = new Context.Key<>();

    // Per-source file lint information
    private final Map<JavaFileObject, LintInfo> lintInfoMap = new HashMap<>();

    // Compiler context
    private final Context context;

    // The root Lint instance, calculated on-demand to avoid init loops
    private Lint rootLint;

    /**
     * Obtain the {@link LintMapper} context singleton.
     */
    public static LintMapper instance(Context context) {
        LintMapper instance = context.get(CONTEXT_KEY);
        if (instance == null)
            instance = new LintMapper(context);
        return instance;
    }

    /**
     * Constructor.
     */
    @SuppressWarnings("this-escape")
    protected LintMapper(Context context) {
        context.put(CONTEXT_KEY, this);
        this.context = context;
    }

    private Lint rootLint() {
        if (rootLint == null)
            rootLint = Lint.instance(context);
        return rootLint;
    }

    /**
     * Obtain the {@link Lint} configuration that applies at the given position if known.
     *
     * <p>
     * A {@link Lint} instance will be returned only if both of the following are true:
     * <ul>
     *  <li>The position is contained within some top-level declaration (class, package, or module)
     *  <li>That top-level declaration has had its {@link Lint}s calculated via {@link #calculateLints}
     * </ul>
     * Note: for positions outside of any top-level declaration, the root {@link Lint} applies.
     *
     * @param sourceFile source file
     * @param pos source position
     * @return the applicatble {@link Lint}, or empty if not known
     */
    public Optional<Lint> lintAt(JavaFileObject sourceFile, DiagnosticPosition pos) {
        return Optional.of(sourceFile)
          .map(lintInfoMap::get)
          .flatMap(lintInfo -> lintInfo.findLintSpan(pos))
          .map(LintSpan::lint);
    }

    /**
     * Calculate {@lint Lint} configurations for all positions within the given top-level declaration.
     *
     * @param sourceFile source file
     * @param tree top-level declaration (class, package, or module)
     */
    public void calculateLints(JavaFileObject sourceFile, JCTree tree) {
        Assert.check(tree.getTag() == Tag.MODULEDEF
                  || tree.getTag() == Tag.PACKAGEDEF
                  || tree.getTag() == Tag.CLASSDEF);

        // Create a new entry for this tree in the source file's spanMap
        LintSpan topSpan = new LintSpan(tree, rootLint());
        LintInfo lintInfo = lintInfoMap.computeIfAbsent(sourceFile, LintInfo::new);
        Assert.check(!lintInfoMap.containsKey(topSpan), "duplicate calculateLints()");
        List<LintSpan> lintSpans = new ArrayList<>();
        lintInfo.spanMap.put(topSpan, lintSpans);

        // Populate the list of lints for declarations within the top-level declaration
        new LintCalculator(lintSpans, topSpan.lint).scan(tree);
    }

    /**
     * Reset this instance (except for listeners).
     */
    public void clear() {
        lintInfoMap.clear();
    }

// LintInfo

    /**
     * Holds the calculated {@link Lint}s for top-level declarations in some source file.
     *
     * @param sourceFile the source file (for debug only)
     * @param spanMap top-level declarations and their associated {@link LintSpan}s
     */
    private record LintInfo(JavaFileObject sourceFile, Map<LintSpan, List<LintSpan>> spanMap) {

        LintInfo(JavaFileObject sourceFile) {
            this(sourceFile, new HashMap<>());
        }

        // Find the (innermost) declaration containing the given position with a known lint.
        // If the entry's list is empty, that means the root lint applies to that whole subtree.
        Optional<LintSpan> findLintSpan(DiagnosticPosition pos) {
            return spanMap.entrySet().stream()
              .filter(entry -> entry.getKey().contains(pos))
              .findFirst()
              .map(entry -> bestMatch(entry.getKey(), entry.getValue(), pos));
        }

        static LintSpan bestMatch(LintSpan topSpan, List<LintSpan> lintSpans, DiagnosticPosition pos) {
            int position = pos.getStartPosition();
            Assert.check(position != Position.NOPOS);
            LintSpan bestSpan = null;
            for (LintSpan lintSpan : lintSpans) {
                if (lintSpan.contains(position) && (bestSpan == null || bestSpan.contains(lintSpan))) {
                    bestSpan = lintSpan;
                }
            }
            return bestSpan != null ? bestSpan : topSpan;
        }
    }

// LintSpan

    /**
     * Represents a lexical range and the {@link Lint} configuration that applies to it.
     *
     * @param tag tree node type (for debug only)
     * @param startPos starting position (inclusive)
     * @param endPos ending position (exclusive)
     * @param lint the applicable {@link Lint} configuration
     */
    private record LintSpan(Tag tag, int startPos, int endPos, Lint lint) {

        LintSpan(JCTree tree, Lint lint) {
            this(tree.getTag(), TreeInfo.getStartPos(tree), TreeInfo.endPos(tree), lint);
        }

        boolean contains(int pos) {
            return pos == startPos || (pos > startPos && pos < endPos);
        }

        boolean contains(DiagnosticPosition pos) {
            return contains(pos.getStartPosition());
        }

        boolean contains(LintSpan that) {
            return this.startPos <= that.startPos && this.endPos >= that.endPos;
        }
    }

// LintCalculator

    private static class LintCalculator extends TreeScanner {

        private final List<LintSpan> lintSpans;
        private Lint currentLint;

        LintCalculator(List<LintSpan> lintSpans, Lint rootLint) {
            this.lintSpans = lintSpans;
            this.currentLint = rootLint;
        }

        @Override
        public void visitModuleDef(JCModuleDecl tree) {
            scanDecl(tree, tree.sym, super::visitModuleDef);
        }

        @Override
        public void visitPackageDef(JCPackageDecl tree) {
            scanDecl(tree, tree.packge, super::visitPackageDef);
        }

        @Override
        public void visitClassDef(JCClassDecl tree) {
            scanDecl(tree, tree.sym, super::visitClassDef);
        }

        @Override
        public void visitMethodDef(JCMethodDecl tree) {
            scanDecl(tree, tree.sym, super::visitMethodDef);
        }

        @Override
        public void visitVarDef(JCVariableDecl tree) {
            scanDecl(tree, tree.sym, super::visitVarDef);
        }

        private <T extends JCTree> void scanDecl(T tree, Symbol symbol, Consumer<? super T> recursion) {
            Lint previousLint = currentLint;
            currentLint = Optional.ofNullable(symbol)   // symbol can be null if there were earlier errors
              .map(currentLint::augment)
              .orElse(currentLint);
            recursion.accept(tree);
            if (currentLint != previousLint) {          // Lint.augment() returns the same object if no change
                lintSpans.add(new LintSpan(tree, currentLint));
                currentLint = previousLint;
            }
        }
    }
}
