/*
 * @test /nodynamiccopyright/
 * @bug 9999999
 * @compile/ref=SuppressionNarrowingTest.out -Xlint:suppression -XDrawDiagnostics SuppressionNarrowingTest.java
 */
public class SuppressionNarrowingTest {

    @SuppressWarnings("divzero")        // there's no way to narrow this
    public int a = 1/0;

    @SuppressWarnings("divzero")        // there's no way to narrow this
    public Object b = new Object() {
        {
            System.out.println(1/0);
        }
    };

    @SuppressWarnings("divzero")        // this one could be narrowed (move to "x")
    public Object c = new Object() {
        int x = 1/0;
    };

    @SuppressWarnings("rawtypes")       // this one could be narrowed (move to "x")
    public void m(java.util.List x) {
    }

    @SuppressWarnings("divzero")        // there's no way to narrow this (without splitting it in two)
    public Object d = new Object() {
        int x = 1/0;
        int y = 1/0;
    };

    @SuppressWarnings("divzero")        // this one could be narrowed (move to "m")
    public Object e = new Object() {
        public void m() {
            int x = 1/0;
            int y = 1/0;
        }
    };
}
