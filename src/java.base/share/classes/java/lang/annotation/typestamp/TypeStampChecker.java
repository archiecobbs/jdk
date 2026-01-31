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

package java.lang.annotation.typestamp;

import java.lang.annotation.Annotation;

/**
 * Implemented by classes capable of runtime checks of values whose types are annotated with
 * a {@link TypeStamp &#64;TypeStamp} meta-annotated annotation.
 *
 * <p>
 * Implementations need only override the {@code check()} method(s) corresponding to supported
 * value types.
 */
public interface TypeStampChecker {

// "check" methods

    /**
     * Checks that the given value satisfies the given {@link TypeStamp &#64;TypeStamp}'s predicate.
     *
     * <p>
     * Invalid values must trigger a {@link ClassCastException}.
     *
     * @param annotationType {@link TypeStamp &#64;TypeStamp} meta-annotated annotation type
     * @param value the value to check
     * @throws ClassCastException if {@code value} is invalid
     * @throws NullPointerException if {@code annotationType} is null (possibly)
     */
    default void check(Class<? extends Annotation> annotationType, boolean value) {
        throw new ClassCastException("unsupported type: boolean");
    }

    /**
     * Checks that the given value satisfies the given {@link TypeStamp &#64;TypeStamp}'s predicate.
     *
     * <p>
     * Invalid values must trigger a {@link ClassCastException}.
     *
     * @param annotationType {@link TypeStamp &#64;TypeStamp} meta-annotated annotation type
     * @param value the value to check
     * @throws ClassCastException if {@code value} is invalid
     * @throws NullPointerException if {@code annotationType} is null (possibly)
     */
    default void check(Class<? extends Annotation> annotationType, byte value) {
        throw new ClassCastException("unsupported type: byte");
    }

    /**
     * Checks that the given value satisfies the given {@link TypeStamp &#64;TypeStamp}'s predicate.
     *
     * <p>
     * Invalid values must trigger a {@link ClassCastException}.
     *
     * @param annotationType {@link TypeStamp &#64;TypeStamp} meta-annotated annotation type
     * @param value the value to check
     * @throws ClassCastException if {@code value} is invalid
     * @throws NullPointerException if {@code annotationType} is null (possibly)
     */
    default void check(Class<? extends Annotation> annotationType, char value) {
        throw new ClassCastException("unsupported type: char");
    }

    /**
     * Checks that the given value satisfies the given {@link TypeStamp &#64;TypeStamp}'s predicate.
     *
     * <p>
     * Invalid values must trigger a {@link ClassCastException}.
     *
     * @param annotationType {@link TypeStamp &#64;TypeStamp} meta-annotated annotation type
     * @param value the value to check
     * @throws ClassCastException if {@code value} is invalid
     * @throws NullPointerException if {@code annotationType} is null (possibly)
     */
    default void check(Class<? extends Annotation> annotationType, short value) {
        throw new ClassCastException("unsupported type: short");
    }

    /**
     * Checks that the given value satisfies the given {@link TypeStamp &#64;TypeStamp}'s predicate.
     *
     * <p>
     * Invalid values must trigger a {@link ClassCastException}.
     *
     * @param annotationType {@link TypeStamp &#64;TypeStamp} meta-annotated annotation type
     * @param value the value to check
     * @throws ClassCastException if {@code value} is invalid
     * @throws NullPointerException if {@code annotationType} is null (possibly)
     */
    default void check(Class<? extends Annotation> annotationType, int value) {
        throw new ClassCastException("unsupported type: int");
    }

    /**
     * Checks that the given value satisfies the given {@link TypeStamp &#64;TypeStamp}'s predicate.
     *
     * <p>
     * Invalid values must trigger a {@link ClassCastException}.
     *
     * @param annotationType {@link TypeStamp &#64;TypeStamp} meta-annotated annotation type
     * @param value the value to check
     * @throws ClassCastException if {@code value} is invalid
     * @throws NullPointerException if {@code annotationType} is null (possibly)
     */
    default void check(Class<? extends Annotation> annotationType, float value) {
        throw new ClassCastException("unsupported type: float");
    }

