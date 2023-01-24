/*
 * @test /nodynamiccopyright/
 * @bug 8194743
 * @summary Test order in which duplicate assignments are reported
 *
 * @compile/fail/ref=SuperInitBad19.out -XDrawDiagnostics SuperInitBad19.java
 */
class SuperInitBad19 {

    static class Example1 {

        final int x;

        {
            this.x = 123;       // this is the duplicate assignment
        }

        Example1(int x) {
            this.x = 456;
            super();
        }
    }

    static class Example2 {

        final int x;

        {
            this.x = 123;
        }

        Example2(int x) {
            super();
            this.x = 456;       // this is the duplicate assignment
        }
    }
}
