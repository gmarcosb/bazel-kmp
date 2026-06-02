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
package com.google.devtools.build.lib.bazel.bzlmod.modcommand

import com.google.common.base.Ascii
import com.google.common.collect.ImmutableBiMap
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.bazel.bzlmod.Version
import com.google.devtools.build.lib.cmdline.RepositoryMapping
import com.google.devtools.build.lib.server.FailureDetails.ModCommand.Code
import com.google.devtools.common.options.Converter
import com.google.devtools.common.options.Converters
import com.google.devtools.common.options.OptionsParsingException
import net.starlark.java.eval.EvalException
import java.lang.String
import java.util.*
import java.util.function.Function
import java.util.function.Predicate
import kotlin.Boolean
import kotlin.Int
import kotlin.toString

/**
 * Represents a reference to one or more modules in the external dependency graph, used for
 * modquery. This is parsed from a command-line argument (either as the value of a flag, or just as
 * a bare argument), and can take one of various forms (see implementations).
 */
interface ModuleArg {
    /** Resolves this module argument to a set of module keys.  */
    @Throws(InvalidArgumentException::class)
    fun resolveToModuleKeys(
        modulesIndex: ImmutableMap<String?, ImmutableSet<ModuleKey?>?>?,
        depGraph: ImmutableMap<ModuleKey?, AugmentedModule?>?,
        moduleKeyToCanonicalNames: ImmutableMap<ModuleKey?, RepositoryName?>?,
        baseModuleDeps: ImmutableBiMap<String?, ModuleKey?>?,
        baseModuleUnusedDeps: ImmutableBiMap<String?, ModuleKey?>?,
        includeUnused: Boolean,
        warnUnused: Boolean
    ): ImmutableSet<ModuleKey?>?

    /** Resolves this module argument to a set of repo names.  */
    @Throws(InvalidArgumentException::class)
    fun resolveToRepoNames(
        modulesIndex: ImmutableMap<String?, ImmutableSet<ModuleKey?>?>?,
        depGraph: ImmutableMap<ModuleKey?, AugmentedModule?>?,
        moduleKeyToCanonicalNames: ImmutableMap<ModuleKey?, RepositoryName?>?,
        mapping: RepositoryMapping?
    ): ImmutableMap<String?, RepositoryName?>?

    /**
     * Refers to a specific version of a module. Parsed from `<module>@<version>`. `<version>` can be the special string `_` to signify the empty version (for non-registry
     * overrides).
     */
    class SpecificVersionOfModule(moduleKey: ModuleKey?) : ModuleArg {
        @Throws(InvalidArgumentException::class)
        private fun throwIfNonexistent(
            modulesIndex: ImmutableMap<String?, ImmutableSet<ModuleKey?>?>,
            depGraph: ImmutableMap<ModuleKey?, AugmentedModule?>,
            includeUnused: Boolean,
            warnUnused: Boolean
        ) {
            val mod: AugmentedModule? = depGraph.get(this.moduleKey)
            if (mod != null && !includeUnused && warnUnused && !mod.isUsed()) {
                // Warn the user when unused modules are allowed and the specified version exists, but the
                // --include_unused flag was not set.
                throw InvalidArgumentException(
                    String.format(
                        "Module version %s is unused as a result of module resolution. Use the"
                                + " --include_unused flag to include it.",
                        this.moduleKey
                    ),
                    Code.INVALID_ARGUMENTS
                )
            }
            if (mod == null || (!includeUnused && !mod.isUsed())) {
                val existingKeys: ImmutableSet<ModuleKey?>? = modulesIndex.get(this.moduleKey.name)
                if (existingKeys == null) {
                    throw InvalidArgumentException(
                        String.format(
                            "Module %s does not exist in the dependency graph.", this.moduleKey.name
                        ),
                        Code.INVALID_ARGUMENTS
                    )
                }
                // If --include_unused is not true, unused modules will be considered non-existent and an
                // error will be thrown.
                val filteredKeys: ImmutableSet<ModuleKey?> =
                    existingKeys.stream()
                        .filter(Predicate { k: ModuleKey? -> includeUnused || depGraph.get(k).isUsed() })
                        .collect(ImmutableSet.toImmutableSet<ModuleKey?>())
                throw InvalidArgumentException(
                    String.format(
                        "Module version %s does not exist, available versions: %s.",
                        this.moduleKey, filteredKeys
                    ),
                    Code.INVALID_ARGUMENTS
                )
            }
        }

