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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.packages.RuleClass.Builder.STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME

/**
 * SkyFunction that creates the [BuildOptionsScopeValue] for a given [BuildOptions].
 * This SkyFunction is responsible for the following:
 * 
 * 
 *  * Resolving the [Scope.ScopeType] for each scoped flag if not already resolved.
 *  * Getting the PROJECT.scl files for each flag scoped with [Scope.ScopeType.PROJECT].
 *  * Looking up [ProjectValue] for scoped flags that have PROJECT.scl files to get the
 * list of active directories that define the scope of the flag.
 * 
 */
class BuildOptionsScopeFunction : SkyFunction {
    @Throws(BuildOptionsScopeFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val key: com.google.devtools.build.lib.skyframe.BuildOptionsScopeValue.Key =
            skyKey.argument() as com.google.devtools.build.lib.skyframe.BuildOptionsScopeValue.Key
        var fullyResolvedBuildOptionsBuilder: BuildOptions.Builder = key.getBuildOptions().toBuilder()
        val scopes: LinkedHashMap<Label, Scope?> = LinkedHashMap<Label, Scope?>()
        for (scopedFlag in key.getFlagsWithIncompleteScopeInfo()) {
            var scopeType: Scope.ScopeType? = key.getBuildOptions().getScopeTypeMap().get(scopedFlag)
            var onLeaveScopeValue: Any? = key.getBuildOptions().getOnLeaveScopeValues().get(scopedFlag)
            if (scopeType == null) {
                val target = getTarget(env, scopedFlag, scopedFlag.getPackageIdentifier())
                if (target == null) {
                    return null
                }
                scopeType = getScopeType(target)
                onLeaveScopeValue = getOnleaveScopeValue(target)
            }
            scopes.put(scopedFlag, Scope(scopeType, null))

            // this is needed because the final BuildOptions used to create the BuildConfigurationKey
            // needs to have the scopeType set for all starlark flags.
            fullyResolvedBuildOptionsBuilder =
                if (onLeaveScopeValue != null)
                    fullyResolvedBuildOptionsBuilder
                        .addScopeType(scopedFlag, scopeType)
                        .addOnLeaveScopeValue(scopedFlag, onLeaveScopeValue)
                else
                    fullyResolvedBuildOptionsBuilder.addScopeType(scopedFlag, scopeType)

            if (scopeType.scopeType().startsWith(Scope.CUSTOM_EXEC_SCOPE_PREFIX)) {
                // handling custom exec case with scope "exec:--<another_flag_name>".
                // For example: --python_launcher=--host_python_launcher
                // have the --<another_flag_name> flag default value in the target config but also make sure
                // that it won't propagate to the exec config by setting the scope to "target".
                val anotherFlag: Label = Label.parseCanonicalUnchecked(scopeType.scopeType().substring(7))
                val anotherFlagTarget = getTarget(env, anotherFlag, scopedFlag.getPackageIdentifier())
                if (anotherFlagTarget == null) {
                    return null
                }

                if (!key.getBuildOptions().getStarlarkOptions().containsKey(anotherFlag)) {
                    val anotherFlagAttrs: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        RawAttributeMapper.of(anotherFlagTarget.getAssociatedRule())
                    var anotherFlagScopeType: String? = Scope.ScopeType.DEFAULT
                    if (anotherFlagAttrs.isAttributeValueExplicitlySpecified("scope")) {
                        anotherFlagScopeType = anotherFlagAttrs.get("scope", Type.STRING)
                    }
                    fullyResolvedBuildOptionsBuilder =
                        fullyResolvedBuildOptionsBuilder
                            .addStarlarkOption(
                                anotherFlag,
                                anotherFlagTarget
                                    .getAssociatedRule()
                                    .getAttr(STARLARK_BUILD_SETTING_DEFAULT_ATTR_NAME)
                            )
                            .addScopeType(anotherFlag, ScopeType(anotherFlagScopeType))
                }
            }
        }

        // get PROJECT.scl files for each scoped flag that is not universal
        val projectFiles: com.google.common.collect.ImmutableMultimap<Label?, Label?>?
        try {
            projectFiles = findProjectFiles(scopes, env)
        } catch (e: ProjectResolutionException) {
            throw BuildOptionsScopeFunctionException(e)
        }

        if (projectFiles == null) {
            return null
        }

        // look up ProjectValue for scoped flags that have PROJECT.scl files to get the list of
        // active directories that define the scope of the flag.
        val projectValueSkyKeysMap: MutableMap<Label?, SkyKey?> = HashMap<Label?, SkyKey?>()
        for (projectScopedFlag in projectFiles.keySet()) {
            if (!projectFiles.get(projectScopedFlag).isEmpty()) {
                val projectKey: com.google.devtools.build.lib.skyframe.ProjectValue.Key =
                    com.google.devtools.build.lib.skyframe.ProjectValue.Key(
                        projectFiles.get(projectScopedFlag).asList().get(0)
                    )
                projectValueSkyKeysMap.put(projectScopedFlag, projectKey)
            }
        }

        val projectValuesLookUpResult: SkyframeLookupResult =
            env.getValuesAndExceptions(projectValueSkyKeysMap.values())

        if (env.valuesMissing()) {
            return null
        }

        for (entry in projectValueSkyKeysMap.entrySet()) {
            val projectScopedFlag: Label? = entry.getKey()
            val projectValue: ProjectValue? = projectValuesLookUpResult.get(entry.getValue()) as ProjectValue?
            scopes.put(
                projectScopedFlag,
                Scope(
                    scopes.get(projectScopedFlag).scopeType,
                    if (projectValue.getDefaultProjectDirectories().isEmpty())
                        null
                    else
                        ScopeDefinition(projectValue.getDefaultProjectDirectories())
                )
            )
        }

        return BuildOptionsScopeValue.Companion.create(
            fullyResolvedBuildOptionsBuilder.build(),
            com.google.common.collect.Lists.newArrayList<Label?>(projectValueSkyKeysMap.keySet()),
            scopes
        )
    }

