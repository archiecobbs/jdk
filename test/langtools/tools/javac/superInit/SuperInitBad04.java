/*
 * @test /nodynamiccopyright/
 * @bug 8193760
 * @summary Test invalid reference to "this" prior to superclass construction
 *
 * @compile/fail/ref=SuperInitBad04.out -XDrawDiagnostics SuperInitBad04.java
 */
class SuperInitBad04 {

    SuperInitBad04() {
        super.hashCode();
        super();
    }
}
