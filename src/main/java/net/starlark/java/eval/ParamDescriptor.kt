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
package net.starlark.java.eval

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import java.util.concurrent.ConcurrentHashMap

/** A value class for storing [Param] metadata to avoid using Java proxies.  */
internal class ParamDescriptor private constructor(
    name: String?,
    defaultExpr: String,
    named: Boolean,
    positional: Boolean,
    allowedClasses: MutableList<java.lang.Class<*>?>,
    enableOnlyWithFlag: String,
    disableWithFlag: String
) {
    private val name: String?
    private val defaultValue: Any?
    private val named: Boolean
    private val positional: Boolean

    // Null means any class is allowed.
    // Should be not empty otherwise.
    private val allowedClasses: MutableList<java.lang.Class<*>?>?

    // Non-null when the parameter might be enabled or disabled with a semantics flag.
    // It is an error for Starlark code to supply a value to a disabled parameter.
    // Making it nullable is cpu performance optimization (don't need to call String::isEmpty)
    // Making it a class is a memory optimization, conditional parameters don't take more space
    // Current serialization code can't handle records.
    val conditionalCheck: ConditionalCheck?

    internal class ConditionalCheck(enableOnlyWithFlag: String, disableWithFlag: String?) {
        private val enableOnlyWithFlag: String
        private val disableWithFlag: String?

        init {
            this.enableOnlyWithFlag = enableOnlyWithFlag
            this.disableWithFlag = disableWithFlag
        }

        fun disableWithFlag(): String? {
            return disableWithFlag
        }

        fun enableOnlyWithFlag(): String {
            return enableOnlyWithFlag
        }
    }

    /** @see Param.name
     */
    fun getName(): String? {
        return name
    }

    /** Returns a description of allowed argument types suitable for an error message.  */
    fun getTypeErrorMessage(): String? {
        // Result has one of these forms:
        // "a"
        // "a or b"
        // "a, b, or c"
        if (allowedClasses == null) {
            return net.starlark.java.eval.Starlark.Companion.classType(Any::class.java)
        }
        val buf: java.lang.StringBuilder = java.lang.StringBuilder()
        // TODO(b/200065655#comment3): Remove when we have an official way for package defaults.
        val allowedClassesFiltered: com.google.common.collect.ImmutableList<java.lang.Class<*>?> =
            allowedClasses.stream()
                .filter(java.util.function.Predicate { x: java.lang.Class<*>? ->
                    net.starlark.java.eval.Starlark.Companion.classType(
                        x
                    ) != "NativeComputedDefault"
                })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<java.lang.Class<*>?>())
        var i = 0
        val n: Int = allowedClassesFiltered.size()
        while (i < n) {
            if (i > 0) {
                buf.append(if (n == 2) " or " else if (i < n - 1) ", " else ", or ")
            }
            buf.append(net.starlark.java.eval.Starlark.Companion.classType(allowedClassesFiltered.get(i)))
            i++
        }
        return buf.toString()
    }

    fun getAllowedClasses(): MutableList<java.lang.Class<*>?>? {
        return allowedClasses
    }

    /** @see Param.positional
     */
    fun isPositional(): Boolean {
        return positional
    }

    /** See [Param.named].  */
    fun isNamed(): Boolean {
        return named
    }

    /** Returns the effective default value of this parameter, or null if mandatory.  */
    fun getDefaultValue(): Any? {
        return defaultValue
    }

    /** Returns true if parameter is enabled.  */
    fun isEnabled(thread: net.starlark.java.eval.StarlarkThread): Boolean {
        return conditionalCheck == null
                || thread
            .getSemantics()
            .isFeatureEnabledBasedOnTogglingFlags(
                conditionalCheck.enableOnlyWithFlag, conditionalCheck.disableWithFlag
            )
    }

    /** Returns a phrase meaning "disabled" appropriate to the specified flag.  */
    fun getDisabledErrorMessage(): String? {
        com.google.common.base.Preconditions.checkNotNull<ConditionalCheck?>(conditionalCheck)
        // TODO(b/407506132): A parameter enabled by a non-experimental flag should not be marked as
        //  experimental
        if (!conditionalCheck!!.enableOnlyWithFlag().isEmpty()) {
            return java.lang.String.format(
                "experimental and thus unavailable with the current flags. It may be enabled by setting"
                        + " --%s",
                conditionalCheck.enableOnlyWithFlag().substring(1)
            ) // remove [+-] prefix
        }
        return java.lang.String.format(
            "deprecated and will be removed soon. It may be temporarily re-enabled by setting"
                    + " --%s=false",
            conditionalCheck.disableWithFlag().substring(1)
        ) // remove [+-] prefix
    }

    init {
        this.name = name
        // TODO(adonovan): apply the same validation logic to the default value
        // as we do to caller-supplied values (see BuiltinFunction.checkParamValue).
        this.defaultValue =
            if (defaultExpr.isEmpty()) null else net.starlark.java.eval.ParamDescriptor.Companion.evalDefault(
                name,
                defaultExpr
            )
        this.named = named
        this.positional = positional
        if (allowedClasses.contains(Any::class.java)) {
            this.allowedClasses = null
        } else {
            this.allowedClasses = allowedClasses
        }
        if (!enableOnlyWithFlag.isEmpty() || !disableWithFlag.isEmpty()) {
            this.conditionalCheck =
                net.starlark.java.eval.ParamDescriptor.ConditionalCheck(enableOnlyWithFlag, disableWithFlag)
        } else {
            this.conditionalCheck = null
        }
    }

    companion object {
        /** Returns a [ParamDescriptor] representing the given raw [Param] annotation.  */
        fun of(param: net.starlark.java.annot.Param, paramClass: java.lang.Class<*>?): ParamDescriptor {
            val defaultExpr: String = param.defaultValue

            // Compute set of allowed classes.
            val allowedTypes: Array<net.starlark.java.annot.ParamType> = param.allowedTypes
            val allowedClasses: MutableList<java.lang.Class<*>?> = java.util.ArrayList<java.lang.Class<*>?>()
            if (allowedTypes.size > 0) {
                for (pt in allowedTypes) {
                    allowedClasses.add(pt.type)
                }
            } else {
                // Use the class of the parameter itself.
                // Interpret primitive boolean parameter as j.l.Boolean.
                allowedClasses.add(if (paramClass == java.lang.Boolean.TYPE) Boolean::class.java else paramClass)
            }

            return net.starlark.java.eval.ParamDescriptor(
                param.name,
                defaultExpr,
                param.named,
                param.positional,
                allowedClasses,
                param.enableOnlyWithFlag,
                param.disableWithFlag
            )
        }

        // A memoization of evalDefault, keyed by expression.
        // This cache is manually maintained (instead of using LoadingCache),
        // as default values may sometimes be recursively requested.
        private val defaultValueCache: ConcurrentHashMap<String?, Any?> = ConcurrentHashMap<String?, Any?>()

        // Evaluates the default value expression for a parameter.
        private fun evalDefault(name: String?, expr: String): Any? {
            // Values required by defaults of functions in UNIVERSE must
            // be handled without depending on the evaluator, or even
            // on defaultValueCache, because JVM global variable initialization
            // is such a mess. (Specifically, it's completely dynamic,
            // so if two or more variables are mutually dependent, like
            // defaultValueCache and UNIVERSE would be, you have to write
            // code that works in all possible dynamic initialization orders.)
            // Better not to go there.
            if (expr == "None") {
                return net.starlark.java.eval.Starlark.Companion.NONE
            } else if (expr == "True") {
                return true
            } else if (expr == "False") {
                return false
            } else if (expr == "unbound") {
                return net.starlark.java.eval.Starlark.Companion.UNBOUND
            } else if (expr == "0") {
                return net.starlark.java.eval.StarlarkInt.Companion.of(0)
            } else if (expr == "1") {
                return net.starlark.java.eval.StarlarkInt.Companion.of(1)
            } else if (expr == "[]") {
                return net.starlark.java.eval.StarlarkList.Companion.empty<Any?>()
            } else if (expr == "()") {
                return net.starlark.java.eval.Tuple.Companion.empty()
            } else if (expr == "\" \"") {
                return " "
            }

            var x: Any? = net.starlark.java.eval.ParamDescriptor.Companion.defaultValueCache.get(expr)
            if (x != null) {
                return x
            }

            // We can't evaluate Starlark code until UNIVERSE is bootstrapped.
            com.google.common.base.Preconditions.checkState(
                net.starlark.java.eval.Starlark.Companion.UNIVERSE != null,
                """
        Attempted to evaluate a builtin method's parameter's default expr ('%s = %s'), prior to static initialization of Starlark.UNIVERSE. Either avoid this initialization cycle or else add the expr to the bootstrap list in ParamDescriptor#evalDefault.
        """.trimIndent(),
                name,
                expr
            )

            val module: net.starlark.java.eval.Module = net.starlark.java.eval.Module.Companion.create()
            try {
                net.starlark.java.eval.Mutability.Companion.create("Builtin param default init").use { mu ->
                    // Note that this Starlark thread ignores command line flags.
                    // TODO: b/326588519 - The known default parameters are all simple values. If that changes, a
                    // non-transient symbol generator would be needed here.
                    val thread: net.starlark.java.eval.StarlarkThread =
                        net.starlark.java.eval.StarlarkThread.Companion.createTransient(
                            mu,
                            net.starlark.java.eval.StarlarkSemantics.Companion.DEFAULT
                        )

                    // Disable polling of the java.lang.Thread.interrupt flag during
                    // Starlark evaluation. Assuming the expression does not call a
                    // built-in that throws InterruptedException, this allows us to
                    // assert that InterruptedException "can't happen".
                    //
                    // Bazel Java threads are routinely interrupted during Starlark execution,
                    // and the Starlark interpreter may be in a call to LoadingCache (in CallUtils).
                    // LoadingCache computes the cache entry in the same thread that first
                    // requested the entry, propagating undesirable thread state (which Einstein
                    // called "spooky action at a distance") from an arbitrary application thread
                    // to here, which is logically one-time initialization code.
                    //
                    // A simpler non-solution would be to use a "clean" pool thread
                    // to compute each cache entry; we could safely assume such a thread
                    // is never interrupted. However, this runs afoul of JVM class initialization:
                    // the initialization of Starlark.UNIVERSE depends on Starlark.UNBOUND
                    // because of the reference above. That's fine if they are initialized by
                    // the same thread, as JVM class initialization locks are reentrant,
                    // but the reference deadlocks if made from another thread.
                    // See https://docs.oracle.com/javase/specs/jls/se12/html/jls-12.html#jls-12.4
                    thread.ignoreThreadInterrupts()
                    x = net.starlark.java.eval.Starlark.Companion.eval(
                        net.starlark.java.syntax.ParserInput.fromLines(expr),
                        net.starlark.java.syntax.FileOptions.DEFAULT,
                        module,
                        thread
                    )
                }
            } catch (ex: java.lang.InterruptedException) {
                throw java.lang.IllegalStateException(ex) // can't happen
            } catch (ex: net.starlark.java.syntax.SyntaxError.Exception) {
                throw java.lang.IllegalArgumentException(
                    java.lang.String.format(
                        "failed to evaluate default value '%s' of parameter '%s': %s",
                        expr, name, ex.getMessage()
                    ),
                    ex
                )
            } catch (ex: net.starlark.java.eval.EvalException) {
                throw java.lang.IllegalArgumentException(
                    java.lang.String.format(
                        "failed to evaluate default value '%s' of parameter '%s': %s",
                        expr, name, ex.getMessage()
                    ),
                    ex
                )
            }
            net.starlark.java.eval.ParamDescriptor.Companion.defaultValueCache.put(expr, x)
            return x
        }
    }
}
