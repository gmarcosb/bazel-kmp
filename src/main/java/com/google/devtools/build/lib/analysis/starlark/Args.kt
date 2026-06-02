// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.starlark

import com.google.devtools.build.lib.actions.Artifact

/**
 * Implementation of the `Args` Starlark type, which, in a builder-like pattern, encapsulates
 * the data needed to build all or part of a command line.
 */
abstract class Args private constructor() : CommandLineArgsApi {
    @Throws(net.starlark.java.eval.EvalException::class)
    override fun checkHashable() {
        // Even a frozen Args is not hashable.
        throw net.starlark.java.eval.Starlark.errorf(
            "unhashable type: '%s'",
            net.starlark.java.eval.Starlark.type(this)
        )
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.append("context.args() object")
    }

    override fun debugPrint(printer: net.starlark.java.eval.Printer, thread: net.starlark.java.eval.StarlarkThread?) {
        try {
            printer.append(
                com.google.common.base.Joiner.on(" ")
                    .join(build( /* mainRepoMappingSupplier= */InterruptibleSupplier { null }).arguments())
            )
        } catch (e: CommandLineExpansionException) {
            printer.append("Cannot expand command line: " + e.getMessage())
        } catch (e: java.lang.InterruptedException) {
            java.lang.Thread.currentThread().interrupt()
            printer.append("Interrupted while expanding command line: " + e.message)
        }
    }

    /**
     * Returns the file format to use if this object's encapsulated arguments were to be written to a
     * param file. This value is meaningful even if [.getParamFileInfo] is null, as one can
     * force these args to be written to a param file using `actions.write`, even if the args
     * would not be written to a params file if used in normal action registration.
     */
    @kotlin.jvm.JvmField
    abstract val parameterFileType: ParameterFileType?

    /**
     * Returns a [ParamFileInfo] describing how a params file should be constructed to contain
     * this object's encapsulated arguments when an action is registered using this object. If a
     * parameter file should not be used (even under operating system arg limits), returns null.
     */
    @kotlin.jvm.JvmField
    abstract val paramFileInfo: ParamFileInfo?

    /**
     * Returns a set of directory artifacts which will need to be expanded for evaluating the
     * encapsulated arguments during execution.
     */
    abstract val directoryArtifacts: com.google.common.collect.ImmutableSet<Artifact?>?

    /** Returns the command line built by this [Args] object.  */
    @Throws(java.lang.InterruptedException::class)
    abstract fun build(
        mainRepoMappingSupplier: InterruptibleSupplier<com.google.devtools.build.lib.cmdline.RepositoryMapping?>?
    ): CommandLine?

