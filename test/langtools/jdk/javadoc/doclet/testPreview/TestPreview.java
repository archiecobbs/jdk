/*
 * Copyright (c) 2003, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/*
 * @test
 * @bug      8250768 8261976 8277300 8282452 8287597 8325325 8325874 8297879
 *           8331947 8281533 8343239 8318416 8346109 8359024
 * @summary  test generated docs for items declared using preview
 * @library  /tools/lib ../../lib
 * @modules jdk.javadoc/jdk.javadoc.internal.tool
 *          jdk.javadoc/jdk.javadoc.internal.doclets.formats.html.resources:+open
 * @build    toolbox.ToolBox javadoc.tester.*
 * @run main TestPreview
 */

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import javadoc.tester.JavadocTester;
import toolbox.ToolBox;

public class TestPreview extends JavadocTester {
    ToolBox tb = new ToolBox();

    public static void main(String... args) throws Exception {
        var tester = new TestPreview();
        tester.runTests();
    }

    @Test
    public void testUserJavadoc() {
        String doc = Paths.get(testSrc, "doc").toUri().toString();
        javadoc("-d", "out-user-javadoc",
                "-XDforcePreview", "--enable-preview", "-source", System.getProperty("java.specification.version"),
                "--patch-module", "java.base=" + Paths.get(testSrc, "api").toAbsolutePath().toString(),
                "--add-exports", "java.base/preview=m",
                "--module-source-path", testSrc,
                "-linkoffline", doc, doc,
                "m/pkg");
        checkExit(Exit.OK);

        checkOutput("m/pkg/TestPreviewDeclarationUse.html", true,
                    "<code><a href=\"TestPreviewDeclaration.html\" title=\"interface in pkg\">TestPreviewDeclaration</a></code>");
        checkOutput("m/pkg/TestPreviewAPIUse.html", true,
                "<a href=\"" + doc + "java.base/preview/Core.html\" title=\"class or interface in preview\" class="
                        + "\"external-link\">Core</a><sup class=\"preview-mark\"><a href=\"" + doc + "java.base/pr"
                        + "eview/Core.html#preview-preview.Core\" title=\"class or interface in preview\" class=\""
                        + "external-link\">PREVIEW</a>");
        checkOutput("m/pkg/DocAnnotation.html", true,
                "<span class=\"modifiers\">public @interface </span><span class=\"element-name type-name-label\">DocAnnotation</span>");
        checkOutput("m/pkg/DocAnnotationUse1.html", true,
                "<div class=\"inheritance\">pkg.DocAnnotationUse1</div>");
        checkOutput("m/pkg/DocAnnotationUse2.html", true,
                "<div class=\"inheritance\">pkg.DocAnnotationUse2</div>");
    }