    /** TODO: b/384057043 - deduplicate this method in several places in a follow up CL.  */
    @Throws(java.lang.InterruptedException::class, ProjectResolutionException::class)
    private fun findProjectFiles(
        scopes: MutableMap<Label, Scope?>, env: SkyFunction.Environment
    ): com.google.common.collect.ImmutableMultimap<Label?, Label?>? {
        val targetsToSkyKeys: MutableMap<Label?, com.google.devtools.build.lib.skyframe.ProjectFilesLookupValue.Key?> =
            HashMap<Label?, com.google.devtools.build.lib.skyframe.ProjectFilesLookupValue.Key?>()
        for (starlarkOption in scopes.keySet()) {
            if (scopes.get(starlarkOption).scopeType.scopeType().equals(Scope.ScopeType.PROJECT)) {
                targetsToSkyKeys.put(
                    starlarkOption, ProjectFilesLookupValue.key(starlarkOption.getPackageIdentifier())
                )
            }
        }

        val projectFilesLookupValues: MutableMap<Label?, ProjectFilesLookupValue?> =
            HashMap<Label?, ProjectFilesLookupValue?>()
        for (skyKeyEntry in targetsToSkyKeys.entrySet()) {
            val projectFilesLookupValue: ProjectFilesLookupValue? =
                env.getValueOrThrow<E?>(
                    skyKeyEntry.getValue(),
                    ProjectResolutionException::class.java
                ) as ProjectFilesLookupValue?

            if (projectFilesLookupValue == null) {
                return null
            }
            projectFilesLookupValues.put(skyKeyEntry.getKey(), projectFilesLookupValue)
        }

        val projectFiles: com.google.common.collect.ImmutableMultimap.Builder<Label?, Label?> =
            com.google.common.collect.ImmutableMultimap.builder<Label?, Label?>()
        for (entry in projectFilesLookupValues.entrySet()) {
            projectFiles.putAll(entry.getKey(), entry.getValue().getProjectFiles())
        }

        return projectFiles.build()
    }

