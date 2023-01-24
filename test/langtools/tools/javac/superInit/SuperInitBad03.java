/*
 * @test /nodynamiccopyright/
 * @bug 8194743
 * @summary Test invalid reference to "this" prior to superclass construction
 *
 * @compile/fail/ref=SuperInitBad03.out -XDrawDiagnostics SuperInitBad03.java
 */
class SuperInitBad03 {

    SuperInitBad03() {
        SuperInitBad03.super.hashCode();
        super();
    }
}