        @Throws(InvalidArgumentException::class)
        override fun resolveToModuleKeys(
            modulesIndex: ImmutableMap<kotlin.String?, ImmutableSet<ModuleKey?>?>,
            depGraph: ImmutableMap<ModuleKey?, AugmentedModule?>,
            moduleKeyToCanonicalNames: ImmutableMap<ModuleKey?, RepositoryName?>?,
            baseModuleDeps: ImmutableBiMap<kotlin.String?, ModuleKey?>?,
            baseModuleUnusedDeps: ImmutableBiMap<kotlin.String?, ModuleKey?>?,
            includeUnused: Boolean,
            warnUnused: Boolean
        ): ImmutableSet<ModuleKey?> {
            throwIfNonexistent(modulesIndex, depGraph, includeUnused, warnUnused)
            return ImmutableSet.of<ModuleKey?>(this.moduleKey)
        }

        @Throws(InvalidArgumentException::class)
        override fun resolveToRepoNames(
            modulesIndex: ImmutableMap<kotlin.String?, ImmutableSet<ModuleKey?>?>,
            depGraph: ImmutableMap<ModuleKey?, AugmentedModule?>,
            moduleKeyToCanonicalNames: ImmutableMap<ModuleKey?, RepositoryName?>,
            mapping: RepositoryMapping?
        ): ImmutableMap<kotlin.String?, RepositoryName?> {
            throwIfNonexistent(
                modulesIndex, depGraph,  /* includeUnused= */false,  /* warnUnused= */false
            )
            return ImmutableMap.of<kotlin.String?, RepositoryName?>(
                this.moduleKey.toString(),
                moduleKeyToCanonicalNames.get(this.moduleKey)
            )
        }

        override fun toString(): kotlin.String {
            return this.moduleKey.toString()
        }

        val moduleKey: ModuleKey?

        init {
            this.moduleKey = moduleKey
            Objects.requireNonNull<ModuleKey?>(moduleKey, "moduleKey")
        }

        companion object {
            fun create(key: ModuleKey?): SpecificVersionOfModule {
                return SpecificVersionOfModule(key)
            }
        }
    }

    /** Refers to all versions of a module. Parsed from `<module>`.  */
    @kotlin.jvm.JvmRecord
    data class AllVersionsOfModule(val moduleName: kotlin.String?) : ModuleArg {
        @Throws(InvalidArgumentException::class)
        private fun resolveInternal(
            modulesIndex: ImmutableMap<kotlin.String?, ImmutableSet<ModuleKey?>?>,
            depGraph: ImmutableMap<ModuleKey?, AugmentedModule?>,
            includeUnused: Boolean,
            warnUnused: Boolean
        ): ImmutableSet<ModuleKey?> {
            val existingKeys: ImmutableSet<ModuleKey?>? = modulesIndex.get(this.moduleName)
            if (existingKeys == null) {
                throw InvalidArgumentException(
                    String.format("Module %s does not exist in the dependency graph.", this.moduleName),
                    Code.INVALID_ARGUMENTS
                )
            }
            val filteredKeys: ImmutableSet<ModuleKey?> =
                existingKeys.stream()
                    .filter(Predicate { k: ModuleKey? -> includeUnused || depGraph.get(k).isUsed() })
                    .collect(ImmutableSet.toImmutableSet<ModuleKey?>())
            if (filteredKeys.isEmpty()) {
                if (warnUnused) {
                    throw InvalidArgumentException(
                        String.format(
                            "Module %s is unused as a result of module resolution. Use the --include_unused"
                                    + " flag to include it.",
                            this.moduleName
                        ),
                        Code.INVALID_ARGUMENTS
                    )
                }
                throw InvalidArgumentException(
                    String.format("Module %s does not exist in the dependency graph.", this.moduleName),
                    Code.INVALID_ARGUMENTS
                )
            }
            return filteredKeys
        }

