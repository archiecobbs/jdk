/*
 * @test /nodynamiccopyright/
 * @bug 8193760
 * @summary Verify this()/super() can only be invoked from constructors
 *
 * @compile/fail/ref=SuperInitBad15.out -XDrawDiagnostics SuperInitBad15.java
 */
class SuperInitBad15 {

    {
        super();
    }

    {
        this();
    }

    void normalMethod1() {
        super();
    }
    void normalMethod2() {
        this();
    }
    void normalMethod3() {
        Runnable r = () -> super();
    }
    void normalMethod4() {
        Runnable r = () -> this();
    }
}