    @Throws(BuildOptionsScopeFunctionException::class, java.lang.InterruptedException::class)
    private fun getTarget(env: SkyFunction.Environment, label: Label, packageIdentifier: PackageIdentifier?): Target? {
        val packageContext: PackageContext? = PackageContext.of(packageIdentifier, RepositoryMapping.EMPTY)
        val targetLoader: SkyframeTargetLoader =
            com.google.devtools.build.lib.skyframe.BuildOptionsScopeFunction.SkyframeTargetLoader(env, packageContext)

        val target: Target?
        try {
            target = targetLoader.loadBuildSetting(label.getUnambiguousCanonicalForm())
        } catch (e: TargetParsingException) {
            throw BuildOptionsScopeFunctionException(e)
        }

        if (target == null) {
            return null
        }

        return target
    }

    private fun getScopeType(target: Target): Scope.ScopeType {
        val attrs: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RawAttributeMapper.of(target.getAssociatedRule())
        if (!attrs.has(
                "scope",
                Type.STRING
            ) // TODO: https://github.com/bazelbuild/bazel/issues/26909 - Honor the rule's actual
            // value when --incompatible_exclude_starlark_flags_from_exec_config is stably enabled
            // and existing rules like skylib's have updated to TARGET.
            || !attrs.isAttributeValueExplicitlySpecified("scope")
        ) {
            return ScopeType(Scope.ScopeType.DEFAULT)
        }
        return ScopeType(attrs.get("scope", Type.STRING))
    }

    private fun getOnleaveScopeValue(target: Target): Any? {
        val attrs: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RawAttributeMapper.of(target.getAssociatedRule())
        if (!attrs.isAttributeValueExplicitlySpecified("on_leave_scope")) {
            // do nothing if on_leave_scope is not set.
            return null
        }

        return attrs.get(
            "on_leave_scope",
            target.getAssociatedRule().getRuleClassObject().getBuildSetting().getType()
        )
    }

    /**
     * Same as [ParsedFlagsFunction.SkyframeTargetLoader] but forking it here to avoid circular
     * dependencies.
     */
    private class SkyframeTargetLoader(env: SkyFunction.Environment, packageContext: PackageContext?) :
        StarlarkOptionsParser.BuildSettingLoader {
        private val env: SkyFunction.Environment
        private val packageContext: PackageContext?

        init {
            this.env = env
            this.packageContext = packageContext
        }

        @Throws(java.lang.InterruptedException::class, TargetParsingException::class)
        public override fun loadBuildSetting(name: String?): Target? {
            val asLabel: Label
            try {
                asLabel = Label.parseWithPackageContext(name, packageContext)
            } catch (e: LabelSyntaxException) {
                throw java.lang.IllegalArgumentException(e)
            }
            try {
                val pkgKey: SkyKey? = asLabel.getPackageIdentifier()
                val pkg: PackageValue? =
                    env.getValueOrThrow<E?>(pkgKey, NoSuchPackageException::class.java) as PackageValue?
                if (pkg == null) {
                    return null
                }
                return pkg.getPackage().getTarget(asLabel.name)
            } catch (e: NoSuchPackageException) {
                throw TargetParsingException(
                    java.lang.String.format("Failed to load %s", name), e, DEPENDENCY_NOT_FOUND
                )
            } catch (e: NoSuchTargetException) {
                throw TargetParsingException(
                    java.lang.String.format("Failed to load %s", name), e, DEPENDENCY_NOT_FOUND
                )
            }
        }
    }

    /** Exception thrown by BuildOptionsScopesFunction.  */
    class BuildOptionsScopeFunctionException : SkyFunctionException {
        internal constructor(cause: ProjectResolutionException?) : super(cause, Transience.PERSISTENT)

        internal constructor(cause: TargetParsingException?) : super(cause, Transience.PERSISTENT)

        internal constructor(cause: java.lang.IllegalArgumentException?) : super(cause, Transience.PERSISTENT)
    }
}
