/*
 * @test /nodynamiccopyright/
 * @bug 8194743
 * @summary Disallow calls to this() and super() within try blocks
 *
 * @compile/fail/ref=SuperInitBad14.out -XDrawDiagnostics SuperInitBad14.java
 */
import java.io.*;
class SuperInitBad14 {

    SuperInitBad14() {
        try {
            this(4);
        } catch (IllegalArgumentException e) {
            throw new RuntimeException();
        }
    }

    SuperInitBad14(int x) {
        try {
            super();
        } catch (Error e) {
            throw new IllegalArgumentException();
        }
    }

    SuperInitBad14(char x) {
        try {
            this((int)x);
        } finally {
            throw new IllegalArgumentException();
        }
    }

    SuperInitBad14(short x) throws IOException {
        try (StringWriter w = new StringWriter()) {
            this((int)x);
        }
    }
}