    @Test
    public void testPreviewAPIJavadoc() {
        javadoc("-d", "out-preview-api",
                "--patch-module", "java.base=" + Paths.get(testSrc, "api").toAbsolutePath().toString(),
                "--add-exports", "java.base/preview=m",
                "--source-path", Paths.get(testSrc, "api").toAbsolutePath().toString(),
                "--show-packages=all",
                "preview");
        checkExit(Exit.OK);

        checkOutput("preview-list.html", true,
                """
                    <ul class="preview-feature-list checkboxes">
                    <li><label for="feature-1"><input type="checkbox" id="feature-1" disabled checked onclick="tog\
                    gleGlobal(this, '1', 3)"><span>2147483647: <a href="https://openjdk.org/jeps/2147483647">Test \
                    Feature (Preview)</a></span></label></li>
                    <li><label for="feature-all"><input type="checkbox" id="feature-all" disabled checked onclick=\
                    "toggleGlobal(this, 'all', 3)"><span>Toggle all</span></label></li>
                    </ul>
                    <h2 title="Contents">Contents</h2>
                    <ul class="contents-list">
                    <li id="contents-package"><a href="#package">Packages</a></li>
                    <li id="contents-class"><a href="#class">Classes</a></li>
                    <li id="contents-record-class"><a href="#record-class">Record Classes</a></li>
                    <li id="contents-method"><a href="#method">Methods</a></li>
                    </ul>
                    """,
                """
                    <div id="package">
                    <div class="table-tabs">
                    <div class="caption"><span>Packages</span></div>
                    </div>
                    <div id="package.tabpanel" role="tabpanel" aria-labelledby="package-tab0">
                    <div class="summary-table three-column-summary">
                    <div class="table-header col-first sort-asc" onclick="sortTable(this, 0, 3)">Package</div>
                    <div class="table-header col-second" onclick="sortTable(this, 1, 3)">Preview Feature</div>
                    <div class="table-header col-last">Description</div>
                    <div class="col-summary-item-name even-row-color package package-tab1"><a href="java.base/prev\
                    iew/package-summary.html">preview</a><sup class="preview-mark"><a href="java.base/preview/pack\
                    age-summary.html#preview-preview">PREVIEW</a></sup></div>
                    <div class="col-second even-row-color package package-tab1">Test Feature</div>
                    <div class="col-last even-row-color package package-tab1">
                    <div class="block">Preview package.</div>
                    </div>
                    """,
                """
                    <div id="record-class">
                    <div class="table-tabs">
                    <div class="caption"><span>Record Classes</span></div>
                    </div>
                    <div id="record-class.tabpanel" role="tabpanel" aria-labelledby="record-class-tab0">
                    <div class="summary-table three-column-summary">
                    <div class="table-header col-first sort-asc" onclick="sortTable(this, 0, 3)">Record Class</div>
                    <div class="table-header col-second" onclick="sortTable(this, 1, 3)">Preview Feature</div>
                    <div class="table-header col-last">Description</div>
                    <div class="col-summary-item-name even-row-color record-class record-class-tab1"><a href="java\
                    .base/preview/CoreRecord.html" title="class in preview">preview.CoreRecord</a><sup class="prev\
                    iew-mark"><a href="java.base/preview/CoreRecord.html#preview-preview.CoreRecord">PREVIEW</a></sup></div>
                    <div class="col-second even-row-color record-class record-class-tab1">Test Feature</div>
                    <div class="col-last even-row-color record-class record-class-tab1"></div>
                    </div>
                    """,
                """
                    <div id="method">
                    <div class="table-tabs">
                    <div class="caption"><span>Methods</span></div>
                    </div>
                    <div id="method.tabpanel" role="tabpanel" aria-labelledby="method-tab0">
                    <div class="summary-table three-column-summary">
                    <div class="table-header col-first sort-asc" onclick="sortTable(this, 0, 3)">Method</div>
                    <div class="table-header col-second" onclick="sortTable(this, 1, 3)">Preview Feature</div>
                    <div class="table-header col-last">Description</div>
                    <div class="col-summary-item-name even-row-color method method-tab1"><a href="java.base/previe\
                    w/CoreRecordComponent.html#i()">preview.CoreRecordComponent.i()</a><sup class="preview-mark"><\
                    a href="java.base/preview/CoreRecordComponent.html#preview-i()">PREVIEW</a></sup></div>
                    <div class="col-second even-row-color method method-tab1">Test Feature</div>
                    <div class="col-last even-row-color method method-tab1">
                    <div class="block">Returns the value of the <code>i</code> record component.</div>
                    </div>
                    """);

        // 8325325: Breadcrumb navigation links should not contain PREVIEW link
        checkOutput("java.base/preview/package-summary.html", true,
                """
                    <ol class="sub-nav-list">
                    <li><a href="../module-summary.html">java.base</a></li>
                    <li><a href="package-summary.html" class="current-selection">preview</a></li>
                    </ol>""");
        checkOutput("java.base/preview/Core.html", true,
                """
                    <ol class="sub-nav-list">
                    <li><a href="../module-summary.html">java.base</a></li>
                    <li><a href="package-summary.html">preview</a></li>
                    <li><a href="Core.html" class="current-selection">Core</a></li>
                    </ol>""",
                """
                    <div class="block">Preview feature. Links: <a href="CoreRecord.html" title="cla\
                    ss in preview"><code>CoreRecord</code></a><sup class="preview-mark"><a href="Co\
                    reRecord.html#preview-preview.CoreRecord">PREVIEW</a></sup>, <a href="CoreRecor\
                    d.html" title="class in preview"><code>core record</code></a><sup class="previe\
                    w-mark"><a href="CoreRecord.html#preview-preview.CoreRecord">PREVIEW</a></sup>,
                    <a href="CoreRecord.html" title="class in preview">CoreRecord</a>, <a href="Co\
                    reRecord.html" title="class in preview">core record</a>.</div>""",
                """
                    <li><a href="CoreRecord.html" title="class in preview"><code>CoreRecord</code><\
                    /a><sup class="preview-mark"><a href="CoreRecord.html#preview-preview.CoreRecor\
                    d">PREVIEW</a></sup></li>
                    <li><a href="CoreRecord.html" title="class in preview">core record</a></li>""");

        // 8331947: Support preview features without JEP should not be included in Preview API page
        checkOutput("preview-list.html", false, "supportMethod");
    }

