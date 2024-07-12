/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
/*
 * @test
 * @bug 8336255
 * @summary Verify allowed outer class field reference compiles successfully
 * @enablePreview
 */

public class EarlyLocalTest9 {

    Object f1 = "f1";

    public EarlyLocalTest9() {
        class C2 {                              // not early for C1
            C2() {
                class C3 {                      // early for C2
                    C3() {
                        class C4 {              // not early for C3
                            C4() {
                                f1.hashCode();  // this SHOULD compile
                            }
                        }
                        new C4();
                    }
                }
                new C3();
                super();
            }
        }
        new C2();
    }

    public static void main(String[] args) {
        new EarlyLocalTest9();
    }
}
