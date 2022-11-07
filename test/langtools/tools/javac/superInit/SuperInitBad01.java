/*
 * @test /nodynamiccopyright/
 * @bug 8193760
 * @summary Test circular invocation of this() in constructors
 *
 * @compile/fail/ref=SuperInitBad01.out -XDrawDiagnostics SuperInitBad01.java
 */
class SuperInitBad01 {

    SuperInitBad01(double x) {
        if (x > 0.0)
            this((int)x);
        else
            super();
    }

    SuperInitBad01(int x) {
        if (x > 0)
            super();
        else
            this((double)x);
    }
}
