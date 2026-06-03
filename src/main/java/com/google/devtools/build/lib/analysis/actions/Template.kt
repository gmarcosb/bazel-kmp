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
package com.google.devtools.build.lib.analysis.actions

import com.google.devtools.build.lib.actions.Artifact

/** A template that contains text content, or alternatively throws an [IOException].  */
@Immutable // all subclasses are immutable
abstract class Template
/** We only allow subclasses in this file.  */
private constructor() {
    /** Returns the text content of the template.  */
    @Throws(IOException::class)
    abstract fun getContent(resolver: ArtifactPathResolver?): String?

    open fun getTemplateArtifact(): Artifact? {
        return null
    }

    /**
     * Returns a string that is used for the action key. This must change if the getContent method
     * returns something different, but is not allowed to throw an exception.
     */
    abstract fun getKey(): String?

    override fun toString(): String {
        return getKey()!!
    }

    private class ErrorTemplate(e: IOException, templateName: String?) : Template() {
        private val e: IOException
        private val templateName: String?

        init {
            this.e = e
            this.templateName = templateName
        }

        @Throws(IOException::class)
        override fun getContent(resolver: ArtifactPathResolver?): String? {
            throw IOException(
                "failed to load resource file '" + templateName + "' due to I/O error: " + e.message,
                e
            )
        }

        protected override fun getKey(): String {
            return "ERROR: " + e.message
        }
    }

    private class StringTemplate(private val templateText: String?) : Template() {
        override fun getContent(resolver: ArtifactPathResolver?): String? {
            return templateText
        }

        protected override fun getKey(): String? {
            return templateText
        }
    }

    private class ArtifactTemplate(templateArtifact: Artifact) : Template() {
        private val templateArtifact: Artifact

        init {
            this.templateArtifact = templateArtifact
        }

        @Throws(IOException::class)
        override fun getContent(resolver: ArtifactPathResolver): String {
            val templatePath: Path = resolver.toPath(templateArtifact)
            try {
                // Bazel's internal encoding for strings is raw bytes as Latin-1
                return FileSystemUtils.readContent(templatePath, java.nio.charset.StandardCharsets.ISO_8859_1)
            } catch (e: IOException) {
                throw IOException(
                    ("failed to load template file '"
                            + templatePath.getPathString()
                            + "' due to I/O error: "
                            + e.message),
                    e
                )
            }
        }

        protected override fun getKey(): String {
            // This isn't strictly necessary, because the action inputs are automatically considered.
            return "ARTIFACT: " + templateArtifact.getExecPathString()
        }

        override fun getTemplateArtifact(): Artifact {
            return templateArtifact
        }
    }

    companion object {
        /**
         * Loads a template from the given resource. The resource is looked up relative to the given
         * class. If the resource cannot be loaded, the returned template throws an [IOException]
         * when [.getContent] is called. This makes it safe to use this method in a constant
         * initializer.
         */
        fun forResource(relativeToClass: java.lang.Class<*>?, templateName: String?): Template {
            try {
                val content: String? = ResourceFileLoader.loadResource(relativeToClass, templateName)
                return com.google.devtools.build.lib.analysis.actions.Template.Companion.forString(content)
            } catch (e: IOException) {
                return ErrorTemplate(e, templateName)
            }
        }

        /** Returns a template for the given text string.  */
        fun forString(templateText: String?): Template {
            return com.google.devtools.build.lib.analysis.actions.Template.StringTemplate(templateText)
        }

        /**
         * Returns a template that loads the given artifact. It is important that the artifact is also an
         * input for the action, or this won't work. Therefore this method is private, and you should use
         * the corresponding [TemplateExpansionAction] constructor.
         */
        @com.google.common.annotations.VisibleForTesting
        fun forArtifact(templateArtifact: Artifact): Template {
            return ArtifactTemplate(templateArtifact)
        }
    }
}
