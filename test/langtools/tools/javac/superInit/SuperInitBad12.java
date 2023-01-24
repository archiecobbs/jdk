/*
 * @test /nodynamiccopyright/
 * @bug 8194743
 * @summary Verify non-canonical record constructors only invoke this()
 *
 * @compile/fail/ref=SuperInitBad12.out -XDrawDiagnostics SuperInitBad12.java
 */
class SuperInitBad12 {
    record Record(int value) {
        Record(float x) {
        }
    }
}
