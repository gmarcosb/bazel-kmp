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
package com.google.devtools.build.lib.rules.java

import com.google.common.base.Ascii
import com.google.common.base.Optional
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.analysis.PlatformOptions
import com.google.devtools.build.lib.concurrent.ThreadSafety
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.StarlarkThread
import java.util.*

/** A java compiler configuration containing the flags required for compilation.  */
@ThreadSafety.Immutable
@RequiresOptions(options = [JavaOptions::class, PlatformOptions::class])
class JavaConfiguration(buildOptions: BuildOptions) : Fragment(), JavaConfigurationApi {
    /** Values for the --java_classpath option  */
    enum class JavaClasspathMode {
        /** Use full transitive classpaths, the default behavior.  */
        OFF,

        /** JavaBuilder computes the reduced classpath before invoking javac.  */
        JAVABUILDER,

        /** Bazel computes the reduced classpath and tries it in a separate action invocation.  */
        BAZEL,

        /** Bazel uses the reduced classpath, but doesn't fallback to the full transitive classpath  */
        BAZEL_NO_FALLBACK,
    }

    /** Values for the --experimental_one_version_enforcement option  */
    enum class OneVersionEnforcementLevel {
        /** Don't attempt to check for one version violations (the default)  */
        OFF,

        /**
         * Check for one version violations, emit warnings to stderr if any are found, but don't break
         * the binary.
         */
        WARNING,

        /**
         * Check for one version violations, emit warnings to stderr if any are found, and break the
         * rule if it's found.
         */
        ERROR
    }

    private val commandLineJavacFlags: NestedSet<String?>
    private val javaLauncherLabel: Label?

    /** Returns true iff Java compilation should use ijars.  */
    val useIjars: Boolean
    private val useHeaderCompilation: Boolean

    /** Returns true iff dependency information is generated after compilation.  */
    val generateJavaDeps: Boolean
    private val enforceOneVersion: OneVersionEnforcementLevel
    private val enforceOneVersionOnJavaTests: Boolean
    val reduceJavaClasspath: JavaClasspathMode?
    private val inmemoryJdepsFiles: Boolean
    val defaultJvmFlags: ImmutableList<String?>
    private val strictJavaDeps: StrictDepsMode

    /** Which tool to use for fixing dependency errors.  */
    val fixDepsTool: String?
    private val proguardBinary: Label?
    private val bytecodeOptimizer: NamedLabel
    private val runLocalJavaOptimizations: Boolean
    private val localJavaOptimizationConfiguration: Label?
    private val splitBytecodeOptimizationPass: Boolean
    private val bytecodeOptimizationPassActions: Int
    private val enforceProguardFileExtension: Boolean
    private val runAndroidLint: Boolean
    private val explicitJavaTestDeps: Boolean
    private val addTestSupportToCompileTimeDeps: Boolean
    private val pluginList: ImmutableList<Label?>
    private val experimentalTurbineAnnotationProcessing: Boolean
    private val experimentalTurbineCpuReservation: Int
    private val experimentalEnableJspecify: Boolean
    private val multiReleaseDeployJars: Boolean
    private val disallowJavaImportExports: Boolean
    private val autoCreateDeployJarForJavaTests: Boolean

