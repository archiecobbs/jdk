/*
 * @test /nodynamiccopyright/
 * @bug 8194743
 * @summary Verify super() can't appear in an initialization block
 *
 * @compile/fail/ref=SuperInitBad18.out -XDrawDiagnostics SuperInitBad18.java
 */
import java.util.concurrent.atomic.AtomicReference;
public class SuperInitBad18 extends AtomicReference<Object> {
    SuperInitBad18() {
        super(new Object() {
            {
                super();
            }
        });
    }
}
