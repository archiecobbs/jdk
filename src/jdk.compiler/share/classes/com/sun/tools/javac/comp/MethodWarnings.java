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
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Names;

import static com.sun.tools.javac.code.Flags.*;
import static com.sun.tools.javac.code.Kinds.Kind.*;
import static com.sun.tools.javac.code.TypeTag.*;

/** Generates warnings for for serialization-related problems.
 *
 *  <p><b>This is NOT part of any supported API.
 *  If you write code that depends on this, you do so at your own risk.
 *  This code and its internal interfaces are subject to change or
 *  deletion without notice.</b>
 */
public class MethodWarnings extends TreeScanner {

    protected static final Context.Key<MethodWarnings> contextKey = new Context.Key<>();

    // Flag bits indicating which item(s) chosen from a pair of items
    private static final int FIRST = 0x01;
    private static final int SECOND = 0x02;

    private final Log log;
    private final Names names;
    private final Resolve rs;
    private final Symtab syms;
    private final Types types;

    public static MethodWarnings instance(Context context) {
        MethodWarnings instance = context.get(contextKey);
        if (instance == null)
            instance = new MethodWarnings(context);
        return instance;
    }

    @SuppressWarnings("this-escape")
    protected MethodWarnings(Context context) {
        context.put(contextKey, this);
        log = Log.instance(context);
        names = Names.instance(context);
        rs = Resolve.instance(context);
        syms = Symtab.instance(context);
        types = Types.instance(context);
    }

    public void analyze(Env<AttrContext> env) {
        scan(env.tree);
    }

    @Override
    public void visitClassDef(JCClassDecl tree) {
        checkPotentiallyAmbiguousOverloads(tree, tree.sym.type);
        checkClassOverrideEqualsAndHashIfNeeded(tree.pos(), tree.sym);
        super.visitClassDef(tree);
    }

// Checks equals() and hashCode()

    private Predicate<Symbol> equalsHasCodeFilter =
      s -> MethodSymbol.implementation_filter.test(s) && (s.flags() & BAD_OVERRIDE) == 0;

    public void checkClassOverrideEqualsAndHashIfNeeded(DiagnosticPosition pos, ClassSymbol someClass) {
        /* At present, annotations cannot possibly have a method that is override
         * equivalent with Object.equals(Object) but in any case the condition is
         * fine for completeness.
         */
        if (someClass == (ClassSymbol)syms.objectType.tsym ||
            someClass.isInterface() || someClass.isEnum() ||
            (someClass.flags() & ANNOTATION) != 0 ||
            (someClass.flags() & ABSTRACT) != 0) return;
        //anonymous inner classes implementing interfaces need especial treatment
        if (someClass.isAnonymous()) {
            List<Type> interfaces =  types.interfaces(someClass.type);
            if (interfaces != null && !interfaces.isEmpty() &&
                interfaces.head.tsym == syms.comparatorType.tsym) return;
        }
        checkClassOverrideEqualsAndHash(pos, someClass);
    }

    private void checkClassOverrideEqualsAndHash(DiagnosticPosition pos, ClassSymbol someClass) {
        if (log.lintAt(pos).isEnabled(LintCategory.OVERRIDES)) {
            MethodSymbol equalsAtObject = (MethodSymbol)syms.objectType
                    .tsym.members().findFirst(names.equals);
            MethodSymbol hashCodeAtObject = (MethodSymbol)syms.objectType
                    .tsym.members().findFirst(names.hashCode);
            MethodSymbol equalsImpl = types.implementation(equalsAtObject,
                    someClass, false, equalsHasCodeFilter);
            boolean overridesEquals = equalsImpl != null &&
                                      equalsImpl.owner == someClass;
            boolean overridesHashCode = types.implementation(hashCodeAtObject,
                someClass, false, equalsHasCodeFilter) != hashCodeAtObject;

            if (overridesEquals && !overridesHashCode) {
                log.warnIfEnabled(pos, LintWarnings.OverrideEqualsButNotHashcode(someClass));
            }
        }
    }

// Potentially Ambiguous Overloads

