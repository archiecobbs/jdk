/*
 * @test /nodynamiccopyright/
 * @bug 8194743
 * @summary Test invalid reference to "this" prior to superclass construction
 *
 * @compile/fail/ref=SuperInitBad07.out -XDrawDiagnostics SuperInitBad07.java
 */
class SuperInitBad07 {

    SuperInitBad07() {
        System.identityHashCode(this);
        super();
    }
}