    init {
        val javaOptions: JavaOptions = buildOptions.get(JavaOptions::class.java)
        this.commandLineJavacFlags =
            JavaHelper.detokenizeJavaOptions(
                JavaHelper.tokenizeJavaOptions(javaOptions.getJavacOpts())
            )
        this.javaLauncherLabel = javaOptions.getJavaLauncher()
        this.useIjars = javaOptions.getUseIjars()
        this.useHeaderCompilation = javaOptions.getHeaderCompilation()
        this.generateJavaDeps =
            javaOptions.getJavaDeps() || javaOptions.getJavaClasspath() != JavaClasspathMode.OFF
        this.reduceJavaClasspath = javaOptions.getJavaClasspath()
        this.inmemoryJdepsFiles = javaOptions.getInmemoryJdepsFiles()
        this.defaultJvmFlags = ImmutableList.copyOf<String?>(javaOptions.getJvmOpts())
        this.strictJavaDeps = javaOptions.getStrictJavaDeps()
        this.fixDepsTool = javaOptions.getFixDepsTool()
        this.proguardBinary = javaOptions.getProguard()
        this.runLocalJavaOptimizations = javaOptions.getRunLocalJavaOptimizations()
        this.localJavaOptimizationConfiguration = javaOptions.getLocalJavaOptimizationConfiguration()
        this.splitBytecodeOptimizationPass = javaOptions.getSplitBytecodeOptimizationPass()
        this.bytecodeOptimizationPassActions = javaOptions.getBytecodeOptimizationPassActions()
        this.enforceProguardFileExtension = javaOptions.getEnforceProguardFileExtension()
        this.enforceOneVersion = javaOptions.getEnforceOneVersion()
        this.enforceOneVersionOnJavaTests = javaOptions.getEnforceOneVersionOnJavaTests()
        this.explicitJavaTestDeps = javaOptions.getExplicitJavaTestDeps()
        this.addTestSupportToCompileTimeDeps = javaOptions.getAddTestSupportToCompileTimeDeps()
        this.runAndroidLint = javaOptions.getRunAndroidLint()
        this.multiReleaseDeployJars = javaOptions.getMultiReleaseDeployJars()
        this.disallowJavaImportExports = javaOptions.getDisallowJavaImportExports()
        this.autoCreateDeployJarForJavaTests = javaOptions.getAutoCreateDeployJarForJavaTests()
        val optimizers: MutableMap<String?, Label?> = javaOptions.getBytecodeOptimizers()
        if (optimizers.size() != 1) {
            throw InvalidConfigurationException(
                java.lang.String.format(
                    "--experimental_bytecode_optimizers can only accept exactly one mapping, but %d"
                            + " mappings were provided.",
                    optimizers.size()
                )
            )
        }
        val optimizer: MutableMap.MutableEntry<String?, Label?>? =
            Iterables.getOnlyElement<MutableMap.MutableEntry<String?, Label?>?>(optimizers.entrySet())
        val mnemonic: String? = optimizer.getKey()
        val optimizerLabel: Label? = optimizer.getValue()
        if (optimizerLabel == null && "Proguard" != mnemonic) {
            throw InvalidConfigurationException("Must supply label for optimizer " + mnemonic)
        }
        this.bytecodeOptimizer = NamedLabel.Companion.create(mnemonic, Optional.fromNullable<Label?>(optimizerLabel))
        if (runLocalJavaOptimizations && optimizerLabel == null) {
            throw InvalidConfigurationException(
                "--experimental_local_java_optimizations cannot be provided without "
                        + "--experimental_bytecode_optimizers."
            )
        }

        this.pluginList = ImmutableList.copyOf<Label?>(javaOptions.getPluginList())
        this.experimentalTurbineAnnotationProcessing =
            javaOptions.getExperimentalTurbineAnnotationProcessing()
        this.experimentalTurbineCpuReservation = javaOptions.getTurbineCpuReservation()
        this.experimentalEnableJspecify = javaOptions.getExperimentalEnableJspecify()
    }

    val defaultJavacFlagsForStarlarkAsList: ImmutableList<String?>?
        // TODO(bazel-team): this is the command-line passed options, we should remove from Starlark
        get() = JavaHelper.tokenizeJavaOptions(commandLineJavacFlags)

    val defaultJavacFlagsStarlark: Depset
        // TODO(bazel-team): this is the command-line passed options, we should remove from Starlark
        get() = Depset.of(String::class.java, commandLineJavacFlags)

    val strictJavaDepsName: String
        get() = Ascii.toLowerCase(strictJavaDeps.name())

    /**
     * Returns true iff Java compilation should use ijars. Checks if the functions is been called from
     * builtins.
     */
    @Throws(EvalException::class)
    override fun getUseIjarsInStarlark(thread: StarlarkThread?): Boolean {
        JavaStarlarkCommon.Companion.checkPrivateAccess(thread)
        return useIjars
    }

    @Throws(EvalException::class)
    override fun useHeaderCompilationStarlark(thread: StarlarkThread?): Boolean {
        JavaStarlarkCommon.Companion.checkPrivateAccess(thread)
        return useHeaderCompilation
    }

    @Throws(EvalException::class)
    override fun getGenerateJavaDepsStarlark(thread: StarlarkThread?): Boolean {
        JavaStarlarkCommon.Companion.checkPrivateAccess(thread)
        return this.generateJavaDeps
    }

    @Throws(EvalException::class)
    override fun getReduceJavaClasspathStarlark(thread: StarlarkThread?): String? {
        JavaStarlarkCommon.Companion.checkPrivateAccess(thread)
        return this.reduceJavaClasspath.name()
    }

    fun inmemoryJdepsFiles(): Boolean {
        return inmemoryJdepsFiles
    }

    /** Returns proper label only if --java_launcher= is specified, otherwise null.  */
    @StarlarkConfigurationField(
        name = "launcher",
        doc = "Returns the label provided with --java_launcher, if any.",
        defaultInToolRepository = true
    )
    fun getJavaLauncherLabel(): Label? {
        return javaLauncherLabel
    }

    /** Returns the label provided with --proguard_top, if any.  */
    @StarlarkConfigurationField(
        name = "proguard_top",
        doc = "Returns the label provided with --proguard_top, if any.",
        defaultInToolRepository = true
    )
    fun getProguardBinary(): Label? {
        return proguardBinary
    }

    /**
     * Returns whether the OPTIMIZATION stage of the bytecode optimizer will be split across two
     * actions.
     */
    override fun splitBytecodeOptimizationPass(): Boolean {
        return splitBytecodeOptimizationPass
    }

