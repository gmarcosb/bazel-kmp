// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.python

import com.google.common.collect.ImmutableCollection
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.devtools.build.lib.actions.ActionExecutionContext
import com.google.devtools.build.lib.util.OS
import net.starlark.java.annot.Param
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.Sequence
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkValue
import java.io.OutputStream
import javax.annotation.concurrent.Immutable

/** Bridge to allow builtins bzl code to call Java code.  */
@StarlarkBuiltin(name = "py_builtins", documented = false)
abstract class PyBuiltins protected constructor(emptyFilesSupplier: Runfiles.EmptyFilesSupplier?) : StarlarkValue {
    private val emptyFilesSupplier: Runfiles.EmptyFilesSupplier?

    init {
        this.emptyFilesSupplier = emptyFilesSupplier
    }

    @StarlarkMethod(
        name = "is_bzlmod_enabled",
        doc = "Tells if bzlmod is enabled",
        parameters = [Param(name = "ctx", positional = true, named = true, defaultValue = "unbound")]
    )
    fun isBzlmodEnabled(starlarkCtx: StarlarkRuleContext?): Boolean {
        return true
    }

    @StarlarkMethod(
        name = "is_singleton_depset",
        doc = "Efficiently checks if the depset is a singleton.",
        parameters = [Param(
            name = "value",
            positional = true,
            named = false,
            defaultValue = "unbound",
            doc = "depset to check for being a singleton"
        )]
    )
    fun isSingletonDepset(depset: Depset): Boolean {
        return depset.getSet().isSingleton()
    }

    @StarlarkMethod(
        name = "regex_match",
        doc = "Return true if subject matches pattern; pattern is implicitly anchored with ^ and $",
        parameters = [Param(
            name = "subject",
            positional = true,
            named = false,
            defaultValue = "unbound"
        ), Param(name = "pattern", positional = true, named = false, defaultValue = "unbound")]
    )
    fun regexMatch(subject: String, pattern: String?): Boolean {
        return subject.matches(pattern)
    }

    @StarlarkMethod(
        name = "get_rule_name",
        doc = "Get the name of the rule for the given ctx",
        parameters = [Param(name = "ctx", positional = true, named = true, defaultValue = "unbound")]
    )
    @Throws(
        EvalException::class
    )
    fun getRuleName(starlarkCtx: StarlarkRuleContext): String {
        return starlarkCtx.getRuleContext().getRule().getRuleClass()
    }

    @get:StarlarkMethod(
        name = "get_current_os_name",
        doc = "Get the name of the OS Bazel itself is running on.",
        parameters = []
    )
    val currentOsName: String?
        get() = OS.getCurrent().canonicalName

    @StarlarkMethod(
        name = "get_label_repo_runfiles_path",
        doc = "Given a label, return a runfiles path that includes the repository directory",
        parameters = [Param(name = "label", positional = true, named = true, defaultValue = "unbound")]
    )
    fun getLabelRepoRunfilesPath(label: Label): String {
        return label.getPackageIdentifier().getRunfilesPath().getPathString()
    }

    // TODO(bazel-team): Remove this once rules are switched to using execpath semanatics for the
    // $(location) function. See https://github.com/bazelbuild/bazel/issues/15294
    @StarlarkMethod(
        name = "expand_location_and_make_variables",
        doc = ("Expands $(location) and makevar references. Note that $(location) performs "
                + "rootpath (runfiles-relative) expansion, not execpath expansion."),
        parameters = [Param(
            name = "ctx",
            positional = false,
            named = true,
            defaultValue = "unbound",
            doc = "Rule context"
        ), Param(
            name = "attribute_name",
            positional = false,
            named = true,
            defaultValue = "unbound",
            doc = "Name of attribute being expanded; only used for error reporting."
        ), Param(
            name = "expression",
            positional = false,
            named = true,
            defaultValue = "unbound",
            doc = "The expression to expand."
        ), Param(
            name = "targets",
            positional = false,
            named = true,
            defaultValue = "unbound",
            doc = "List of additional targets to allow in expansions"
        )]
    )
    @Throws(
        EvalException::class, InterruptedException::class
    )
    fun expandLocationAndMakeVariables(
        ruleContext: StarlarkRuleContext, attributeName: String?, expression: String?, targets: Sequence<*>?
    ): Any {
        val builder: ImmutableMap.Builder<Label?, ImmutableCollection<Artifact?>?> =
            ImmutableMap.builder<Label?, ImmutableCollection<Artifact?>?>()

        for (current in Sequence.cast<TransitiveInfoCollection>(
            targets,
            TransitiveInfoCollection::class.java,
            "targets"
        )) {
            val artifacts: ImmutableList<Artifact?>?
            // This logic is basically a copy of how LocationExpander.java treats the data attribute.
            val filesToRun: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                current.getProvider(FilesToRunProvider::class.java)
            val filesToBuild: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                current.getProvider(FileProvider::class.java).getFilesToBuild()
            if (filesToRun == null) {
                artifacts = filesToBuild.toList()
            } else {
                val executable: Artifact? = filesToRun.getExecutable()
                if (executable == null) {
                    artifacts = filesToBuild.toList()
                } else {
                    artifacts = ImmutableList.of<Artifact?>(executable)
                }
            }
            builder.put(AliasProvider.getDependencyLabel(current), artifacts)
        }

        return ruleContext
            .getRuleContext()
            .getExpander(builder.buildOrThrow())
            .withDataLocations() // Enables $(location) expansion.
            .expand(attributeName, expression)
    }

