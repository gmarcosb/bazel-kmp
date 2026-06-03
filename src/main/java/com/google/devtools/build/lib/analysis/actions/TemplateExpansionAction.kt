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

/** Action to expand a template and write the expanded content to a file.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable // if all substitutions are immutable
class TemplateExpansionAction private constructor(
    owner: ActionOwner?,
    inputs: NestedSet<Artifact?>?,
    primaryOutput: Artifact,
    template: com.google.devtools.build.lib.analysis.actions.Template,
    substitutions: MutableList<com.google.devtools.build.lib.analysis.actions.Substitution?>,
    makeExecutable: Boolean
) : AbstractAction(owner, inputs, com.google.common.collect.ImmutableSet.of<E?>(primaryOutput)) {
    private val template: com.google.devtools.build.lib.analysis.actions.Template
    private val substitutions: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.analysis.actions.Substitution>
    private val makeExecutable: Boolean

    /**
     * Creates a new TemplateExpansionAction instance.
     * 
     * @param owner the action owner.
     * @param inputs the Artifacts that this Action depends on
     * @param primaryOutput the Artifact that will be created by executing this Action.
     * @param template the template that will be expanded by this Action.
     * @param substitutions the substitutions that will be applied to the template. All substitutions
     * will be applied in order.
     * @param makeExecutable iff true will change the output file to be executable.
     */
    init {
        this.template = template
        this.substitutions =
            com.google.common.collect.ImmutableList.copyOf<com.google.devtools.build.lib.analysis.actions.Substitution?>(
                substitutions
            )
        this.makeExecutable = makeExecutable
    }

    /**
     * Creates a new TemplateExpansionAction instance for an artifact template.
     * 
     * @param owner the action owner.
     * @param templateArtifact the Artifact that will be read as the text template
     * file
     * @param output the Artifact that will be created by executing this Action.
     * @param substitutions the substitutions that will be applied to the
     * template. All substitutions will be applied in order.
     * @param makeExecutable iff true will change the output file to be
     * executable.
     */
    constructor(
        owner: ActionOwner?,
        templateArtifact: Artifact?,
        output: Artifact,
        substitutions: MutableList<com.google.devtools.build.lib.analysis.actions.Substitution?>,
        makeExecutable: Boolean
    ) : this(
        owner,
        NestedSetBuilder.create(Order.STABLE_ORDER, templateArtifact),
        output,
        com.google.devtools.build.lib.analysis.actions.Template.Companion.forArtifact(templateArtifact),
        substitutions,
        makeExecutable
    )

    /**
     * Creates a new TemplateExpansionAction instance without inputs.
     * 
     * @param owner the action owner.
     * @param output the Artifact that will be created by executing this Action.
     * @param template the template
     * @param substitutions the substitutions that will be applied to the
     * template. All substitutions will be applied in order.
     * @param makeExecutable iff true will change the output file to be
     * executable.
     */
    constructor(
        owner: ActionOwner?,
        output: Artifact,
        template: com.google.devtools.build.lib.analysis.actions.Template,
        substitutions: MutableList<com.google.devtools.build.lib.analysis.actions.Substitution?>,
        makeExecutable: Boolean
    ) : this(
        owner,
        NestedSetBuilder.emptySet(Order.STABLE_ORDER),
        output,
        template,
        substitutions,
        makeExecutable
    )

    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    public override fun execute(actionExecutionContext: ActionExecutionContext): ActionResult {
        return execute(
            actionExecutionContext,
            this,
            TemplateMetadata.Companion.builder()
                .setTemplate(template)
                .setPrimaryOutput(getPrimaryOutput())
                .setSubstitutions(substitutions)
                .setMakeExecutable(makeExecutable)
                .build()
        )
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(IOException::class, net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    fun getFileContents(): String? {
        return LocalTemplateExpansionStrategy.Companion.INSTANCE.getExpandedTemplateUnsafe(
            template, substitutions, ArtifactPathResolver.IDENTITY
        )
    }

    @Throws(IOException::class, net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    public override fun getStarlarkContent(): String? {
        return getFileContents()
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    protected override fun computeKey(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        fp.addString(GUID)
        fp.addString(makeExecutable.toString())
        fp.addString(template.getKey())
        fp.addInt(substitutions.size)
        for (entry in substitutions) {
            fp.addString(entry.getKey())
            fp.addString(entry.getValue())
        }
    }

    public override fun describeKey(): String? {
        return String.format(
            "GUID: %s\nmakeExecutable: %s\ntemplate: %s\nsubstitutions: %s\n",
            GUID, makeExecutable, template.getKey(), substitutions
        )
    }

    public override fun getMnemonic(): String {
        return "TemplateExpand"
    }

    protected override fun getRawProgressMessage(): String {
        return "Expanding template " + com.google.common.collect.Iterables.getOnlyElement<T?>(getOutputs())
            .prettyPrint()
    }

    fun getSubstitutions(): MutableList<com.google.devtools.build.lib.analysis.actions.Substitution> {
        return substitutions
    }

    fun getTemplate(): com.google.devtools.build.lib.analysis.actions.Template {
        return template
    }

    fun makeExecutable(): Boolean {
        return makeExecutable
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    public override fun getStarlarkSubstitutions(): Dict<String?, String?>? {
        val builder: net.starlark.java.eval.Dict.Builder<String?, String?> = Dict.builder<String?, String?>()
        for (entry in substitutions) {
            builder.put(entry.getKey(), entry.getValue())
        }
        return builder.buildImmutable()
    }

    companion object {
        private const val GUID = "786c1fe0-dca8-407a-b108-e1ecd6d1bc7f"

        @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
        fun execute(
            actionExecutionContext: ActionExecutionContext,
            action: AbstractAction?,
            templateMetadata: TemplateMetadata?
        ): ActionResult {
            try {
                val result: com.google.common.collect.ImmutableList<SpawnResult?>? =
                    actionExecutionContext
                        .getContext(TemplateExpansionContext::class.java)
                        .expandTemplate(action, actionExecutionContext, templateMetadata)

                return ActionResult.create(result)
            } catch (e: net.starlark.java.eval.EvalException) {
                val exitCode: DetailedExitCode? =
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setExecution(
                                Execution.newBuilder()
                                    .setCode(Execution.Code.LOCAL_TEMPLATE_EXPANSION_FAILURE)
                            )
                            .build()
                    )
                throw ActionExecutionException(e, action,  /* catastrophe= */false, exitCode)
            } catch (e: ExecException) {
                throw ActionExecutionException.fromExecException(e, action)
            }
        }
    }
}
