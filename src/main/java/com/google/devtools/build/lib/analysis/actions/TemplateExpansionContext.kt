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

import com.google.devtools.build.lib.actions.AbstractAction

/** The action context for [TemplateExpansionAction] instances  */
interface TemplateExpansionContext : ActionContext {
    /** Placeholder for metadata associated with a template.  */
    class TemplateMetadata(
        template: com.google.devtools.build.lib.analysis.actions.Template?,
        primaryOutput: Artifact?,
        substitutions: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.analysis.actions.Substitution?>?,
        val makeExecutable: Boolean
    ) {
        /** Builder of [TemplateMetadata] instances.  */
        @AutoBuilder
        abstract class Builder {
            abstract fun setTemplate(value: com.google.devtools.build.lib.analysis.actions.Template?): Builder?

            abstract fun setPrimaryOutput(value: Artifact?): Builder?

            abstract fun setSubstitutions(value: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.analysis.actions.Substitution?>?): Builder?

            abstract fun setMakeExecutable(value: Boolean): Builder?

            abstract fun build(): TemplateMetadata?
        }

        val template: com.google.devtools.build.lib.analysis.actions.Template?
        val primaryOutput: Artifact?
        val substitutions: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.analysis.actions.Substitution?>?

        init {
            this.substitutions = substitutions
            this.primaryOutput = primaryOutput
            this.template = template
            java.util.Objects.requireNonNull<com.google.devtools.build.lib.analysis.actions.Template?>(
                template,
                "template"
            )
            java.util.Objects.requireNonNull<Any?>(primaryOutput, "primaryOutput")
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<com.google.devtools.build.lib.analysis.actions.Substitution?>?>(
                substitutions,
                "substitutions"
            )
        }

        companion object {
            fun builder(): Builder {
                return AutoBuilder_TemplateExpansionContext_TemplateMetadata_Builder()
            }
        }
    }

    @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class, ExecException::class)
    fun expandTemplate(
        action: AbstractAction?, ctx: ActionExecutionContext?, templateMetadata: TemplateMetadata?
    ): com.google.common.collect.ImmutableList<SpawnResult?>?
}