    /**
     * This specifies the number of actions to divide the OPTIMIZATION stage of the bytecode optimizer
     * into. Note that if split_bytecode_optimization_pass is set, this will only change behavior if
     * it is > 2.
     */
    override fun bytecodeOptimizationPassActions(): Int {
        return bytecodeOptimizationPassActions
    }

    /** Returns whether ProGuard configuration files are required to use a *.pgcfg extension.  */
    override fun enforceProguardFileExtension(): Boolean {
        return enforceProguardFileExtension
    }

    /** Stores a String name and an optional associated label.  */
    @AutoCodec
    class NamedLabel(name: String?, label: Optional<Label?>?) {
        val name: String?
        val label: Optional<Label?>?

        init {
            this.label = label
            this.name = name
            Objects.requireNonNull<String?>(name, "name")
            Objects.requireNonNull<Optional<Label?>?>(label, "label")
        }

        companion object {
            fun create(name: String?, label: Optional<Label?>?): NamedLabel {
                return NamedLabel(name, label)
            }
        }
    }

    /** Returns bytecode optimizer to run.  */
    fun getBytecodeOptimizer(): NamedLabel? {
        return bytecodeOptimizer
    }

    val bytecodeOptimizerMnemonic: String?
        get() = bytecodeOptimizer.name

    @get:StarlarkConfigurationField(
        name = "bytecode_optimizer",
        doc = "Returns the label provided with --proguard_top, if any.",
        defaultInToolRepository = true
    )
    val bytecodeOptimizerLabel: Label?
        get() = bytecodeOptimizer.label!!.orNull()

    /** Returns true if the bytecode optimizer should incrementally optimize all Java artifacts.  */
    fun runLocalJavaOptimizations(): Boolean {
        return runLocalJavaOptimizations
    }

    @get:StarlarkConfigurationField(
        name = "java_toolchain_bytecode_optimizer",
        documented = false,
        defaultInToolRepository = true
    )
    val bytecodeOptimizerLabelForJavaToolchain: Label?
        get() {
            if (runLocalJavaOptimizations) {
                return bytecodeOptimizer.label!!.orNull()
            } else {
                return null
            }
        }

    /** Returns the optimization configuration for local Java optimizations if they are enabled.  */
    @StarlarkConfigurationField(name = "local_java_optimization_configuration", documented = false)
    fun getLocalJavaOptimizationConfiguration(): Label? {
        if (runLocalJavaOptimizations) {
            return localJavaOptimizationConfiguration
        } else {
            return null
        }
    }

    /**
     * Make it mandatory for java_test targets to explicitly declare any JUnit or Hamcrest
     * dependencies instead of accidentally obtaining them from the TestRunner's dependencies.
     */
    @Throws(EvalException::class)
    override fun explicitJavaTestDepsStarlark(thread: StarlarkThread?): Boolean {
        JavaStarlarkCommon.Companion.checkPrivateAccess(thread)
        return explicitJavaTestDeps
    }

    override fun multiReleaseDeployJars(): Boolean {
        return multiReleaseDeployJars
    }

    /** Returns true if java_import exports are not allowed.  */
    @Throws(EvalException::class)
    override fun getDisallowJavaImportExportsInStarlark(thread: StarlarkThread?): Boolean {
        JavaStarlarkCommon.Companion.checkPrivateAccess(thread)
        return disallowJavaImportExports
    }

    /**
     * Returns an enum representing whether or not Bazel should attempt to enforce one-version
     * correctness on java_binary rules using the 'oneversion' tool in the java_toolchain.
     * 
     * 
     * One-version correctness will inspect for multiple non-identical versions of java classes in
     * the transitive dependencies for a java_binary.
     */
    override fun starlarkOneVersionEnforcementLevel(): String? {
        return enforceOneVersion.name()
    }

    override fun enforceOneVersionOnJavaTests(): Boolean {
        return enforceOneVersionOnJavaTests
    }

    override fun addTestSupportToCompileTimeDeps(): Boolean {
        return addTestSupportToCompileTimeDeps
    }

    override fun runAndroidLint(): Boolean {
        return runAndroidLint
    }

    val plugins: ImmutableList<Label>
        get() = pluginList

    fun experimentalTurbineAnnotationProcessing(): Boolean {
        return experimentalTurbineAnnotationProcessing
    }

    fun experimentalTurbineCpuReservation(): Int {
        return experimentalTurbineCpuReservation
    }

    fun experimentalEnableJspecify(): Boolean {
        return experimentalEnableJspecify
    }

    @Throws(EvalException::class)
    override fun autoCreateJavaTestDeployJars(thread: StarlarkThread?): Boolean {
        BuiltinRestriction.failIfCalledOutsideDefaultAllowlist(thread)
        return autoCreateDeployJarForJavaTests
    }

    // TODO: b/417791104 - Remove this method once usages are removed.
    @Throws(EvalException::class)
    override fun getUseHeaderCompilationDirectDepsInStarlark(thread: StarlarkThread?): Boolean {
        JavaStarlarkCommon.Companion.checkPrivateAccess(thread)
        return true
    }
}
