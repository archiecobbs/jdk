/*
 * @test /nodynamiccopyright/
 * @bug 8194743
 * @summary Test invalid reference to "this" prior to superclass construction
 *
 * @compile/fail/ref=SuperInitBad05.out -XDrawDiagnostics SuperInitBad05.java
 */
class SuperInitBad05 {

    int x;
    int y;

    SuperInitBad05(int a) {
        this.x = a;
        this.y = x + 1;
        super();
    }
}
