/*
 * @test /nodynamiccopyright/
 * @bug 8194743
 * @summary Test invalid reference to "this" prior to superclass construction
 *
 * @compile/fail/ref=SuperInitBad06.out -XDrawDiagnostics SuperInitBad06.java
 */
class SuperInitBad06 {

    int x;

    SuperInitBad06() {
        this.x++;
        super();
    }
}