        @Throws(InvalidArgumentException::class)
        override fun resolveToModuleKeys(
            modulesIndex: ImmutableMap<kotlin.String?, ImmutableSet<ModuleKey?>?>,
            depGraph: ImmutableMap<ModuleKey?, AugmentedModule?>,
            moduleKeyToCanonicalNames: ImmutableMap<ModuleKey?, RepositoryName?>?,
            baseModuleDeps: ImmutableBiMap<kotlin.String?, ModuleKey?>?,
            baseModuleUnusedDeps: ImmutableBiMap<kotlin.String?, ModuleKey?>?,
            includeUnused: Boolean,
            warnUnused: Boolean
        ): ImmutableSet<ModuleKey?> {
            return resolveInternal(modulesIndex, depGraph, includeUnused, warnUnused)
        }

        @Throws(InvalidArgumentException::class)
        override fun resolveToRepoNames(
            modulesIndex: ImmutableMap<kotlin.String?, ImmutableSet<ModuleKey?>?>,
            depGraph: ImmutableMap<ModuleKey?, AugmentedModule?>,
            moduleKeyToCanonicalNames: ImmutableMap<ModuleKey?, RepositoryName?>,
            mapping: RepositoryMapping?
        ): ImmutableMap<kotlin.String?, RepositoryName?> {
            return resolveInternal(
                modulesIndex, depGraph,  /* includeUnused= */false,  /* warnUnused= */false
            )
                .stream()
                .collect(
                    ImmutableMap.toImmutableMap<ModuleKey?, kotlin.String?, RepositoryName?>(
                        Function { obj: ModuleKey? -> obj.toString() },
                        Function { key: ModuleKey? -> moduleKeyToCanonicalNames.get(key) })
                )
        }

        override fun toString(): kotlin.String {
            return this.moduleName!!
        }

        init {
            Objects.requireNonNull<kotlin.String?>(moduleName, "moduleName")
        }

        companion object {
            @kotlin.jvm.JvmStatic
            fun create(moduleName: kotlin.String?): AllVersionsOfModule {
                return AllVersionsOfModule(moduleName)
            }
        }
    }

    /**
     * Refers to a module with the given apparent repo name, in the context of `--base_module`
     * (or when parsing that flag itself, in the context of the root module). Parsed from
     * `@<name>`.
     */
    @kotlin.jvm.JvmRecord
    data class ApparentRepoName(val name: kotlin.String) : ModuleArg {
        @Throws(InvalidArgumentException::class)
        override fun resolveToModuleKeys(
            modulesIndex: ImmutableMap<kotlin.String?, ImmutableSet<ModuleKey?>?>?,
            depGraph: ImmutableMap<ModuleKey?, AugmentedModule?>?,
            moduleKeyToCanonicalNames: ImmutableMap<ModuleKey?, RepositoryName?>?,
            baseModuleDeps: ImmutableBiMap<kotlin.String?, ModuleKey?>,
            baseModuleUnusedDeps: ImmutableBiMap<kotlin.String?, ModuleKey?>,
            includeUnused: Boolean,
            warnUnused: Boolean
        ): ImmutableSet<ModuleKey?> {
            val builder: ImmutableSet.Builder<ModuleKey?> = ImmutableSet.Builder<ModuleKey?>()
            val dep: ModuleKey? = baseModuleDeps.get(this.name)
            if (dep != null) {
                builder.add(dep)
            }
            val unusedDep: ModuleKey? = baseModuleUnusedDeps.get(this.name)
            if (includeUnused && unusedDep != null) {
                builder.add(unusedDep)
            }
            val result: ImmutableSet<ModuleKey?> = builder.build()
            if (result.isEmpty()) {
                throw InvalidArgumentException(
                    String.format(
                        "No module with the apparent repo name @%s exists in the dependency graph", this.name
                    ),
                    Code.INVALID_ARGUMENTS
                )
            }
            return result
        }

