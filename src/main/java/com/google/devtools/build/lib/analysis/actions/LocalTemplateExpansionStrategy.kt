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

/** Strategy to perform template expansion locally.  */
class LocalTemplateExpansionStrategy : TemplateExpansionContext {
    @Throws(java.lang.InterruptedException::class, ExecException::class)
    override fun expandTemplate(
        action: AbstractAction?,
        ctx: ActionExecutionContext,
        templateMetadata: TemplateMetadata
    ): com.google.common.collect.ImmutableList<SpawnResult?> {
        try {
            val expandedTemplate =
                getExpandedTemplateUnsafe(
                    templateMetadata.template, templateMetadata.substitutions, ctx.getPathResolver()
                )
            val deterministicWriter: DeterministicWriter =
                DeterministicWriter { out -> out.write(expandedTemplate.toByteArray(java.nio.charset.StandardCharsets.ISO_8859_1)) }
            return ctx.getContext(FileWriteActionContext::class.java)
                .writeOutputToFile(
                    action,
                    ctx,
                    deterministicWriter,
                    templateMetadata.makeExecutable,  /* isRemotable= */
                    true
                )
        } catch (e: IOException) {
            throw EnvironmentalExecException(
                e,
                FailureDetail.newBuilder()
                    .setExecution(
                        Execution.newBuilder().setCode(Execution.Code.LOCAL_TEMPLATE_EXPANSION_FAILURE)
                    )
                    .build()
            )
        } catch (e: net.starlark.java.eval.EvalException) {
            throw EnvironmentalExecException(
                e,
                FailureDetail.newBuilder()
                    .setExecution(
                        Execution.newBuilder().setCode(Execution.Code.LOCAL_TEMPLATE_EXPANSION_FAILURE)
                    )
                    .build()
            )
        }
    }

    /**
     * Get the result of the template expansion prior to executing the action. TODO(b/110418949): Stop
     * public access to this method as it's unhealthy to evaluate the action result without the action
     * being executed.
     */
    @Throws(net.starlark.java.eval.EvalException::class, IOException::class, java.lang.InterruptedException::class)
    fun getExpandedTemplateUnsafe(
        template: com.google.devtools.build.lib.analysis.actions.Template,
        substitutions: MutableList<com.google.devtools.build.lib.analysis.actions.Substitution>,
        resolver: ArtifactPathResolver?
    ): String {
        var templateString: String
        templateString = template.getContent(resolver)
        for (entry in substitutions) {
            templateString =
                StringUtilities.replaceAllLiteral(templateString, entry.getKey(), entry.getValue())
        }
        return templateString
    }

    companion object {
        val TYPE: java.lang.Class<LocalTemplateExpansionStrategy?> = LocalTemplateExpansionStrategy::class.java

        var INSTANCE: LocalTemplateExpansionStrategy = LocalTemplateExpansionStrategy()
    }
}
