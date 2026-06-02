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
package net.starlark.java.eval

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import java.util.HashSet
import java.util.LinkedHashMap

/**
 * A [Module] represents a Starlark module, a container of global variables populated by
 * executing a Starlark file. Each top-level assignment updates a global variable in the module.
 * 
 * 
 * Each module references its "predeclared" environment, which is often shared among many
 * modules. These are the names that are defined even at the start of execution. For example, in
 * Bazel, the predeclared environment of the module for a BUILD or .bzl file defines name values
 * such as cc_binary and glob.
 * 
 * 
 * The predeclared environment implicitly includes the "universal" names present in every
 * Starlark thread in every dialect, such as None, len, and str; see [Starlark.UNIVERSE].
 * 
 * 
 * Global bindings in a Module may shadow bindings inherited from the predeclared block.
 * 
 * 
 * A module may carry an arbitrary piece of client data. In Bazel, for example, the client data
 * records the module's build label (such as "//dir:file.bzl"). This client data is accessible to
 * (for instance) application-defined builtin methods.
 * 
 * 
 * You may create a Module using [.create], [.withPredeclared], or [ ][.withPredeclaredAndData]. The latter two give you the ability to add predeclared bindings (beyond
 * the universal ones) and client data. The particular [StarlarkSemantics] and client data may
 * filter what predeclared bindings are available via [GuardedValue].
 */