    /**
     * Checks that the given value satisfies the given {@link TypeStamp &#64;TypeStamp}'s predicate.
     *
     * <p>
     * Invalid values must trigger a {@link ClassCastException}.
     *
     * @param annotationType {@link TypeStamp &#64;TypeStamp} meta-annotated annotation type
     * @param value the value to check
     * @throws ClassCastException if {@code value} is invalid
     * @throws NullPointerException if {@code annotationType} is null (possibly)
     */
    default void check(Class<? extends Annotation> annotationType, long value) {
        throw new ClassCastException("unsupported type: long");
    }

    /**
     * Checks that the given value satisfies the given {@link TypeStamp &#64;TypeStamp}'s predicate.
     *
     * <p>
     * Invalid values must trigger a {@link ClassCastException}.
     *
     * @param annotationType {@link TypeStamp &#64;TypeStamp} meta-annotated annotation type
     * @param value the value to check
     * @throws ClassCastException if {@code value} is invalid
     * @throws NullPointerException if {@code annotationType} is null (possibly)
     */
    default void check(Class<? extends Annotation> annotationType, double value) {
        throw new ClassCastException("unsupported type: double");
    }

    /**
     * Checks that the given value satisfies the given {@link TypeStamp &#64;TypeStamp}'s predicate.
     *
     * <p>
     * Invalid values must trigger a {@link ClassCastException}.
     *
     * <p>
     * Whether or not this method admits null values is up to the implementation.
     *
     * @param annotationType {@link TypeStamp &#64;TypeStamp} meta-annotated annotation type
     * @param value the value to check
     * @throws ClassCastException if {@code value} is invalid
     * @throws NullPointerException if {@code annotationType} is null (possibly)
     */
    default void check(Class<? extends Annotation> annotationType, Object value) {
        throw new ClassCastException("unsupported type: reference");
    }

// "isValid" methods

    /**
     * Determine whether the given value satisfies the given {@link TypeStamp &#64;TypeStamp}'s predicate.
     *
     * <p>
     * The implementation in {@link TypeStampChecker} invokes {@link #check(Class, boolean)}
     * and returns false if that method throws a {@link ClassCastException}, otherwise true.
     *
     * @param annotationType {@link TypeStamp &#64;TypeStamp} meta-annotated annotation type
     * @param value the value to check
     * @return true if {@code value} is valid for {@code annotationType}, otherwise false
     * @throws NullPointerException if {@code annotationType} is null (possibly)
     */
    default boolean isValid(Class<? extends Annotation> annotationType, boolean value) {
        try {
            check(annotationType, value);
        } catch (ClassCastException e) {
            return false;
        }
        return true;
    }

    /**
     * Determine whether the given value satisfies the given {@link TypeStamp &#64;TypeStamp}'s predicate.
     *
     * <p>
     * The implementation in {@link TypeStampChecker} invokes {@link #check(Class, byte)}
     * and returns false if that method throws a {@link ClassCastException}, otherwise true.
     *
     * @param annotationType {@link TypeStamp &#64;TypeStamp} meta-annotated annotation type
     * @param value the value to check
     * @return true if {@code value} is valid for {@code annotationType}, otherwise false
     * @throws NullPointerException if {@code annotationType} is null (possibly)
     */
    default boolean isValid(Class<? extends Annotation> annotationType, byte value) {
        try {
            check(annotationType, value);
        } catch (ClassCastException e) {
            return false;
        }
        return true;
    }

    /**
     * Determine whether the given value satisfies the given {@link TypeStamp &#64;TypeStamp}'s predicate.
     *
     * <p>
     * The implementation in {@link TypeStampChecker} invokes {@link #check(Class, char)}
     * and returns false if that method throws a {@link ClassCastException}, otherwise true.
     *
     * @param annotationType {@link TypeStamp &#64;TypeStamp} meta-annotated annotation type
     * @param value the value to check
     * @return true if {@code value} is valid for {@code annotationType}, otherwise false
     * @throws NullPointerException if {@code annotationType} is null (possibly)
     */
    default boolean isValid(Class<? extends Annotation> annotationType, char value) {
        try {
            check(annotationType, value);
        } catch (ClassCastException e) {
            return false;
        }
        return true;
    }