    /** Report warnings for potentially ambiguous method declarations in the given site. */
    void checkPotentiallyAmbiguousOverloads(JCClassDecl tree, Type site) {

        // Skip if warning not enabled
        Lint lint = log.lintAt(tree.pos());
        if (!lint.isEnabled(LintCategory.OVERLOADS))
            return;

        // Gather all of site's methods, including overridden methods, grouped by name (except Object methods)
        List<java.util.List<MethodSymbol>> methodGroups = methodsGroupedByName(site,
            new PotentiallyAmbiguousFilter(site), ArrayList::new);

        // Build the predicate that determines if site is responsible for an ambiguity
        BiPredicate<MethodSymbol, MethodSymbol> responsible = buildResponsiblePredicate(site, methodGroups);

        // Now remove overridden methods from each group, leaving only site's actual members
        methodGroups.forEach(list -> removePreempted(list, (m1, m2) -> m1.overrides(m2, site.tsym, types, false)));

        // Allow site's own declared methods (only) to apply @SuppressWarnings("overloads")
        methodGroups.forEach(list -> list.removeIf(
            m -> m.owner == site.tsym && !lint.augment(m).isEnabled(LintCategory.OVERLOADS)));

        // Warn about ambiguous overload method pairs for which site is responsible
        methodGroups.forEach(list -> compareAndRemove(list, (m1, m2) -> {

            // See if this is an ambiguous overload for which "site" is responsible
            if (!potentiallyAmbiguousOverload(site, m1, m2) || !responsible.test(m1, m2))
                return 0;

            // Locate the warning at one of the methods, if possible
            DiagnosticPosition pos =
                m1.owner == site.tsym ? TreeInfo.diagnosticPositionFor(m1, tree) :
                m2.owner == site.tsym ? TreeInfo.diagnosticPositionFor(m2, tree) :
                tree.pos();

            // Log the warning
            log.warning(pos,
                LintWarnings.PotentiallyAmbiguousOverload(
                    m1.asMemberOf(site, types), m1.location(),
                    m2.asMemberOf(site, types), m2.location()));

            // Don't warn again for either of these two methods
            return FIRST | SECOND;
        }));
    }

    /** Build a predicate that determines, given two methods that are members of the given class,
     *  whether the class should be held "responsible" if the methods are potentially ambiguous.
     *
     *  Sometimes ambiguous methods are unavoidable because they're inherited from a supertype.
     *  For example, any subtype of Spliterator.OfInt will have ambiguities for both
     *  forEachRemaining() and tryAdvance() (in both cases the overloads are IntConsumer and
     *  Consumer&lt;? super Integer&gt;). So we only want to "blame" a class when that class is
     *  itself responsible for creating the ambiguity. We declare that a class C is "responsible"
     *  for the ambiguity between two methods m1 and m2 if there is no direct supertype T of C
     *  such that m1 and m2, or some overrides thereof, both exist in T and are ambiguous in T.
     *  As an optimization, we first check if either method is declared in C and does not override
     *  any other methods; in this case the class is definitely responsible.
     */
    BiPredicate<MethodSymbol, MethodSymbol> buildResponsiblePredicate(Type site,
        List<? extends Collection<MethodSymbol>> methodGroups) {

        // Define the "overrides" predicate
        BiPredicate<MethodSymbol, MethodSymbol> overrides = (m1, m2) -> m1.overrides(m2, site.tsym, types, false);

        // Map each method declared in site to a list of the supertype method(s) it directly overrides
        HashMap<MethodSymbol, ArrayList<MethodSymbol>> overriddenMethodsMap = new HashMap<>();
        methodGroups.forEach(list -> {
            for (MethodSymbol m : list) {

                // Skip methods not declared in site
                if (m.owner != site.tsym)
                    continue;

                // Gather all supertype methods overridden by m, directly or indirectly
                ArrayList<MethodSymbol> overriddenMethods = list.stream()
                  .filter(m2 -> m2 != m && overrides.test(m, m2))
                  .collect(Collectors.toCollection(ArrayList::new));

                // Eliminate non-direct overrides
                removePreempted(overriddenMethods, overrides);

                // Add to map
                overriddenMethodsMap.put(m, overriddenMethods);
            }
        });

        // Build the predicate
        return (m1, m2) -> {

            // Get corresponding supertype methods (if declared in site)
            java.util.List<MethodSymbol> overriddenMethods1 = overriddenMethodsMap.get(m1);
            java.util.List<MethodSymbol> overriddenMethods2 = overriddenMethodsMap.get(m2);

            // Quick check for the case where a method was added by site itself
            if (overriddenMethods1 != null && overriddenMethods1.isEmpty())
                return true;
            if (overriddenMethods2 != null && overriddenMethods2.isEmpty())
                return true;

            // Get each method's corresponding method(s) from supertypes of site
            java.util.List<MethodSymbol> supertypeMethods1 = overriddenMethods1 != null ?
              overriddenMethods1 : Collections.singletonList(m1);
            java.util.List<MethodSymbol> supertypeMethods2 = overriddenMethods2 != null ?
              overriddenMethods2 : Collections.singletonList(m2);

            // See if we can blame some direct supertype instead
            return types.directSupertypes(site).stream()
              .filter(stype -> stype != syms.objectType)
              .map(stype -> stype.tsym.type)                // view supertype in its original form
              .noneMatch(stype -> {
                for (MethodSymbol sm1 : supertypeMethods1) {
                    if (!types.isSubtype(types.erasure(stype), types.erasure(sm1.owner.type)))
                        continue;
                    for (MethodSymbol sm2 : supertypeMethods2) {
                        if (!types.isSubtype(types.erasure(stype), types.erasure(sm2.owner.type)))
                            continue;
                        if (potentiallyAmbiguousOverload(stype, sm1, sm2))
                            return true;
                    }
                }
                return false;
            });
        };
    }

