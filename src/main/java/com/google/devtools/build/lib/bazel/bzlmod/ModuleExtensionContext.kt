// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.analysis.BlazeDirectories

/** The Starlark object passed to the implementation function of module extensions.  */
@net.starlark.java.annot.StarlarkBuiltin(
    name = "module_ctx",
    category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
    doc = ("The context of the module extension containing helper functions and information about"
            + " pertinent tags across the dependency graph. You get a module_ctx object as an"
            + " argument to the <code>implementation</code> function when you create a module"
            + " extension.")
)
class ModuleExtensionContext(
    workingDirectory: com.google.devtools.build.lib.vfs.Path?,
    directories: BlazeDirectories?,
    env: SkyFunction.Environment?,
    repoEnv: com.google.common.collect.ImmutableMap<String?, String?>?,
    nonstrictRepoEnv: com.google.common.collect.ImmutableMap<String?, String?>?,
    downloadManager: DownloadManager?,
    timeoutScaling: Double,
    processWrapper: ProcessWrapper?,
    starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?,
    remoteExecutor: RepositoryRemoteExecutor?,
    extensionId: ModuleExtensionId,
    modules: net.starlark.java.eval.StarlarkList<StarlarkBazelModule?>?,
    facts: Facts?,
    rootModuleHasNonDevDependency: Boolean,
    staticEnvVars: com.google.common.collect.ImmutableMap<String?, java.util.Optional<String?>?>,
    staticRepoMappingEntries: com.google.common.collect.ImmutableTable<RepositoryName?, String?, RepositoryName?>
) : StarlarkBaseExternalContext(
    workingDirectory,
    directories,
    env,
    repoEnv,
    nonstrictRepoEnv,
    downloadManager,
    timeoutScaling,
    processWrapper,
    starlarkSemantics,
    ModuleExtensionEvaluationProgress.Companion.moduleExtensionEvaluationContextString(extensionId),
    remoteExecutor,  /* allowWatchingPathsOutsideWorkspace= */
    false
) {
    private val extensionId: ModuleExtensionId
    private val modules: net.starlark.java.eval.StarlarkList<StarlarkBazelModule?>?
    private val facts: Facts?
    private val rootModuleHasNonDevDependency: Boolean

    init {
        this.extensionId = extensionId
        this.modules = modules
        this.facts = facts
        this.rootModuleHasNonDevDependency = rootModuleHasNonDevDependency
        // Record inputs to the extension that are known prior to evaluation.
        RepoRecordedInput.EnvVar.wrap(staticEnvVars)
            .forEach(java.util.function.BiConsumer { input: RepoRecordedInput.EnvVar?, value: java.util.Optional<kotlin.String?>? ->
                recordInputWithValue(
                    input,
                    value.orElse(null)
                )
            })
        repoMappingRecorder.record(staticRepoMappingEntries)
    }

    override fun shouldDeleteWorkingDirectoryOnClose(successful: Boolean): Boolean {
        // The contents of the working directory are purely ephemeral, only the repos instantiated by
        // the extension are considered its results.
        return true
    }

    override fun isRemotable(): Boolean {
        // Maybe we can some day support remote execution, but not today.
        return false
    }

    override fun getRemoteExecProperties(): com.google.common.collect.ImmutableMap<String?, String?> {
        return com.google.common.collect.ImmutableMap.of<String?, String?>()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "modules",
        structField = true,
        doc = ("A list of all the Bazel modules in the external dependency graph that use this module "
                + "extension, each of which is a <a href=\"../builtins/bazel_module.html\">"
                + "bazel_module</a> object that exposes all the tags it specified for this extension."
                + " The iteration order of this dictionary is guaranteed to be the same as"
                + " breadth-first search starting from the root module.")
    )
    fun getModules(): net.starlark.java.eval.StarlarkList<StarlarkBazelModule?>? {
        return modules
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "facts", structField = true, doc = """
          The JSON-like dict returned by a previous execution of this extension in the `facts`
          parameter of [`extension_metadata`](../builtins/module_ctx#extension_metadata) or else
          `{}`.
          This is useful for extensions that want to preserve universally true facts such as the
          hashes of artifacts in an immutable repository.
          Note that the returned value may have been created by a different version of the
          extension, which may have used a different schema.
          
          """.trimIndent()
    )
    fun getFacts(): Facts? {
        return facts
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "is_dev_dependency", doc = ("Returns whether the given tag was specified on the result of a <a "
                + "href=\"../globals/module.html#use_extension\">use_extension</a> call with "
                + "<code>devDependency = True</code>."), parameters = [net.starlark.java.annot.Param(
            name = "tag",
            doc = "A tag obtained from <a"
                    + " href=\"../builtins/bazel_module.html#tags\">bazel_module.tags</a>.",
            allowedTypes = [net.starlark.java.annot.ParamType(type = TypeCheckedTag::class)]
        )]
    )
    fun isDevDependency(tag: TypeCheckedTag): Boolean {
        return tag.isDevDependency()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "tag_sort_key", doc = """
          Returns an opaque <code>sort_key</code> object for the given tag, which can be compared with other <code>sort_key</code> objects to determine the relative order of tags, even across tag classes. Tags are ordered by their position within a module file and by the BFS ordering of modules in the dependency graph. This can be used with <code>sorted()</code> to recover the original order of tags: <code>sorted(tags, key=lambda tag: module_ctx.tag_sort_key(tag))</code>
          
          """.trimIndent(), parameters = [net.starlark.java.annot.Param(
            name = "tag",
            doc = "A tag obtained from <a"
                    + " href=\"../builtins/bazel_module.html#tags\">bazel_module.tags</a>.",
            allowedTypes = [net.starlark.java.annot.ParamType(type = TypeCheckedTag::class)]
        )]
    )
    fun getTagSortKey(tag: TypeCheckedTag): Any? {
        return tag.getSortKey()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "is_isolated",
        doc = ("Whether this particular usage of the extension had <code>isolate = True</code> "
                + "specified and is thus isolated from all other usages."
                + "<p>This field is currently experimental and only available with the flag "
                + "<code>--experimental_isolated_extension_usages</code>."),
        structField = true,
        enableOnlyWithFlag = "-experimental_isolated_extension_usages"
    )
    fun isIsolated(): Boolean {
        return extensionId.isolationKey.isPresent()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "root_module_has_non_dev_dependency",
        doc = "Whether the root module uses this extension as a non-dev dependency.",
        structField = true
    )
    fun rootModuleHasNonDevDependency(): Boolean {
        return rootModuleHasNonDevDependency
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "extension_metadata",
        doc = ("Constructs an opaque object that can be returned from the module extension's"
                + " implementation function to provide metadata about the repositories generated by"
                + " the extension to Bazel."),
        parameters = [net.starlark.java.annot.Param(
            name = "root_module_direct_deps",
            doc = ("The names of the repositories that the extension considers to be direct"
                    + " dependencies of the root module. If the root module imports additional"
                    + " repositories or does not import all of these repositories via <a"
                    + " href=\"../globals/module.html#use_repo\"><code>use_repo</code></a>, Bazel"
                    + " will print a warning when the extension is evaluated, instructing the user"
                    + " to run <code>bazel mod tidy</code> to fix the <code>use_repo</code> calls"
                    + " automatically. <p>If one of <code>root_module_direct_deps</code> and"
                    + " will print a warning and a fixup command when the extension is"
                    + " evaluated.<p>If one of <code>root_module_direct_deps</code> and"
                    + " <code>root_module_direct_dev_deps</code> is specified, the other has to be"
                    + " as well. The lists specified by these two parameters must be"
                    + " disjoint.<p>Exactly one of <code>root_module_direct_deps</code> and"
                    + " <code>root_module_direct_dev_deps</code> can be set to the special value"
                    + " <code>\"all\"</code>, which is treated as if a list with the names of"
                    + " all repositories generated by the extension was specified as the value."),
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = String::class
            ), net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(type = net.starlark.java.eval.NoneType::class)]
        ), net.starlark.java.annot.Param(
            name = "root_module_direct_dev_deps",
            doc = ("The names of the repositories that the extension considers to be direct dev"
                    + " dependencies of the root module. If the root module imports additional"
                    + " repositories or does not import all of these repositories via <a"
                    + " href=\"../globals/module.html#use_repo\"><code>use_repo</code></a> on an"
                    + " extension proxy created with <code><a"
                    + " href=\"../globals/module.html#use_extension\">use_extension</a>(...,"
                    + " dev_dependency = True)</code>, Bazel will print a warning when the "
                    + " extension is evaluated, instructing the user to run"
                    + " <code>bazel mod tidy</code> to fix the <code>use_repo</code> calls"
                    + " automatically. <p>If one of <code>root_module_direct_deps</code> and"
                    + " <code>root_module_direct_dev_deps</code> is specified, the other has to be"
                    + " as well. The lists specified by these two parameters must be"
                    + " disjoint.<p>Exactly one of <code>root_module_direct_deps</code> and"
                    + " <code>root_module_direct_dev_deps</code> can be set to the special value"
                    + " <code>\"all\"</code>, which is treated as if a list with the names of"
                    + " all repositories generated by the extension was specified as the value."),
            positional = false,
            named = true,
            defaultValue = "None",
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = String::class
            ), net.starlark.java.annot.ParamType(type = String::class), net.starlark.java.annot.ParamType(type = net.starlark.java.eval.NoneType::class)]
        ), net.starlark.java.annot.Param(
            name = "reproducible",
            doc = "States that this module extension ensures complete reproducibility, thereby it "
                    + "should not be stored in the lockfile.",
            positional = false,
            named = true,
            defaultValue = "False",
            allowedTypes = [net.starlark.java.annot.ParamType(type = Boolean::class)]
        ), net.starlark.java.annot.Param(
            name = "facts",
            doc = """
                A JSON-like dict that is made available to future executions of this extension via
                the `module_ctx.facts` property.
                This is useful for extensions that want to preserve universally true facts such as
                the hashes of artifacts in an immutable repository.

                Bazel may shallowly merge multiple facts dicts returned by different versions of the
                extension in order to resolve merge conflicts on the MODULE.bazel.lock file, as if
                by applying the `dict.update()` method or the `|` operator in Starlark. Extensions
                should use facts for key-value storage only and ensure that the key uniquely
                determines the value, although perhaps only via additional information and network
                access. An extension can opt out of this merging by providing a dict with a single,
                fixed top-level key and an arbitrary value.

                Note that the value provided here may be read back by a different version of the
                extension, so either include a version number or use a schema that is unlikely to
                result in ambiguities.
                
                """.trimIndent(),
            positional = false,
            named = true,
            defaultValue = "{}",
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Dict::class,
                generic1 = String::class
            )]
        )]
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun extensionMetadata(
        rootModuleDirectDepsUnchecked: Any?,
        rootModuleDirectDevDepsUnchecked: Any?,
        reproducible: Boolean,
        facts: Any?
    ): ModuleExtensionMetadata {
        return ModuleExtensionMetadata.Companion.create(
            rootModuleDirectDepsUnchecked,
            rootModuleDirectDevDepsUnchecked,
            reproducible,
            net.starlark.java.eval.Dict.cast<String?, Any?>(facts, String::class.java, Any::class.java, "facts")
        )
    }
}
