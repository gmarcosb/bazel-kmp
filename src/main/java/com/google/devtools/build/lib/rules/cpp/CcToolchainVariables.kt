// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.Artifact

/**
 * Configured build variables usable by the toolchain configuration.
 * 
 * 
 * TODO(b/32655571): Investigate cleanup once implicit iteration is not needed. Variables
 * instance could serve as a top level View used to expand all flag_groups.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
abstract class CcToolchainVariables : CcToolchainVariablesApi {
    /**
     * A piece of a single string value.
     * 
     * 
     * A single value can contain a combination of text and variables (for example "-f
     * %{var1}/%{var2}"). We split the string into chunks, where each chunk represents either a text
     * snippet, or a variable that is to be replaced.
     */
    internal interface StringChunk {
        /**
         * Expands this chunk.
         * 
         * @param variables binding of variable names to their values for a single flag expansion.
         */
        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        fun expand(variables: CcToolchainVariables?, pathMapper: PathMapper?): String?
    }

    /** A plain text chunk of a string (containing no variables).  */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @AutoCodec
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    @kotlin.jvm.JvmRecord
    internal data class StringLiteralChunk(val text: String?) : StringChunk {
        override fun expand(variables: CcToolchainVariables?, pathMapper: PathMapper?): String? {
            return text
        }
    }

    /** A chunk of a string value into which a variable should be expanded.  */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @AutoCodec
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    @kotlin.jvm.JvmRecord
    internal data class VariableChunk(val variableName: String?) : StringChunk {
        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        override fun expand(variables: CcToolchainVariables, pathMapper: PathMapper?): String? {
            // We check all variables in FlagGroup.expandCommandLine.
            // If we arrive here with the variable not being available, the variable was provided, but
            // the nesting level of the NestedSequence was deeper than the nesting level of the flag
            // groups.
            return variables.getStringVariable(variableName!!, pathMapper)
        }
    }

    /** A chunk of an exec path that can be mapped upon expansion.  */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @AutoCodec
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    internal class RelativePathChunk(execPath: PathFragment?) : StringChunk {
        override fun expand(variables: CcToolchainVariables?, pathMapper: PathMapper): String {
            return pathMapper.map(execPath).getPathString()
        }

        val execPath: PathFragment?

        init {
            this.execPath = execPath
            com.google.common.base.Preconditions.checkArgument(
                !execPath.isAbsolute(),
                "execPath is not relative: %s",
                execPath
            )
        }
    }

    /**
     * Parser for toolchain string values.
     * 
     * 
     * A string value contains a snippet of text supporting variable expansion. For example, a
     * string value "-f %{var1}/%{var2}" will expand the values of the variables "var1" and "var2" in
     * the corresponding places in the string.
     * 
     * 
     * The `StringValueParser` takes a string and parses it into a list of [ ] objects, where each chunk represents either a snippet of text or a variable to be
     * expanded. In the above example, the resulting chunks would be ["-f ", var1, "/", var2].
     * 
     * 
     * To get a literal percent character, "%%" can be used in the string.
     */
    class StringValueParser(private val value: String) {
        /** The current position in {@value} during parsing.  */
        private var current = 0

        private val chunks: com.google.common.collect.ImmutableList.Builder<StringChunk?> =
            com.google.common.collect.ImmutableList.builder<StringChunk?>()
        private val usedVariables: com.google.common.collect.ImmutableSet.Builder<String?> =
            com.google.common.collect.ImmutableSet.builder<String?>()

        init {
            parse()
        }

        /** Returns the parsed chunks for this string.  */
        fun getChunks(): com.google.common.collect.ImmutableList<StringChunk?> {
            return chunks.build()
        }

        /**
         * Parses the string.
         * 
         * @throws EvalException if there is a parsing error.
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        private fun parse() {
            while (current < value.length()) {
                if (atVariableStart()) {
                    parseVariableChunk()
                } else {
                    parseStringChunk()
                }
            }
        }

        /**
         * @return whether the current position is the start of a variable.
         */
        private fun atVariableStart(): Boolean {
            // We parse a variable when value starts with '%', but not '%%'.
            return value.charAt(current) == '%'
                    && (current + 1 >= value.length() || value.charAt(current + 1) != '%')
        }

        /**
         * Parses a chunk of text until the next '%', which indicates either an escaped literal '%' or a
         * variable.
         */
        private fun parseStringChunk() {
            var start = current
            // We only parse string chunks starting with '%' if they also start with '%%'.
            // In that case, we want to have a single '%' in the string, so we start at the second
            // character.
            // Note that for strings like "abc%%def" this will lead to two string chunks, the first
            // referencing the subtring "abc", and a second referencing the substring "%def".
            if (value.charAt(current) == '%') {
                current = current + 1
                start = current
            }
            current = value.indexOf('%'.code, current + 1)
            if (current == -1) {
                current = value.length()
            }
            val text: String = value.substring(start, current)
            chunks.add(StringLiteralChunk(text))
        }

        /**
         * Parses a variable to be expanded.
         * 
         * @throws EvalException if there is a parsing error.
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        private fun parseVariableChunk() {
            current = current + 1
            if (current >= value.length() || value.charAt(current) != '{') {
                abort("expected '{'")
            }
            current = current + 1
            if (current >= value.length() || value.charAt(current) == '}') {
                abort("expected variable name")
            }
            val end: Int = value.indexOf('}'.code, current)
            val name: String = value.substring(current, end)
            if (name.startsWith(PATH_PREFIX)) {
                val path: String = name.substring(PATH_PREFIX.length())
                if (path.isEmpty()) {
                    abort("expected path after 'path:'")
                }
                // The provided path is expected to be an exec path, which always uses '/' as a separator
                // and is relative. Ensure that it is parsed consistently.
                val pathFragment: PathFragment =
                    PathFragment.createForOs(path, com.google.devtools.build.lib.util.OS.LINUX)
                if (pathFragment.isAbsolute()) {
                    abort("expected relative Unix-style path after 'path:'")
                }
                chunks.add(RelativePathChunk(pathFragment))
            } else {
                usedVariables.add(name)
                chunks.add(VariableChunk(name))
            }
            current = end + 1
        }

        /**
         * @throws EvalException with the given error text, adding information about the current
         * position in the string.
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        private fun abort(error: String?) {
            throw net.starlark.java.eval.Starlark.errorf(
                "Invalid toolchain configuration: %s at position %s while parsing a flag containing '%s'",
                error, current, value
            )
        }

        companion object {
            private const val PATH_PREFIX = "path:"
        }
    }

    /** A flag or flag group that can be expanded under a set of variables.  */
    interface Expandable {
        /**
         * Expands the current expandable under the given `view`, adding new flags to `commandLine`.
         * 
         * 
         * The `variables` controls which variables are visible during the expansion and allows
         * to recursively expand nested flag groups.
         */
        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        fun expand(
            variables: CcToolchainVariables?,
            inputMetadataProvider: InputMetadataProvider?,
            pathMapper: PathMapper?,
            commandLine: MutableList<String?>?
        )
    }

    /**
     * Avoids cyclic class initialization issues with [MapVariables].
     * 
     * 
     * Without this holder, there would be a cycle here. [MapVariables] depends on its parent
     * class [CcToolchainVariables] and [CcToolchainVariables] would depend on [ ] via [.EMPTY].
     * 
     * 
     * See [Initialization on
     * demand idiom](https://en.wikipedia.org/wiki/Initialization-on-demand_holder_idiom).
     */
    private object EmptyVariablesHolder {
        private val EMPTY = builder().build()
    }

    // Values in this cache are either VariableValue, String error message, or NULL_MARKER.
    //
    // It is initialized lazily.
    @kotlin.concurrent.Volatile
    @Transient
    private var structuredVariableCache: MutableMap<String?, Any?>? = null

    /**
     * Gets a variable value named `name`. Supports accessing fields in structures (e.g.
     * 'libraries_to_link.interface_libraries')
     * 
     * @throws ExpansionException when no such variable or no such field are present, or when
     * accessing a field of non-structured variable
     */
    @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
    fun getVariable(name: String, pathMapper: PathMapper?): VariableValue? {
        return lookupVariable(
            name,  /* throwOnMissingVariable= */true,  /* inputMetadataProvider= */null, pathMapper
        )
    }

    @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
    fun getVariable(
        name: String, inputMetadataProvider: InputMetadataProvider?, pathMapper: PathMapper?
    ): VariableValue? {
        return lookupVariable(
            name,  /* throwOnMissingVariable= */true, inputMetadataProvider, pathMapper
        )
    }

    /**
     * Looks up a variable named `name` or return a reason why the variable was not found.
     * Supports accessing fields in structures.
     */
    @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
    private fun lookupVariable(
        name: String,
        throwOnMissingVariable: Boolean,
        inputMetadataProvider: InputMetadataProvider?,
        pathMapper: PathMapper?
    ): VariableValue? {
        val `var` = getNonStructuredVariable(name)
        if (`var` != null) {
            return `var`
        }

        if (!name.contains(".")) {
            if (throwOnMissingVariable) {
                throw com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException(
                    java.lang.String.format(
                        "Invalid toolchain configuration: Cannot find variable named '%s'.", name
                    )
                )
            }
            return null
        }

        if (structuredVariableCache == null) {
            synchronized(this) {
                if (structuredVariableCache == null) {
                    structuredVariableCache = ConcurrentHashMap<String?, Any?>()
                }
            }
        }

        var variableOrError = structuredVariableCache!!.get(name)
        if (variableOrError == null) {
            try {
                val variable =
                    getStructureVariable(name, throwOnMissingVariable, inputMetadataProvider, pathMapper)
                variableOrError = if (variable != null) variable else NULL_MARKER
            } catch (e: com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException) {
                if (throwOnMissingVariable) {
                    variableOrError = e.getMessage()
                } else {
                    throw java.lang.IllegalStateException(
                        "Should not happen - call to getStructuredVariable threw when asked not to.", e
                    )
                }
            }
            structuredVariableCache.putIfAbsent(name, variableOrError)
        }

        if (variableOrError is VariableValue) {
            return variableOrError
        }
        if (throwOnMissingVariable) {
            throw com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException(
                if (variableOrError is String)
                    variableOrError
                else
                    java.lang.String.format(
                        "Invalid toolchain configuration: Cannot find variable named '%s'.", name
                    )
            )
        }
        return null
    }

    @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
    private fun getStructureVariable(
        name: String,
        throwOnMissingVariable: Boolean,
        inputMetadataProvider: InputMetadataProvider?,
        pathMapper: PathMapper?
    ): VariableValue? {
        if (!name.contains(".")) {
            return null
        }

        val fieldsToAccess: java.util.Stack<String?> = java.util.Stack<String?>()
        var structPath = name
        var variable: VariableValue?

        do {
            fieldsToAccess.push(structPath.substring(structPath.lastIndexOf('.'.code) + 1))
            structPath = structPath.substring(0, structPath.lastIndexOf('.'.code))
            variable = getNonStructuredVariable(structPath)
        } while (variable == null && structPath.contains("."))

        if (variable == null) {
            return null
        }

        while (!fieldsToAccess.empty()) {
            val field: String? = fieldsToAccess.pop()
            variable =
                variable!!.getFieldValue(
                    structPath, field, inputMetadataProvider, pathMapper, throwOnMissingVariable
                )
            if (variable == null) {
                if (throwOnMissingVariable) {
                    throw com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException(
                        java.lang.String.format(
                            "Invalid toolchain configuration: Cannot expand variable '%s.%s': structure %s "
                                    + "doesn't have a field named '%s'",
                            structPath, field, structPath, field
                        )
                    )
                } else {
                    return null
                }
            }
        }
        return variable
    }

    @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
    fun getStringVariable(variableName: String, pathMapper: PathMapper?): String? {
        return getVariable(variableName,  /* inputMetadataProvider= */null, pathMapper)!!
            .getStringValue(variableName, pathMapper)
    }

    /** Returns whether `variable` is set.  */
    fun isAvailable(variable: String): Boolean {
        return isAvailable(variable,  /* inputMetadataProvider= */null)
    }

    fun isAvailable(variable: String, inputMetadataProvider: InputMetadataProvider?): Boolean {
        try {
            // Availability doesn't depend on the path mapper.
            return (lookupVariable(
                variable,  /* throwOnMissingVariable= */false, inputMetadataProvider, PathMapper.NOOP
            )
                    != null)
        } catch (e: com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException) {
            throw java.lang.IllegalStateException(
                "Should not happen - call to lookupVariable threw when asked not to.", e
            )
        }
    }

    abstract val variableKeys: MutableSet<String?>?

    abstract fun addVariablesToMap(variablesMap: MutableMap<String?, Any>?)

    abstract fun getNonStructuredVariable(name: String?): VariableValue?

    /**
     * Value of a build variable exposed to the CROSSTOOL used for flag expansion.
     * 
     * 
     * [VariableValue] represent either primitive values or an arbitrarily deeply nested
     * recursive structures or sequences. Since there are builds with millions of values, some
     * implementations might exist only to optimize memory usage.
     * 
     * 
     * Implementations must be immutable and without any side-effects. They will be expanded and
     * queried multiple times.
     */
    internal interface VariableValue {
        /** Returns human-readable variable type name to be used in error messages.  */
        val variableTypeName: String?

        /**
         * Returns string value of the variable, if the variable type can be converted to string (e.g.
         * StringValue), or throw exception if it cannot (e.g. Sequence).
         * 
         * @param variableName name of the variable value at hand, for better exception message.
         */
        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        fun getStringValue(variableName: String?, pathMapper: PathMapper?): String?

        /**
         * Returns value of the field, if the variable is of struct type or throw exception if it is not
         * or no such field exists.
         * 
         * @param variableName name of the variable value at hand, for better exception message.
         */
        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        fun getFieldValue(
            variableName: String?,
            field: String?,
            inputMetadataProvider: InputMetadataProvider?,
            pathMapper: PathMapper?,
            throwOnMissingVariable: Boolean
        ): VariableValue?

        @com.google.common.annotations.VisibleForTesting
        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        fun getFieldValue(variableName: String?, field: String?): VariableValue? {
            return getFieldValue(
                variableName,
                field,  /* inputMetadataProvider= */
                null,
                PathMapper.NOOP,  /* throwOnMissingVariable= */
                true
            )
        }

        /** Returns true if the variable is truthy  */
        @kotlin.jvm.JvmField
        val isTruthy: Boolean
    }

    /**
     * Adapter for [VariableValue] predefining error handling methods. Override [ ][.getVariableTypeName], [.isTruthy], and one of [.getFieldValue], or [VariableValue.getStringValue], and you'll get error
     * handling for the other methods for free.
     */
    internal interface VariableValueAdapter : VariableValue {
        override fun isTruthy(): Boolean

        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        override fun getFieldValue(
            variableName: String?,
            field: String?,
            inputMetadataProvider: InputMetadataProvider?,
            pathMapper: PathMapper?,
            throwOnMissingVariable: Boolean
        ): VariableValue? {
            if (throwOnMissingVariable) {
                throw com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException(
                    java.lang.String.format(
                        "Invalid toolchain configuration: Cannot expand variable '%s.%s': variable '%s' is "
                                + "%s, expected structure",
                        variableName, field, variableName, this.variableTypeName
                    )
                )
            } else {
                return null
            }
        }

        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        override fun getStringValue(variableName: String?, pathMapper: PathMapper?): String? {
            throw com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException(
                java.lang.String.format(
                    "Invalid toolchain configuration: Cannot expand variable '%s': expected string, "
                            + "found %s",
                    variableName, this.variableTypeName
                )
            )
        }
    }

    /** Sequence of arbitrary VariableValue objects.  */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @AutoCodec
    internal class Sequence(values: com.google.common.collect.ImmutableList<*>?) : VariableValueAdapter {
        val sequenceValue: Iterable<out VariableValue>
            get() = com.google.common.collect.Iterables.transform(
                values,
                { o: Any -> asVariableValue(o) })

        override fun getVariableTypeName(): String {
            return com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.Sequence.Companion.SEQUENCE_VARIABLE_TYPE_NAME
        }

        override fun isTruthy(): Boolean {
            return !values.isEmpty()
        }

        val values: com.google.common.collect.ImmutableList<*>?

        init {
            this.values = values
        }

        companion object {
            private const val SEQUENCE_VARIABLE_TYPE_NAME = "sequence"
        }
    }

    /**
     * Most leaves in the variable sequence node tree are simple string values. Note that this should
     * never live outside of `expand`, as the object overhead is prohibitively expensive.
     */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @AutoCodec
    internal class StringValue(value: String?) : VariableValueAdapter {
        override fun getStringValue(variableName: String?, pathMapper: PathMapper): String {
            return pathMapper.mapHeuristically(value)
        }

        override fun getVariableTypeName(): String {
            return com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.StringValue.Companion.STRING_VARIABLE_TYPE_NAME
        }

        override fun isTruthy(): Boolean {
            return !value.isEmpty()
        }

        val value: String?

        init {
            this.value =
                com.google.common.base.Preconditions.checkNotNull<String?>(value, "Cannot create StringValue from null")
        }

        companion object {
            private const val STRING_VARIABLE_TYPE_NAME = "string"
        }
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @AutoCodec
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    @kotlin.jvm.JvmRecord
    internal data class BooleanValue(val value: Boolean) : VariableValueAdapter {
        override fun getStringValue(variableName: String?, pathMapper: PathMapper?): String {
            return if (value) "1" else "0"
        }

        override fun getVariableTypeName(): String {
            return "boolean"
        }

        override fun isTruthy(): Boolean {
            return value
        }

        companion object {
            private val TRUE: BooleanValue =
                com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.BooleanValue(true)
            private val FALSE: BooleanValue =
                com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.BooleanValue(false)

            private fun of(value: Boolean): BooleanValue? {
                return if (value) com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.BooleanValue.Companion.TRUE else com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.BooleanValue.Companion.FALSE
            }
        }
    }

    /**
     * Represents leaves in the variable sequence node tree that are paths of artifacts. Note that
     * this should never live outside of `expand`, as the object overhead is prohibitively
     * expensive.
     */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @AutoCodec
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    internal class ArtifactValue(value: Artifact?) : VariableValueAdapter {
        override fun getStringValue(variableName: String?, pathMapper: PathMapper): String {
            return pathMapper.getMappedExecPathString(value)
        }

        override fun getVariableTypeName(): String {
            return ARTIFACT_VARIABLE_TYPE_NAME
        }

        override fun isTruthy(): Boolean {
            return true
        }

        val value: Artifact?

        init {
            this.value = value
        }

        companion object {
            private const val ARTIFACT_VARIABLE_TYPE_NAME = "artifact"
        }
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @AutoCodec
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    internal class PathFragmentValue(value: PathFragment?) : VariableValueAdapter {
        override fun getStringValue(variableName: String?, pathMapper: PathMapper): String {
            return pathMapper.map(value).getSafePathString()
        }

        override fun getVariableTypeName(): String {
            return PATH_FRAGMENT_VARIABLE_TYPE_NAME
        }

        override fun isTruthy(): Boolean {
            return true
        }

        val value: PathFragment?

        init {
            this.value = value
        }

        companion object {
            private const val PATH_FRAGMENT_VARIABLE_TYPE_NAME = "pathfragment"
        }
    }

    /** Builder for `Variables`.  */ // TODO(b/65472725): Forbid sequences with empty string in them.
    class Builder // private to avoid class initialization deadlock between this class and its outer class
    private constructor(private val parent: CcToolchainVariables?) {
        private val variablesMap: MutableMap<String?, Any> = LinkedHashMap<String?, Any>()

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun overrideVariable(name: String?, value: Artifact?): Builder {
            com.google.common.base.Preconditions.checkNotNull<Any?>(
                value,
                "Cannot set null as a value for variable '%s'",
                name
            )
            variablesMap.put(name, value)
            return this
        }

        /**
         * Add a sequence variable that expands `name` to `values`.
         * 
         * 
         * Accepts values as ImmutableSet. As ImmutableList has smaller memory footprint, we copy the
         * values into a new list.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addStringSequenceVariable(
            name: String?,
            values: com.google.common.collect.ImmutableSet<String?>?
        ): Builder {
            checkVariableNotPresentAlready(name)
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableSet<String?>?>(
                values,
                "Cannot set null as a value for variable '%s'",
                name
            )
            val builder: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            builder.addAll(values)
            variablesMap.put(
                name,
                com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.Builder.Companion.stringSequenceInterner.intern(
                    builder.build()
                )
            )
            return this
        }

        /**
         * Add a sequence variable that expands `name` to `values`.
         * 
         * 
         * Accepts values as Iterable. The iterable is stored directly, not cloned, not iterated. Be
         * mindful of memory consumption of the particular Iterable. Prefer ImmutableList, or be sure
         * that the iterable always returns the same elements in the same order, without any side
         * effects.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addStringSequenceVariable(name: String?, values: Iterable<String?>?): Builder {
            checkVariableNotPresentAlready(name)
            com.google.common.base.Preconditions.checkNotNull<Iterable<String?>?>(
                values,
                "Cannot set null as a value for variable '%s'",
                name
            )
            variablesMap.put(
                name,
                com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.Builder.Companion.stringSequenceInterner.intern(
                    com.google.common.collect.ImmutableList.copyOf<String?>(values)
                )
            )
            return this
        }

        /** Adds a variable that expands `name` to the `value`.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @com.google.common.annotations.VisibleForTesting
        fun addVariable(name: String?, value: Any?): Builder {
            checkVariableNotPresentAlready(name)
            com.google.common.base.Preconditions.checkNotNull<Any?>(
                value,
                "Cannot use null value for variable '%s'",
                name
            )
            variablesMap.put(name, value!!)
            return this
        }

        /** Add all string variables in a map.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addAllStringVariables(variables: MutableMap<String?, String?>): Builder {
            for (name in variables.keySet()) {
                checkVariableNotPresentAlready(name)
            }
            variablesMap.putAll(variables)
            return this
        }

        private fun checkVariableNotPresentAlready(name: String?) {
            com.google.common.base.Preconditions.checkNotNull<String?>(name)
            com.google.common.base.Preconditions.checkArgument(
                !variablesMap.containsKey(name), "Cannot overwrite variable '%s'", name
            )
        }

        /**
         * Adds all variables to this builder. Cannot override already added variables. Does not add
         * variables defined in the `parent` variables.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addAllNonTransitive(variables: CcToolchainVariables): Builder {
            val intersection: com.google.common.collect.Sets.SetView<String?> =
                com.google.common.collect.Sets.intersection<String?>(variables.variableKeys, variablesMap.keySet())
            com.google.common.base.Preconditions.checkArgument(
                intersection.isEmpty(), "Cannot overwrite existing variables: %s", intersection
            )
            variables.addVariablesToMap(variablesMap)
            return this
        }

        /**
         * @return a new [CcToolchainVariables] object.
         */
        fun build(): CcToolchainVariables {
            if (variablesMap.size() == 1) {
                return SingleVariables(
                    parent,
                    variablesMap.keySet().iterator().next(),
                    variablesMap.values().iterator().next()
                )
            }
            return MapVariables(parent, variablesMap)
        }

        companion object {
            private val stringSequenceInterner: com.google.common.collect.Interner<com.google.common.collect.ImmutableList<String?>?> =
                BlazeInterners.newWeakInterner<com.google.common.collect.ImmutableList<String?>?>()
        }
    }

    /**
     * Adapts Starlark structures.
     * 
     * 
     * It's used to support NamedLibraryInfo, ObjectFileGroupInfo and VersionedLibraryInfo
     * structures create in `create_libraries_to_link_values.bzl`
     */
    class StarlarkStructureAdapter internal constructor(`val`: net.starlark.java.eval.Structure) :
        VariableValueAdapter {
        private val `val`: net.starlark.java.eval.Structure

        init {
            this.`val` = `val`
        }

        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        override fun getFieldValue(
            variableName: String?,
            field: String?,
            inputMetadataProvider: InputMetadataProvider?,
            pathMapper: PathMapper,
            throwOnMissingVariable: Boolean
        ): VariableValue? {
            try {
                val fieldValue: Any? = `val`.getValue(field)

                // Special handling for tree artifacts. Needed for ObjectFileGroupInfo containing a tree
                // artifact. When this code is migrated to Starlark, the expansion should happen on Starlark
                // command lines.
                if (fieldValue is Iterable<*>) {
                    val expandedIterable: com.google.common.collect.ImmutableList.Builder<Any?> =
                        com.google.common.collect.ImmutableList.builder<Any?>()
                    for (element in fieldValue) {
                        if (element is Artifact
                            && element.isTreeArtifact()
                            && inputMetadataProvider != null
                        ) {
                            val treeArtifactValue: TreeArtifactValue? = inputMetadataProvider.getTreeMetadata(element)
                            if (treeArtifactValue != null) {
                                expandedIterable.addAll(
                                    com.google.common.collect.Collections2.transform<TreeFileArtifact?, Any?>(
                                        treeArtifactValue.getChildren(), pathMapper::getMappedExecPathString
                                    )
                                )
                            }
                        } else {
                            expandedIterable.add(element)
                        }
                    }
                    return com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.Sequence(expandedIterable.build())
                }
                return Companion.asVariableValue(fieldValue!!)
            } catch (e: net.starlark.java.eval.EvalException) {
                if (throwOnMissingVariable) {
                    throw com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException(
                        java.lang.String.format(
                            "Invalid toolchain configuration: Cannot expand variable '%s.%s': variable '%s'"
                                    + " is %s, expected structure",
                            variableName, field, variableName, getVariableTypeName()
                        ),
                        e
                    )
                } else {
                    return null
                }
            }
        }

        override fun getVariableTypeName(): String? {
            return net.starlark.java.eval.Starlark.type(`val`)
        }

        override fun isTruthy(): Boolean {
            return `val`.truth()
        }
    }

    internal class MapVariables(private val parent: CcToolchainVariables?, variablesMap: MutableMap<String?, Any>) :
        CcToolchainVariables() {
        /**
         * This is a slightly interesting data structure that's necessary to optimize for memory
         * consumption. The premise is that a lot of compilations use the exact same variable keys, just
         * with different values. Thus, it is important to store the keys separately so that they can be
         * interned while storing the values in a compact way. keyToIndex maps from a variable name to
         * the index of the corresponding value in values.
         */
        private val keyToIndex: com.google.common.collect.ImmutableMap<String?, Int?>

        /** The values belonging to the keys stored in keyToIndex.  */
        private val values: com.google.common.collect.ImmutableList<Any?>

        init {
            val keyBuilder: com.google.common.collect.ImmutableMap.Builder<String?, Int?> =
                com.google.common.collect.ImmutableMap.builder<String?, Int?>()
            val valuesBuilder: com.google.common.collect.ImmutableList.Builder<Any?> =
                com.google.common.collect.ImmutableList.builder<Any?>()
            var index = 0
            for (key in com.google.common.collect.ImmutableList.sortedCopyOf<String?>(variablesMap.keySet())) {
                keyBuilder.put(key, index++)
                var value: Any = variablesMap.get(key)!!
                if (value is Depset) { // Unwrap Depsets; needed to prevent memory regression
                    value = value.getSet()
                }
                valuesBuilder.add(value)
            }
            this.keyToIndex = keyInterner.intern(keyBuilder.buildOrThrow())
            this.values = valuesBuilder.build()
        }

        val isImmutable: Boolean
            get() = true // immutable and Starlark-hashable

        override fun getVariableKeys(): com.google.common.collect.ImmutableSet<String?> {
            return keyToIndex.keySet()
        }

        override fun addVariablesToMap(variablesMap: MutableMap<String?, Any?>) {
            for (entry in keyToIndex.entrySet()) {
                variablesMap.put(entry.getKey(), values.get(entry.getValue()))
            }
        }

        override fun getNonStructuredVariable(name: String?): VariableValue? {
            if (keyToIndex.containsKey(name)) {
                return asVariableValue(values.get(keyToIndex.get(name)))
            }

            if (parent != null) {
                return parent.getNonStructuredVariable(name)
            }

            return null
        }

        /**
         * NB: this compares parents using reference equality instead of logical equality.
         * 
         * 
         * This is a performance optimization to avoid possibly expensive recursive equality
         * expansions and suitable for comparisons needed by interning deserialized values. If full
         * logical equality is desired, it's possible to either enable full interning (at a modest CPU
         * cost) or change the parent comparison to use deep equality.
         * 
         * 
         * This same comment applies to [SingleVariables.equals].
         */
        override fun equals(other: Any?): Boolean {
            if (other !is MapVariables) {
                return false
            }
            if (this === other) {
                return true
            }
            if (this.parent !== other.parent) {
                return false
            }
            return this.keyToIndex == other.keyToIndex
                    && this.values == other.values
        }

        override fun hashCode(): Int {
            return 31 * java.util.Objects.hash(keyToIndex, values) + java.lang.System.identityHashCode(parent)
        }

        companion object {
            private val keyInterner: com.google.common.collect.Interner<com.google.common.collect.ImmutableMap<String?, Int?>> =
                BlazeInterners.newWeakInterner<com.google.common.collect.ImmutableMap<String?, Int?>?>()
        }
    }

    internal class SingleVariables(
        private val parent: CcToolchainVariables?,
        private val name: String,
        private val variableValue: Any
    ) : CcToolchainVariables() {
        override fun getVariableKeys(): com.google.common.collect.ImmutableSet<String?> {
            return com.google.common.collect.ImmutableSet.of<String?>(name)
        }

        override fun addVariablesToMap(variablesMap: MutableMap<String?, Any?>) {
            variablesMap.put(name, variableValue)
        }

        override fun getNonStructuredVariable(name: String?): VariableValue? {
            if (this.name == name) {
                return asVariableValue(variableValue)
            }
            return if (parent == null) null else parent.getNonStructuredVariable(name)
        }

        override fun equals(other: Any?): Boolean {
            if (other !is SingleVariables) {
                return false
            }
            if (this === other) {
                return true
            }
            if (this.parent !== other.parent) {
                return false
            }
            return this.name == other.name
                    && this.variableValue == other.variableValue
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(parent, name, variableValue)
        }
    }

    companion object {
        /** Returns an empty variables instance.  */
        @kotlin.jvm.JvmStatic
        fun empty(): CcToolchainVariables {
            return EmptyVariablesHolder.EMPTY
        }

        private val NULL_MARKER = Any()

        /**
         * Retrieves a string sequence variable named `variableName` from `variables` and
         * converts it into a list of plain strings.
         * 
         * 
         * Throws [ExpansionException] when the variable is not a string sequence.
         */
        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        fun toStringList(
            variables: CcToolchainVariables, variableName: String, pathMapper: PathMapper?
        ): com.google.common.collect.ImmutableList<String?> {
            val result: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            for (value in Companion.getSequenceValue(variableName, variables.getVariable(variableName, pathMapper)!!)) {
                result.add(value.getStringValue(variableName, pathMapper))
            }
            return result.build()
        }

        /**
         * Returns Iterable value of the variable, if the variable type can be converted to a Iterable
         * (e.g. Sequence), or throw exception if it cannot (e.g. StringValue).
         * 
         * @param variableName name of the variable value at hand, for better exception message.
         */
        @kotlin.jvm.JvmStatic
        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        fun getSequenceValue(
            variableName: String?, value: VariableValue
        ): Iterable<out VariableValue> {
            if (value is Sequence) {
                return value.sequenceValue
            }
            throw com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException(
                java.lang.String.format(
                    "Invalid toolchain configuration: Cannot expand variable '%s': expected sequence, "
                            + "found %s",
                    variableName, value.variableTypeName
                )
            )
        }

        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.Builder(null)
        }

        fun builder(parent: CcToolchainVariables?): Builder {
            return com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.Builder(parent)
        }

        /** Wraps a raw variablesMap value into an appropriate VariableValue if necessary.  */
        private fun asVariableValue(o: Any): VariableValue? {
            return when (o) {
                -> null
                -> com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.BooleanValue.Companion.of(b)
                -> com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.StringValue(s)
                -> ArtifactValue(artifact)
                -> PathFragmentValue(pathFragment)
                -> com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.Sequence(
                    com.google.common.collect.ImmutableList.copyOf(
                        iterable
                    )
                )

                -> com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.Sequence(nestedSet.toList())
                -> com.google.devtools.build.lib.rules.cpp.CcToolchainVariables.Sequence(depset.toList())
                -> StarlarkStructureAdapter(val
                    )
                else -> o as VariableValue
            }
        }
    }
}
