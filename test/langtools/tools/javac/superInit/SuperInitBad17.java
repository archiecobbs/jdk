/*
 * @test /nodynamiccopyright/
 * @bug 8193760
 * @summary Verify non-canonical record constructors only invoke this()
 *
 * @compile/fail/ref=SuperInitBad17.out -XDrawDiagnostics SuperInitBad17.java
 */
class SuperInitBad17 {
    record Record(int value) {
        Record(float x) {
            if (x < 0)
                this((int)x);
            else
                super();
        }
    }
}
