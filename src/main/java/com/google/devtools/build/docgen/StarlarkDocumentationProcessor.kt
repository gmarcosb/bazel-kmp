// Copyright 2014 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.docgen

import com.google.devtools.build.lib.util.Classpath.ClassPathException

/** A class to assemble documentation for Starlark.  */
object StarlarkDocumentationProcessor {
    /** Generates the Starlark documentation to the given output directory.  */
    @Throws(IOException::class, ClassPathException::class)
    fun generateDocumentation(outputDir: String?, options: StarlarkDocumentationOptions) {
        if (options.getStarlarkDocsRoot() != null) {
            DocgenConsts.starlarkDocsRoot = options.getStarlarkDocsRoot()
        }

        val linkMap: DocLinkMap? =
            DocLinkMap.Companion.createFromFile(com.google.common.base.Preconditions.checkNotNull<String?>(options.getLinkMapPath()))
        val expander: StarlarkDocExpander =
            StarlarkDocExpander(RuleLinkExpander( /* singlePage= */false, linkMap))

        val allPages: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?> =
            StarlarkDocumentationCollector.getAllDocPages(
                expander, com.google.common.collect.ImmutableList.copyOf<String?>(options.getApiStardocProtos())
            )

        for (categoryAndPages in allPages.entries) {
            writeCategoryPage(
                categoryAndPages.key, outputDir, categoryAndPages.value, expander
            )
            for (page in categoryAndPages.value) {
                writePage(outputDir, categoryAndPages.key, page)
            }
        }

        writeOverviewPage(outputDir, allPages)
        if (options.getCreateToc()) {
            writeTableOfContents(outputDir, allPages)
        }
    }

    @Throws(IOException::class)
    private fun writePage(outputDir: String?, category: Category, docPage: StarlarkDocPage) {
        val starlarkDocPath: java.io.File =
            java.io.File(String.format("%s/%s/%s.html", outputDir, category.path, docPage.getName()))
        val page: com.google.devtools.build.docgen.Page = TemplateEngine.newPage(DocgenConsts.STARLARK_LIBRARY_TEMPLATE)
        page.add("page", docPage)
        page.write(starlarkDocPath)
    }

    @Throws(IOException::class)
    private fun writeCategoryPage(
        category: Category,
        outputDir: String?,
        allPages: com.google.common.collect.ImmutableList<StarlarkDocPage?>?,
        expander: StarlarkDocExpander
    ) {
        java.nio.file.Files.createDirectories(Path.of(outputDir, category.path))

        val starlarkDocPath: java.io.File = java.io.File(String.format("%s/%s.html", outputDir, category.path))
        val page: com.google.devtools.build.docgen.Page =
            TemplateEngine.newPage(DocgenConsts.STARLARK_MODULE_CATEGORY_TEMPLATE)
        page.add("category", category)
        page.add("allPages", allPages)
        page.add("description", expander.expand(category.description))
        page.write(starlarkDocPath)
    }

    @Throws(IOException::class)
    private fun writeOverviewPage(
        outputDir: String?,
        allPages: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?>?
    ) {
        val starlarkDocPath: java.io.File = java.io.File(outputDir + "/overview.html")
        val page: com.google.devtools.build.docgen.Page =
            TemplateEngine.newPage(DocgenConsts.STARLARK_OVERVIEW_TEMPLATE)
        page.add("allPages", allPages)
        page.write(starlarkDocPath)
    }

    @Throws(IOException::class)
    private fun writeTableOfContents(
        outputDir: String?,
        allPages: com.google.common.collect.ImmutableMap<Category?, com.google.common.collect.ImmutableList<StarlarkDocPage>?>?
    ) {
        val starlarkDocPath: java.io.File = java.io.File(outputDir + "/_toc.yaml")
        val page: com.google.devtools.build.docgen.Page = TemplateEngine.newPage(DocgenConsts.STARLARK_TOC_TEMPLATE)
        page.add("allPages", allPages)
        page.write(starlarkDocPath)
    }

    /**
     * An enumeration of categories used to organize the API index. Instances of this class are
     * accessed by templates, using reflection.
     */
    enum class Category(title: String, path: String, description: String) {
        GLOBAL_FUNCTION(
            "Global functions",
            "globals",
            ("This section lists the global functions available in Starlark. The list of available"
                    + " functions differs depending on the file type (whether a BUILD file, or a .bzl file,"
                    + " etc).")
        ),

        CONFIGURATION_FRAGMENT(
            "Configuration Fragments",
            "fragments",
            ("Configuration fragments give rules access to "
                    + "language-specific parts of <a href=\"builtins/configuration.html\">"
                    + "configuration</a>. "
                    + "<p>Rule implementations can get them using "
                    + "<code><a href=\"builtins/ctx.html#fragments\">ctx."
                    + "fragments</a>.<i>[fragment name]</i></code>")
        ),

        PROVIDER(
            "Providers",
            "providers",
            ("This section lists providers available on built-in rules. See the <a"
                    + " href='https://bazel.build/extending/rules#providers'>Rules page</a> for more on"
                    + " providers. These symbols are available only in .bzl files.")
        ),

        BUILTIN(
            "Built-in Types",
            "builtins",
            ("This section lists types of Starlark objects. With some exceptions, these type names are"
                    + " not valid Starlark symbols; instances of them may be acquired through different"
                    + " means.")
        ),

        // Used for top-level modules of functions in the global namespace. Such modules will always
        // be usable solely by accessing their members, via modulename.funcname() or
        // modulename.constantname.
        // Examples: attr, cc_common, config, java_common
        TOP_LEVEL_MODULE(
            "Top-level Modules",
            "toplevel",
            "This section lists top-level modules. These symbols are available only in .bzl files."
        ),

        CORE(
            "Core Starlark data types",
            "core",
            ("This section lists the data types of the <a"
                    + " href='https://github.com/bazelbuild/starlark/blob/master/spec.md#built-in-constants-and-functions'>Starlark"
                    + " core language</a>. With some exceptions, these type names are not valid Starlark"
                    + " symbols; instances of them may be acquired through different means.")
        );

        val title: String?
        val path: String?
        val description: String?

        init {
            this.title = title
            this.path = path
            this.description = description
        }

        companion object {
            // Maps (essentially free-form) strings in annotations to permitted categories.
            fun of(annot: StarlarkBuiltin): Category {
                return when (annot.category) {
                    com.google.devtools.build.docgen.annot.DocCategory.CONFIGURATION_FRAGMENT -> com.google.devtools.build.docgen.StarlarkDocumentationProcessor.Category.CONFIGURATION_FRAGMENT
                    com.google.devtools.build.docgen.annot.DocCategory.PROVIDER -> com.google.devtools.build.docgen.StarlarkDocumentationProcessor.Category.PROVIDER
                    com.google.devtools.build.docgen.annot.DocCategory.BUILTIN -> com.google.devtools.build.docgen.StarlarkDocumentationProcessor.Category.BUILTIN
                    com.google.devtools.build.docgen.annot.DocCategory.TOP_LEVEL_MODULE -> com.google.devtools.build.docgen.StarlarkDocumentationProcessor.Category.TOP_LEVEL_MODULE
                    "core", "core.lib" ->  // interpreter built-ins (e.g. int)
                        // Starlark standard modules (e.g. json)
                        com.google.devtools.build.docgen.StarlarkDocumentationProcessor.Category.CORE

                    else -> throw java.lang.IllegalStateException(
                        String.format(
                            "docgen does not recognize DocCategory '%s' for StarlarkBuiltin '%s'",
                            annot.category, annot.name
                        )
                    )
                }
            }
        }
    }
}
