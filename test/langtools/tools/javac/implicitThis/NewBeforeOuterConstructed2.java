/*
 * @test /nodynamiccopyright/
 * @bug 4689058
 * @summary unverifiable code for implicit outer in super constructor call
 */

public class NewBeforeOuterConstructed2 {
    NewBeforeOuterConstructed2(Object o) {}
    class Middle extends NewBeforeOuterConstructed2 {
        Middle(int i) {
            super(null);
        }
        Middle() {
            super(/*NewBeforeOuterConstructed2.this.*/new Middle(1));
        }
        class Inner {}
        void f() {
            System.out.println("ok");
        }
    }

    public static void main(String[] args) {
        new NewBeforeOuterConstructed2(null).new Middle();
    }
}
