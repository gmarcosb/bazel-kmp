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
package com.google.devtools.build.lib.rules.java

import com.google.common.base.Function
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.Lists
import com.google.devtools.build.lib.actions.Artifact
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.function.Predicate

/** Builds the action to package the resources for a Java rule into a jar.  */
class ResourceJarActionBuilder {
    private var outputJar: Artifact? = null
    private var resources: MutableMap<PathFragment?, Artifact?> = ImmutableMap.of<PathFragment?, Artifact?>()
    private var resourceJars: NestedSet<Artifact?> = NestedSetBuilder.emptySet(Order.STABLE_ORDER)
    private var classpathResources: ImmutableList<Artifact?> = ImmutableList.of<Artifact?>()
    private var messages: ImmutableList<Artifact?> = ImmutableList.of<Artifact?>()
    private var javaToolchain: JavaToolchainProvider? = null
    private var additionalInputs: NestedSet<Artifact?>? = NestedSetBuilder.emptySet(Order.STABLE_ORDER)

    @CanIgnoreReturnValue
    fun setOutputJar(outputJar: Artifact?): ResourceJarActionBuilder {
        this.outputJar = outputJar
        return this
    }

    @CanIgnoreReturnValue
    fun setAdditionalInputs(additionalInputs: NestedSet<Artifact?>?): ResourceJarActionBuilder {
        this.additionalInputs = additionalInputs
        return this
    }

    @CanIgnoreReturnValue
    fun setClasspathResources(
        classpathResources: ImmutableList<Artifact?>
    ): ResourceJarActionBuilder {
        this.classpathResources = classpathResources
        return this
    }

    @CanIgnoreReturnValue
    fun setResources(resources: MutableMap<PathFragment?, Artifact?>): ResourceJarActionBuilder {
        this.resources = resources
        return this
    }

    @CanIgnoreReturnValue
    fun setResourceJars(resourceJars: NestedSet<Artifact?>): ResourceJarActionBuilder {
        this.resourceJars = resourceJars
        return this
    }

    @CanIgnoreReturnValue
    fun setTranslations(translations: ImmutableList<Artifact?>): ResourceJarActionBuilder {
        this.messages = translations
        return this
    }

    @CanIgnoreReturnValue
    fun setJavaToolchain(javaToolchain: JavaToolchainProvider): ResourceJarActionBuilder {
        this.javaToolchain = javaToolchain
        return this
    }

    @Throws(RuleErrorException::class)
    fun build(semantics: JavaSemantics, ruleContext: RuleContext, execGroup: String?) {
        Preconditions.checkNotNull<Any?>(outputJar, "outputJar must not be null")
        Preconditions.checkNotNull<JavaToolchainProvider?>(javaToolchain, "javaToolchain must not be null")
        Preconditions.checkNotNull<JavaRuntimeInfo?>(javaToolchain!!.getJavaRuntime(), "javabase must not be null")

        val builder: SpawnAction.Builder = Builder()
        val command: CustomCommandLine.Builder =
            CustomCommandLine.builder()
                .add("--normalize")
                .add("--dont_change_compression")
                .add("--exclude_build_data")
                .add("--no_strip_module_info") // bazelbuild/rules_java/issues/293
                .addExecPath("--output", outputJar)
        if (!resourceJars.isEmpty()) {
            command.addExecPaths("--sources", resourceJars)
        }
        addResources(command, semantics)
        if (!classpathResources.isEmpty()) {
            command.addExecPaths("--classpath_resources", classpathResources)
        }

        var executionInfo: ImmutableMap<String?, String?> = EXECUTION_INFO
        if (ruleContext.isAllowTagsPropagation()) {
            val executionInfoBuilder = ImmutableMap.builder<String?, String?>()
            executionInfoBuilder.putAll(EXECUTION_INFO)
            executionInfoBuilder.putAll(
                TargetUtils.getExecutionInfo(
                    ruleContext.getRule(), ruleContext.isAllowTagsPropagation()
                )
            )
            executionInfo = executionInfoBuilder.build()
        }

        ruleContext.registerAction(
            builder
                .setExecutable(javaToolchain!!.getSingleJar())
                .useDefaultShellEnvironment(ImmutableMap.of<K?, V?>())
                .addOutput(outputJar)
                .addInputs(messages)
                .addInputs(resources.values())
                .addTransitiveInputs(resourceJars)
                .addTransitiveInputs(additionalInputs)
                .addInputs(classpathResources)
                .addCommandLine(command.build(), PARAM_FILE_INFO)
                .setProgressMessage("Building Java resource jar")
                .setMnemonic(MNEMONIC)
                .setExecutionInfo(executionInfo)
                .setExecGroup(execGroup)
                .build(ruleContext)
        )
    }

    private fun addResources(command: CustomCommandLine.Builder, semantics: JavaSemantics) {
        if (resources.isEmpty() && messages.isEmpty()) {
            return
        }

        command.add("--resources")
        val resourcesWithDefaultPath: ImmutableList<Artifact?>

        // When all resources use the default path (common case), save memory by throwing away those
        // path fragments. The artifacts can be lazily converted to default-prefixed strings.
        if (resources.entrySet().stream()
                .allMatch(Predicate { e: MutableMap.MutableEntry<PathFragment?, Artifact?>? ->
                    e.getKey() == defaultResourcePath(
                        e.getValue(),
                        semantics
                    )
                })
        ) {
            resourcesWithDefaultPath =
                ImmutableList.builderWithExpectedSize<Artifact?>(resources.size() + messages.size())
                    .addAll(resources.values())
                    .addAll(messages)
                    .build()
        } else {
            command.addObject(
                Lists.transform<F?, T?>(
                    ImmutableList.< E > copyOf < E ? > (resources.entrySet()),
                    Function { e: F? -> resourcePrefixedExecPath(e.getKey(), e.getValue()) })
            )
            resourcesWithDefaultPath = messages
        }

        if (!resourcesWithDefaultPath.isEmpty()) {
            command.addObject(
                Lists.transform<F?, T?>(
                    resourcesWithDefaultPath,
                    Function { artifact: F? ->
                        resourcePrefixedExecPath(
                            defaultResourcePath(artifact, semantics),
                            artifact
                        )
                    })
            )
        }
    }

    companion object {
        const val MNEMONIC: String = "JavaResourceJar"

        private val PARAM_FILE_INFO: ParamFileInfo? = ParamFileInfo.builder(ParameterFileType.SHELL_QUOTED).build()
        private val EXECUTION_INFO: ImmutableMap<String?, String?> =
            ImmutableMap.of<String?, String?>(ExecutionRequirements.SUPPORTS_PATH_MAPPING, "1")

        private fun defaultResourcePath(artifact: Artifact, semantics: JavaSemantics): PathFragment? {
            return semantics.getDefaultJavaResourcePath(artifact.getRootRelativePath())
        }

        private fun resourcePrefixedExecPath(resourcePath: PathFragment?, artifact: Artifact): String? {
            val execPath: PathFragment = artifact.getExecPath()
            return if (execPath == resourcePath) execPath.getPathString() else execPath.toString() + ":" + resourcePath
        }
    }
}
