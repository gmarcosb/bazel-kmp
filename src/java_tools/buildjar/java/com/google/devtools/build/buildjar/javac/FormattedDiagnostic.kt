// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.buildjar.javac

import com.google.devtools.build.buildjar.javac.FormattedDiagnostic
import com.google.devtools.build.buildjar.javac.WerrorCustomOption
import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.Builder.build
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule.Builder.build
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.build
import com.google.devtools.build.lib.clock.BlazeClock.instance
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import com.sun.tools.javac.api.ClientCodeWrapper
import com.sun.tools.javac.api.DiagnosticFormatter
import com.sun.tools.javac.code.Lint.LintCategory
import com.sun.tools.javac.util.JCDiagnostic
import com.sun.tools.javac.util.JavacMessages
import java.nio.file.Path
import java.util.Locale
import javax.tools.Diagnostic
import javax.tools.DiagnosticListener
import javax.tools.JavaFileObject

/**
 * A [<] that includes the full formatted message produced by javac,
 * which relies on compilation internals and can't be reproduced after the compilation is complete.
 */
class FormattedDiagnostic(
    diagnostic: Diagnostic<out JavaFileObject?>,
    formatted: String?,
    lintCategory: String?,
    werror: Boolean
) : Diagnostic<JavaFileObject?> {
    val diagnostic: Diagnostic<out JavaFileObject?>

    /** The formatted diagnostic message produced by javac's diagnostic formatter.  */
    val formatted: String?
    val lintCategory: String?
    val werror: Boolean

    init {
        this.diagnostic = diagnostic
        this.formatted = formatted
        this.lintCategory = lintCategory
        this.werror = werror
    }

    override fun toString(): String {
        return formatted!!
    }

    val kind: Diagnostic.Kind?
        get() = if (werror) Diagnostic.Kind.ERROR else diagnostic.getKind()

    val source: JavaFileObject?
        get() = diagnostic.getSource()

    val position: Long
        get() = diagnostic.getPosition()

    val startPosition: Long
        get() = diagnostic.getStartPosition()

    val endPosition: Long
        get() = diagnostic.getEndPosition()

    val lineNumber: Long
        get() = diagnostic.getLineNumber()

    val columnNumber: Long
        get() = diagnostic.getColumnNumber()

    val code: String?
        get() = diagnostic.getCode()

    override fun getMessage(locale: Locale?): String? {
        return diagnostic.getMessage(locale)
    }

    /** A [<] that saves [FormattedDiagnostic]s.  */
    @ClientCodeWrapper.Trusted
    internal class Listener(
        private val failFast: Boolean,
        werrorCustomOption: java.util.Optional<WerrorCustomOption?>,
        context: com.sun.tools.javac.util.Context,
        workDir: Path
    ) : DiagnosticListener<JavaFileObject?> {
        private val diagnostics: com.google.common.collect.ImmutableList.Builder<FormattedDiagnostic?> =
            com.google.common.collect.ImmutableList.builder<FormattedDiagnostic?>()
        private val werrorCustomOption: java.util.Optional<WerrorCustomOption?>
        private val context: com.sun.tools.javac.util.Context

        // Strips the (non-hermetic) working directory from paths in diagnostics when using multiplex
        // sandboxing.
        private val workDirPattern: java.util.regex.Pattern?

        private var werror = false

        init {
            this.werrorCustomOption = werrorCustomOption
            // retrieve context values later, in case it isn't initialized yet
            this.context = context
            if (workDir.toString().isEmpty()) {
                this.workDirPattern = null
            } else {
                this.workDirPattern =
                    java.util.regex.Pattern.compile("^" + java.util.regex.Pattern.quote(workDir.toString() + java.io.File.separator))
            }
        }

        override fun report(diagnostic: Diagnostic<out JavaFileObject?>?) {
            val jcDiagnostic: JCDiagnostic = diagnostic as JCDiagnostic
            val werror = isWerror(jcDiagnostic)
            if (werror) {
                this.werror = true
            }
            val formatter: DiagnosticFormatter<JCDiagnostic?> =
                com.sun.tools.javac.util.Log.instance(context).getDiagnosticFormatter()
            val messages: JavacMessages = JavacMessages.instance(context)
            val locale: Locale? = messages.getCurrentLocale()
            var formatted: String = formatter.format(jcDiagnostic, locale)
            if (werror) {
                formatted =
                    formatted.replaceFirst(
                        formatter.formatKind(jcDiagnostic, locale).toRegex(),
                        messages.getLocalizedString(locale, "compiler.err.error")
                    )
            }
            if (workDirPattern != null) {
                formatted = workDirPattern.matcher(formatted).replaceAll("")
            }
            val lintCategory: LintCategory? = jcDiagnostic.getLintCategory()
            val formattedDiagnostic =
                FormattedDiagnostic(
                    diagnostic, formatted, if (lintCategory != null) lintCategory.option else null, werror
                )
            diagnostics.add(formattedDiagnostic)
            if (failFast && diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                throw FailFastException(formatted)
            }
        }

        private fun isWerror(diagnostic: JCDiagnostic): Boolean {
            if (werrorCustomOption.isEmpty()) {
                return false
            }
            val lintCategory: String? =
                com.google.devtools.build.buildjar.javac.FormattedDiagnostic.Listener.Companion.lintCategory(diagnostic)
            if (lintCategory == null) {
                return false
            }
            when (diagnostic.getKind()) {
                Diagnostic.Kind.WARNING, Diagnostic.Kind.MANDATORY_WARNING -> return werrorCustomOption.get()
                    .isEnabled(lintCategory)

                else -> return false
            }
        }

        fun build(): com.google.common.collect.ImmutableList<FormattedDiagnostic?> {
            return diagnostics.build()
        }

        fun werror(): Boolean {
            return werror
        }

        companion object {
            private fun lintCategory(diagnostic: JCDiagnostic): String? {
                if (diagnostic.getCode() == "compiler.warn.sun.proprietary") {
                    return "sunapi"
                }
                val lintCategory: LintCategory? = diagnostic.getLintCategory()
                if (lintCategory == null) {
                    return null
                }
                return lintCategory.option
            }
        }
    }

    internal class FailFastException(message: String?) : java.lang.RuntimeException(message)

    val isJSpecifyDiagnostic: Boolean
        get() = this.kind == Diagnostic.Kind.ERROR
                && this.code == "compiler.err.proc.messager"
                && getMessage(Locale.ENGLISH).startsWith("[nullness] ")
}
