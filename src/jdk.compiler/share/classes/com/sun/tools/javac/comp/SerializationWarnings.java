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

import java.util.Objects;
import java.util.HashMap;
import java.util.Set;
import java.util.function.BiConsumer;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.ElementKindVisitor14;

import com.sun.tools.javac.code.Kinds.KindSelector;
import com.sun.tools.javac.code.Lint.LintCategory;
import com.sun.tools.javac.code.Lint;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.*;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.*;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.resources.CompilerProperties.LintWarnings;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.JCTree.*;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.tree.TreeScanner;
import com.sun.tools.javac.util.Assert;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JCDiagnostic.DiagnosticPosition;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import com.sun.tools.javac.util.Options;

import static com.sun.tools.javac.code.Flags.*;
import static com.sun.tools.javac.code.Kinds.Kind.*;
import static com.sun.tools.javac.code.TypeTag.*;
import static com.sun.tools.javac.tree.JCTree.Tag.VARDEF;

/** Generates warnings for for serialization-related problems.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class SerializationWarnings extends TreeScanner {

    protected static final Context.Key<SerializationWarnings> contextKey = new Context.Key<>();

    private final Log log;
    private final Names names;
    private final Resolve rs;
    private final Symtab syms;
    private final Types types;
    private final boolean warnOnAnyAccessToMembers;

    private final HashMap<JCTree, Boolean> checkAccessMap = new HashMap<>();

    public static SerializationWarnings instance(Context context) {
        SerializationWarnings instance = context.get(contextKey);
        if (instance == null)
            instance = new SerializationWarnings(context);
        return instance;
    }

    @SuppressWarnings("this-escape")
    protected SerializationWarnings(Context context) {
        context.put(contextKey, this);
        log = Log.instance(context);
        names = Names.instance(context);
        rs = Resolve.instance(context);
        syms = Symtab.instance(context);
        types = Types.instance(context);

        Options options = Options.instance(context);
        warnOnAnyAccessToMembers = options.isSet("warnOnAccessToMembers");
    }

    /**
     * Request that an access check occur during the WARN phase.
     *
     * @param tree the tree node to check
     * @param isLambda if it's a lambda
     */
    public void checkAccessFromSerializableElement(final JCTree tree, boolean isLambda) {
        Assert.check(tree.getTag() == Tag.REFERENCE
                  || tree.getTag() == Tag.SELECT
                  || tree.getTag() == Tag.IDENT);
        checkAccessMap.put(tree, isLambda);
    }

    public void analyze(Env<AttrContext> env) {
        scan(env.tree);
    }

