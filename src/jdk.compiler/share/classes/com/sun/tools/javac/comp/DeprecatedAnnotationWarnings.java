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

package com.sun.tools.javac.comp;

import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Kinds;
import com.sun.tools.javac.code.Lint.LintCategory;
import com.sun.tools.javac.code.Lint;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.resources.CompilerProperties.LintWarnings;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import com.sun.tools.javac.util.Log;

/** Generates warnings for problems relating to deprecation annotations.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class DeprecatedAnnotationWarnings extends TreeScanner {

    protected static final Context.Key<DeprecatedAnnotationWarnings> contextKey = new Context.Key<>();

    private final Log log;
    private final Symtab syms;

    public static DeprecatedAnnotationWarnings instance(Context context) {
        DeprecatedAnnotationWarnings instance = context.get(contextKey);
        if (instance == null)
            instance = new DeprecatedAnnotationWarnings(context);
        return instance;
    }

    @SuppressWarnings("this-escape")
    protected DeprecatedAnnotationWarnings(Context context) {
        context.put(contextKey, this);
        log = Log.instance(context);
        syms = Symtab.instance(context);
    }

    public void analyze(Env<AttrContext> env) {
        scan(env.tree);
    }

    @Override
    public void visitModuleDef(JCModuleDecl tree) {
        checkDeprecatedAnnotation(tree.pos(), tree.sym);
        super.visitModuleDef(tree);
    }

    @Override
    public void visitPackageDef(JCPackageDecl tree) {
        checkDeprecatedAnnotation(tree.pid.pos(), tree.packge);
        super.visitPackageDef(tree);
    }

    @Override
    public void visitClassDef(JCClassDecl tree) {
        checkDeprecatedAnnotation(tree.pos(), tree.sym);
        super.visitClassDef(tree);
    }

    @Override
    public void visitMethodDef(JCMethodDecl tree) {
        checkDeprecatedAnnotation(tree.pos(), tree.sym);
        super.visitMethodDef(tree);
    }

    @Override
    public void visitVarDef(JCVariableDecl tree) {
        checkDeprecatedAnnotation(tree.pos(), tree.sym);
        super.visitVarDef(tree);
    }

    protected void checkDeprecatedAnnotation(DiagnosticPosition pos, Symbol s) {
        Lint lint = log.lintAt(pos);
        if (lint.isEnabled(LintCategory.DEP_ANN) && s.isDeprecatableViaAnnotation() &&
            (s.flags() & Flags.DEPRECATED) != 0 &&
            !syms.deprecatedType.isErroneous() &&
            s.attribute(syms.deprecatedType.tsym) == null) {
            log.warnIfEnabled(pos, LintWarnings.MissingDeprecatedAnnotation);
        }
        // Note: @Deprecated has no effect on local variables, parameters and package decls.
        if (lint.isEnabled(LintCategory.DEPRECATION) && !s.isDeprecatableViaAnnotation()) {
            if (!syms.deprecatedType.isErroneous() && s.attribute(syms.deprecatedType.tsym) != null) {
                log.warnIfEnabled(pos, LintWarnings.DeprecatedAnnotationHasNoEffect(Kinds.kindName(s)));
            }
        }
    }
}
