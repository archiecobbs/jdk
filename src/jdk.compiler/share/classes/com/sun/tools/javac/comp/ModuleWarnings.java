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

import com.sun.tools.javac.code.Directive.RequiresDirective;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Lint.LintCategory;
import com.sun.tools.javac.code.Lint;
import com.sun.tools.javac.resources.CompilerProperties.LintWarnings;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.Assert;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Name;

/** Generates warnings for problems relating to modules.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class ModuleWarnings extends TreeScanner {

    protected static final Context.Key<ModuleWarnings> contextKey = new Context.Key<>();

    private final Log log;
    private final Enter enter;
    private final Modules modules;

    public static ModuleWarnings instance(Context context) {
        ModuleWarnings instance = context.get(contextKey);
        if (instance == null)
            instance = new ModuleWarnings(context);
        return instance;
    }

    @SuppressWarnings("this-escape")
    protected ModuleWarnings(Context context) {
        context.put(contextKey, this);
        log = Log.instance(context);
        enter = Enter.instance(context);
        modules = Modules.instance(context);
    }

    public void analyze(Env<AttrContext> env) {
        if (env.tree.getTag() != Tag.MODULEDEF)
            return;
        scan(env.tree);
    }

    @Override
    public void visitModuleDef(JCModuleDecl tree) {
        if (!log.lintAt(tree.pos()).isEnabled(LintCategory.MODULE))
            return;
        checkModuleName(tree);
        super.visitModuleDef(tree);
    }

    public void checkModuleName(JCModuleDecl tree) {
        Name moduleName = tree.sym.name;
        Assert.checkNonNull(moduleName);
        JCExpression qualId = tree.qualId;
        while (qualId != null) {
            Name componentName;
            DiagnosticPosition pos;
            switch (qualId.getTag()) {
                case SELECT:
                    JCFieldAccess selectNode = ((JCFieldAccess) qualId);
                    componentName = selectNode.name;
                    pos = selectNode.pos();
                    qualId = selectNode.selected;
                    break;
                case IDENT:
                    componentName = ((JCIdent) qualId).name;
                    pos = qualId.pos();
                    qualId = null;
                    break;
                default:
                    throw new AssertionError("Unexpected qualified identifier: " + qualId.toString());
            }
            if (componentName != null) {
                String moduleNameComponentString = componentName.toString();
                int nameLength = moduleNameComponentString.length();
                if (nameLength > 0 && Character.isDigit(moduleNameComponentString.charAt(nameLength - 1))) {
                    log.warnIfEnabled(pos, LintWarnings.PoorChoiceForModuleName(componentName));
                }
            }
        }
    }

    @Override
    public void visitRequires(JCRequires tree) {
        if (tree.directive != null && modules.allModules().contains(tree.directive.module)) {
            checkModuleRequires(tree.moduleName.pos(), tree.directive);
        }
    }

    void checkModuleRequires(final DiagnosticPosition pos, final RequiresDirective rd) {
        if ((rd.module.flags() & Flags.AUTOMATIC_MODULE) != 0) {
            if (rd.isTransitive() && log.lintAt(pos).isEnabled(LintCategory.REQUIRES_TRANSITIVE_AUTOMATIC)) {
                log.warnIfEnabled(pos, LintWarnings.RequiresTransitiveAutomatic);
            } else {
                log.warnIfEnabled(pos, LintWarnings.RequiresAutomatic);
            }
        }
    }
}