    /**
     * A frozen (immutable) representation of [Args], constructed from an already-built command
     * line.
     */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    private class FrozenArgs(
        commandLine: CommandLine?,
        paramFileInfo: ParamFileInfo?,
        directoryInputs: com.google.common.collect.ImmutableSet<Artifact?>?
    ) : Args() {
        private val commandLine: CommandLine?
        private val paramFileInfo: ParamFileInfo?
        private val directoryInputs: com.google.common.collect.ImmutableSet<Artifact?>?

        init {
            this.commandLine = commandLine
            this.paramFileInfo = paramFileInfo
            this.directoryInputs = directoryInputs
        }

        val isImmutable: Boolean
            get() = true // immutable but not directly hashable (though may be hashed as an element of,
        // say, a struct).

        override fun getDirectoryArtifacts(): com.google.common.collect.ImmutableSet<Artifact?>? {
            return directoryInputs
        }

        override fun build(mainRepoMappingSupplier: InterruptibleSupplier<com.google.devtools.build.lib.cmdline.RepositoryMapping?>?): CommandLine? {
            return commandLine
        }

        override fun getParameterFileType(): ParameterFileType {
            if (paramFileInfo != null) {
                return paramFileInfo.getFileType()
            } else {
                return ParameterFileType.SHELL_QUOTED
            }
        }

        override fun getParamFileInfo(): ParamFileInfo? {
            return paramFileInfo
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun addArgument(
            argNameOrValue: Any?, value: Any?, format: Any?, thread: net.starlark.java.eval.StarlarkThread?
        ): CommandLineArgsApi? {
            throw net.starlark.java.eval.Starlark.errorf("cannot modify frozen value")
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun addAll(
            argNameOrValue: Any?,
            values: Any?,
            mapEach: Any?,
            formatEach: Any?,
            beforeEach: Any?,
            omitIfEmpty: Boolean?,
            uniquify: Boolean?,
            expandDirectories: Boolean?,
            terminateWith: Any?,
            allowClosure: Boolean?,
            thread: net.starlark.java.eval.StarlarkThread?
        ): CommandLineArgsApi? {
            throw net.starlark.java.eval.Starlark.errorf("cannot modify frozen value")
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun addJoined(
            argNameOrValue: Any?,
            values: Any?,
            joinWith: String?,
            mapEach: Any?,
            formatEach: Any?,
            formatJoined: Any?,
            omitIfEmpty: Boolean?,
            uniquify: Boolean?,
            expandDirectories: Boolean?,
            allowClosure: Boolean?,
            thread: net.starlark.java.eval.StarlarkThread?
        ): CommandLineArgsApi? {
            throw net.starlark.java.eval.Starlark.errorf("cannot modify frozen value")
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun useParamsFile(paramFileArg: String?, useAlways: Boolean?): CommandLineArgsApi? {
            // TODO(cparsons): Even "frozen" Args may need to use params files.
            // If we go down this path, we will need to rename this class and update the documentation
            // (as this class no longe behaves exactly like a frozen Args object)
            throw net.starlark.java.eval.Starlark.errorf("cannot modify frozen value")
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun setParamFileFormat(format: String?): CommandLineArgsApi? {
            // TODO(cparsons): Even "frozen" Args may need to use params files.
            // If we go down this path, we will need to rename this class and update the documentation
            // (as this class no longe behaves exactly like a frozen Args object)
            throw net.starlark.java.eval.Starlark.errorf("cannot modify frozen value")
        }
    }

    /** Args module.  */
    private class MutableArgs(
        mutability: net.starlark.java.eval.Mutability?,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?
    ) : Args(), net.starlark.java.eval.StarlarkValue, net.starlark.java.eval.Mutability.Freezable {
        private val mutability: net.starlark.java.eval.Mutability?
        private val commandLine: com.google.devtools.build.lib.analysis.starlark.StarlarkCustomCommandLine.Builder

        private val potentialDirectoryArtifacts: MutableList<NestedSet<*>> = java.util.ArrayList<NestedSet<*>>()
        private val directoryArtifacts: MutableSet<Artifact?> = HashSet<Artifact?>()

        /**
         * If true, flag names and values will be grouped with '=', e.g.
         * 
         * <pre>
         * --a=b
         * --noc
         * --d=e
        </pre> * 
         * 
         * Further, if this is true, the ParamFileInfo will be marked 'flagsOnly', so that positional
         * parameters stay on the command line and the param file contains only flags.
         */
        private var flagPerLine = false

        /**
         * True if the command line needs to stringify any [Label]s without an explicit 'map_each'
         * function.
         */
        private var mayStringifyExternalLabel = false

        // May be set explicitly once -- if unset defaults to ParameterFileType.SHELL_QUOTED.
        private var parameterFileType: ParameterFileType? = null
        private var flagFormatString: String? = null
        private var alwaysUseParamFile = false

        override fun getParameterFileType(): ParameterFileType? {
            return if (parameterFileType == null) ParameterFileType.SHELL_QUOTED else parameterFileType
        }

        override fun getParamFileInfo(): ParamFileInfo? {
            if (flagFormatString == null) {
                return null
            } else {
                val builder: ParamFileInfo.Builder =
                    ParamFileInfo.builder(getParameterFileType())
                        .setFlagFormatString(flagFormatString)
                        .setUseAlways(alwaysUseParamFile)
                return builder.setFlagsOnly(flagPerLine).build()
            }
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(net.starlark.java.eval.EvalException::class)
        override fun addArgument(
            argNameOrValue: Any, value: Any, format: Any?, thread: net.starlark.java.eval.StarlarkThread?
        ): CommandLineArgsApi {
            var value = value
            net.starlark.java.eval.Starlark.checkMutable(this)
            val argName: String?
            if (value === net.starlark.java.eval.Starlark.UNBOUND) {
                value = argNameOrValue
                argName = null
            } else {
                validateArgName(argNameOrValue)
                argName = argNameOrValue as String
            }
            commandLine.recordArgStart()
            if (argName != null) {
                commandLine.add(argName)
            }
            if (value is Depset || value is net.starlark.java.eval.Sequence<*>) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "Args.add() doesn't accept vectorized arguments. Please use Args.add_all() or"
                            + " Args.add_joined() instead."
                )
            }
            if (value is com.google.devtools.build.lib.cmdline.Label && !value.getRepository().isMain()) {
                mayStringifyExternalLabel = true
            }
            addSingleArg(value, if (format !== net.starlark.java.eval.Starlark.NONE) format as String? else null)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(net.starlark.java.eval.EvalException::class)
        override fun addAll(
            argNameOrValue: Any,
            values: Any,
            mapEach: Any?,
            formatEach: Any?,
            beforeEach: Any?,
            omitIfEmpty: Boolean,
            uniquify: Boolean,
            expandDirectories: Boolean,
            terminateWith: Any?,
            allowClosure: Boolean,
            thread: net.starlark.java.eval.StarlarkThread
        ): CommandLineArgsApi {
            var values = values
            net.starlark.java.eval.Starlark.checkMutable(this)
            val argName: String?
            if (values === net.starlark.java.eval.Starlark.UNBOUND) {
                values = argNameOrValue
                validateValues(values)
                argName = null
            } else {
                validateArgName(argNameOrValue)
                argName = argNameOrValue as String
            }
            addVectorArg(
                values,
                argName,
                validateMapEach(mapEach, allowClosure),
                if (formatEach !== net.starlark.java.eval.Starlark.NONE) formatEach as String? else null,
                if (beforeEach !== net.starlark.java.eval.Starlark.NONE) beforeEach as String? else null,  /* joinWith= */
                null,  /* formatJoined= */
                null,
                omitIfEmpty,
                uniquify,
                expandDirectories,
                if (terminateWith !== net.starlark.java.eval.Starlark.NONE) terminateWith as String? else null,
                thread.getCallerLocation()
            )
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(net.starlark.java.eval.EvalException::class)
        override fun addJoined(
            argNameOrValue: Any,
            values: Any,
            joinWith: String?,
            mapEach: Any?,
            formatEach: Any?,
            formatJoined: Any?,
            omitIfEmpty: Boolean,
            uniquify: Boolean,
            expandDirectories: Boolean,
            allowClosure: Boolean,
            thread: net.starlark.java.eval.StarlarkThread
        ): CommandLineArgsApi {
            var values = values
            net.starlark.java.eval.Starlark.checkMutable(this)
            val argName: String?
            if (values === net.starlark.java.eval.Starlark.UNBOUND) {
                values = argNameOrValue
                validateValues(values)
                argName = null
            } else {
                validateArgName(argNameOrValue)
                argName = argNameOrValue as String
            }
            addVectorArg(
                values,
                argName,
                validateMapEach(mapEach, allowClosure),
                if (formatEach !== net.starlark.java.eval.Starlark.NONE) formatEach as String? else null,  /* beforeEach= */
                null,
                joinWith,
                if (formatJoined !== net.starlark.java.eval.Starlark.NONE) formatJoined as String? else null,
                omitIfEmpty,
                uniquify,
                expandDirectories,  /* terminateWith= */
                null,
                thread.getCallerLocation()
            )
            return this
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun addVectorArg(
            value: Any?,
            argName: String?,
            mapEach: net.starlark.java.eval.StarlarkCallable?,
            formatEach: String?,
            beforeEach: String?,
            joinWith: String?,
            formatJoined: String?,
            omitIfEmpty: Boolean,
            uniquify: Boolean,
            expandDirectories: Boolean,
            terminateWith: String?,
            loc: net.starlark.java.syntax.Location?
        ) {
            validateFormatString("format_each", formatEach)
            validateFormatString("format_joined", formatJoined)
            val vectorArg: com.google.devtools.build.lib.analysis.starlark.StarlarkCustomCommandLine.VectorArg.Builder?
            if (value is Depset) {
                if (mapEach == null && com.google.devtools.build.lib.cmdline.Label::class.java == value.getElementClass()) {
                    // We don't want to eagerly check whether all labels reference targets in the main repo,
                    // so just assume they might not. Nested sets of labels should be rare.
                    mayStringifyExternalLabel = true
                }
                val nestedSet: NestedSet<*> = value.getSet()
                if (nestedSet.isEmpty() && omitIfEmpty) {
                    return
                }
                if (expandDirectories) {
                    potentialDirectoryArtifacts.add(nestedSet)
                }
                vectorArg =
                    com.google.devtools.build.lib.analysis.starlark.StarlarkCustomCommandLine.VectorArg.Builder(
                        nestedSet, value.getElementClass()
                    )
            } else {
                val starlarkList: net.starlark.java.eval.Sequence<*> = value as net.starlark.java.eval.Sequence<*>
                if (starlarkList.isEmpty() && omitIfEmpty) {
                    return
                }
                for (`object` in starlarkList) {
                    if (expandDirectories && isDirectory(`object`)) {
                        directoryArtifacts.add(`object` as Artifact)
                    }
                    // Labels referencing targets in the main repo are stringified as //pkg:name and thus
                    // don't require a RepositoryMapping. If a map_each function is provided, default
                    // stringification via Label#toString() is not used.
                    if (mapEach == null && `object` is com.google.devtools.build.lib.cmdline.Label && !`object`.getRepository()
                            .isMain()
                    ) {
                        mayStringifyExternalLabel = true
                    }
                }
                vectorArg = com.google.devtools.build.lib.analysis.starlark.StarlarkCustomCommandLine.VectorArg.Builder(
                    starlarkList
                )
            }
            commandLine.recordArgStart()
            vectorArg
                .setLocation(loc)
                .setArgName(argName)
                .setExpandDirectories(expandDirectories)
                .setFormatEach(formatEach)
                .setBeforeEach(beforeEach)
                .setJoinWith(joinWith)
                .setFormatJoined(formatJoined)
                .omitIfEmpty(omitIfEmpty)
                .uniquify(uniquify)
                .setTerminateWith(terminateWith)
                .setMapEach(mapEach)
            commandLine.add(vectorArg)
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        fun addSingleArg(value: Any, format: String?) {
            var value = value
            if (value is String) {
                value = value.intern()
            }
            validateNoDirectory(value)
            validateFormatString("format", format)
            if (format == null) {
                commandLine.add(value)
            } else {
                commandLine.addFormatted(value, format)
            }
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(net.starlark.java.eval.EvalException::class)
        override fun useParamsFile(paramFileArg: String?, useAlways: Boolean): CommandLineArgsApi {
            net.starlark.java.eval.Starlark.checkMutable(this)
            if (!SingleStringArgFormatter.isValid(paramFileArg)) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "Invalid value for parameter \"param_file_arg\": Expected string with a single \"%%s\","
                            + " got \"%s\"",
                    paramFileArg
                )
            }
            this.flagFormatString = paramFileArg
            this.alwaysUseParamFile = useAlways
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(net.starlark.java.eval.EvalException::class)
        override fun setParamFileFormat(format: String): CommandLineArgsApi {
            net.starlark.java.eval.Starlark.checkMutable(this)
            if (this.parameterFileType != null) {
                throw net.starlark.java.eval.Starlark.errorf("set_param_file_format() may only be called once")
            }
            val parameterFileType: ParameterFileType?
            val flagPerLine: Boolean
            when (format) {
                "shell" -> {
                    parameterFileType = ParameterFileType.SHELL_QUOTED
                    flagPerLine = false
                }

                "multiline" -> {
                    parameterFileType = ParameterFileType.UNQUOTED
                    flagPerLine = false
                }

                "flag_per_line" -> {
                    parameterFileType = ParameterFileType.UNQUOTED
                    flagPerLine = true
                }

                else -> throw net.starlark.java.eval.Starlark.errorf(
                    "Invalid value for parameter \"format\": Expected one of \"shell\", \"multiline\","
                            + " \"flag_per_line\""
                )
            }
            this.parameterFileType = parameterFileType
            this.flagPerLine = flagPerLine
            return this
        }

        init {
            this.mutability = if (mutability != null) mutability else net.starlark.java.eval.Mutability.IMMUTABLE
            this.commandLine =
                com.google.devtools.build.lib.analysis.starlark.StarlarkCustomCommandLine.Builder(starlarkSemantics)
        }

        @Throws(java.lang.InterruptedException::class)
        override fun build(mainRepoMappingSupplier: InterruptibleSupplier<com.google.devtools.build.lib.cmdline.RepositoryMapping?>): CommandLine? {
            return commandLine.build(
                flagPerLine, if (mayStringifyExternalLabel) mainRepoMappingSupplier.get() else null
            )
        }

        override fun mutability(): net.starlark.java.eval.Mutability? {
            return mutability
        }

        override fun getDirectoryArtifacts(): com.google.common.collect.ImmutableSet<Artifact?> {
            for (collection in potentialDirectoryArtifacts) {
                for (`object` in collection.toList()) {
                    if (isDirectory(`object`)) {
                        directoryArtifacts.add(`object` as Artifact)
                    }
                }
            }
            potentialDirectoryArtifacts.clear()
            return com.google.common.collect.ImmutableSet.copyOf<Artifact?>(directoryArtifacts)
        }

        companion object {
            @Throws(net.starlark.java.eval.EvalException::class)
            private fun validateMapEach(fn: Any?, allowClosure: Boolean): net.starlark.java.eval.StarlarkCallable? {
                if (fn === net.starlark.java.eval.Starlark.NONE) {
                    return null
                }
                if (fn is net.starlark.java.eval.StarlarkFunction) {
                    // Reject non-global functions, because arbitrary closures may cause large
                    // analysis-phase data structures to remain live into the execution phase.
                    // We require that the function is "global" as opposed to "not a closure"
                    // because a global function may be closure if it refers to load bindings.
                    // This unfortunately disallows such trivially safe non-global
                    // functions as "lambda x: x".
                    // See https://github.com/bazelbuild/bazel/issues/12701.
                    if (!(fn.isGlobal() || allowClosure)) {
                        throw net.starlark.java.eval.Starlark.errorf(
                            ("to avoid unintended retention of analysis data structures, "
                                    + "the map_each function (declared at %s) must be declared "
                                    + "by a top-level def statement"),
                            fn.getLocation()
                        )
                    }
                }
                return fn as net.starlark.java.eval.StarlarkCallable?
            }

            @Throws(net.starlark.java.eval.EvalException::class)
            private fun validateArgName(argName: Any) {
                if (argName !is String) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "expected value of type 'string' for arg name, got '%s'",
                        net.starlark.java.eval.Starlark.type(argName)
                    )
                }
            }

            @Throws(net.starlark.java.eval.EvalException::class)
            private fun validateValues(values: Any) {
                if (!(values is net.starlark.java.eval.Sequence<*> || values is Depset)) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "expected value of type 'sequence or depset' for values, got '%s'",
                        net.starlark.java.eval.Starlark.type(values)
                    )
                }
            }

            @Throws(net.starlark.java.eval.EvalException::class)
            private fun validateFormatString(argumentName: String?, formatStr: String?) {
                if (formatStr != null && !SingleStringArgFormatter.isValid(formatStr)) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "Invalid value for parameter \"%s\": Expected string with a single \"%%s\"",
                        argumentName
                    )
                }
            }

            @Throws(net.starlark.java.eval.EvalException::class)
            private fun validateNoDirectory(value: Any) {
                if (isDirectory(value)) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        ("Cannot add directories to Args#add since they may expand to multiple values. "
                                + "Either use Args#add_all (if you want expansion) "
                                + "or args.add(directory.path) (if you do not).")
                    )
                }
            }

            private fun isDirectory(`object`: Any): Boolean {
                return ((`object` is Artifact) && (`object` as Artifact).isDirectory())
            }
        }
    }

    companion object {
        /**
         * Returns a frozen [Args] representation corresponding to an already-registered action.
         * 
         * @param commandLineAndParamFileInfo the command line / ParamFileInfo pair that this Args should
         * represent
         * @param directoryInputs a set containing all directory artifacts of the action; [     ][Artifact.isDirectory] must be true for each artifact in the set
         */
        fun forRegisteredAction(
            commandLineAndParamFileInfo: CommandLineAndParamFileInfo,
            directoryInputs: com.google.common.collect.ImmutableSet<Artifact?>?
        ): Args {
            return FrozenArgs(
                commandLineAndParamFileInfo.commandLine,
                commandLineAndParamFileInfo.paramFileInfo,
                directoryInputs
            )
        }

        /** Creates and returns a new (empty) [Args] object.  */
        fun newArgs(
            mutability: net.starlark.java.eval.Mutability?,
            starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?
        ): Args {
            return MutableArgs(mutability, starlarkSemantics)
        }
    }
}
