/*
 * @test /nodynamiccopyright/
 * @bug 8194743
 * @summary Test invalid reference to "this" prior to superclass construction
 *
 * @compile/fail/ref=SuperInitBad11.out -XDrawDiagnostics SuperInitBad11.java
 */
class SuperInitBad11 implements Iterable<Byte> {

    SuperInitBad11() {
        Iterable.super.spliterator();
        super();
    }

    @Override
    public java.util.Iterator<Byte> iterator() {
        return null;
    }
}
