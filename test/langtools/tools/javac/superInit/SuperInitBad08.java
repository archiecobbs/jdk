/*
 * @test /nodynamiccopyright/
 * @bug 8193760
 * @summary Test invalid reference to "this" prior to superclass construction
 *
 * @compile/fail/ref=SuperInitBad08.out -XDrawDiagnostics SuperInitBad08.java
 */
class SuperInitBad08 {

    SuperInitBad08() {
        this(this);
    }

    SuperInitBad08(Object obj) {
    }
}