    // 8343239 pre-existing permanent API that is later retrofitted
    // to extend a @PreviewFeature interface should not be flagged as a preview feature
    @Test
    public void nonPreviewExtendsPreview(Path base) throws IOException {

        Path src = base.resolve("src");
        tb.writeJavaFiles(src, """
                package p;
                import jdk.internal.javac.PreviewFeature;

                /**
                 * Preview feature
                 */
                @PreviewFeature(feature= PreviewFeature.Feature.TEST)
                public interface CoreInterface {
                }
                """, """
                package p;

                 /**
                  * Non preview feature
                  */
                 public interface NonPreviewExtendsPreview extends CoreInterface {
                     default int getNumber() {
                         return 0;
                     }
                 }
                """);
        javadoc("-d", "out-non-preview-extends-preview",
                "--add-exports", "java.base/jdk.internal.javac=ALL-UNNAMED",
                "--source-path",
                src.toString(),
                "p");
        checkExit(Exit.OK);
        checkOutput("p/NonPreviewExtendsPreview.html", false,
                """
                 <code>NonPreviewExtendsPreview</code> relies on preview features of the Java platform:
                """,
                """
                <code>NonPreviewExtendsPreview</code> refers to one or more preview APIs:
                """);
        checkOutput("p/CoreInterface.html", true,
                """
                <div class="horizontal-scroll">
                <div class="type-signature"><span class="modifiers">public interface </span><span class="element-name type-name-label">CoreInterface</span></div>
                <div class="preview-block" id="preview-p.CoreInterface"><span class="preview-label"><code>CoreInterface</code> is a preview API of the Java platform.</span>
                <div class="preview-comment">Programs can only use <code>CoreInterface</code> when preview features are enabled.</div>
                <div class="preview-comment">Preview features may be removed in a future release, or upgraded to permanent features of the Java platform.</div>
                </div>
                <div class="block">Preview feature</div>
                </div>
                """);
    }

