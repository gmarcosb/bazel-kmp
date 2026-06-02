// Copyright 2024 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.bzlmod.CompiledModuleFile
import com.google.devtools.build.lib.bazel.bzlmod.InterimModule
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId.IsolationKey
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.bazel.bzlmod.ModuleThreadContext
import com.google.devtools.build.lib.bazel.bzlmod.NonRegistryOverride
import com.google.devtools.build.lib.cmdline.LabelConstants
import com.google.devtools.build.lib.cmdline.RepositoryName
import com.google.devtools.build.lib.cmdline.StarlarkThreadContext
import com.google.devtools.build.lib.vfs.PathFragment
import java.util.HashMap
import java.util.LinkedHashMap

/** Context object for a Starlark thread evaluating the MODULE.bazel file and files it includes.  */
class ModuleThreadContext(
    builtinModules: com.google.common.collect.ImmutableMap<String?, NonRegistryOverride?>,
    key: ModuleKey?,
    private val ignoreDevDeps: Boolean,
    includeLabelToCompiledModuleFile: com.google.common.collect.ImmutableMap<String?, CompiledModuleFile?>?
) : StarlarkThreadContext( /* mainRepoMappingSupplier= */null) {
    private var moduleCalled = false
    private var hadNonModuleCall = false
    private var currentModuleFilePath: PathFragment? = LabelConstants.MODULE_DOT_BAZEL_FILE_NAME

    private val module: com.google.devtools.build.lib.bazel.bzlmod.InterimModule.Builder
    private val builtinModules: com.google.common.collect.ImmutableMap<String?, NonRegistryOverride?>
    private val includeLabelToCompiledModuleFile: com.google.common.collect.ImmutableMap<String?, CompiledModuleFile?>?
    private val deps: MutableMap<String?, ModuleKey?> = LinkedHashMap<String?, ModuleKey?>()
    private val extensionUsageBuilders: MutableList<ModuleExtensionUsageBuilder> =
        java.util.ArrayList<ModuleExtensionUsageBuilder>()
    private val overrides: MutableMap<String?, com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?> =
        LinkedHashMap<String?, com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?>()
    private val repoNameUsages: MutableMap<String?, RepoNameUsage?> = HashMap<String?, RepoNameUsage?>()
    private val warnings: MutableList<com.google.devtools.build.lib.events.Event?> =
        java.util.ArrayList<com.google.devtools.build.lib.events.Event?>()

    private val overriddenRepos: MutableMap<String?, RepoOverride> = HashMap<String?, RepoOverride>()
    private val overridingRepos: MutableMap<String?, RepoOverride> = HashMap<String?, RepoOverride>()

    init {
        module = InterimModule.Companion.builder().setKey(key)
        this.builtinModules = builtinModules
        this.includeLabelToCompiledModuleFile = includeLabelToCompiledModuleFile
    }

    internal class RepoOverride(
        val overriddenRepoName: String?,
        val overridingRepoName: String?,
        val mustExist: Boolean,
        val extensionName: String?,
        stack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>?
    ) {
        fun location(): net.starlark.java.syntax.Location? {
            if (stack.size() < 2) {
                return net.starlark.java.syntax.Location.BUILTIN
            }
            // Skip over the override_repo builtin frame.
            return stack.reverse().get(1).location
        }

        val stack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>?

        init {
            this.stack = stack
        }
    }

    internal class RepoNameUsage(
        val how: String?,
        stack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>?
    ) {
        fun location(): net.starlark.java.syntax.Location? {
            if (stack.size() < 2) {
                return net.starlark.java.syntax.Location.BUILTIN
            }
            // Skip over the override_repo builtin frame.
            return stack.reverse().get(1).location
        }

        val stack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>?

        init {
            this.stack = stack
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    fun addRepoNameUsage(
        repoName: String?,
        how: String?,
        stack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>?
    ) {
        val incoming = RepoNameUsage(how, stack)
        val collision = repoNameUsages.put(repoName, incoming)
        if (collision != null) {
            throw net.starlark.java.eval.Starlark.errorf(
                "The repo name '%s' cannot be defined %s at %s as it is already defined %s at %s",
                repoName, incoming.how, incoming.location(), collision.how, collision.location()
            )
        }
    }

    /** Whether the `module()` directive has been called.  */
    fun isModuleCalled(): Boolean {
        return moduleCalled
    }

    fun setModuleCalled() {
        moduleCalled = true
    }

    /** Whether any directives other than `module()` have been called.  */
    fun hadNonModuleCall(): Boolean {
        return hadNonModuleCall
    }

    fun setNonModuleCalled() {
        hadNonModuleCall = true
    }

    fun getModuleBuilder(): com.google.devtools.build.lib.bazel.bzlmod.InterimModule.Builder {
        return module
    }

    fun addWarning(event: com.google.devtools.build.lib.events.Event?) {
        warnings.add(event)
    }

    fun getWarnings(): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?> {
        return com.google.common.collect.ImmutableList.copyOf<com.google.devtools.build.lib.events.Event?>(warnings)
    }

    fun shouldIgnoreDevDeps(): Boolean {
        return ignoreDevDeps
    }

    fun addDep(repoName: java.util.Optional<String?>, depKey: ModuleKey?) {
        if (repoName.isPresent()) {
            deps.put(repoName.get(), depKey)
        } else {
            module.addNodepDep(depKey)
        }
    }

    fun getOrCreateExtensionUsageBuilder(
        extensionBzlFile: String, extensionName: String, isolate: Boolean
    ): ModuleExtensionUsageBuilder {
        // Isolated extensions have to always get a new builder, non-isolated ones have to reuse an
        // existing one if it exists so that they all contribute usages to the same row in a table.
        if (!isolate) {
            for (builder in extensionUsageBuilders) {
                if (builder.isForExtension(extensionBzlFile, extensionName)) {
                    return builder
                }
            }
        }
        val newBuilder =
            ModuleExtensionUsageBuilder(this, extensionBzlFile, extensionName, isolate)
        extensionUsageBuilders.add(newBuilder)
        return newBuilder
    }

    internal class ModuleExtensionUsageBuilder(
        private val context: ModuleThreadContext,
        private val extensionBzlFile: String,
        private val extensionName: String,
        private val isolate: Boolean
    ) {
        private val proxyBuilders: java.util.ArrayList<com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage.Proxy.Builder?>
        private val imports: com.google.common.collect.HashBiMap<String?, String?>
        private val repoOverrides: MutableMap<String?, RepoOverride?>
        private val tags: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.bazel.bzlmod.Tag?>

        init {
            this.proxyBuilders =
                java.util.ArrayList<com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage.Proxy.Builder?>()
            this.imports = com.google.common.collect.HashBiMap.create<String?, String?>()
            this.repoOverrides = HashMap<String?, RepoOverride?>()
            this.tags =
                com.google.common.collect.ImmutableList.builder<com.google.devtools.build.lib.bazel.bzlmod.Tag?>()
        }

        fun getContext(): ModuleThreadContext {
            return context
        }

        fun addProxyBuilder(builder: com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage.Proxy.Builder?) {
            proxyBuilders.add(builder)
        }

        private fun isForExtension(extensionBzlFile: String?, extensionName: String?): Boolean {
            return this.extensionBzlFile == extensionBzlFile
                    && this.extensionName == extensionName
                    && !this.isolate
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun addImport(
            localRepoName: String?,
            exportedName: String?,
            byWhat: String?,
            stack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>?
        ) {
            RepositoryName.validateUserProvidedRepoName(localRepoName)
            RepositoryName.validateUserProvidedRepoName(exportedName)
            context.addRepoNameUsage(localRepoName, byWhat, stack)
            if (imports.containsValue(exportedName)) {
                val collisionRepoName: String? = imports.inverse().get(exportedName)
                throw net.starlark.java.eval.Starlark.errorf(
                    "The repo exported as '%s' by module extension '%s' is already imported at %s",
                    exportedName, extensionName, context.repoNameUsages.get(collisionRepoName)!!.location()
                )
            }
            imports.put(localRepoName, exportedName)
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun addRepoOverride(
            overriddenRepoName: String?,
            overridingRepoName: String?,
            mustExist: Boolean,
            stack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>?
        ) {
            RepositoryName.validateUserProvidedRepoName(overriddenRepoName)
            RepositoryName.validateUserProvidedRepoName(overridingRepoName)
            val collision =
                repoOverrides.put(
                    overriddenRepoName,
                    com.google.devtools.build.lib.bazel.bzlmod.ModuleThreadContext.RepoOverride(
                        overriddenRepoName, overridingRepoName, mustExist, extensionName, stack
                    )
                )
            if (collision != null) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "The repo exported as '%s' by module extension '%s' is already overridden with '%s' at"
                            + " %s",
                    overriddenRepoName, extensionName, collision.overridingRepoName, collision.location()
                )
            }
        }

        fun addTag(tag: com.google.devtools.build.lib.bazel.bzlmod.Tag) {
            tags.add(tag)
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun buildUsage(): ModuleExtensionUsage? {
            val proxies: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage.Proxy?> =
                proxyBuilders.stream()
                    .map<com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage.Proxy?>(java.util.function.Function { p: com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage.Proxy.Builder? -> p.build() })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage.Proxy?>())
            val builder: com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage.Builder =
                ModuleExtensionUsage.Companion.builder()
                    .setExtensionBzlFile(extensionBzlFile)
                    .setExtensionName(extensionName)
                    .setProxies(proxies)
                    .setTags(tags.build())
            if (isolate) {
                val onlyProxy: com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage.Proxy? =
                    com.google.common.collect.Iterables.getOnlyElement<com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage.Proxy?>(
                        proxies
                    )
                if (onlyProxy.getProxyName().isEmpty()) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "Isolated extension usage at %s must be assigned to a top-level variable",
                        onlyProxy.getLocation()
                    )
                }
                builder.setIsolationKey(
                    java.util.Optional.of<IsolationKey?>(
                        IsolationKey.Companion.create(
                            context.getModuleBuilder().getKey(), onlyProxy.getProxyName()
                        )
                    )
                )
            } else {
                builder.setIsolationKey(java.util.Optional.empty<IsolationKey?>())
            }

            for (override in repoOverrides.entrySet()) {
                val overriddenRepoName: String? = override.getKey()
                val overridingRepoName: String? = override.getValue().overridingRepoName
                if (!context.repoNameUsages.containsKey(overridingRepoName)) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "The repo exported as '%s' by module extension '%s' is overridden with '%s', but"
                                + " no repo is visible under this name%s",
                        overriddenRepoName,
                        extensionName,
                        overridingRepoName,
                        net.starlark.java.spelling.SpellChecker.didYouMean(
                            overridingRepoName,
                            context.repoNameUsages.keySet()
                        )
                    )
                        .withCallStack(override.getValue().stack)
                }
                val importedAs: String? = imports.inverse().get(overriddenRepoName)
                if (importedAs != null) {
                    if (!override.getValue().mustExist) {
                        throw net.starlark.java.eval.Starlark.errorf(
                            "Cannot import repo '%s' that has been injected into module extension '%s' at"
                                    + " %s. Please refer to @%s directly.",
                            overriddenRepoName,
                            extensionName,
                            override.getValue().location(),
                            overridingRepoName
                        )
                            .withCallStack(context.repoNameUsages.get(importedAs)!!.stack)
                    }
                    context.overriddenRepos.put(importedAs, override.getValue())
                }
                context.overridingRepos.put(overridingRepoName, override.getValue())
            }
            builder.setRepoOverrides(
                com.google.common.collect.ImmutableMap.copyOf<String?, com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage.RepoOverride?>(
                    com.google.common.collect.Maps.transformValues<String?, RepoOverride?, com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage.RepoOverride?>(
                        repoOverrides,
                        com.google.common.base.Function { v: RepoOverride? ->
                            com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage.RepoOverride(
                                v!!.overridingRepoName, v.mustExist, v.location()
                            )
                        })
                )
            )

            return builder.build()
        }
    }

    @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class)
    fun include(includeLabel: String?, thread: net.starlark.java.eval.StarlarkThread?) {
        if (includeLabelToCompiledModuleFile == null) {
            // This should never happen because compiling the non-root module file should have failed, way
            // before evaluation started.
            throw net.starlark.java.eval.Starlark.errorf("trying to call `include()` from a non-root module")
        }
        val compiledModuleFile: CompiledModuleFile? = includeLabelToCompiledModuleFile.get(includeLabel)
        if (compiledModuleFile == null) {
            // This should never happen because the file we're trying to include should have already been
            // compiled before evaluation started.
            throw net.starlark.java.eval.Starlark.errorf("internal error; included file %s not compiled", includeLabel)
        }
        val includer: PathFragment? = currentModuleFilePath
        currentModuleFilePath =
            com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked(includeLabel).toPathFragment()
        compiledModuleFile.runOnThread(thread)
        currentModuleFilePath = includer
    }

    fun getCurrentModuleFilePath(): PathFragment? {
        return currentModuleFilePath
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    fun addOverride(moduleName: String?, override: com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?) {
        if (shouldIgnoreDevDeps()) {
            return
        }
        val existingOverride: com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride? =
            overrides.putIfAbsent(moduleName, override)
        if (existingOverride != null) {
            throw net.starlark.java.eval.Starlark.errorf("multiple overrides for dep %s found", moduleName)
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    fun buildModule(registry: com.google.devtools.build.lib.bazel.bzlmod.Registry?): InterimModule? {
        // Add builtin modules as default deps of the current module.
        for (builtinModule in builtinModules.keySet()) {
            if (module.getKey().name == builtinModule) {
                // The built-in module does not depend on itself.
                continue
            }
            deps.put(
                builtinModule,
                ModuleKey(builtinModule, com.google.devtools.build.lib.bazel.bzlmod.Version.Companion.EMPTY)
            )
            try {
                addRepoNameUsage(
                    builtinModule,
                    "as a built-in dependency",
                    com.google.common.collect.ImmutableList.of<net.starlark.java.eval.StarlarkThread.CallStackEntry?>()
                )
            } catch (e: net.starlark.java.eval.EvalException) {
                throw net.starlark.java.eval.EvalException(
                    e.getMessage()
                            + java.lang.String.format(
                        ", '%s' is a built-in dependency and cannot be used by any 'bazel_dep' or"
                                + " 'use_repo' directive",
                        builtinModule
                    ),
                    e
                )
            }
        }
        // Build module extension usages and the rest of the module.
        val extensionUsages: com.google.common.collect.ImmutableList.Builder<ModuleExtensionUsage?> =
            com.google.common.collect.ImmutableList.builder<ModuleExtensionUsage?>()
        for (extensionUsageBuilder in extensionUsageBuilders) {
            if (extensionUsageBuilder.proxyBuilders.isEmpty()) {
                // This can happen for the special extension used for "use_repo_rule" calls.
                continue
            }
            extensionUsages.add(extensionUsageBuilder.buildUsage())
        }
        // A repo cannot be both overriding and overridden. This ensures that repo overrides can be
        // applied to repo mappings in a single step (and also prevents cycles).
        val overridingAndOverridden: java.util.Optional<String?> =
            overridingRepos.keySet().stream()
                .filter(java.util.function.Predicate { key: String? -> overriddenRepos.containsKey(key) }).findFirst()
        if (overridingAndOverridden.isPresent()) {
            val override: RepoOverride = overridingRepos.get(overridingAndOverridden.get())!!
            val overrideOnOverride: RepoOverride = overriddenRepos.get(overridingAndOverridden.get())!!
            throw net.starlark.java.eval.Starlark.errorf(
                "The repo '%s' used as an override for '%s' in module extension '%s' is itself"
                        + " overridden with '%s' at %s, which is not supported.",
                override.overridingRepoName,
                override.overriddenRepoName,
                override.extensionName,
                overrideOnOverride.overridingRepoName,
                overrideOnOverride.location()
            )
                .withCallStack(override.stack)
        }

        return module
            .setRegistry(registry)
            .setDeps(com.google.common.collect.ImmutableMap.copyOf<String?, ModuleKey?>(deps))
            .setOriginalDeps(com.google.common.collect.ImmutableMap.copyOf<String?, ModuleKey?>(deps))
            .setExtensionUsages(extensionUsages.build())
            .build()
    }

    fun buildOverrides(): com.google.common.collect.ImmutableMap<String?, com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?> {
        // Add overrides for builtin modules if there is no existing override for them.
        if (ModuleKey.Companion.ROOT == module.getKey()) {
            for (moduleName in builtinModules.keySet()) {
                overrides.putIfAbsent(moduleName, builtinModules.get(moduleName))
            }
        }
        return com.google.common.collect.ImmutableMap.copyOf<String?, com.google.devtools.build.lib.bazel.bzlmod.ModuleOverride?>(
            overrides
        )
    }

    companion object {
        @Throws(net.starlark.java.eval.EvalException::class)
        fun fromOrFail(thread: net.starlark.java.eval.StarlarkThread, what: String?): ModuleThreadContext {
            val context: StarlarkThreadContext? =
                thread.getThreadLocal<StarlarkThreadContext?>(StarlarkThreadContext::class.java)
            if (context is ModuleThreadContext) {
                return context
            }
            throw net.starlark.java.eval.Starlark.errorf(
                "%s can only be called from MODULE.bazel and files it includes",
                what
            )
        }
    }
}