    @StarlarkMethod(
        name = "create_repo_mapping_manifest",
        doc = "Write a repo_mapping file for the given runfiles",
        parameters = [Param(
            name = "ctx",
            positional = false,
            named = true,
            defaultValue = "unbound"
        ), Param(name = "runfiles", positional = false, named = true, defaultValue = "unbound"), Param(
            name = "output",
            positional = false,
            named = true,
            defaultValue = "unbound"
        )]
    )
    fun repoMappingAction(
        starlarkCtx: StarlarkRuleContext, runfiles: Runfiles, repoMappingManifest: Artifact?
    ) {
        val ruleContext: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            starlarkCtx.getRuleContext()
        ruleContext
            .getAnalysisEnvironment()
            .registerAction(
                RepoMappingManifestAction(
                    ruleContext.getActionOwner(),
                    repoMappingManifest,
                    ruleContext.getTransitivePackagesForRunfileRepoMappingManifest(),
                    runfiles.getArtifacts(),
                    runfiles.getSymlinks(),
                    runfiles.getRootSymlinks(),
                    ruleContext.getWorkspaceName(),
                    ruleContext
                        .getConfiguration()
                        .getOptions()
                        .get(CoreOptions::class.java)
                        .getCompactRepoMapping()
                )
            )
    }

    @StarlarkMethod(
        name = "merge_runfiles_with_generated_inits_empty_files_supplier",
        doc = ("Create a runfiles that generates missing __init__.py files using Java and "
                + "the internal EmptyFilesProvider interface"),
        parameters = [Param(
            name = "ctx",
            positional = false,
            named = true,
            defaultValue = "unbound"
        ), Param(
            name = "runfiles",
            positional = false,
            named = true,
            defaultValue = "unbound",
            doc = "Runfiles to merge into the result; must be non-empty"
        )]
    )
    @Throws(
        EvalException::class
    )
    fun mergeRunfilesWithGeneratedInitsEmptyFilesSupplier(
        starlarkCtx: StarlarkRuleContext, runfiles: Runfiles
    ): Any {
        if (runfiles.isEmpty()) {
            // The Runfiles merge functions have an optimization to detect an empty runfiles and return a
            // singleton. Unfortunately, this optimization considers an empty runfiles with
            // a emptyFilesSupplier as empty, so then drops it when returning the singleton. To work
            // around this, require that there is *something* in the runfiles.
            throw Starlark.errorf("input runfiles cannot be empty")
        }
        return Builder(starlarkCtx.getWorkspaceName())
            .setEmptyFilesSupplier(emptyFilesSupplier)
            .merge(runfiles)
            .build()
    }

    @StarlarkMethod(
        name = "declare_constant_metadata_file",
        doc = "Declare a file that always reports it is unchanged.",
        parameters = [Param(
            name = "ctx",
            positional = false,
            named = true,
            defaultValue = "unbound"
        ), Param(name = "name", positional = false, named = true, defaultValue = "unbound"), Param(
            name = "root",
            positional = false,
            named = true,
            defaultValue = "unbound"
        )]
    )
    fun declareConstantMetadataFile(
        ctx: StarlarkRuleContext, name: String?, rootUnchecked: Any?
    ): Any {
        val root: ArtifactRoot? = rootUnchecked as ArtifactRoot?
        return ctx.getRuleContext()
            .getAnalysisEnvironment()
            .getConstantMetadataArtifact(
                ctx.getRuleContext().getPackageDirectory().getRelative(PathFragment.create(name)),
                root
            )
    }

    @StarlarkMethod(
        name = "create_sources_only_manifest",
        doc = "Create a manifest of the files in runfiles",
        parameters = [Param(
            name = "ctx",
            positional = false,
            named = true,
            defaultValue = "unbound"
        ), Param(name = "runfiles", positional = false, named = true, defaultValue = "unbound"), Param(
            name = "output",
            positional = false,
            named = true,
            defaultValue = "unbound"
        )]
    )
    fun createRunfilesManifest(
        starlarkCtx: StarlarkRuleContext, runfiles: Runfiles?, output: Artifact?
    ) {
        val ruleContext: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            starlarkCtx.getRuleContext()
        ruleContext
            .getAnalysisEnvironment()
            .registerAction(
                SourceManifestAction(
                    ManifestType.SOURCES_ONLY,
                    ruleContext.getActionOwner(),
                    output,
                    runfiles,  /* repoMappingManifest= */
                    null,
                    ruleContext.getConfiguration().remotableSourceManifestActions()
                )
            )
    }

