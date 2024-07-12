/*
 * @test /nodynamiccopyright/
 * @bug 0000000
 * @summary Verify the "try/catch DU exception"
 * @compile/fail/ref=TryCatchDUException.out -XDrawDiagnostics TryCatchDUException.java
 */
import java.util.function.IntSupplier;

public class TryCatchDUException {

    // Test the catch DU exception for a final variable
    void method1(Object obj) {
        final int hash;
        try {
            hash = obj.hashCode();
        } catch (NullPointerException e) {
            hash = -1;                                  // ok!
        }
    }

    // Test the catch DU exception for an effectively final variable
    void method2(Object obj) {
        int hash;                                       // effectively final
        try {
            hash = obj.hashCode();
        } catch (NullPointerException e) {
            hash = -1;
        }
        Runnable r = () -> System.out.println(hash);    // ok!
    }

    // Verify the catch DU exception doesn't apply when statement is not last
    void method3(Object obj) {
        final int hash;
        try {
            hash = obj.hashCode();
            obj.getClass();
        } catch (NullPointerException e) {
            hash = -1;                                  // expect error here
        }
    }

    // Verify the catch DU exception doesn't apply when RHS sets the variable too
    void method4(Object obj) {
        final int hash;
        try {
            hash = (hash = 1) < 0 ? -1 : 1;             // expect error here
        } catch (NullPointerException e) {
            hash = -1;                                  // expect error here
        }
    }

    // Verify the catch DU exception is not confused by a lambda
    void method5(Object obj) {
        final int hash;
        try {
            hash = ((IntSupplier)() -> {
                hash++;                                 // expect error here
                return 0;
            }).getAsInt();
        } catch (NullPointerException e) {
            hash = -1;                                  // ok!
        }
    }
}
