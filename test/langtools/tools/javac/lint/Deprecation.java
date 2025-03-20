/*
 * @test /nodynamiccopyright/
 * @bug 4821359
 * @summary Add -Xlint flag
 * @author gafter
 *
 * @compile/fail/ref=Deprecation.out -XDrawDiagnostics  -Xlint:deprecation -Werror -XDshould-stop.ifError=WARN Deprecation.java
 */

/** @deprecated */
class A {
}

class B extends A {
}
