/*
 * @test /nodynamiccopyright/
 * @bug 8194743
 * @summary Test invalid reference to "this" prior to superclass construction
 *
 * @compile/fail/ref=SuperInitBad09.out -XDrawDiagnostics SuperInitBad09.java
 */
import java.util.concurrent.atomic.*;
class SuperInitBad09 extends AtomicReference<Object> {

    SuperInitBad09() {
        super(AtomicReference.this);
    }
}
