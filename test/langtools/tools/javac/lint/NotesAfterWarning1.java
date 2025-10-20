/*
 * @test /nodynamiccopyright/
 * @bug 8888888
 * @summary Verify aggregated warning notes are emitted even if errors occur prior to attribution
 * @compile/fail/ref=NotesAfterWarning1a.out -XDrawDiagnostics -XDforcePreview                 NotesAfterWarning1.java
 * @compile/fail/ref=NotesAfterWarning1a.out -XDrawDiagnostics -XDforcePreview -Xlint:-preview NotesAfterWarning1.java
 * @compile/fail/ref=NotesAfterWarning1b.out -XDrawDiagnostics -XDforcePreview  -Xlint:preview NotesAfterWarning1.java
 * @enablePreview
 */
public class NotesAfterWarning1 {
    // This will be considered the use of a preview feature
    String s = """
               """;
    // This lexical error will end compilation prior to attribution
    foobar
}