        @Throws(InvalidArgumentException::class)
        override fun resolveToRepoNames(
            modulesIndex: ImmutableMap<kotlin.String?, ImmutableSet<ModuleKey?>?>?,
            depGraph: ImmutableMap<ModuleKey?, AugmentedModule?>?,
            moduleKeyToCanonicalNames: ImmutableMap<ModuleKey?, RepositoryName?>?,
            mapping: RepositoryMapping
        ): ImmutableMap<kotlin.String?, RepositoryName?> {
            val repoName: RepositoryName = mapping.get(this.name)
            if (!repoName.isVisible()) {
                throw InvalidArgumentException(
                    String.format(
                        "No repo visible as %s from @%s", this.name, repoName.getContextRepoDisplayString()
                    ),
                    Code.INVALID_ARGUMENTS
                )
            }
            return ImmutableMap.of<kotlin.String?, RepositoryName?>(toString(), repoName)
        }

        override fun toString(): kotlin.String {
            return "@" + this.name
        }

        init {
            Objects.requireNonNull<kotlin.String?>(name, "name")
        }

        companion object {
            @kotlin.jvm.JvmStatic
            fun create(name: kotlin.String): ApparentRepoName {
                return ApparentRepoName(name)
            }
        }
    }

    /** Refers to a module with the given canonical repo name. Parsed from `@@<name>`.  */
    class CanonicalRepoName(repoName: RepositoryName?) : ModuleArg {
        @Throws(InvalidArgumentException::class)
        override fun resolveToModuleKeys(
            modulesIndex: ImmutableMap<kotlin.String?, ImmutableSet<ModuleKey?>?>?,
            depGraph: ImmutableMap<ModuleKey?, AugmentedModule?>,
            moduleKeyToCanonicalNames: ImmutableMap<ModuleKey?, RepositoryName?>,
            baseModuleDeps: ImmutableBiMap<kotlin.String?, ModuleKey?>?,
            baseModuleUnusedDeps: ImmutableBiMap<kotlin.String?, ModuleKey?>?,
            includeUnused: Boolean,
            warnUnused: Boolean
        ): ImmutableSet<ModuleKey?> {
            val mod: Optional<AugmentedModule?> =
                depGraph.values().stream()
                    .filter(Predicate { m: AugmentedModule? -> this.repoName == moduleKeyToCanonicalNames.get(m.key) })
                    .findAny()
            if (mod.isPresent() && !includeUnused && warnUnused && !mod.get().isUsed()) {
                // Warn the user when unused modules are allowed and the specified version exists, but the
                // --include_unused flag was not set.
                throw InvalidArgumentException(
                    String.format(
                        "Module version %s is unused as a result of module resolution. Use the"
                                + " --include_unused flag to include it.",
                        mod.get().key
                    ),
                    Code.INVALID_ARGUMENTS
                )
            }
            if (mod.isEmpty() || (!includeUnused && !mod.get().isUsed())) {
                // If --include_unused is not true, unused modules will be considered non-existent and an
                // error will be thrown.
                throw InvalidArgumentException(
                    String.format(
                        "No module with the canonical repo name @@%s exists in the dependency graph",
                        this.repoName.getName()
                    ),
                    Code.INVALID_ARGUMENTS
                )
            }
            return ImmutableSet.of<ModuleKey?>(mod.get().key)
        }

