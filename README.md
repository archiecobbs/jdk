# Welcome to the JDK!

For build instructions please see the
[online documentation](https://openjdk.org/groups/build/doc/building.html),
or either of these files:

- [doc/building.html](doc/building.html) (html version)
- [doc/building.md](doc/building.md) (markdown version)

See <https://openjdk.org/> for more information about the OpenJDK
Community and the JDK and see <https://bugs.openjdk.org> for JDK issue
tracking.

_Note: the content below is temporary and will be removed before submission_

**JDK-8193760 - allow code in a constructor before the call to the super constructor**

This is an initial proof-of-concept for JDK-8193760, which relaxes the rules for where `this()` and `super()` can appear in constructors to match what the JVM actually accepts.

_The goal of this draft PR is just to play around with how this might be implemented at the compiler level to see what it might look like. Since this change implies a JLS change, there is obviously a larger discussion that would need to occur if people wanted to pursue this further._

**Overview**

Currently, invocations of `this()` or `super()` must be the first statement in a constructor. However, the JVM actually allows more flexibility:

* Multiple invocations of `this()`/`super()` may appear, as long as on any code path there is exactly one invocation
* Arbitrary code may appear before `this()`/`super()` as long as it does not reference the instance under construction, with an exception carved out for field assignments
* Invocations of `this()`/`super()` may not appear within a `try { }` block (i.e., within a bytecode exception range)

This change would make the compiler accept any input that follows the rules above.

**Why Would This Change Be Helpful?**

The first reason is that it's often just nice to be able to do some private "housekeeping" before invoking `super()` or `this()`.

Here's a somewhat contrived example:
```java
import java.math.*;

public class BigPositiveValue extends BigInteger {

    /**
     * Constructor taking a {@code long} value.
     *
     * @param value value, must be one or greater
     */
    public BigPositiveValue(long value) {
        if (value < 1)
            throw new IllegalArgumentException("non-positive value");
        super(String.valueOf(value));
    }

    /**
     * Constructor taking a power of two. Negative exponents are clipped to zero.
     *
     * @param pow2 power-of-two exponent
     */
    public BigPositiveValue(float pow2) {
        if (Float.isNaN(pow2))
            throw new IllegalArgumentException("not a number");
        if (pow2 <= 0)      // clip negative exponents to zero
            super("1");
        else
            this(Math.round(Math.pow(2.0, pow2)));
    }
}
```

A more compelling reason is a somewhat subtle bug that happens because constructors can invoke instance methods, and subclasses can override those instance methods, and so any superclass constructor that invokes an instance method can fail if some subclass happens to override that method - because the method will be executing on an incompletely initialized instance.

The insidious part about this particular problem is that the failure is not visible if you are looking at just one of the two classes (which could have been written by two different people at two different times). To recognize such a bug, you have to connect dots of information from multiple classes.

For example, consider this class:
```java
import java.util.*;
import java.util.function.*;

/**
 * A {@link Set} that rejects elements not accepted by the configured {@link Predicate}.
 */
public class FilteredSet<E> extends HashSet<E> {

    private final Predicate<? super E> filter;

    public FilteredSet(Predicate<? super E> filter, Collection<? extends E> elems) {
        super(elems);
        this.filter = filter;
    }

    @Override
    public boolean add(E elem) {
        if (!this.filter.test(elem))
            throw new IllegalArgumentException("disallowed element");
        return super.add(elem);
    }

    public static void main(String[] args) {
        new FilteredSet<>(s -> true, Arrays.asList("abc", "def"));   // boom!
    }
}
```
At first glance it appears to be bug-free, but if run it actually throws a `NullPointerException`. That's not obvious until you look at the `HashSet(Collection)` constructor, notice that it invokes invokes `addAll()`, and then look in `AbstractCollection` to see that `addAll()` invokes `add()`, and then realize that the overridden `add()` implementation will then reference `this.filter` before that field is initialized. Moreover, there's no way to fix this without some kind of kludgey workaround.

However, the problem would go away if, instead, we could simply write the constructor like this:

```java
    public FilteredSet(Predicate<? super E> filter, Collection<? extends E> elems) {
        this.filter = filter;
        super(elems);
    }
```

**Side Note: `try { }` Blocks**

The JVM `try { }` block restriction is due to how StackMaps are represented (see [this StackOverflow link](https://stackoverflow.com/a/69554673/263801) for details). The intention is that when a superclass constructor throws an exception, the new instance on the stack is neither fully uninitialized nor fully initialized, so it should be considered unusable, and such a constructor must never return. This makes sense, of course. However, the JVM doesn't allow the bytecode to simply discard the unusable instance by throwing another exception. Instead, it doesn't allow it to exist at all. The net effect is that a constructor can't catch exceptions thrown by superclass initialization, even if they never return.

If that JVM limitation were removed, with a few changes this patch would also make code like this possible:
```java
    public class GracefulHandleNull extends SomeClass {
        public GracefulHandleNull(Object obj) {
            try {
                super(obj);
            } catch (NullPointerException e) {
                throw new IllegalArgumentException("null values not allowed");
            }
        }
    }
```

One could argue that if we are going to make this JLS change, the JVM change would also be worth considering in order to remove this somewhat awkward restriction. It could be done (for example) by adding a new `Unusable` type to StackMaps, with the associated verification requirement that such values are never used.

**Side Note: Initializers and Initialization Blocks**

Currently, all field initializers and initialization blocks execute at the beginning of every constructor. This follows from the fact that in the bytecode, they are executed after every `super()` call.

So for example this class:
```java
class Test1 {
    final int x;
    {
        x = 123;
    }
    public Test1() {
        this.x = 456;
    }
}
```
generates this error as usual:
```
Test1.java:7: error: variable x might already have been assigned
        this.x = 456;
            ^
```

An interesting side-effect of this change is that, although field initializers and initialization blocks are executed after every `super()` call just as before, these calls are no longer necessarily first, so (for example) a duplicate assignment in an initializer block can "lose" to an earlier assignment in the constructor prior to `super()`:
```java
class Test1 {
    final int x;
    {
        x = 123;
    }
    public Test1() {
        this.x = 456;
        super();
    }
}
```
generates this error:
```
Test1.java:4: error: variable x might already have been assigned
        x = 123;
        ^
```
It would be nice if we could move initializers and initialization blocks to the start of every constructor, but that doesn't work. First, they could have early references (e.g., by invoking an instance method), and second, the constructor might invoke `this()` and `super()` on different code branches, so you'd be executing the initialization twice in the `this()` case.

As before, initializers and initialization blocks happen immediately after superclass initialization, which happens when `this()` or `super()` is invoked. But now this can be anywhere in the constructor.

**Compiler Overview: Current Behavior**

Before describing what this patch changes, here's a quick review of how the affected code currently behaves.

All constructors except `java.lang.Object()` must initialize their superclass. Currently, there are three options for superclass initialization:

1. Invoke `super()` as the first statement
1. Invoke `this()` as the first statement
1. Do not invoke `super()` or `this()` → the compiler adds a `super()` for you

In the compiler, constructors are divided into two categories:
1. _Initial_ constructors invoke `super()` (either explicitly or implicitly)
1. _Non-inital_ constructors invoke `this()`

In the current code, non-initial constructors are treated almost the same as normal methods, because once `this()` is invoked at the start of the constructor, the object is fully initialized. Initial constructors, however, must be more closely watched to insure final fields are initialized correctly. Initial constructors also must be modified during compliation to execute any non-static field initializers and initialization blocks. All constructors are modified to handle non-static nested class references to outer instances, and free variable proxies.

Overall, the following "syntactic sugar" adjustments are applied to constructors during compilation:

1. If the constructor doesn't invoke `this()` or `super()`, an initial `super()` invocation is inserted
1. If the class has non-static fields initializers or initialization blocks:
    1. Code is added after `super()` invocations to initialize fields and run initialization blocks
1. If the class has an outer instance:
    1. A synthetic `this$0` field is added to the class
    1. Constructors have an extra parameter prepended to carry it
    1. Code is added prior to `super()` invocations to initialize `this$0` from the new parameter
1. If the class has proxies for free variables:
    1. Synthetic `val$x` fields are added to the class
    1. Constructors have extra parameters appended
    1. Code is added prior to `super()` invocations to initialize each `val$x` from its new parameter

Steps 2 and 3 are performed in `comp/Lower.java`, while step 1 is performed in `jvm/Gen.java`.

It's interesting to note that by initializing `this$0` and `val$x` fields before invoking `super()`, the compiler is already taking advantage of the looser JVM requirements for its own purposes (why should the compiler get to have all the fun? :)

This means the `FilteredSet` bug above can't occur with outer instance and free variables. So for example the following version of the `FilteredSet` example works just fine:
```java
import java.util.*;
import java.util.function.*;

public class FilteredSet2 {

    public static <E> Set<E> create(Predicate<? super E> filter, Collection<? extends E> elems) {
        return new HashSet<E>(elems) {
            @Override
            public boolean add(E elem) {
                if (!filter.test(elem))
                    throw new IllegalArgumentException("disallowed element");
                return super.add(elem);
            }
        };
    }

    public static void main(String[] args) {
        FilteredSet2.create(s -> true, Arrays.asList("abc", "def"));   // works!
    }
}
```

**Description of the Change**

This change impacts a few different areas of the compiler. In all cases, existing classes should compile the same way as they did before (which makes sense, because we are strictly expanding the set of accepted source inputs).

At a high level, here's what changes:

1. We relax checks so that `this()`/`super()` may appear anywhere in constructors except for `try { }` blocks
1. We add DA/DU analysis for superclass initialization
1. We add checks to disallow early `this` references, except for field assignments
1. We refactor/replace any code that currently assumes `this()`/`super()` is always first in constructors

**Changes to Specific Files**

Below are per-file descriptions of the changes being made.

**`comp/Attr.java`**

The check that `super()`/`this()` is the first statement of a constructor is relaxed to just check that `super()`/`this()` occurs within a constructor.

Non-canonical record constructors may now invoke `this()` more than once on different code branches, but (as before) they must invoke `this()` exactly once and they must not ever invoke `super()`.

**`comp/Check.java`**

The check for recursive constructor invocation is adjusted to handle the fact that a constructor may invoke more than one other constructor, i.e., the invocation call graph is now one-to-many instead of one-to-one.

**`comp/Flow.java`**

`Flow.FlowAnalyzer` checks for uncaught checked exceptions. For initializer blocks, this was previously done by requiring that any checked exceptions thrown be declared as thrown by all initial constructors. This list of checked exceptions is pre-calculated before recursing into the initial constructors. This works because initializer blocks are executed at the beginning of each initial constructor right after `super()` is called.

In the new version of `FlowAnalyzer`, initializer blocks are traversed in the flow analysis after each `super()` invocation, reflecting what actually will happen at runtime (see below), and the pre-calculation is removed. The effect is the same as before, namely, any checked exceptions thrown by initializer blocks must be declared as thrown by all constructors that invoke `super()`. Side note: even if we later removed the `try { }` block restriction, the analysis would still function properly, only now requiring that _uncaught_ exceptions have to be declared as thrown by the constructor.

`Flow.AssignAnalyzer` is responsible for DA/DU analysis for fields and variables. We piggy-back on the existing machinery for tracking assignments to final instance fields to track superclass initialization, which acts like an assignment to a blank final field, in that it must happen exactly once in each constructor no matter what code branch is taken. To do this we allocate an additional bit in the existing DA/DU bitmaps, and for the most part the existing AST walking machinery takes care of the rest.

Previously, the code worked as follows:

1. For initial constructors:
    1. Assume final fields with initializers or assigned within initialization blocks start out DA.
        1. Note: This is an optimization based on the assumption that `super()` is always first and then followed by initializers
    1. Assume all blank final fields start out DU.
    1. Upon seeing an assignment to a blank final field:
        1. Before, the blank final field must be DU
        1. After, the blank final field is DA
    1. Require all final fields to be DA at the end.
1. For non-initial constructors, don't do DA/DU analysis for fields (i.e., treat non-initial constructors like a normal method)
    1. Note: This is another optimization, based on the assumption that `this()` is always first

Now that `super()` and `this()` can appear anywhere in constructors, there is no longer such a thing as an "initial" constructor, so the new code works as follows:

1. For all constructors:
    1. Assume all final fields start out DU.
    1. Upon seeing an assignment to a blank final field:
        1. Before, the blank final field must be DU
        1. After, the blank final field is DA
    1. Upon seeing `super()`:
        1. Superclass initialization must be DU
        1. Mark superclass initialization as DA
        1. Recurse on initializers and initialization blocks normally to process field assignments therein
    1. Upon seeing `this()`:
        1. Superclass initialization must be DU
        1. Mark superclass initialization as DA
        1. "Infer" assignments to all blank final fields, i.e.:
            1. All blank final fields must be DU
            1. Mark all blank final fields as DA
    1. Require all final fields to be DA at the end.
    1. Require superclass initialization to be DA at the end.

The result is that on every path through every constructor, each blank final field must be assigned exactly once, and superclass initialization must also happen exactly once.


`AssignAnalyzer` is also augmented to enforce these new restrictions:

1. Disallow any reference to the current instance prior to `super()` or `this()`, except for assigments to fields.
1. Disallow invocations of `this()` or `super()` invocations within `try { }` blocks.

**`comp/Lower.java`**

This is where the adjustments are made for initializing outer instances and free variable proxies. This now must be done at every `super()` invocation instead of just at the presumed first and only one, so the new code goes and finds all `super()` invocations. Otherwise the adjustments made are the same.

**`jvm/Code.java`**

This class required a change because of the following problem: while the class `Code.State` is used to model the JVM state on each code branch, the "uninitialized" status of each local variable is not part of `Code.State` but rather stored in the `LocalVar` fields themselves (which are not cloned per code branch). Previously this was not a problem because the initial `this()` or `super()` invocation was always on the (only) initial branch of the code.  Now that different branches of code may or may not initialize the superclass, we have to keep track of the "uninitialized" status of each `LocalVar` separately in each `Code.State` instance.

This is done by adding a bitmap indicating which local variables are initialized. As a result, to get the current type of a `LocalVar`, now you ask the `State` instead of asking the `LocalVar` directly.

**`jvm/Gen.java`**

Previously, the method `Gen.normalizeMethod()` added initialization code to initial constructors after the intial `super()` invocation. This is now done at every `super()` invocation instead of just after the presumed first and only one.

**`jvm/UninitializedType.java`**

`toString()` method modified to display `[!INIT]` after the description of the type. This was helpful to me during debugging but obviously is not essential.

**`tree/TreeInfo.java`**

Removed these utility methods:
1. `public static Name getConstructorInvocationName(List<? extends JCTree> trees, Names names)`
1. `public static boolean isInitialConstructor(JCTree tree)`

Added these utility methods:
1. `public static boolean hasConstructorCalls(JCTree tree, Name target)`
1. `public static boolean hasAnyConstructorCalls(JCTree tree)`
1. `public static List<JCMethodInvocation> findConstructorCalls(JCTree tree, Name target)`
1. `public static List<JCMethodInvocation> findAllConstructorCalls(JCTree tree)`
1. `public static void mapSuperCalls(JCBlock block, Function<? super JCExpressionStatement, ? extends JCStatement> mapper)`

**`resources/compiler.properties`**

There are some changes to error messages:

Removed these errors
1. `call to {0} must be first statement in constructor`

Added these errors:
1. `calls to {0}() may only appear within constructors`
1. `calls to {0}() may not appear within try statements`
1. `superclass constructor might not have been invoked`
1. `superclass constructor might already have been invoked`

Changed these errors:
1. Old: `canonical constructor must not contain explicit constructor invocation`
    1. New: `canonical constructor must not contain explicit constructor invocations`
1. Old: `constructor is not canonical, so its first statement must invoke another constructor of class {0}`
    1. New: `constructor is not canonical, so it must invoke other constructors of class {0}`

**`jdk/jshell/ExpressionToTypeInfo.java`**

Replaced reference to `TreeInfo.firstConstructorCall()` with `TreeInfo.findAllConstructorCalls()`.

**`jdk/test/langtools/tools/javac`**

Updated a couple of existing tests and add a bunch of positive and negative new ones.

**Lingering Questions**

* I'm still not 100% confident in the checking for illegal early `this` references in `Flow.AssignAnalyzer`
  * Are the new methods `isStrictOuterType()` and `requiresSuperInit()` implemented in the optimal way?
  * Same question for newly added code in `visitApply()`, `visitSelect()`, and `visitIdent()`.
* Are there any weird corner cases that could be caused by the changes in `jvm/Code.java`?
* Are there more test cases that should be added (either positive or negative)?