// TreeScanner

    @Override
    public void visitClassDef(JCClassDecl tree) {
        ClassSymbol c = tree.sym;
        if (log.lintAt(tree.pos()).isEnabled(LintCategory.SERIAL)
                && rs.isSerializable(c.type)
                && !c.isAnonymous()) {
            checkSerialStructure(tree, c);
        }
        super.visitClassDef(tree);
    }

    @Override
    public void visitSelect(JCFieldAccess tree) {
        checkAccessFromSerializableElementIfNeeded(tree);
        super.visitSelect(tree);
    }

    @Override
    public void visitReference(JCMemberReference tree) {
        checkAccessFromSerializableElementIfNeeded(tree);
        super.visitReference(tree);
    }

    @Override
    public void visitIdent(JCIdent tree) {
        checkAccessFromSerializableElementIfNeeded(tree);
        super.visitIdent(tree);
    }

    /**
     * Check structure of serialization declarations.
     */
    public void checkSerialStructure(JCClassDecl tree, ClassSymbol c) {
        new SerialTypeVisitor().visit(c, tree);
    }

    /**
     * This visitor will warn if a serialization-related field or
     * method is declared in a suspicious or incorrect way. In
     * particular, it will warn for cases where the runtime
     * serialization mechanism will silently ignore a mis-declared
     * entity.
     *
     * Distinguished serialization-related fields and methods:
     *
     * Methods:
     *
     * private void writeObject(ObjectOutputStream stream) throws IOException
     * ANY-ACCESS-MODIFIER Object writeReplace() throws ObjectStreamException
     *
     * private void readObject(ObjectInputStream stream) throws IOException, ClassNotFoundException
     * private void readObjectNoData() throws ObjectStreamException
     * ANY-ACCESS-MODIFIER Object readResolve() throws ObjectStreamException
     *
     * Fields:
     *
     * private static final long serialVersionUID
     * private static final ObjectStreamField[] serialPersistentFields
     *
     * Externalizable: methods defined on the interface
     * public void writeExternal(ObjectOutput) throws IOException
     * public void readExternal(ObjectInput) throws IOException
     */
    private class SerialTypeVisitor extends ElementKindVisitor14<Void, JCClassDecl> {

        private static final Set<String> serialMethodNames =
            Set.of("writeObject", "writeReplace",
                   "readObject",  "readObjectNoData",
                   "readResolve");

        private static final Set<String> serialFieldNames =
            Set.of("serialVersionUID", "serialPersistentFields");

        // Type of serialPersistentFields
        private final Type OSF_TYPE = new Type.ArrayType(syms.objectStreamFieldType, syms.arrayClass);

        @Override
        public Void defaultAction(Element e, JCClassDecl p) {
            throw new IllegalArgumentException(Objects.requireNonNullElse(e.toString(), ""));
        }

        @Override
        public Void visitType(TypeElement e, JCClassDecl p) {
            runIfNeeded(e, p, (symbol, param) -> super.visitType(symbol, param));
            return null;
        }

        @Override
        public Void visitTypeAsClass(TypeElement e,
                                     JCClassDecl p) {
            // Anonymous classes filtered out by caller.

            ClassSymbol c = (ClassSymbol)e;

            checkCtorAccess(p, c);

            // Check for missing serialVersionUID; check *not* done
            // for enums or records.
            VarSymbol svuidSym = null;
            for (Symbol sym : c.members().getSymbolsByName(names.serialVersionUID)) {
                if (sym.kind == VAR) {
                    svuidSym = (VarSymbol)sym;
                    break;
                }
            }

            if (svuidSym == null) {
                log.warnIfEnabled(p.pos(), LintWarnings.MissingSVUID(c));
            }

            // Check for serialPersistentFields to gate checks for
            // non-serializable non-transient instance fields
            boolean serialPersistentFieldsPresent =
                    c.members()
                     .getSymbolsByName(names.serialPersistentFields, sym -> sym.kind == VAR)
                     .iterator()
                     .hasNext();

            // Check declarations of serialization-related methods and
            // fields
            for(Symbol el : c.getEnclosedElements()) {
                runIfNeeded(el, p, (enclosed, tree) -> {
                    String name = null;
                    switch(enclosed.getKind()) {
                    case FIELD -> {
                        if (!serialPersistentFieldsPresent) {
                            var flags = enclosed.flags();
                            if ( ((flags & TRANSIENT) == 0) &&
                                 ((flags & STATIC) == 0)) {
                                Type varType = enclosed.asType();
                                if (!canBeSerialized(varType)) {
                                    // Note per JLS arrays are
                                    // serializable even if the
                                    // component type is not.
                                    log.warnIfEnabled(TreeInfo.diagnosticPositionFor(enclosed, tree),
                                        LintWarnings.NonSerializableInstanceField);
                                } else if (varType.hasTag(ARRAY)) {
                                    ArrayType arrayType = (ArrayType)varType;
                                    Type elementType = arrayType.elemtype;
                                    while (elementType.hasTag(ARRAY)) {
                                        arrayType = (ArrayType)elementType;
                                        elementType = arrayType.elemtype;
                                    }
                                    if (!canBeSerialized(elementType)) {
                                        log.warnIfEnabled(TreeInfo.diagnosticPositionFor(enclosed, tree),
                                            LintWarnings.NonSerializableInstanceFieldArray(elementType));
                                    }
                                }
                            }
                        }

                        name = enclosed.getSimpleName().toString();
                        if (serialFieldNames.contains(name)) {
                            VarSymbol field = (VarSymbol)enclosed;
                            switch (name) {
                            case "serialVersionUID"       ->  checkSerialVersionUID(tree, e, field);
                            case "serialPersistentFields" ->  checkSerialPersistentFields(tree, e, field);
                            default -> throw new AssertionError();
                            }
                        }
                    }

                    // Correctly checking the serialization-related
                    // methods is subtle. For the methods declared to be
                    // private or directly declared in the class, the
                    // enclosed elements of the class can be checked in
                    // turn. However, writeReplace and readResolve can be
                    // declared in a superclass and inherited. Note that
                    // the runtime lookup walks the superclass chain
                    // looking for writeReplace/readResolve via
                    // Class.getDeclaredMethod. This differs from calling
                    // Elements.getAllMembers(TypeElement) as the latter
                    // will also pull in default methods from
                    // superinterfaces. In other words, the runtime checks
                    // (which long predate default methods on interfaces)
                    // do not admit the possibility of inheriting methods
                    // this way, a difference from general inheritance.

                    // The current implementation just checks the enclosed
                    // elements and does not directly check the inherited
                    // methods. If all the types are being checked this is
                    // less of a concern; however, there are cases that
                    // could be missed. In particular, readResolve and
                    // writeReplace could, in principle, by inherited from
                    // a non-serializable superclass and thus not checked
                    // even if compiled with a serializable child class.
                    case METHOD -> {
                        var method = (MethodSymbol)enclosed;
                        name = method.getSimpleName().toString();
                        if (serialMethodNames.contains(name)) {
                            switch (name) {
                            case "writeObject"      -> checkWriteObject(tree, e, method);
                            case "writeReplace"     -> checkWriteReplace(tree,e, method);
                            case "readObject"       -> checkReadObject(tree,e, method);
                            case "readObjectNoData" -> checkReadObjectNoData(tree, e, method);
                            case "readResolve"      -> checkReadResolve(tree, e, method);
                            default ->  throw new AssertionError();
                            }
                        }
                    }
                    }
                });
            }

            return null;
        }

        boolean canBeSerialized(Type type) {
            return type.isPrimitive() || rs.isSerializable(type);
        }

        /**
         * Check that Externalizable class needs a public no-arg
         * constructor.
         *
         * Check that a Serializable class has access to the no-arg
         * constructor of its first nonserializable superclass.
         */
        private void checkCtorAccess(JCClassDecl tree, ClassSymbol c) {
            if (isExternalizable(c.type)) {
                for(var sym : c.getEnclosedElements()) {
                    if (sym.isConstructor() &&
                        ((sym.flags() & PUBLIC) == PUBLIC)) {
                        if (((MethodSymbol)sym).getParameters().isEmpty()) {
                            return;
                        }
                    }
                }
                log.warnIfEnabled(tree.pos(), LintWarnings.ExternalizableMissingPublicNoArgCtor);
            } else {
                // Approximate access to the no-arg constructor up in
                // the superclass chain by checking that the
                // constructor is not private. This may not handle
                // some cross-package situations correctly.
                Type superClass = c.getSuperclass();
                // java.lang.Object is *not* Serializable so this loop
                // should terminate.
                while (rs.isSerializable(superClass) ) {
                    try {
                        superClass = (Type)((TypeElement)(((DeclaredType)superClass)).asElement()).getSuperclass();
                    } catch(ClassCastException cce) {
                        return ; // Don't try to recover
                    }
                }
                // Non-Serializable superclass
                try {
                    ClassSymbol supertype = ((ClassSymbol)(((DeclaredType)superClass).asElement()));
                    for(var sym : supertype.getEnclosedElements()) {
                        if (sym.isConstructor()) {
                            MethodSymbol ctor = (MethodSymbol)sym;
                            if (ctor.getParameters().isEmpty()) {
                                if (((ctor.flags() & PRIVATE) == PRIVATE) ||
                                    // Handle nested classes and implicit this$0
                                    (supertype.getNestingKind() == NestingKind.MEMBER &&
                                     ((supertype.flags() & STATIC) == 0)))
                                    log.warnIfEnabled(tree.pos(),
                                        LintWarnings.SerializableMissingAccessNoArgCtor(supertype.getQualifiedName()));
                            }
                        }
                    }
                } catch (ClassCastException cce) {
                    return ; // Don't try to recover
                }
                return;
            }
        }

        private void checkSerialVersionUID(JCClassDecl tree, Element e, VarSymbol svuid) {
            // To be effective, serialVersionUID must be marked static
            // and final, but private is recommended. But alas, in
            // practice there are many non-private serialVersionUID
            // fields.
             if ((svuid.flags() & (STATIC | FINAL)) != (STATIC | FINAL)) {
                 log.warnIfEnabled(TreeInfo.diagnosticPositionFor(svuid, tree), LintWarnings.ImproperSVUID((Symbol)e));
             }

             // check svuid has type long
             if (!svuid.type.hasTag(LONG)) {
                 log.warnIfEnabled(TreeInfo.diagnosticPositionFor(svuid, tree), LintWarnings.LongSVUID((Symbol)e));
             }

             if (svuid.getConstValue() == null)
                 log.warnIfEnabled(TreeInfo.diagnosticPositionFor(svuid, tree), LintWarnings.ConstantSVUID((Symbol)e));
        }

        private void checkSerialPersistentFields(JCClassDecl tree, Element e, VarSymbol spf) {
            // To be effective, serialPersisentFields must be private, static, and final.
             if ((spf.flags() & (PRIVATE | STATIC | FINAL)) != (PRIVATE | STATIC | FINAL)) {
                 log.warnIfEnabled(TreeInfo.diagnosticPositionFor(spf, tree), LintWarnings.ImproperSPF);
             }

             if (!types.isSameType(spf.type, OSF_TYPE)) {
                 log.warnIfEnabled(TreeInfo.diagnosticPositionFor(spf, tree), LintWarnings.OSFArraySPF);
             }

            if (isExternalizable((Type)(e.asType()))) {
                log.warnIfEnabled(TreeInfo.diagnosticPositionFor(spf, tree), LintWarnings.IneffectualSerialFieldExternalizable);
            }

            // Warn if serialPersistentFields is initialized to a
            // literal null.
            JCTree spfDecl = TreeInfo.declarationFor(spf, tree);
            if (spfDecl != null && spfDecl.getTag() == VARDEF) {
                JCVariableDecl variableDef = (JCVariableDecl) spfDecl;
                JCExpression initExpr = variableDef.init;
                 if (initExpr != null && TreeInfo.isNull(initExpr)) {
                     log.warnIfEnabled(initExpr.pos(), LintWarnings.SPFNullInit);
                 }
            }
        }

        private void checkWriteObject(JCClassDecl tree, Element e, MethodSymbol method) {
            // The "synchronized" modifier is seen in the wild on
            // readObject and writeObject methods and is generally
            // innocuous.

            // private void writeObject(ObjectOutputStream stream) throws IOException
            checkPrivateNonStaticMethod(tree, method);
            checkReturnType(tree, e, method, syms.voidType);
            checkOneArg(tree, e, method, syms.objectOutputStreamType);
            checkExceptions(tree, e, method, syms.ioExceptionType);
            checkExternalizable(tree, e, method);
        }

        private void checkWriteReplace(JCClassDecl tree, Element e, MethodSymbol method) {
            // ANY-ACCESS-MODIFIER Object writeReplace() throws
            // ObjectStreamException

            // Excluding abstract, could have a more complicated
            // rule based on abstract-ness of the class
            checkConcreteInstanceMethod(tree, e, method);
            checkReturnType(tree, e, method, syms.objectType);
            checkNoArgs(tree, e, method);
            checkExceptions(tree, e, method, syms.objectStreamExceptionType);
        }

        private void checkReadObject(JCClassDecl tree, Element e, MethodSymbol method) {
            // The "synchronized" modifier is seen in the wild on
            // readObject and writeObject methods and is generally
            // innocuous.

            // private void readObject(ObjectInputStream stream)
            //   throws IOException, ClassNotFoundException
            checkPrivateNonStaticMethod(tree, method);
            checkReturnType(tree, e, method, syms.voidType);
            checkOneArg(tree, e, method, syms.objectInputStreamType);
            checkExceptions(tree, e, method, syms.ioExceptionType, syms.classNotFoundExceptionType);
            checkExternalizable(tree, e, method);
        }

        private void checkReadObjectNoData(JCClassDecl tree, Element e, MethodSymbol method) {
            // private void readObjectNoData() throws ObjectStreamException
            checkPrivateNonStaticMethod(tree, method);
            checkReturnType(tree, e, method, syms.voidType);
            checkNoArgs(tree, e, method);
            checkExceptions(tree, e, method, syms.objectStreamExceptionType);
            checkExternalizable(tree, e, method);
        }

        private void checkReadResolve(JCClassDecl tree, Element e, MethodSymbol method) {
            // ANY-ACCESS-MODIFIER Object readResolve()
            // throws ObjectStreamException

            // Excluding abstract, could have a more complicated
            // rule based on abstract-ness of the class
            checkConcreteInstanceMethod(tree, e, method);
            checkReturnType(tree,e, method, syms.objectType);
            checkNoArgs(tree, e, method);
            checkExceptions(tree, e, method, syms.objectStreamExceptionType);
        }

        private void checkWriteExternalRecord(JCClassDecl tree, Element e, MethodSymbol method, boolean isExtern) {
            //public void writeExternal(ObjectOutput) throws IOException
            checkExternMethodRecord(tree, e, method, syms.objectOutputType, isExtern);
        }

        private void checkReadExternalRecord(JCClassDecl tree, Element e, MethodSymbol method, boolean isExtern) {
            // public void readExternal(ObjectInput) throws IOException
            checkExternMethodRecord(tree, e, method, syms.objectInputType, isExtern);
         }

        private void checkExternMethodRecord(JCClassDecl tree, Element e, MethodSymbol method, Type argType,
                                             boolean isExtern) {
            if (isExtern && isExternMethod(tree, e, method, argType)) {
                log.warnIfEnabled(TreeInfo.diagnosticPositionFor(method, tree),
                    LintWarnings.IneffectualExternalizableMethodRecord(method.getSimpleName().toString()));
            }
        }

        void checkPrivateNonStaticMethod(JCClassDecl tree, MethodSymbol method) {
            var flags = method.flags();
            if ((flags & PRIVATE) == 0) {
                log.warnIfEnabled(TreeInfo.diagnosticPositionFor(method, tree),
                    LintWarnings.SerialMethodNotPrivate(method.getSimpleName()));
            }

            if ((flags & STATIC) != 0) {
                log.warnIfEnabled(TreeInfo.diagnosticPositionFor(method, tree),
                    LintWarnings.SerialMethodStatic(method.getSimpleName()));
            }
        }

        /**
         * Per section 1.12 "Serialization of Enum Constants" of
         * the serialization specification, due to the special
         * serialization handling of enums, any writeObject,
         * readObject, writeReplace, and readResolve methods are
         * ignored as are serialPersistentFields and
         * serialVersionUID fields.
         */
        @Override
        public Void visitTypeAsEnum(TypeElement e,
                                    JCClassDecl p) {
            boolean isExtern = isExternalizable((Type)e.asType());
            for(Element el : e.getEnclosedElements()) {
                runIfNeeded(el, p, (enclosed, tree) -> {
                    String name = enclosed.getSimpleName().toString();
                    switch(enclosed.getKind()) {
                    case FIELD -> {
                        var field = (VarSymbol)enclosed;
                        if (serialFieldNames.contains(name)) {
                            log.warnIfEnabled(TreeInfo.diagnosticPositionFor(field, tree),
                                LintWarnings.IneffectualSerialFieldEnum(name));
                        }
                    }

                    case METHOD -> {
                        var method = (MethodSymbol)enclosed;
                        if (serialMethodNames.contains(name)) {
                            log.warnIfEnabled(TreeInfo.diagnosticPositionFor(method, tree),
                                LintWarnings.IneffectualSerialMethodEnum(name));
                        }

                        if (isExtern) {
                            switch(name) {
                            case "writeExternal" -> checkWriteExternalEnum(tree, e, method);
                            case "readExternal"  -> checkReadExternalEnum(tree, e, method);
                            }
                        }
                    }

                    // Also perform checks on any class bodies of enum constants, see JLS 8.9.1.
                    case ENUM_CONSTANT -> {
                        var field = (VarSymbol)enclosed;
                        JCVariableDecl decl = (JCVariableDecl) TreeInfo.declarationFor(field, p);
                        if (decl.init instanceof JCNewClass nc && nc.def != null) {
                            ClassSymbol enumConstantType = nc.def.sym;
                            visitTypeAsEnum(enumConstantType, p);
                        }
                    }

                    }});
            }
            return null;
        }

        private void checkWriteExternalEnum(JCClassDecl tree, Element e, MethodSymbol method) {
            //public void writeExternal(ObjectOutput) throws IOException
            checkExternMethodEnum(tree, e, method, syms.objectOutputType);
        }

        private void checkReadExternalEnum(JCClassDecl tree, Element e, MethodSymbol method) {
             // public void readExternal(ObjectInput) throws IOException
            checkExternMethodEnum(tree, e, method, syms.objectInputType);
         }

        private void checkExternMethodEnum(JCClassDecl tree, Element e, MethodSymbol method, Type argType) {
            if (isExternMethod(tree, e, method, argType)) {
                log.warnIfEnabled(TreeInfo.diagnosticPositionFor(method, tree),
                    LintWarnings.IneffectualExternMethodEnum(method.getSimpleName().toString()));
            }
        }

        private boolean isExternMethod(JCClassDecl tree, Element e, MethodSymbol method, Type argType) {
            long flags = method.flags();
            Type rtype = method.getReturnType();

            // Not necessary to check throws clause in this context
            return (flags & PUBLIC) != 0 && (flags & STATIC) == 0 &&
                types.isSameType(syms.voidType, rtype) &&
                hasExactlyOneArgWithType(tree, e, method, argType);
        }

        /**
         * Most serialization-related fields and methods on interfaces
         * are ineffectual or problematic.
         */
        @Override
        public Void visitTypeAsInterface(TypeElement e,
                                         JCClassDecl p) {
            for(Element el : e.getEnclosedElements()) {
                runIfNeeded(el, p, (enclosed, tree) -> {
                    String name = null;
                    switch(enclosed.getKind()) {
                    case FIELD -> {
                        var field = (VarSymbol)enclosed;
                        name = field.getSimpleName().toString();
                        switch(name) {
                        case "serialPersistentFields" -> {
                            log.warnIfEnabled(TreeInfo.diagnosticPositionFor(field, tree),
                                LintWarnings.IneffectualSerialFieldInterface);
                        }

                        case "serialVersionUID" -> {
                            checkSerialVersionUID(tree, e, field);
                        }
                        }
                    }

                    case METHOD -> {
                        var method = (MethodSymbol)enclosed;
                        name = enclosed.getSimpleName().toString();
                        if (serialMethodNames.contains(name)) {
                            switch (name) {
                            case
                                "readObject",
                                "readObjectNoData",
                                "writeObject"      -> checkPrivateMethod(tree, e, method);

                            case
                                "writeReplace",
                                "readResolve"      -> checkDefaultIneffective(tree, e, method);

                            default ->  throw new AssertionError();
                            }

                        }
                    }}
                });
            }

            return null;
        }

        private void checkPrivateMethod(JCClassDecl tree,
                                        Element e,
                                        MethodSymbol method) {
            if ((method.flags() & PRIVATE) == 0) {
                log.warnIfEnabled(TreeInfo.diagnosticPositionFor(method, tree), LintWarnings.NonPrivateMethodWeakerAccess);
            }
        }

        private void checkDefaultIneffective(JCClassDecl tree,
                                             Element e,
                                             MethodSymbol method) {
            if ((method.flags() & DEFAULT) == DEFAULT) {
                log.warnIfEnabled(TreeInfo.diagnosticPositionFor(method, tree), LintWarnings.DefaultIneffective);

            }
        }

        @Override
        public Void visitTypeAsAnnotationType(TypeElement e,
                                              JCClassDecl p) {
            // Per the JLS, annotation types are not serializeable
            return null;
        }

        /**
         * From the Java Object Serialization Specification, 1.13
         * Serialization of Records:
         *
         * "The process by which record objects are serialized or
         * externalized cannot be customized; any class-specific
         * writeObject, readObject, readObjectNoData, writeExternal,
         * and readExternal methods defined by record classes are
         * ignored during serialization and deserialization. However,
         * a substitute object to be serialized or a designate
         * replacement may be specified, by the writeReplace and
         * readResolve methods, respectively. Any
         * serialPersistentFields field declaration is
         * ignored. Documenting serializable fields and data for
         * record classes is unnecessary, since there is no variation
         * in the serial form, other than whether a substitute or
         * replacement object is used. The serialVersionUID of a
         * record class is 0L unless explicitly declared. The
         * requirement for matching serialVersionUID values is waived
         * for record classes."
         */
        @Override
        public Void visitTypeAsRecord(TypeElement e,
                                      JCClassDecl p) {
            boolean isExtern = isExternalizable((Type)e.asType());
            for(Element el : e.getEnclosedElements()) {
                runIfNeeded(el, p, (enclosed, tree) -> {
                    String name = enclosed.getSimpleName().toString();
                    switch(enclosed.getKind()) {
                    case FIELD -> {
                        var field = (VarSymbol)enclosed;
                        switch(name) {
                        case "serialPersistentFields" -> {
                            log.warnIfEnabled(TreeInfo.diagnosticPositionFor(field, tree),
                                LintWarnings.IneffectualSerialFieldRecord);
                        }

                        case "serialVersionUID" -> {
                            // Could generate additional warning that
                            // svuid value is not checked to match for
                            // records.
                            checkSerialVersionUID(tree, e, field);
                        }}
                    }

                    case METHOD -> {
                        var method = (MethodSymbol)enclosed;
                        switch(name) {
                        case "writeReplace" -> checkWriteReplace(tree, e, method);
                        case "readResolve"  -> checkReadResolve(tree, e, method);

                        case "writeExternal" -> checkWriteExternalRecord(tree, e, method, isExtern);
                        case "readExternal"  -> checkReadExternalRecord(tree, e, method, isExtern);

                        default -> {
                            if (serialMethodNames.contains(name)) {
                                log.warnIfEnabled(TreeInfo.diagnosticPositionFor(method, tree),
                                    LintWarnings.IneffectualSerialMethodRecord(name));
                            }
                        }}
                    }}});
            }
            return null;
        }

        void checkConcreteInstanceMethod(JCClassDecl tree,
                                         Element enclosing,
                                         MethodSymbol method) {
            if ((method.flags() & (STATIC | ABSTRACT)) != 0) {
                    log.warnIfEnabled(TreeInfo.diagnosticPositionFor(method, tree),
                        LintWarnings.SerialConcreteInstanceMethod(method.getSimpleName()));
            }
        }

        private void checkReturnType(JCClassDecl tree,
                                     Element enclosing,
                                     MethodSymbol method,
                                     Type expectedReturnType) {
            // Note: there may be complications checking writeReplace
            // and readResolve since they return Object and could, in
            // principle, have covariant overrides and any synthetic
            // bridge method would not be represented here for
            // checking.
            Type rtype = method.getReturnType();
            if (!types.isSameType(expectedReturnType, rtype)) {
                log.warnIfEnabled(TreeInfo.diagnosticPositionFor(method, tree),
                    LintWarnings.SerialMethodUnexpectedReturnType(method.getSimpleName(), rtype, expectedReturnType));
            }
        }

        private void checkOneArg(JCClassDecl tree,
                                 Element enclosing,
                                 MethodSymbol method,
                                 Type expectedType) {
            String name = method.getSimpleName().toString();

            var parameters= method.getParameters();

            if (parameters.size() != 1) {
                log.warnIfEnabled(TreeInfo.diagnosticPositionFor(method, tree),
                    LintWarnings.SerialMethodOneArg(method.getSimpleName(), parameters.size()));
                return;
            }

            Type parameterType = parameters.get(0).asType();
            if (!types.isSameType(parameterType, expectedType)) {
                log.warnIfEnabled(TreeInfo.diagnosticPositionFor(method, tree),
                    LintWarnings.SerialMethodParameterType(method.getSimpleName(), expectedType, parameterType));
            }
        }

        private boolean hasExactlyOneArgWithType(JCClassDecl tree,
                                                 Element enclosing,
                                                 MethodSymbol method,
                                                 Type expectedType) {
            var parameters = method.getParameters();
            return (parameters.size() == 1) &&
                types.isSameType(parameters.get(0).asType(), expectedType);
        }


        private void checkNoArgs(JCClassDecl tree, Element enclosing, MethodSymbol method) {
            var parameters = method.getParameters();
            if (!parameters.isEmpty()) {
                log.warnIfEnabled(TreeInfo.diagnosticPositionFor(parameters.get(0), tree),
                    LintWarnings.SerialMethodNoArgs(method.getSimpleName()));
            }
        }

        private void checkExternalizable(JCClassDecl tree, Element enclosing, MethodSymbol method) {
            // If the enclosing class is externalizable, warn for the method
            if (isExternalizable((Type)enclosing.asType())) {
                log.warnIfEnabled(TreeInfo.diagnosticPositionFor(method, tree),
                    LintWarnings.IneffectualSerialMethodExternalizable(method.getSimpleName()));
            }
            return;
        }

        private void checkExceptions(JCClassDecl tree,
                                     Element enclosing,
                                     MethodSymbol method,
                                     Type... declaredExceptions) {
            for (Type thrownType: method.getThrownTypes()) {
                // For each exception in the throws clause of the
                // method, if not an Error and not a RuntimeException,
                // check if the exception is a subtype of a declared
                // exception from the throws clause of the
                // serialization method in question.
                if (types.isSubtype(thrownType, syms.runtimeExceptionType) ||
                    types.isSubtype(thrownType, syms.errorType) ) {
                    continue;
                } else {
                    boolean declared = false;
                    for (Type declaredException : declaredExceptions) {
                        if (types.isSubtype(thrownType, declaredException)) {
                            declared = true;
                            continue;
                        }
                    }
                    if (!declared) {
                        log.warnIfEnabled(TreeInfo.diagnosticPositionFor(method, tree),
                            LintWarnings.SerialMethodUnexpectedException(method.getSimpleName(),
                                                                             thrownType));
                    }
                }
            }
            return;
        }

        private <E extends Element> void runIfNeeded(E symbol, JCClassDecl p, BiConsumer<E, JCClassDecl> task) {
            if (log.lintAt(p.pos()).augment((Symbol)symbol).isEnabled(LintCategory.SERIAL)) {
                task.accept(symbol, p);
            }
        }
    }

    /** check if a type is a subtype of Externalizable, if that is available. */
    private boolean isExternalizable(Type t) {
        try {
            syms.externalizableType.complete();
        } catch (CompletionFailure e) {
            return false;
        }
        return types.isSubtype(t, syms.externalizableType);
    }

