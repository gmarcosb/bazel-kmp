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
package com.google.devtools.build.lib.rules.genrule

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.devtools.build.lib.analysis.constraints.ConstraintConstants.getOsFromConstraintsOrHost
import com.google.devtools.build.lib.analysis.stringtemplate.ExpansionException
import com.google.devtools.build.lib.util.Pair
import com.google.errorprone.annotations.ForOverride
import java.lang.String
import java.util.Map
import java.util.function.Function
import kotlin.Boolean

/**
 * A base implementation of genrule, to be used by specific implementing rules which can change the
 * semantics of [.collectSources].
 */
abstract class GenRuleBase : RuleConfiguredTargetFactory {
    @Throws(InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
    public override fun create(ruleContext: RuleContext): ConfiguredTarget? {
        val filesToBuild: NestedSet<Artifact?> =
            NestedSetBuilder.wrap(Order.STABLE_ORDER, ruleContext.getOutputArtifacts())

        if (filesToBuild.isEmpty()) {
            ruleContext.attributeError("outs", "Genrules without outputs don't make sense")
        }
        if (ruleContext.attributes().get("executable", Type.BOOLEAN)
            && !filesToBuild.isEmpty() && !filesToBuild.isSingleton()
        ) {
            ruleContext.attributeError(
                "executable",
                ("if genrules produce executables, they are allowed only one output. "
                        + "If you need the executable=1 argument, then you should split this genrule into "
                        + "genrules producing single outputs")
            )
        }

        val cmdTypeAndAttr: Pair<CommandType?, String?>? = determineCommandTypeAndAttribute(ruleContext)

        val labelMap: ImmutableMap<Label?, NestedSet<Artifact?>?> =
            collectSources(ruleContext.getPrerequisites("srcs"))
        val resolvedSrcsBuilder: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
        labelMap.values().forEach(resolvedSrcsBuilder::addTransitive)
        val resolvedSrcs: NestedSet<Artifact?> = resolvedSrcsBuilder.build()

        val toolchainPrerequisites: ImmutableList<ConfiguredTarget?>? =
            ruleContext.getToolchainContext().prerequisiteTargets().stream()
                .map({ obj: ConfiguredTargetAndData? -> obj.getConfiguredTarget() })
                .collect(ImmutableList.toImmutableList<E?>())
        // The CommandHelper class makes an explicit copy of this in the constructor, so flattening
        // here should be benign.
        val commandHelper: CommandHelper =
            CommandHelper.builder(ruleContext)
                .addToolDependencies("tools")
                .addToolDependencies("toolchains")
                .addToolDependencies(toolchainPrerequisites)
                .addLabelMap(
                    labelMap.entrySet().stream()
                        .collect(
                            ImmutableMap.toImmutableMap<T?, K?, V?>(
                                Function { Map.Entry.getKey() },
                                Function { e: T? -> e.getValue().toList() })
                        )
                )
                .build()

        if (ruleContext.hasErrors()) {
            return null
        }

        val cmdType = cmdTypeAndAttr!!.first
        val cmdAttr = cmdTypeAndAttr.second
        val expandToWindowsPath = cmdType == CommandType.WINDOWS_BATCH

        val baseCommand: String? = ruleContext.attributes().get(cmdAttr, Type.STRING)

        // Expand template variables and functions.
        val commandResolverContext =
            CommandResolverContext(
                ruleContext,
                resolvedSrcs,
                filesToBuild,  /* makeVariableSuppliers= */
                ImmutableList.of<MakeVariableSupplier?>(),
                expandToWindowsPath
            )
        var command: String? =
            ruleContext
                .getExpander(commandResolverContext)
                .withExecLocationsNoSrcs(commandHelper.getLabelMap(), expandToWindowsPath)
                .expand(cmdAttr, baseCommand)

        // Heuristically expand things that look like labels.
        if (ruleContext.attributes().get("heuristic_label_expansion", Type.BOOLEAN)) {
            command = commandHelper.expandLabelsHeuristically(command)
        }

        if (cmdType == CommandType.BASH) {
            // Add the genrule environment setup script before the actual shell command.
            command =
                String.format(
                    "source %s; %s",
                    ruleContext.getPrerequisiteArtifact("\$genrule_setup").getExecPath(), command
                )
        }

        val messageAttr: kotlin.String = ruleContext.attributes().get("message", Type.STRING)
        val message = if (messageAttr.isEmpty()) "Executing genrule" else messageAttr
        val label: Label? = ruleContext.getLabel()
        val progressMessage: OnDemandString =
            object : OnDemandString() {
                override fun toString(): kotlin.String {
                    return message + " " + label
                }
            }

        val executionInfo: MutableMap<kotlin.String?, kotlin.String?> = LinkedHashMap<kotlin.String?, kotlin.String?>()
        executionInfo.putAll(TargetUtils.getExecutionInfo(ruleContext.getRule()))

        if (ruleContext.attributes().get("local", Type.BOOLEAN)) {
            executionInfo.put("local", "")
        }

        ruleContext.getConfiguration().modifyExecutionInfo(executionInfo, GenRuleAction.Companion.MNEMONIC)

        val inputs: NestedSetBuilder<Artifact?> = NestedSetBuilder.stableOrder()
        inputs.addTransitive(resolvedSrcs)
        inputs.addTransitive(commandHelper.getResolvedTools())
        if (cmdType == CommandType.BASH) {
            val genruleSetup: FileProvider = ruleContext.getPrerequisite("\$genrule_setup", FileProvider::class.java)
            inputs.addTransitive(genruleSetup.getFilesToBuild())
        }
        if (ruleContext.hasErrors()) {
            return null
        }

        val constructor: CommandConstructor?
        when (cmdType) {
            CommandType.WINDOWS_BATCH -> constructor =
                CommandHelper.buildWindowsBatchCommandConstructor(".genrule_script.bat")

            CommandType.WINDOWS_POWERSHELL -> constructor =
                CommandHelper.buildWindowsPowershellCommandConstructor(".genrule_script.ps1")

            CommandType.BASH -> {
                val shExecutable: PathFragment? =
                    ShToolchain.getPathForPlatform(
                        ruleContext.getConfiguration(), ruleContext.getExecutionPlatform()
                    )
                constructor =
                    CommandHelper.buildBashCommandConstructor(
                        executionInfo, shExecutable, ".genrule_script.sh"
                    )
            }

            else -> {
                val shExecutable: PathFragment? =
                    ShToolchain.getPathForPlatform(
                        ruleContext.getConfiguration(), ruleContext.getExecutionPlatform()
                    )
                constructor =
                    CommandHelper.buildBashCommandConstructor(
                        executionInfo, shExecutable, ".genrule_script.sh"
                    )
            }
        }
        val argv: ImmutableList<kotlin.String?>? =
            commandHelper.buildCommandLine(
                command,
                inputs,
                constructor,
                getOsFromConstraintsOrHost(ruleContext.getExecutionPlatform())
            )

        if (isStampingEnabled(ruleContext)) {
            inputs.add(ruleContext.getAnalysisEnvironment().getStableWorkspaceStatusArtifact())
            inputs.add(ruleContext.getAnalysisEnvironment().getVolatileWorkspaceStatusArtifact())
        }

        ruleContext.registerAction(
            GenRuleAction(
                ruleContext.getActionOwner(),
                commandHelper.getResolvedTools(),
                inputs.build(),
                filesToBuild.toSet(),
                CommandLines.of(argv),
                ruleContext.getConfiguration().getActionEnvironment(),
                ImmutableMap.copyOf<kotlin.String?, kotlin.String?>(executionInfo),
                progressMessage
            )
        )

        val runfilesProvider: RunfilesProvider? =
            RunfilesProvider.withData( // No runfiles provided if not a data dependency.
                Runfiles.EMPTY,  // We only need to consider the outputs of a genrule. No need to visit the dependencies
                // of a genrule. They cross from the target into the exec configuration, because the
                // dependencies of a genrule are always built for the exec configuration.
                Builder(ruleContext.getWorkspaceName())
                    .addTransitiveArtifacts(filesToBuild)
                    .build()
            )

        return RuleConfiguredTargetBuilder(ruleContext)
            .setFilesToBuild(filesToBuild)
            .setRunfilesSupport(null, getExecutable(ruleContext, filesToBuild))
            .addProvider(RunfilesProvider::class.java, runfilesProvider)
            .addNativeDeclaredProvider(
                InstrumentedFilesCollector.collect(
                    ruleContext,
                    InstrumentationSpec(FileTypeSet.ANY_FILE).withSourceAttributes("srcs")
                )
            )
            .build()
    }

    /** Collects sources from src attribute.  */
    @ForOverride
    @Throws(RuleErrorException::class)
    protected abstract fun collectSources(
        srcs: MutableList<out TransitiveInfoCollection?>?
    ): ImmutableMap<Label?, NestedSet<Artifact?>?>

    private enum class CommandType {
        BASH,
        WINDOWS_BATCH,
        WINDOWS_POWERSHELL,
    }

    /**
     * Implementation of [ConfigurationMakeVariableContext] used to expand variables in a
     * genrule command string.
     */
    private class CommandResolverContext(
        ruleContext: RuleContext,
        resolvedSrcs: NestedSet<Artifact?>,
        filesToBuild: NestedSet<Artifact?>,
        makeVariableSuppliers: Iterable<out MakeVariableSupplier?>?,
        windowsPath: Boolean
    ) : ConfigurationMakeVariableContext(
        ruleContext.getRule().getPackageDeclarations(),
        ruleContext.getConfiguration(),
        ruleContext.getDefaultTemplateVariableProviders(),
        makeVariableSuppliers
    ) {
        private val ruleContext: RuleContext
        private val resolvedSrcs: NestedSet<Artifact?>
        private val filesToBuild: NestedSet<Artifact?>
        private val windowsPath: Boolean

        init {
            this.ruleContext = ruleContext
            this.resolvedSrcs = resolvedSrcs
            this.filesToBuild = filesToBuild
            this.windowsPath = windowsPath
        }

        @Throws(ExpansionException::class)
        public override fun lookupVariable(variableName: kotlin.String): kotlin.String? {
            val `val` = lookupVariableImpl(variableName)
            if (windowsPath) {
                return `val`.replace('/', '\\')
            }
            return `val`
        }

        @Throws(ExpansionException::class)
        fun lookupVariableImpl(variableName: kotlin.String): kotlin.String {
            if (variableName == "SRCS") {
                return Artifact.joinExecPaths(" ", resolvedSrcs.toList())
            }

            if (variableName == "<") {
                return expandSingletonArtifact(resolvedSrcs, "$<", "input file")
            }

            if (variableName == "OUTS") {
                return Artifact.joinExecPaths(" ", filesToBuild.toList())
            }

            if (variableName == "@") {
                return expandSingletonArtifact(filesToBuild, "$@", "output file")
            }

            val ruleDirPackagePath: PathFragment? = ruleContext.getPackageDirectory()
            val ruleDirExecPath: PathFragment =
                ruleContext.getBinOrGenfilesDirectory().getExecPath().getRelative(ruleDirPackagePath)

            if (variableName == "RULEDIR") {
                // The output root directory. This variable expands to the package's root directory
                // in the genfiles tree.
                return ruleDirExecPath.getPathString()
            }

            if (variableName == "@D") {
                // The output directory. If there is only one filename in outs,
                // this expands to the directory containing that file. If there are
                // multiple filenames, this variable instead expands to the
                // package's root directory in the genfiles tree, even if all the
                // generated files belong to the same subdirectory!
                if (filesToBuild.isSingleton()) {
                    val outputFile: Artifact = filesToBuild.getSingleton()
                    val relativeOutputFile: PathFragment = outputFile.getExecPath()
                    check(relativeOutputFile.isMultiSegment()) { "$(@D) for genrule " + ruleContext.getLabel() + " has less than one segment" }
                    return relativeOutputFile.getParentDirectory().getPathString()
                } else {
                    return ruleDirExecPath.getPathString()
                }
            }

            return super.lookupVariable(variableName)
        }

        companion object {
            /**
             * Returns the path of the sole element "artifacts", generating an exception with an informative
             * error message iff the set is not a singleton. Used to expand "$<", "$@".
             */
            @Throws(ExpansionException::class)
            private fun expandSingletonArtifact(
                artifacts: NestedSet<Artifact?>, variable: kotlin.String?, artifactName: kotlin.String?
            ): kotlin.String {
                if (artifacts.isEmpty()) {
                    throw ExpansionException("variable '" + variable + "' : no " + artifactName)
                } else if (!artifacts.isSingleton()) {
                    throw ExpansionException("variable '" + variable + "' : more than one " + artifactName)
                }
                return artifacts.getSingleton().getExecPathString()
            }
        }
    }

    companion object {
        private fun isStampingEnabled(ruleContext: RuleContext): Boolean {
            // This intentionally does not call AnalysisUtils.isStampingEnabled(). That method returns false
            // in the exec configuration (regardless of the attribute value), which is the behavior for
            // binaries, but not genrules.
            val stamp: TriState? = ruleContext.attributes().get("stamp", BuildType.TRISTATE)
            return stamp === TriState.YES
                    || (stamp === TriState.AUTO && ruleContext.getConfiguration().stampBinaries())
        }

        private fun determineCommandTypeAndAttribute(
            ruleContext: RuleContext
        ): Pair<CommandType?, kotlin.String?>? {
            val attributeMap: AttributeMap = ruleContext.attributes()
            if (ruleContext.isDefaultExecGroupExecutingOnWindows()) {
                if (attributeMap.isAttributeValueExplicitlySpecified("cmd_ps")) {
                    return Pair.of<CommandType?, kotlin.String?>(CommandType.WINDOWS_POWERSHELL, "cmd_ps")
                }
                if (attributeMap.isAttributeValueExplicitlySpecified("cmd_bat")) {
                    return Pair.of<CommandType?, kotlin.String?>(CommandType.WINDOWS_BATCH, "cmd_bat")
                }
            }
            if (attributeMap.isAttributeValueExplicitlySpecified("cmd_bash")) {
                return Pair.of<CommandType?, kotlin.String?>(CommandType.BASH, "cmd_bash")
            }
            if (attributeMap.isAttributeValueExplicitlySpecified("cmd")) {
                return Pair.of<CommandType?, kotlin.String?>(CommandType.BASH, "cmd")
            }
            ruleContext.attributeError(
                "cmd",
                "missing value for `cmd` attribute, you can also set `cmd_ps` or `cmd_bat` on"
                        + " Windows and `cmd_bash` on other platforms."
            )
            return null
        }

        /**
         * Returns the executable artifact, if the rule is marked as executable and there is only one
         * artifact.
         */
        private fun getExecutable(ruleContext: RuleContext, filesToBuild: NestedSet<Artifact?>): Artifact? {
            if (!ruleContext.attributes().get("executable", Type.BOOLEAN)) {
                return null
            }
            return if (filesToBuild.isSingleton()) filesToBuild.getSingleton() else null
        }
    }
}
