/*
 * @test /nodynamiccopyright/
 * @bug 8194743
 * @summary Test invalid reference to "this" prior to superclass construction
 *
 * @compile/fail/ref=SuperInitBad16.out -XDrawDiagnostics SuperInitBad16.java
 */
class SuperInitBad16 {

    SuperInitBad16() {
        hashCode();
        super();
    }
}
