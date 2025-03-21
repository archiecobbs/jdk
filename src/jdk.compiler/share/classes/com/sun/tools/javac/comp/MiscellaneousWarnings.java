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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToIntBiFunction;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.ElementKindVisitor14;

import com.sun.tools.javac.code.Lint.LintCategory;
import com.sun.tools.javac.code.Lint;
import com.sun.tools.javac.code.Scope.LookupKind;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.*;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.jvm.ByteCodes;
import com.sun.tools.javac.resources.CompilerProperties.LintWarnings;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.Assert;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Names;

import static com.sun.tools.javac.code.Flags.*;
import static com.sun.tools.javac.code.Kinds.Kind.*;
import static com.sun.tools.javac.code.TypeTag.*;

/** Generates miscellaneous warnings.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class MiscellaneousWarnings extends TreeScanner {

    protected static final Context.Key<MiscellaneousWarnings> contextKey = new Context.Key<>();

    private final Log log;
    private final Names names;
    private final Operators operators;
    private final Resolve rs;
    private final Symtab syms;
    private final Types types;

    public static MiscellaneousWarnings instance(Context context) {
        MiscellaneousWarnings instance = context.get(contextKey);
        if (instance == null)
            instance = new MiscellaneousWarnings(context);
        return instance;
    }

    @SuppressWarnings("this-escape")
    protected MiscellaneousWarnings(Context context) {
        context.put(contextKey, this);
        log = Log.instance(context);
        names = Names.instance(context);
        operators = Operators.instance(context);
        rs = Resolve.instance(context);
        syms = Symtab.instance(context);
        types = Types.instance(context);
    }

    public void analyze(Env<AttrContext> env) {
        scan(env.tree);
    }

    @Override
    public void visitModuleDef(JCModuleDecl tree) {
        // skip
    }

    @Override
    public void visitClassDef(JCClassDecl tree) {
        checkStrictFp(tree, tree.mods);
        super.visitClassDef(tree);
    }

    @Override
    public void visitMethodDef(JCMethodDecl tree) {
        checkStrictFp(tree, tree.mods);
        super.visitMethodDef(tree);
    }

    @Override
    public void visitTypeCast(JCTypeCast tree) {
        JCExpression expr = TreeInfo.skipParens(tree.expr);
        boolean isPoly = expr.hasTag(Tag.LAMBDA) || expr.hasTag(Tag.REFERENCE);
        if (!isPoly)
            checkRedundantCast(tree);
        super.visitTypeCast(tree);
    }

    @Override
    public void visitSynchronized(JCSynchronized tree) {
        checkSynchronizedValueType(tree);
        super.visitSynchronized(tree);
    }

    @Override
    public void visitSelect(JCFieldAccess tree) {
        checkStaticQualification(tree);
        super.visitSelect(tree);
    }

    @Override
    public void visitAssignop(JCAssignOp tree) {
        Type owntype = tree.lhs.type;
        Type operand = tree.rhs.type;
        if (tree.operator != operators.noOpSymbol &&
                !owntype.isErroneous() &&
                !operand.isErroneous()) {
            checkDivZero(tree.rhs.pos(), tree.operator, operand);
        }
        super.visitAssignop(tree);
    }

    @Override
    public void visitBinary(JCBinary tree) {
        if (tree.operator != operators.noOpSymbol &&
                !tree.lhs.type.isErroneous() &&
                !tree.rhs.type.isErroneous()) {
            checkDivZero(tree.rhs.pos(), tree.operator, tree.rhs.type);
        }
        super.visitBinary(tree);
    }

// Loss of Precision

// Division by Zero

    /**
     *  Check for division by integer constant zero
     *  @param pos           Position for error reporting.
     *  @param operator      The operator for the expression
     *  @param operand       The right hand operand for the expression
     */
    void checkDivZero(final DiagnosticPosition pos, Symbol operator, Type operand) {
        if (operand.constValue() != null
            && operand.getTag().isSubRangeOf(LONG)
            && ((Number) (operand.constValue())).longValue() == 0) {
            int opc = ((OperatorSymbol)operator).opcode;
            if (opc == ByteCodes.idiv || opc == ByteCodes.imod
                || opc == ByteCodes.ldiv || opc == ByteCodes.lmod) {
                log.warnIfEnabled(pos, LintWarnings.DivZero);
            }
        }
    }

// Static Qualification

    private void checkStaticQualification(JCFieldAccess tree) {
        Symbol sym = tree.sym;
        Symbol sitesym = TreeInfo.symbol(tree.selected);
        if (!(sitesym != null && sitesym.kind == Kind.TYP) &&
                sym.kind != Kind.ERR &&
                sym.isStatic() &&
                sym.name != names._class) {
            // If the qualified item is not a type and the selected item is static, report
            // a warning. Make allowance for the class of an array type e.g. Object[].class)
            if (!sym.owner.isAnonymous()) {
                log.warnIfEnabled(tree, LintWarnings.StaticNotQualifiedByType(sym.kind.kindName(), sym.owner));
            } else {
                log.warnIfEnabled(tree, LintWarnings.StaticNotQualifiedByType2(sym.kind.kindName()));
            }
        }
    }

// Synchronization

    private void checkSynchronizedValueType(JCSynchronized tree) {
        if (isValueBased(tree.lock.type)) {
            log.warnIfEnabled(tree.pos(), LintWarnings.AttemptToSynchronizeOnInstanceOfValueBasedClass);
        }
    }

    private boolean isValueBased(Type t) {
        return t != null && t.tsym != null && (t.tsym.flags() & VALUE_BASED) != 0;
    }

// Strict FP

    private void checkStrictFp(JCTree tree, JCModifiers mods) {
        if ((mods.flags & STRICTFP) != 0) {
            log.warnIfEnabled(tree.pos(), LintWarnings.Strictfp);
        }
    }

// Redundant Casts

    private static final boolean ignoreAnnotatedCasts = true;

    /** Check for redundant casts (i.e. where source type is a subtype of target type)
     * The problem should only be reported for non-292 cast
     */
    public void checkRedundantCast(final JCTypeCast tree) {
        if (!tree.type.isErroneous()
                && types.isSameType(tree.expr.type, tree.clazz.type)
                && !(ignoreAnnotatedCasts && TreeInfo.containsTypeAnnotation(tree.clazz))
                && !is292targetTypeCast(tree)) {
            log.warnIfEnabled(tree.pos(), LintWarnings.RedundantCast(tree.clazz.type));
        }
    }

    private boolean is292targetTypeCast(JCTypeCast tree) {
        boolean is292targetTypeCast = false;
        JCExpression expr = TreeInfo.skipParens(tree.expr);
        if (expr.hasTag(Tag.APPLY)) {
            JCMethodInvocation apply = (JCMethodInvocation)expr;
            Symbol sym = TreeInfo.symbol(apply.meth);
            is292targetTypeCast = sym != null &&
                sym.kind == MTH &&
                (sym.flags() & HYPOTHETICAL) != 0;
        }
        return is292targetTypeCast;
    }
}