    /**
     * Determine whether the given value satisfies the given {@link TypeStamp &#64;TypeStamp}'s predicate.
     *
     * <p>
     * The implementation in {@link TypeStampChecker} invokes {@link #check(Class, short)}
     * and returns false if that method throws a {@link ClassCastException}, otherwise true.
     *
     * @param annotationType {@link TypeStamp &#64;TypeStamp} meta-annotated annotation type
     * @param value the value to check
     * @return true if {@code value} is valid for {@code annotationType}, otherwise false
     * @throws NullPointerException if {@code annotationType} is null (possibly)
     */
    default boolean isValid(Class<? extends Annotation> annotationType, short value) {
        try {
            check(annotationType, value);
        } catch (ClassCastException e) {
            return false;
        }
        return true;
    }

    /**
     * Determine whether the given value satisfies the given {@link TypeStamp &#64;TypeStamp}'s predicate.
     *
     * <p>
     * The implementation in {@link TypeStampChecker} invokes {@link #check(Class, int)}
     * and returns false if that method throws a {@link ClassCastException}, otherwise true.
     *
     * @param annotationType {@link TypeStamp &#64;TypeStamp} meta-annotated annotation type
     * @param value the value to check
     * @return true if {@code value} is valid for {@code annotationType}, otherwise false
     * @throws NullPointerException if {@code annotationType} is null (possibly)
     */
    default boolean isValid(Class<? extends Annotation> annotationType, int value) {
        try {
            check(annotationType, value);
        } catch (ClassCastException e) {
            return false;
        }
        return true;
    }

    /**
     * Determine whether the given value satisfies the given {@link TypeStamp &#64;TypeStamp}'s predicate.
     *
     * <p>
     * The implementation in {@link TypeStampChecker} invokes {@link #check(Class, float)}
     * and returns false if that method throws a {@link ClassCastException}, otherwise true.
     *
     * @param annotationType {@link TypeStamp &#64;TypeStamp} meta-annotated annotation type
     * @param value the value to check
     * @return true if {@code value} is valid for {@code annotationType}, otherwise false
     * @throws NullPointerException if {@code annotationType} is null (possibly)
     */
    default boolean isValid(Class<? extends Annotation> annotationType, float value) {
        try {
            check(annotationType, value);
        } catch (ClassCastException e) {
            return false;
        }
        return true;
    }

    /**
     * Determine whether the given value satisfies the given {@link TypeStamp &#64;TypeStamp}'s predicate.
     *
     * <p>
     * The implementation in {@link TypeStampChecker} invokes {@link #check(Class, long)}
     * and returns false if that method throws a {@link ClassCastException}, otherwise true.
     *
     * @param annotationType {@link TypeStamp &#64;TypeStamp} meta-annotated annotation type
     * @param value the value to check
     * @return true if {@code value} is valid for {@code annotationType}, otherwise false
     * @throws NullPointerException if {@code annotationType} is null (possibly)
     */
    default boolean isValid(Class<? extends Annotation> annotationType, long value) {
        try {
            check(annotationType, value);
        } catch (ClassCastException e) {
            return false;
        }
        return true;
    }

    /**
     * Determine whether the given value satisfies the given {@link TypeStamp &#64;TypeStamp}'s predicate.
     *
     * <p>
     * The implementation in {@link TypeStampChecker} invokes {@link #check(Class, double)}
     * and returns false if that method throws a {@link ClassCastException}, otherwise true.
     *
     * @param annotationType {@link TypeStamp &#64;TypeStamp} meta-annotated annotation type
     * @param value the value to check
     * @return true if {@code value} is valid for {@code annotationType}, otherwise false
     * @throws NullPointerException if {@code annotationType} is null (possibly)
     */
    default boolean isValid(Class<? extends Annotation> annotationType, double value) {
        try {
            check(annotationType, value);
        } catch (ClassCastException e) {
            return false;
        }
        return true;
    }

    /**
     * Determine whether the given value satisfies the given {@link TypeStamp &#64;TypeStamp}'s predicate.
     *
     * <p>
     * The implementation in {@link TypeStampChecker} invokes {@link #check(Class, Object)}
     * and returns false if that method throws a {@link ClassCastException}, otherwise true.
     *
     * @param annotationType {@link TypeStamp &#64;TypeStamp} meta-annotated annotation type
     * @param value the value to check
     * @return true if {@code value} is valid for {@code annotationType}, otherwise false
     * @throws NullPointerException if {@code annotationType} is null (possibly)
     */
    default boolean isValid(Class<? extends Annotation> annotationType, Object value) {
        try {
            check(annotationType, value);
        } catch (ClassCastException e) {
            return false;
        }
        return true;
    }
}