    @StarlarkMethod(
        name = "copy_without_caching",
        doc = "Copy one file to another, but with action caching disabled.",
        parameters = [Param(
            name = "ctx",
            positional = false,
            named = true,
            defaultValue = "unbound"
        ), Param(
            name = "read_from",
            positional = false,
            named = true,
            defaultValue = "unbound"
        ), Param(name = "write_to", positional = false, named = true, defaultValue = "unbound")]
    )
    @Throws(
        InterruptedException::class
    )
    fun copyWithoutCaching(ctx: StarlarkRuleContext, readFrom: Artifact?, writeTo: Artifact?): Any? {
        val ruleContext: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ctx.getRuleContext()
        ruleContext.registerAction(
            CopyWithoutCachingAction(ruleContext.getActionOwner(), readFrom, writeTo)
        )
        return Starlark.NONE
    }

    @Immutable
    internal class CopyWithoutCachingAction(owner: ActionOwner?, readFrom: Artifact?, writeTo: Artifact?) :
        AbstractFileWriteAction(owner, NestedSetBuilder.create(Order.STABLE_ORDER, readFrom), writeTo) {
        public override fun newDeterministicWriter(ctx: ActionExecutionContext): DeterministicWriter {
            return DeterministicWriter { out: OutputStream? ->
                ctx.getInputPath(getPrimaryInput()).getInputStream().transferTo(out)
            }
        }

        public override fun executeUnconditionally(): Boolean {
            return true
        }

        val isVolatile: Boolean
            get() = true

        // NOTE: This method is effectively unused because executeUnconditionally=true.
        protected override fun computeKey(
            actionKeyContext: ActionKeyContext?,
            inputMetadataProvider: InputMetadataProvider?,
            fp: Fingerprint
        ) {
            fp.addString(GUID)
            fp.addPath(getPrimaryInput().getPath())
            fp.addPath(getPrimaryOutput().getPath())
        }

        public override fun describeKey(): String {
            val message = StringBuilder()
            message
                .append("executeUnconditionally: ")
                .append(executeUnconditionally())
                .append("\nGUID: ")
                .append(GUID)
                .append("\nreadFrom: ")
                .append(getPrimaryInput().getExecPathString())
                .append("\nwriteTo: ")
                .append(getPrimaryOutput().getExecPathString())
                .append('\n')

            return message.toString()
        }

        protected val rawProgressMessage: String?
            get() = java.lang.String.format(
                "Copying %s to %s (uncachable action)",
                getPrimaryInput().getExecPathString(), getPrimaryOutput().getExecPathString()
            )

        val mnemonic: String
            get() = "PyCopyWithoutCaching"

        companion object {
            private const val GUID = "67513fa7-3824-493b-aeab-95a8b778ea07"
        }
    }

    // TODO(b/253059598): Remove support for this; https://github.com/bazelbuild/bazel/issues/16455
    @StarlarkMethod(
        name = "are_action_listeners_enabled",
        doc = ("Tells if any action listeners are enabled. This is to prevent registering "
                + "extra actions unless necessary"),
        parameters = [Param(
            name = "ctx",
            positional = true,
            named = false,
            defaultValue = "unbound",
            doc = "Rule context"
        )]
    )
    fun areActionListenersEnabled(starlarkCtx: StarlarkRuleContext): Boolean {
        return !starlarkCtx.getRuleContext().getConfiguration().getActionListeners().isEmpty()
    }

    // TODO(b/253059598): Remove support for this; https://github.com/bazelbuild/bazel/issues/16455
    @StarlarkMethod(
        name = "add_py_extra_pseudo_action",
        doc = "Adds an extra pseudo action for (deprecated) extra actions support",
        parameters = [Param(
            name = "ctx",
            positional = false,
            named = true,
            defaultValue = "unbound",
            doc = "Rule context"
        ), Param(
            name = "dependency_transitive_python_sources",
            positional = false,
            named = true,
            defaultValue = "unbound",
            doc = "Depset of Artifacts from PyInfo.transitive_sources from the deps attribute"
        )]
    )
    @Throws(
        EvalException::class
    )
    fun addPyExtraActionPseudoAction(
        starlarkCtx: StarlarkRuleContext, uncheckedDependencyTransitivePythonSources: Depset?
    ) {
        val dependencyTransitivePythonSources: NestedSet<Artifact?>? =
            Depset.cast(
                uncheckedDependencyTransitivePythonSources,
                Artifact::class.java,
                "dependency_transitive_python_sources"
            )
        PyCommon.registerPyExtraActionPseudoAction(
            starlarkCtx.getRuleContext(), dependencyTransitivePythonSources
        )
    }

    companion object {
        const val NAME: String = "py_builtins"
    }
}