    @Test
    public void test8277300() {
        javadoc("-d", "out-8277300",
                "--add-exports", "java.base/jdk.internal.javac=api2",
                "--source-path", Paths.get(testSrc, "api2").toAbsolutePath().toString(),
                "--show-packages=all",
                "api2/api");
        checkExit(Exit.OK);

        checkOutput("api2/api/API.html", true,
                    "<p><a href=\"#test()\"><code>test()</code></a></p>",
                    "<p><a href=\"#testNoPreviewInSig()\"><code>testNoPreviewInSig()</code></a></p>",
                    "title=\"class or interface in java.util\" class=\"external-link\">List</a>&lt;<a href=\"API.h"
                            + "tml\" title=\"class in api\">API</a><sup class=\"preview-mark\"><a href=\"#preview-"
                            + "api.API\">PREVIEW</a></sup>&gt;");
        checkOutput("api2/api/API2.html", true,
                    "<a href=\"API.html#test()\"><code>API.test()</code></a><sup class=\"preview-mark\"><a href=\""
                            + "API.html#preview-api.API\">PREVIEW</a></sup>",
                    "<a href=\"API.html#testNoPreviewInSig()\"><code>API.testNoPreviewInSig()</code></a><sup class"
                            + "=\"preview-mark\"><a href=\"API.html#preview-api.API\">PREVIEW</a></sup>",
                    "<a href=\"API3.html#test()\"><code>API3.test()</code></a><sup class=\"preview-mark\"><a href="
                            + "\"API3.html#preview-test()\">PREVIEW</a></sup>");
        checkOutput("api2/api/API3.html", true,
                    "<div class=\"block\"><a href=\"#test()\"><code>test()</code></a><sup class=\"preview-mark\"><"
                            + "a href=\"#preview-test()\">PREVIEW</a></sup></div>");
    }

    @Test
    public void test8282452() {
        javadoc("-d", "out-8282452",
                "--patch-module", "java.base=" + Paths.get(testSrc, "api").toAbsolutePath().toString(),
                "--add-exports", "java.base/preview=m",
                "--source-path", Paths.get(testSrc, "api").toAbsolutePath().toString(),
                "--show-packages=all",
                "preview");
        checkExit(Exit.OK);

        checkOutput("java.base/preview/NoPreview.html", false,
                    "refers to one or more preview");
    }

    @Test
    public void testRequiresTransitiveJavaBase() {
        Path src = Paths.get(testSrc, "requiresTransitiveJavaBase");
        javadoc("-d", "out-requires-transitive-java-base",
                "-XDforcePreview", "--enable-preview", "-source", System.getProperty("java.specification.version"),
                "--module-source-path", src.toString(),
                "--module", "m",
                "--expand-requires", "transitive");
        checkExit(Exit.OK);

        checkOutput("m/module-summary.html", true,
                    "Indirect exports from the <code>java.base</code> module are");
    }

    // Test for JDK hidden option to add an entry for a non-preview element
    // in the preview page based on the presence of a javadoc tag.
    @Test
    public void testPreviewNoteTag(Path base) throws IOException {
        Path src = base.resolve("src");
        tb.writeJavaFiles(src, """
                package p;
                import jdk.internal.javac.PreviewFeature;

                /**
                 * Preview feature
                 */
                @PreviewFeature(feature= PreviewFeature.Feature.TEST)
                public interface CoreInterface {
                }
                """, """
                package p;

                 /**
                  * Non preview feature.
                  * {@previewNote 2147483647 Preview API Note}
                  */
                 public interface NonPrevieFeature {
                 }
                """);
        javadoc("-d", "out-preview-note-tag",
                "--add-exports", "java.base/jdk.internal.javac=ALL-UNNAMED",
                "-tag", "previewNote:a:Preview Note:",
                "--preview-note-tag", "previewNote",
                "--source-path",
                src.toString(),
                "p");
        checkExit(Exit.OK);

        checkOutput("preview-list.html", true,
                """
                    <h2 title="Contents">Contents</h2>
                    <ul class="contents-list">
                    <li id="contents-preview-api-notes"><a href="#preview-api-notes">Preview API Notes</a></li>
                    <li id="contents-interface"><a href="#interface">Interfaces</a></li>""",
                """
                    <div class="caption"><span>Elements containing Preview Notes</span></div>""",
                """
                    <div class="col-summary-item-name even-row-color preview-api-notes preview-api-notes-tab1\
                    "><a href="p/NonPrevieFeature.html" title="interface in p">p.NonPrevieFeature</a></div>
                    <div class="col-second even-row-color preview-api-notes preview-api-notes-tab1">Test Feature</div>
                    <div class="col-last even-row-color preview-api-notes preview-api-notes-tab1">
                    <div class="block">Non preview feature.</div>""");
    }
}
