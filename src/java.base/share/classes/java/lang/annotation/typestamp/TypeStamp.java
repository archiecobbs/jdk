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

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-annotation for type annotations that are validated at compile time and runtime.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.ANNOTATION_TYPE)
public @interface TypeStamp {

    /**
     * Restrict valid values to being instances of one the specified types.
     * This property is applied both at compile time and at runtime.
     *
     * <p>
     * If this property is set, then during compilation any value that is not assignable to one of the
     * specified types is considered invalid and will generate an error. At runtime, it is the responsibility
     * of the {@link #checkedBy} class to perform the same check.
     *
     * <p>
     * To not restrict values by type, leave this property set to its default value, i.e., an empty array.
     *
     * @return the supertype(s) of all valid values, or empty for no restriction
     */
    Class<?>[] restrictTo() default { };

    /**
     * Specify the {@link TypeStampChecker} class that checks values at runtime.
     *
     * <p>
     * The specified class must have a static, zero-argument method named {@code getInstance()} that provides
     * an instance of itself. That instance is responsible for validating the {@code value} is an instance of
     * one of the {@link #restrictTo} types (if any), as well as any other requirements specific to the
     * target annotation.
     *
     * @return runtime checker class
     */
    Class<? extends TypeStampChecker> checkedBy();
}
