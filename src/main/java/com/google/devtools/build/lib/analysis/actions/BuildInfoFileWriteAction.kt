// Copyright 2023 The Bazel Authors. All rights reserved.
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

/**
 * Translates workspace status text files([ctx.info_file](https://bazel.build/rules/lib/ctx#info_file) and [ctx.version_file](https://bazel.build/rules/lib/ctx#version_file)) to a language
 * consumable file and writes its contents to output. Keys and values are translated by the callback
 * translation_func Starlark method, and the output file format is generated according to the
 * template.
 * 
 * 
 * Action takes text file as an input and transforms it with a user provided Starlark callback
 * function to a dictionary of strings to strings. This dictionary is then used as substitutions to
 * expand the user provided template file.
 */
class BuildInfoFileWriteAction(
    owner: ActionOwner?,
    input: Artifact,
    output: Artifact,
    translationCallback: StarlarkFunction,
    template: Artifact,
    isVolatile: Boolean,
    semantics: StarlarkSemantics?
) : AbstractAction(
    owner,
    NestedSetBuilder.create(Order.STABLE_ORDER, input, template),
    com.google.common.collect.ImmutableList.of<E?>(output)
) {
    private val translationCallback: StarlarkFunction
    private val template: Artifact
    private val isVolatile: Boolean
    private val semantics: StarlarkSemantics?

    init {
        com.google.common.base.Preconditions.checkNotNull<StarlarkFunction?>(translationCallback)
        com.google.common.base.Preconditions.checkNotNull<Any?>(template)
        com.google.common.base.Preconditions.checkArgument(
            input.getArtifactOwner() is BuildInfoKey,
            "input artifact of BuildInfoFileWriteAction must be one of workspace status artifacts:"
                    + " ctx.info_file or ctx.version_file"
        )
        this.translationCallback = translationCallback
        this.template = template
        this.isVolatile = isVolatile
        this.semantics = semantics
    }

    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    public override fun execute(ctx: ActionExecutionContext): ActionResult? {
        val values: MutableMap<String?, String?> = HashMap<String?, String?>()
        // Parse values from text file.
        try {
            val valueFile: Artifact? = getPrimaryInput()
            values.putAll(WorkspaceStatusAction.parseValues(ctx.getInputPath(valueFile)))
        } catch (e: IOException) {
            val message = "Failed to parse workspace status: " + e.message
            throw ActionExecutionException(
                message,  /* cause= */
                e,  /* action= */
                this,  /* catastrophe= */
                false,
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(message)
                        .setExecution(
                            Execution.newBuilder().setCode(Execution.Code.SOURCE_INPUT_IO_EXCEPTION)
                        )
                        .build()
                )
            )
        }
        // Call Starlark callback function which takes workspace status file's
        // content as an input and produces a dict which is written to the output.
        var substitutionDictObject: Any? = null
        Mutability.create("translate_build_info_file").use { mutability ->
            try {
                val thread: StarlarkThread? =
                    StarlarkThread.create(
                        mutability,
                        semantics,
                        if (isVolatile()) "transform_version_file callback" else "transform_info_file callback",  // Since the result of this thread is a String to String Dict, it should not result
                        // in any reference-equals objects.
                        SymbolGenerator.createTransient()
                    )
                substitutionDictObject =
                    Starlark.positionalOnlyCall(
                        thread,
                        translationCallback,
                        Dict.immutableCopyOf<String?, String?>(values)
                    )
            } catch (e: net.starlark.java.eval.EvalException) {
                val message: String? = String.format(
                    "Error during translating %s status file : %s",
                    if (isVolatile) "volatile" else "stable", e
                )
                throw ActionExecutionException(
                    message,  /* cause= */
                    e,  /* action= */
                    this,  /* catastrophe= */
                    false,
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(message)
                            .setExecution(
                                Execution.newBuilder().setCode(Execution.Code.NON_ACTION_EXECUTION_FAILURE)
                            )
                            .build()
                    )
                )
            }
            var substitutionDict: Dict<String?, String?>? = null
            try {
                substitutionDict =
                    Dict.cast<String?, String?>(
                        substitutionDictObject,
                        String::class.java,
                        String::class.java,
                        "substitution_dict"
                    )
            } catch (e: net.starlark.java.eval.EvalException) {
                val message =
                    ("BuildInfo translation callback function is expected to return dict of strings to"
                            + " strings, could not convert return value to Java type: "
                            + e)
                throw ActionExecutionException(
                    message,  /* cause= */
                    e,  /* action= */
                    this,  /* catastrophe= */
                    false,
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(message)
                            .setExecution(
                                Execution.newBuilder().setCode(Execution.Code.NON_ACTION_EXECUTION_FAILURE)
                            )
                            .build()
                    )
                )
            }
            val substitutionList: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.analysis.actions.Substitution?> =
                substitutionDict.entries.stream()
                    .map<com.google.devtools.build.lib.analysis.actions.Substitution?> { s: MutableMap.MutableEntry<String?, String?>? ->
                        com.google.devtools.build.lib.analysis.actions.Substitution.Companion.of(
                            s!!.key,
                            s.value
                        )
                    }
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<com.google.devtools.build.lib.analysis.actions.Substitution?>())
            return TemplateExpansionAction.Companion.execute( /* actionExecutionContext= */
                ctx,  /* action= */
                this,
                TemplateMetadata.Companion.builder()
                    .setTemplate(com.google.devtools.build.lib.analysis.actions.Template.Companion.forArtifact(template))
                    .setPrimaryOutput(getPrimaryOutput())
                    .setSubstitutions(substitutionList)
                    .setMakeExecutable(false)
                    .build()
            )
        }
    }

    protected override fun computeKey(
        actionKeyContext: ActionKeyContext?,
        inputMetadataProvider: InputMetadataProvider?,
        fp: Fingerprint
    ) {
        fp.addString(GUID)
        fp.addBoolean(isVolatile)
        // Add Starlark function to the fingerprint.
        fp.addBytes(BazelModuleContext.of(translationCallback.getModule()).bzlTransitiveDigest())
    }

    public override fun getMnemonic(): String {
        return "TranslateBuildInfo"
    }

    protected override fun getRawProgressMessage(): String {
        if (isVolatile) {
            return "Translating volatile BuildInfo file"
        } else {
            return "Translating stable BuildInfo file"
        }
    }

    public override fun executeUnconditionally(): Boolean {
        return isVolatile
    }

    public override fun isVolatile(): Boolean {
        return isVolatile
    }

    companion object {
        private const val GUID = "7e4657a6-dd09-435e-9423-51d4846aad4a"
    }
}
