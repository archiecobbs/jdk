/*
 * @test /nodynamiccopyright/
 * @bug 8194743
 * @summary Verify inner class creation is disallowed before super()
 *
 * @compile/fail/ref=SuperInitBad20.out -XDrawDiagnostics SuperInitBad20.java
 */
class SuperInitBad20 {

    SuperInitBad20() {
        new Inner();    // illegal implicit reference to 'this'
        super();
    }

    class Inner {
    }
}
