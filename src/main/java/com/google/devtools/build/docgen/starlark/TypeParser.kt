// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.docgen.starlark

import com.google.devtools.build.docgen.StarlarkDocumentationProcessor
import com.google.devtools.build.docgen.annot.GlobalMethods.Environment.getPath
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import net.starlark.java.eval.Starlark
import net.starlark.java.syntax.BinaryOperatorExpression.operator
import net.starlark.java.syntax.BinaryOperatorExpression.x
import net.starlark.java.syntax.BinaryOperatorExpression.y
import net.starlark.java.syntax.Identifier.getName
import net.starlark.java.syntax.TypeApplication.getArguments
import net.starlark.java.syntax.TypeApplication.getConstructor

/**
 * Parses a type expression in a Starlark docstring into HTML text with links to documentation for
 * built-in types.
 * 
 * 
 * This class exists in its current form only because the type resolver in Bazel's Starlark
 * interpreter is (1) disabled by default and (2) doesn't support providers yet. But in future, we
 * shouldn't need type expressions inside docstrings at all; the desired end state should be to
 * resolve type annotations in actual Starlark code, and then serialize the StarlarkType-s in
 * ModuleInfoExtractor into Stardoc protos.
 */
class TypeParser(typeIdentifierToCategory: MutableMap<String?, StarlarkDocumentationProcessor.Category?>) {
    private val typeIdentifierToCategory: com.google.common.collect.ImmutableMap<String?, StarlarkDocumentationProcessor.Category?>

    init {
        this.typeIdentifierToCategory =
            com.google.common.collect.ImmutableMap.copyOf<String?, StarlarkDocumentationProcessor.Category?>(
                typeIdentifierToCategory
            )
    }

    /**
     * The type expression and remainder parts of a docstring for a parameter, return type, or
     * provider field.
     */
    @kotlin.jvm.JvmRecord
    data class TypedDocstring(val typeExpression: String?, val remainder: String?) {
        companion object {
            // Assume that type expressions cannot contain '(' or ')', so we can extract them using a regex.
            private val TYPED_DOCSTRING_PATTERN: java.util.regex.Pattern =
                java.util.regex.Pattern.compile("^\\(([^\\)]+)\\):?\\s*(.*)$")

            /**
             * Splits a docstring into type expression and remainder parts. Specifically, we expect a
             * docstring of the form `"(" <type expression> ")" <separator> <remainder>`, where `<separator>` is an optional ':' followed by 0 or more whitespace characters; for example,
             * `"(string): Some free text about this parameter"`.
             */
            // TODO(arostovtsev): fix ModoleInfoExtractor to also support the `param: (int) ...` form in
            // docstrings.
            fun of(docstring: String?): TypedDocstring {
                val matcher: java.util.regex.Matcher = TYPED_DOCSTRING_PATTERN.matcher(docstring)
                if (matcher.matches()) {
                    return TypedDocstring(matcher.group(1), matcher.group(2))
                } else {
                    return TypedDocstring("", docstring)
                }
            }
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    fun getHtml(typeExpression: String, fallback: String?): String? {
        if (typeExpression.isEmpty()) {
            return fallback
        }
        val parsedTypeExpression: net.starlark.java.syntax.Expression?
        try {
            parsedTypeExpression = net.starlark.java.syntax.Expression.parseTypeExpression(
                net.starlark.java.syntax.ParserInput.fromLines(typeExpression)
            )
        } catch (ex: net.starlark.java.syntax.SyntaxError.Exception) {
            throw Starlark.errorf("Failed to parse type expression '%s': %s", typeExpression, ex)
        }
        return emitHtml(java.lang.StringBuilder("<code>"), parsedTypeExpression).append("</code>").toString()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    fun getHtml(typeExpression: String): String? {
        return getHtml(typeExpression,  /* fallback= */"")
    }

    fun getHtmlForIdentifier(name: String?): String {
        return emitHtmlForIdentifier(java.lang.StringBuilder("<code>"), name).append("</code>").toString()
    }

    fun isDocumentedIdentifier(name: String?): Boolean {
        return SPECIAL_TYPE_URLS.containsKey(name) || typeIdentifierToCategory.containsKey(name)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(net.starlark.java.eval.EvalException::class)
    private fun emitHtml(
        sb: java.lang.StringBuilder,
        typeExpression: net.starlark.java.syntax.Expression?
    ): java.lang.StringBuilder? {
        if (typeExpression is net.starlark.java.syntax.Identifier) {
            return emitHtmlForIdentifier(sb, typeExpression.getName())
        } else if (typeExpression is net.starlark.java.syntax.BinaryOperatorExpression) {
            if (typeExpression.operator != net.starlark.java.syntax.TokenKind.PIPE) {
                throw Starlark.errorf(
                    "Unexpected operator '%s' in type expression '%s'", typeExpression.operator, typeExpression
                )
            }
            emitHtml(sb, typeExpression.x)
            sb.append(" | ")
            return emitHtml(sb, typeExpression.y)
        } else if (typeExpression is net.starlark.java.syntax.TypeApplication) {
            val constructor: net.starlark.java.syntax.Identifier = typeExpression.getConstructor()
            emitHtml(sb, constructor)
            sb.append("[")
            var first = true
            for (arg in typeExpression.getArguments()) {
                if (first) {
                    first = false
                } else {
                    sb.append(", ")
                }
                emitHtml(sb, arg)
            }
            return sb.append("]")
        }
        throw Starlark.errorf("Unsupported type expression '%s'", typeExpression)
    }

    private fun emitHtmlForIdentifier(sb: java.lang.StringBuilder, name: String?): java.lang.StringBuilder {
        val url = getUrl(name, typeIdentifierToCategory)
        if (url.isEmpty()) {
            return sb.append(name)
        } else {
            return sb.append(
                String.format(
                    "<a class=\"anchor\" href=\"%s\">%s</a>",
                    getUrl(name, typeIdentifierToCategory), name
                )
            )
        }
    }

    companion object {
        private const val STARLARK_SPEC_URL = "https://github.com/bazelbuild/starlark/blob/master/spec.md"
        private val SPECIAL_TYPE_URLS: com.google.common.collect.ImmutableMap<String?, String> =
            com.google.common.collect.ImmutableMap.of<String?, String?>(
                "None", "",
                "Collection", STARLARK_SPEC_URL + "#collection-types",
                "Sequence", STARLARK_SPEC_URL + "#collection-types",
                "Mapping", STARLARK_SPEC_URL + "#collection-types",
                "Callable", STARLARK_SPEC_URL + "#functions"
            )

        private fun getUrl(
            name: String?,
            docPages: MutableMap<String?, StarlarkDocumentationProcessor.Category?>
        ): String {
            if (SPECIAL_TYPE_URLS.containsKey(name)) {
                return SPECIAL_TYPE_URLS.get(name)
            } else if (docPages.containsKey(name)) {
                return String.format("../%s/%s.html", docPages.get(name).getPath(), name)
            } else {
                return ""
            }
        }
    }
}
