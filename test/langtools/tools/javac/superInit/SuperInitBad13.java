/*
 * @test /nodynamiccopyright/
 * @bug 8194743
 * @summary Test final fields are initialized exactly once
 *
 * @compile/fail/ref=SuperInitBad13.out -XDrawDiagnostics SuperInitBad13.java
 */
class SuperInitBad13 {

    final int x;        // initialized in constructor
    final int y;        // initialized in initialization block
    final int z;        // initialized in initialization block

    {
        this.y = this.hashCode() % 13;
    }

    SuperInitBad13(int x) {
        this.x = x;
        super();
    }

    SuperInitBad13() {
        this.x = 123;   // first initialization of x
        this(456);      // second initialization of x
    }

    SuperInitBad13(char w) {
        this(456);      // first initialization of z
        this.z = 123;   // second initialization of z
    }

    {
        this.z = this.y + 3;
    }
}
