/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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

/**
 * <h2>Provides support for typestamps.</h2>
 *
 * <h2>Typestamps as User-Defined Type Restriction</h2>
 *
 * <p>
 * A <em>typestamp annotation</em> is a Java type annotation that is itself annotated with the
 * {@link TypeStamp} meta-annotation, which specifies a way to check whether values satisfy
 * some user-defined predicate. The presence of a typestamp annotation in a Java source file
 * (called a <em>typestamp</em>) represents the assertion that the predicate is true for the
 * corresponding values. A typestamp is like an extra "stamp of approval" on the Java type,
 * indicating that the additional check(s) have been performed beyond those provided by Java's
 * normal typing rules.
 *
 * <p>
 * This is a form of <em>type restriction</em>, i.e., using a Java type to respresent values
 * which are restricted to a subset of the type's full range. For example, telephone numbers
 * are often represented using {@link String}, but not every {@link String} is a valid telephone
 * number. Therefore, programmers must explicitly validate telephone numbers coming from outside
 * the application, and manually keep track of which {@link String} telephone number variables
 * within the application have been validated and which haven't.
 *
 * <p>
 * Typestamps provide a way to offload this work to the compiler. The compiler recognizes the
 * {@link TypeStamp} meta-annotation and enables additional static and runtime checks, effectively
 * creating a lightweight subtype of the annotated type and then doing normal type checking on it.
 * The compiler issues unchecked warnings for any assignments of an "unstamped" value to a typestamped
 * type. Such warnings can be addressed using an explicit cast, which will result in the compiler
 * inserting the corresponding runtime check. Warnings may also be suppressed via @{@link SuppressWarnings}
 * if it is known <i>a priori</i> that the predicate is true. In this sense, the compiler guarantees
 * typestamps similarly to how it enforces generic types: as long as the compiler generates no
 * "unchecked" warnings, and the programmer takes responsibility for any suppressed warnings,
 * a successful compilation ensures all typestamped values will actually satisfy the typestamp's
 * predicate at runtime.
 *
 * <p>
 * Examples of type checking and casting:
 * <pre><code class="language-java">
 *  String input = scanner.nextLine();
 *  &#64;PhoneNumber String phoneNumber;
 *  phoneNumber = input;                            // unchecked warning
 *  phoneNumber = (&#64;PhoneNumber String)input    // ok, but may throw ClassCastException
 * </code></pre>
 *
 * <p>
 * The compiler also inserts runtime checks for {@code instanceof}:
 * <pre><code class="language-java">
 *  String input = scanner.nextLine();
 *  if (input instanceof &#64;PhoneNumber String phoneNumber)
 *      ...                 // we now know "phoneNumber" is valid
 * </code></pre>
 *
 * <h2>Defining Typestamp Annotations</h2>
 *
 * <p>
 * Typestamp annotations specify a {@link TypeStampChecker} class that implements the user-defined
 * predicate logic. Here's an example of how a {@code &#64;PhoneNumber} typestamp might be defined:
 *
 * <pre><code class="language-java">
 *  &#47;**
 *   * Annotates declarations of type {&#64;link String} for which
 *   * the value, if non-null, is a valid E.164 phone number.
 *   *
 *   * &lt;a href="https://en.wikipedia.org/wiki/E.164"&gt;E.164&lt;/a&gt;
 *   *&#47;
 *  &#64;Retention(RetentionPolicy.RUNTIME)
 *  &#64;Target(ElementType.TYPE_USE)
 *  &#64;TypeStamp(restrictTo = String.class, checkedBy = PhoneNumberChecker.class)
 *  public &#64;interface PhoneNumber {
 *  }
 *
 *  // Checker for the &#64;PhoneNumber annotation
 *  public static class PhoneNumberChecker implements TypeStampChecker {
 *
 *      // E.164 format
 *      public static final String PATTERN = "\\+[1-9][0-9]{6,14}";
 *
 *      &#64;Override
 *      public void check(Class&gt;? extends Annotation&gt; annotationType, Object value) {
 *          if (string != null &amp;&amp; !((String)value).matches(PhoneNumber.PATTERN))
 *              throw new ClassCastException("not a valid E.164 phone number");
 *      }
 *  }
 * </code></pre>
 */
package java.lang.annotation.typestamp;
