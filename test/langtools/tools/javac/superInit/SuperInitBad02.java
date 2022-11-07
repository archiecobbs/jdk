/*
 * @test /nodynamiccopyright/
 * @bug 8193760
 * @summary Test invalid reference to "this" prior to superclass construction
 *
 * @compile/fail/ref=SuperInitBad02.out -XDrawDiagnostics SuperInitBad02.java
 */
class SuperInitBad02 {

    SuperInitBad02() {
        this.hashCode();
        super();
    }
}
