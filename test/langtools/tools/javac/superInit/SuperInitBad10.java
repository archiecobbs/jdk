/*
 * @test /nodynamiccopyright/
 * @bug 8194743
 * @summary Verify when present this()/super() must be invoked exactly once
 *
 * @compile/fail/ref=SuperInitBad10.out -XDrawDiagnostics SuperInitBad10.java
 */
class SuperInitBad10 {

    SuperInitBad10() {
        if (new Object().hashCode() > 0)
            super();
    }

    SuperInitBad10(int x) {
        for (int i = 0; i < 2; i++)
            super();
    }

    SuperInitBad10(char x) {
        switch (x) {
        case 'a':
            this((int)x);
            break;
        case 'b':
            super();
        default:
            break;
        }
    }
}