// Checks for serializable things that will be publicly accessible to untrusted code

    // Apply special flag "-XDwarnOnAccessToMembers" which turns on just this particular warning for all types of access
    private void checkAccessFromSerializableElementIfNeeded(final JCTree tree) {
        Boolean isLambda = checkAccessMap.remove(tree);
        if (isLambda != null) {
            if (warnOnAnyAccessToMembers || (isLambda && log.lintAt(tree.pos()).isEnabled(LintCategory.SERIAL))) {
                checkAccessFromSerializableElementInner(tree, isLambda);
            }
        }
    }

    private void checkAccessFromSerializableElementInner(final JCTree tree, boolean isLambda) {
        Symbol sym = TreeInfo.symbol(tree);
        if (!sym.kind.matches(KindSelector.VAL_MTH)) {
            return;
        }

        if (sym.kind == VAR) {
            if ((sym.flags() & PARAMETER) != 0 ||
                sym.isDirectlyOrIndirectlyLocal() ||
                sym.name == names._this ||
                sym.name == names._super) {
                return;
            }
        }

        if (!types.isSubtype(sym.owner.type, syms.serializableType) && isEffectivelyNonPublic(sym)) {
            if (isLambda) {
                if (belongsToRestrictedPackage(sym)) {
                    log.warning(tree.pos(), LintWarnings.AccessToMemberFromSerializableLambda(sym));
                }
            } else {
                log.warning(tree.pos(), LintWarnings.AccessToMemberFromSerializableElement(sym));
            }
        }
    }

    private boolean isEffectivelyNonPublic(Symbol sym) {
        if (sym.packge() == syms.rootPackage) {
            return false;
        }

        while (sym.kind != PCK) {
            if ((sym.flags() & PUBLIC) == 0) {
                return true;
            }
            sym = sym.owner;
        }
        return false;
    }

    private boolean belongsToRestrictedPackage(Symbol sym) {
        String fullName = sym.packge().fullname.toString();
        return fullName.startsWith("java.") ||
                fullName.startsWith("javax.") ||
                fullName.startsWith("sun.") ||
                fullName.contains(".internal.");
    }
}