        override fun resolveToRepoNames(
            modulesIndex: ImmutableMap<kotlin.String?, ImmutableSet<ModuleKey?>?>?,
            depGraph: ImmutableMap<ModuleKey?, AugmentedModule?>?,
            moduleKeyToCanonicalNames: ImmutableMap<ModuleKey?, RepositoryName?>?,
            mapping: RepositoryMapping?
        ): ImmutableMap<kotlin.String?, RepositoryName?> {
            return ImmutableMap.of<kotlin.String?, RepositoryName?>(toString(), this.repoName)
        }

        override fun toString(): kotlin.String {
            return "@@" + this.repoName.getName()
        }

        val repoName: RepositoryName?

        init {
            this.repoName = repoName
            Objects.requireNonNull<RepositoryName?>(repoName, "repoName")
        }

        companion object {
            fun create(repoName: RepositoryName?): CanonicalRepoName {
                return CanonicalRepoName(repoName)
            }
        }
    }

    /** Converter for [ModuleArg].  */
    class ModuleArgConverter : Converter.Contextless<ModuleArg?>() {
        @Throws(OptionsParsingException::class)
        override fun convert(input: kotlin.String): ModuleArg {
            if (Ascii.equalsIgnoreCase(input, "<root>")) {
                return SpecificVersionOfModule.Companion.create(ModuleKey.Companion.ROOT)
            }
            if (input.startsWith("@@")) {
                try {
                    return CanonicalRepoName.Companion.create(RepositoryName.create(input.substring(2)))
                } catch (e: LabelSyntaxException) {
                    throw OptionsParsingException("invalid argument '" + input + "': " + e.getMessage())
                }
            }
            if (input.startsWith("@")) {
                val apparentRepoName: kotlin.String = input.substring(1)
                try {
                    RepositoryName.validateUserProvidedRepoName(apparentRepoName)
                } catch (e: EvalException) {
                    throw OptionsParsingException("invalid argument '" + input + "': " + e.getMessage())
                }
                return ApparentRepoName.Companion.create(apparentRepoName)
            }
            val atIdx: Int = input.indexOf('@'.code)
            if (atIdx >= 0) {
                val moduleName: kotlin.String = input.substring(0, atIdx)
                val versionStr: kotlin.String = input.substring(atIdx + 1)
                if (versionStr.isEmpty()) {
                    throw OptionsParsingException(
                        "invalid argument '" + input + "': use _ for the empty version"
                    )
                }
                try {
                    val version: Version? =
                        if (versionStr == "_") Version.Companion.EMPTY else Version.Companion.parse(versionStr)
                    return SpecificVersionOfModule.Companion.create(ModuleKey(moduleName, version))
                } catch (e: Version.ParseException) {
                    throw OptionsParsingException("invalid argument '" + input + "': " + e.getMessage())
                }
            }
            return AllVersionsOfModule.Companion.create(input)
        }

        override fun getTypeDescription(): kotlin.String {
            return ("\"<root>\" for the root module; <module>@<version> for a specific version of a"
                    + " module; <module> for all versions of a module; @<name> for a repo with the"
                    + " given apparent name; or @@<name> for a repo with the given canonical name")
        }

        companion object {
            @kotlin.jvm.JvmField
            val INSTANCE: ModuleArgConverter = ModuleArgConverter()
        }
    }

    /** Converter for a comma-separated list of [ModuleArg]s.  */
    class CommaSeparatedModuleArgListConverter

        : Converter.Contextless<ImmutableList<ModuleArg?>?>() {
        @Throws(OptionsParsingException::class)
        override fun convert(input: kotlin.String): ImmutableList<ModuleArg?> {
            val args = Converters.CommaSeparatedNonEmptyOptionListConverter().convert(input)
            val moduleArgs = ImmutableList.Builder<ModuleArg?>()
            for (arg in args) {
                moduleArgs.add(ModuleArgConverter.Companion.INSTANCE.convert(arg))
            }
            return moduleArgs.build()
        }

        override fun getTypeDescription(): kotlin.String {
            return "a comma-separated list of <module>s"
        }
    }
}