    /** Gather all of site's methods, including overridden methods, grouped and sorted by name,
     *  after applying the given filter.
     */
    <C extends Collection<MethodSymbol>> List<C> methodsGroupedByName(Type site,
            Predicate<Symbol> filter, Supplier<? extends C> groupMaker) {
        Iterable<Symbol> symbols = types.membersClosure(site, false).getSymbols(filter, LookupKind.RECURSIVE);
        return StreamSupport.stream(symbols.spliterator(), false)
          .map(MethodSymbol.class::cast)
          .collect(Collectors.groupingBy(m -> m.name, Collectors.toCollection(groupMaker)))
          .entrySet()
          .stream()
          .sorted(Comparator.comparing(e -> e.getKey().toString()))
          .map(Map.Entry::getValue)
          .collect(List.collector());
    }

    /** Compare elements in a list pair-wise in order to remove some of them.
     *  @param list mutable list of items
     *  @param comparer returns flag bit(s) to remove FIRST and/or SECOND
     */
    <T> void compareAndRemove(java.util.List<T> list, ToIntBiFunction<? super T, ? super T> comparer) {
        for (int index1 = 0; index1 < list.size() - 1; index1++) {
            T item1 = list.get(index1);
            for (int index2 = index1 + 1; index2 < list.size(); index2++) {
                T item2 = list.get(index2);
                int flags = comparer.applyAsInt(item1, item2);
                if ((flags & SECOND) != 0)
                    list.remove(index2--);          // remove item2
                if ((flags & FIRST) != 0) {
                    list.remove(index1--);          // remove item1
                    break;
                }
            }
        }
    }

    /** Remove elements in a list that are preempted by some other element in the list.
     *  @param list mutable list of items
     *  @param preempts decides if one item preempts another, causing the second one to be removed
     */
    <T> void removePreempted(java.util.List<T> list, BiPredicate<? super T, ? super T> preempts) {
        compareAndRemove(list, (item1, item2) -> {
            int flags = 0;
            if (preempts.test(item1, item2))
                flags |= SECOND;
            if (preempts.test(item2, item1))
                flags |= FIRST;
            return flags;
        });
    }

    /** Filters method candidates for the "potentially ambiguous method" check */
    class PotentiallyAmbiguousFilter extends Check.ClashFilter {

        PotentiallyAmbiguousFilter(Type site) {
            super(MethodWarnings.this.types, site);
        }

        @Override
        boolean shouldSkip(Symbol s) {
            return s.owner.type.tsym == syms.objectType.tsym || super.shouldSkip(s);
        }
    }

    /**
      * Report warnings for potentially ambiguous method declarations. Two declarations
      * are potentially ambiguous if they feature two unrelated functional interface
      * in same argument position (in which case, a call site passing an implicit
      * lambda would be ambiguous). This assumes they already have the same name.
      */
    boolean potentiallyAmbiguousOverload(Type site, MethodSymbol msym1, MethodSymbol msym2) {
        Assert.check(msym1.name == msym2.name);
        if (msym1 == msym2)
            return false;
        Type mt1 = types.memberType(site, msym1);
        Type mt2 = types.memberType(site, msym2);
        //if both generic methods, adjust type variables
        if (mt1.hasTag(FORALL) && mt2.hasTag(FORALL) &&
                types.hasSameBounds((ForAll)mt1, (ForAll)mt2)) {
            mt2 = types.subst(mt2, ((ForAll)mt2).tvars, ((ForAll)mt1).tvars);
        }
        //expand varargs methods if needed
        int maxLength = Math.max(mt1.getParameterTypes().length(), mt2.getParameterTypes().length());
        List<Type> args1 = rs.adjustArgs(mt1.getParameterTypes(), msym1, maxLength, true);
        List<Type> args2 = rs.adjustArgs(mt2.getParameterTypes(), msym2, maxLength, true);
        //if arities don't match, exit
        if (args1.length() != args2.length())
            return false;
        boolean potentiallyAmbiguous = false;
        while (args1.nonEmpty() && args2.nonEmpty()) {
            Type s = args1.head;
            Type t = args2.head;
            if (!types.isSubtype(t, s) && !types.isSubtype(s, t)) {
                if (types.isFunctionalInterface(s) && types.isFunctionalInterface(t) &&
                        types.findDescriptorType(s).getParameterTypes().length() > 0 &&
                        types.findDescriptorType(s).getParameterTypes().length() ==
                        types.findDescriptorType(t).getParameterTypes().length()) {
                    potentiallyAmbiguous = true;
                } else {
                    return false;
                }
            }
            args1 = args1.tail;
            args2 = args2.tail;
        }
        return potentiallyAmbiguous;
    }
}