class Module private constructor(
    predeclared: com.google.common.collect.ImmutableMap<String?, Any?>,
    clientData: Any?,
    semantics: net.starlark.java.eval.StarlarkSemantics
) : net.starlark.java.syntax.Resolver.Module, net.starlark.java.syntax.TypeTagger.LoadableModule {
    // The module's predeclared environment. Excludes UNIVERSE bindings. Values that are conditionally
    // present are stored as GuardedValues regardless of whether they are actually enabled.
    private val predeclared: com.google.common.collect.ImmutableMap<String?, Any?>

    // The module's global variables, in order of creation.
    private val globalIndex: LinkedHashMap<String?, Int?> = LinkedHashMap<String?, Int?>()
    private var globals = arrayOfNulls<Any>(8)

    // The module's exported global variables' types. Null if type checking is not enabled for this
    // module. Otherwise, has the same length and same order as {@link #globals}.  Intended for use by
    // other modules which load this.
    private var globalsTypes: Array<net.starlark.java.syntax.StarlarkType?>?

    // An optional piece of application-specific metadata associated with the module/file.
    // Its toString appears to Starlark in str(function): "<function f from ...>".
    private val clientData: Any?

    private val semantics: net.starlark.java.eval.StarlarkSemantics

    // An optional doc string for the module. Set after construction when evaluating a .bzl file.
    @kotlin.jvm.JvmField
    private var documentation: String? = null

    /**
     * Replaces an enabled [GuardedValue] with the value it guards.
     * 
     * 
     * A disabled [GuardedValue] is left in place for error reporting upon access, and should
     * be treated as unavailable.
     */
    private fun filterGuardedValue(v: Any?): Any? {
        com.google.common.base.Preconditions.checkNotNull<Any?>(v)
        if (v !is net.starlark.java.eval.GuardedValue) {
            return v
        }
        val gv: net.starlark.java.eval.GuardedValue = v as net.starlark.java.eval.GuardedValue
        return if (gv.isObjectAccessibleUsingSemantics(semantics, clientData)) gv.getObject() else gv
    }

    /** Returns the client data associated with this module.  */
    fun getClientData(): Any? {
        return clientData
    }

    /** Sets the module's doc string. It may be retrieved using [.getDocumentation].  */
    fun setDocumentation(documentation: String?) {
        this.documentation = documentation
    }

    /**
     * Returns the module's doc string, or null if absent.
     * 
     * 
     * Morally equivalent to calling `program.getResolvedFunction().getDocumentation()` when
     * the Module has a corresponding [net.starlark.java.syntax.Program]. We need to separately
     * save the doc string inside the Module because (1) a Module will usually outlive the Program;
     * and (2) there isn't always a 1-to-1 match between a Module and a Program (multiple programs may
     * be executed in the same module in REPL or in tests).
     */
    fun getDocumentation(): String? {
        return documentation
    }

    /**
     * Returns the value of a predeclared (not universal) binding in this module.
     * 
     * 
     * In the case that the predeclared is a [GuardedValue]: If it is enabled, the underlying
     * value is returned, otherwise the `GuardedValue` itself is returned for error reporting.
     */
    fun getPredeclared(name: String?): Any? {
        val value: Any? = predeclared.get(name)
        if (value == null) {
            return null
        }
        return filterGuardedValue(value)
    }

    override fun getPredeclaredSymbolType(name: String?): net.starlark.java.syntax.StarlarkType? {
        val value = getPredeclared(name)
        if (value == null || value is net.starlark.java.eval.GuardedValue) {
            return null
        }
        // TODO: #27370 - Precompute and cache predeclared types.
        return net.starlark.java.eval.Starlark.Companion.getStarlarkType(value, semantics)
    }

    override fun getUniversalSymbolType(name: String?): net.starlark.java.syntax.StarlarkType? {
        return net.starlark.java.eval.Starlark.Companion.UNIVERSAL_SYMBOL_TYPES.get(name)
    }

    /**
     * Returns this module's additional predeclared bindings. (Excludes [Starlark.UNIVERSE].)
     * 
     * 
     * The map reflects any filtering of [GuardedValue]: enabled ones are replaced by the
     * underlying values that they guard, while disabled ones are left in place for error reporting.
     */
    fun getPredeclaredBindings(): MutableMap<String?, Any?> {
        return com.google.common.collect.Maps.transformValues<String?, Any?, Any?>(
            predeclared,
            com.google.common.base.Function { v: Any? -> this.filterGuardedValue(v) })
    }

    /**
     * Returns an immutable mapping containing the global variables of this module.
     * 
     * 
     * The bindings are returned in a deterministic order (for a given sequence of initial values
     * and updates).
     */
    fun getGlobals(): com.google.common.collect.ImmutableMap<String?, Any?> {
        val n: Int = globalIndex.size()
        val m: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
            com.google.common.collect.ImmutableMap.builderWithExpectedSize<String?, Any?>(n)
        for (e in globalIndex.entrySet()) {
            val v = getGlobalByIndex(e.getValue())
            if (v != null) {
                m.put(e.getKey(), v)
            }
        }
        return m.buildOrThrow()
    }

    /** Implements the resolver's module interface.  */
    @Throws(net.starlark.java.syntax.Resolver.Module.Undefined::class)
    override fun resolve(name: String?): net.starlark.java.syntax.Resolver.Scope {
        // global?
        if (globalIndex.containsKey(name)) {
            return net.starlark.java.syntax.Resolver.Scope.GLOBAL
        }

        // predeclared?
        val v = getPredeclared(name)
        if (v != null) {
            if (v is net.starlark.java.eval.GuardedValue) {
                // Name is correctly spelled, but access is disabled by a flag or by client data.
                throw net.starlark.java.syntax.Resolver.Module.Undefined(
                    (v as net.starlark.java.eval.GuardedValue).getErrorFromAttemptingAccess(
                        name
                    )
                )
            }
            return net.starlark.java.syntax.Resolver.Scope.PREDECLARED
        }

        // universal?
        if (net.starlark.java.eval.Starlark.Companion.UNIVERSE.containsKey(name)) {
            return net.starlark.java.syntax.Resolver.Scope.UNIVERSAL
        }

        // undefined
        val candidates: MutableSet<String?> = HashSet<String?>()
        candidates.addAll(globalIndex.keySet())
        candidates.addAll(predeclared.keySet())
        candidates.addAll(net.starlark.java.eval.Starlark.Companion.UNIVERSE.keySet())
        throw net.starlark.java.syntax.Resolver.Module.Undefined(
            java.lang.String.format(
                "name '%s' is not defined",
                name
            ), candidates
        )
    }

    @Throws(net.starlark.java.syntax.Resolver.Module.Undefined::class)
    override fun getTypeConstructor(name: String?): net.starlark.java.syntax.TypeConstructor? {
        val scope: net.starlark.java.syntax.Resolver.Scope = resolve(name)
        val value: Any?
        when (scope) {
            net.starlark.java.syntax.Resolver.Scope.GLOBAL -> value = getGlobal(name)
            net.starlark.java.syntax.Resolver.Scope.PREDECLARED -> value = getPredeclared(name)
            net.starlark.java.syntax.Resolver.Scope.UNIVERSAL -> value =
                net.starlark.java.eval.Starlark.Companion.UNIVERSE.get(name)

            else -> throw java.lang.AssertionError(java.lang.String.format("Unexpected scope: %s", scope))
        }
        return if (value is net.starlark.java.syntax.TypeConstructor) value else null
    }

    private fun getMethods(clazz: java.lang.Class<*>?): com.google.common.collect.ImmutableMap<String?, net.starlark.java.eval.MethodDescriptor?>? {
        return net.starlark.java.eval.CallUtils.getBuiltinManager(semantics).getAnnotatedMethods(clazz)
    }

    override fun getStrFieldType(name: String?): net.starlark.java.syntax.StarlarkType? {
        val desc: net.starlark.java.eval.MethodDescriptor? = getMethods(String::class.java).get(name)
        return if (desc == null) null else desc.getStarlarkType()
    }

    override fun getListFieldType(name: String?): net.starlark.java.syntax.StarlarkType? {
        val desc: net.starlark.java.eval.MethodDescriptor? =
            getMethods(net.starlark.java.eval.StarlarkList::class.java).get(name)
        return if (desc == null) null else desc.getStarlarkType()
    }

    override fun getDictFieldType(name: String?): net.starlark.java.syntax.StarlarkType? {
        val desc: net.starlark.java.eval.MethodDescriptor? =
            getMethods(net.starlark.java.eval.Dict::class.java).get(name)
        return if (desc == null) null else desc.getStarlarkType()
    }

    override fun getSetFieldType(name: String?): net.starlark.java.syntax.StarlarkType? {
        val desc: net.starlark.java.eval.MethodDescriptor? =
            getMethods(net.starlark.java.eval.StarlarkSet::class.java).get(name)
        return if (desc == null) null else desc.getStarlarkType()
    }

    /**
     * Returns the value of the specified global variable, or null if not bound. Does not look in the
     * predeclared environment.
     */
    fun getGlobal(name: String?): Any? {
        val i: Int? = globalIndex.get(name)
        return if (i != null) globals[i] else null
    }

    override fun getExports(): MutableSet<String?> {
        return globalIndex.keySet()
    }

    override fun hasExport(name: String?): Boolean {
        return globalIndex.containsKey(name)
    }

    /**
     * Returns the exported Starlark type of the specified global variable; intended for use by other
     * modules that load this module (not by the evaluation of this module itself).
     * 
     * 
     * If type checking was enabled for this module, returns the variable's declared static type if
     * there is one; or the variable's value's dynamic type otherwise.
     * 
     * 
     * If type checking was not enabled for this module (or if the global variable does not exist),
     * returns null.
     */
    override fun getExportType(name: String?): net.starlark.java.syntax.StarlarkType? {
        val i: Int? = globalIndex.get(name)
        return if (i != null) getGlobalTypeByIndex(i) else null
    }

    /**
     * Sets the value of a global variable based on its index in this module ({@see
     * * getIndexOfGlobal}).
     */
    fun setGlobalByIndex(i: Int, v: Any?) {
        com.google.common.base.Preconditions.checkArgument(i < globalIndex.size())
        this.globals[i] = v
    }

    /**
     * Returns the value of a global variable based on its index in this module (see [ ][.getIndexOfGlobal].) Returns null if the variable has not been assigned a value.
     */
    fun getGlobalByIndex(i: Int): Any? {
        com.google.common.base.Preconditions.checkArgument(i < globalIndex.size())
        return this.globals[i]
    }

    /**
     * Returns the value of a global variable based on its index in this module (see [ ][.getIndexOfGlobal].) Returns null if the variable has not been assigned an exported type (in
     * particular, if type checking is not enabled).
     */
    fun getGlobalTypeByIndex(i: Int): net.starlark.java.syntax.StarlarkType? {
        com.google.common.base.Preconditions.checkArgument(i < globalIndex.size())
        return if (globalsTypes != null) globalsTypes!![i] else null
    }

    /**
     * Sets the exported type of a global variable based on its index in this module (see [ ][.getIndexOfGlobal].)
     */
    fun setGlobalTypeByIndex(i: Int, type: net.starlark.java.syntax.StarlarkType?) {
        com.google.common.base.Preconditions.checkArgument(i < globalIndex.size())
        if (globalsTypes == null) {
            globalsTypes = arrayOfNulls<net.starlark.java.syntax.StarlarkType>(globals.size)
        }
        globalsTypes!![i] = type
    }

    /**
     * Returns the index within this Module of a global variable, given its name, creating a new slot
     * for it if needed. The numbering of globals used by these functions is not the same as the
     * numbering within any compiled Program. Thus each StarlarkFunction must contain a secondary
     * index mapping Program indices (from Binding.index) to Module indices.
     */
    fun getIndexOfGlobal(name: String?): Int {
        val i: Int = globalIndex.size()
        val prev: Int? = globalIndex.putIfAbsent(name, i)
        if (prev != null) {
            return prev
        }
        if (i == globals.size) {
            // grow by doubling
            com.google.common.base.Preconditions.checkState(globalsTypes == null || globals.size == globalsTypes!!.size)
            globals = java.util.Arrays.copyOf<Any?>(globals, globals.size shl 1)
            if (globalsTypes != null) {
                globalsTypes = java.util.Arrays.copyOf<net.starlark.java.syntax.StarlarkType?>(
                    globalsTypes,
                    globalsTypes!!.size shl 1
                )
            }
        }
        return i
    }

    init {
        this.predeclared = predeclared
        this.clientData = clientData
        this.semantics = semantics
    }

    /** Returns a list of indices of a list of globals; {@see getIndexOfGlobal}.  */
    fun getIndicesOfGlobals(globals: MutableList<String?>): IntArray {
        val n: Int = globals.size()
        if (n == 0) {
            return net.starlark.java.eval.Module.Companion.EMPTY_INDICES
        }
        val array = IntArray(n)
        for (i in 0..<n) {
            array[i] = getIndexOfGlobal(globals.get(i))
        }
        return array
    }

    /**
     * Updates a global binding and (optionally) its declared type in the module environment.
     * 
     * 
     * Intended only for use by tests.
     * 
     * @param declaredType if non-null, the declared type to set for the global; ignored if null.
     */
    @com.google.common.annotations.VisibleForTesting
    fun setGlobal(name: String?, value: Any?, declaredType: net.starlark.java.syntax.StarlarkType?) {
        com.google.common.base.Preconditions.checkNotNull<Any?>(value, "Module.setGlobal(%s, null)", name)
        val index = getIndexOfGlobal(name)
        setGlobalByIndex(index, value)
        if (declaredType != null) {
            setGlobalTypeByIndex(index, declaredType)
        }
    }

    /**
     * Updates a global binding in the module environment, without altering its static type.
     * 
     * 
     * Intended only for use by tests.
     */
    @com.google.common.annotations.VisibleForTesting
    fun setGlobal(name: String?, value: Any?) {
        setGlobal(name, value, null)
    }

    override fun toString(): String {
        return java.lang.String.format("<module %s>", if (clientData == null) "?" else clientData)
    }

    companion object {
        /**
         * Constructs a Module with the specified predeclared bindings (filtered by the semantics), in *
         * addition to the standard environment, [Starlark.UNIVERSE]. No client data is set.
         */
        fun withPredeclared(
            semantics: net.starlark.java.eval.StarlarkSemantics, predeclared: MutableMap<String?, Any?>
        ): Module {
            return net.starlark.java.eval.Module.Companion.withPredeclaredAndData(semantics, predeclared, null)
        }

        /**
         * Constructs a Module as above, but with the specified client data -- an arbitrary
         * application-specific value to be associated with this Module. Client data may also affect the
         * filtering of predeclareds alongside the semantics.
         */
        fun withPredeclaredAndData(
            semantics: net.starlark.java.eval.StarlarkSemantics,
            predeclared: MutableMap<String?, Any?>,
            clientData: Any?
        ): Module {
            return net.starlark.java.eval.Module(
                com.google.common.collect.ImmutableMap.copyOf<String?, Any?>(
                    predeclared
                ), clientData, semantics
            )
        }

        /**
         * Creates a module with no predeclared bindings other than the standard environment, [ ][Starlark.UNIVERSE], and with no client data.
         */
        @kotlin.jvm.JvmStatic
        fun create(): Module {
            return net.starlark.java.eval.Module( /* predeclared= */
                com.google.common.collect.ImmutableMap.of<String?, Any?>(),  /* clientData= */
                null,
                net.starlark.java.eval.StarlarkSemantics.Companion.DEFAULT
            )
        }

        /**
         * Returns the module (file) of the `depth`-th innermost enclosing Starlark function on the
         * call stack, or null if number of the active calls that are functions defined in Starlark is
         * less than or equal to `depth`.
         * 
         * 
         * This method is a temporary workaround for Starlarkification, to check `_builtin`
         * restriction and should not be used anywhere else.
         * 
         * @param depth the depth for the callstack.
         * @throws IllegalArgumentException if `depth` is negative.
         */
        /**
         * Returns the module (file) of the innermost enclosing Starlark function on the call stack, or
         * null if none of the active calls are functions defined in Starlark.
         * 
         * 
         * The name of this function is intentionally horrible to make you feel bad for using it.
         */
        @kotlin.jvm.JvmOverloads
        fun ofInnermostEnclosingStarlarkFunction(
            thread: net.starlark.java.eval.StarlarkThread,
            depth: Int = 0
        ): Module? {
            val fn: net.starlark.java.eval.StarlarkFunction? = thread.getInnermostEnclosingStarlarkFunction(depth)
            if (fn != null) {
                return fn.getModule()
            }
            return null
        }

        private val EMPTY_INDICES = IntArray(0)
    }
}
