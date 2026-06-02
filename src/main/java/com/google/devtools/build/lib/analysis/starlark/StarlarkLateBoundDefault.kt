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
package com.google.devtools.build.lib.analysis.starlark

import com.google.devtools.build.lib.analysis.starlark.annotations.StarlarkConfigurationField

/**
 * An implementation of [LateBoundDefault] which obtains a late-bound attribute value (of type
 * 'label') specifically by Starlark configuration fragment name and field name, as registered by
 * [StarlarkConfigurationField].
 * 
 * 
 * For example, a StarlarkLateBoundDefault on "java" and "toolchain" would require a valid
 * configuration fragment named "java" with a method annotated with [ ] of name "toolchain". This [LateBoundDefault] would provide a
 * late-bound dependency (defined by the label returned by that configuration field) in the current
 * target configuration.
 */
@javax.annotation.concurrent.Immutable
class StarlarkLateBoundDefault<FragmentT> private constructor(
    defaultVal: com.google.devtools.build.lib.cmdline.Label?,
    fragmentClass: java.lang.Class<FragmentT?>?,
    method: java.lang.reflect.Method,
    fragmentName: String?,
    fragmentFieldName: String?
) : AbstractLabelLateBoundDefault<FragmentT?>(fragmentClass, defaultVal), LateBoundDefaultApi {
    private val method: java.lang.reflect.Method

    /**
     * Returns the Starlark name of the configuration fragment that this late bound default requires.
     */
    val fragmentName: String?

    /**
     * Returns the Starlark name of the configuration field name, as registered by [ ] annotation on the configuration fragment.
     */
    val fragmentFieldName: String?

    override fun resolve(
        rule: com.google.devtools.build.lib.packages.Rule?,
        attributes: com.google.devtools.build.lib.packages.AttributeMap?,
        config: FragmentT?
    ): com.google.devtools.build.lib.cmdline.Label? {
        try {
            val result: Any? = method.invoke(config)
            return result as com.google.devtools.build.lib.cmdline.Label?
        } catch (e: java.lang.IllegalAccessException) {
            // Configuration field methods should not throw either of these exceptions.
            throw java.lang.AssertionError("Method invocation failed: " + e)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw java.lang.AssertionError("Method invocation failed: " + e)
        }
    }

    private constructor(
        annotation: StarlarkConfigurationField,
        fragmentClass: java.lang.Class<FragmentT?>?,
        fragmentName: String?,
        method: java.lang.reflect.Method?,
        toolsRepository: RepositoryName?
    ) : this(
        getDefaultLabel(annotation, toolsRepository),
        fragmentClass,
        method,
        fragmentName,
        annotation.name()
    )

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.append("<late-bound default>")
    }

    /**
     * An exception thrown if a user specifies an invalid configuration field identifier.
     * 
     * @see StarlarkConfigurationField
     */
    class InvalidConfigurationFieldException(message: String?) : java.lang.Exception(message)


    private class CacheKey(fragmentClass: java.lang.Class<*>, toolsRepository: RepositoryName) {
        private val fragmentClass: java.lang.Class<*>
        private val toolsRepository: RepositoryName

        init {
            this.fragmentClass = fragmentClass
            this.toolsRepository = toolsRepository
        }

        override fun equals(`object`: Any?): Boolean {
            if (`object` === this) {
                return true
            } else if (`object` !is CacheKey) {
                return false
            } else {
                return fragmentClass == `object`.fragmentClass
                        && toolsRepository == `object`.toolsRepository
            }
        }

        override fun hashCode(): Int {
            var result: Int = fragmentClass.hashCode()
            result = 31 * result + toolsRepository.hashCode()
            return result
        }
    }

    init {
        this.method = method
        this.fragmentName = fragmentName
        this.fragmentFieldName = fragmentFieldName
    }

    companion object {
        /** Returns the [StarlarkConfigurationField] annotation corresponding to this method.  */
        private fun getDefaultLabel(
            annotation: StarlarkConfigurationField, toolsRepository: RepositoryName?
        ): com.google.devtools.build.lib.cmdline.Label? {
            if (annotation.defaultLabel().isEmpty()) {
                return null
            }
            val defaultLabel: com.google.devtools.build.lib.cmdline.Label? =
                if (annotation.defaultInToolRepository())
                    com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked(toolsRepository + annotation.defaultLabel())
                else
                    com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked(annotation.defaultLabel())
            return defaultLabel
        }

        /**
         * A cache for efficient [StarlarkLateBoundDefault] loading by configuration fragment. Each
         * configuration fragment class key is mapped to a [Map] where keys are configuration field
         * Starlark names, and values are the [StarlarkLateBoundDefault]s. Methods must be annotated
         * with [StarlarkConfigurationField] to be considered.
         */
        private val fieldCache: com.github.benmanes.caffeine.cache.LoadingCache<CacheKey?, MutableMap<String?, StarlarkLateBoundDefault<*>?>?> =
            Caffeine.newBuilder()
                .initialCapacity(10)
                .maximumSize(100)
                .build<CacheKey?, MutableMap<String?, StarlarkLateBoundDefault<*>?>?>(
                    com.github.benmanes.caffeine.cache.CacheLoader { key: CacheKey? ->
                        val lateBoundDefaultMap: com.google.common.collect.ImmutableMap.Builder<String?, StarlarkLateBoundDefault<*>?> =
                            com.google.common.collect.ImmutableMap.Builder<String?, StarlarkLateBoundDefault<*>?>()
                        val fragmentClass: java.lang.Class<*> = key.fragmentClass
                        val fragmentModule: net.starlark.java.annot.StarlarkBuiltin? =
                            net.starlark.java.annot.StarlarkAnnotations.getStarlarkBuiltin(fragmentClass)

                        if (fragmentModule != null) {
                            for (method in fragmentClass.getMethods()) {
                                if (method.isAnnotationPresent(StarlarkConfigurationField::class.java)) {
                                    // TODO(b/68817606): Use annotation processors to verify these constraints.
                                    com.google.common.base.Preconditions.checkArgument(
                                        method.getReturnType() == com.google.devtools.build.lib.cmdline.Label::class.java,
                                        "Method %s must have return type 'Label'",
                                        method
                                    )
                                    com.google.common.base.Preconditions.checkArgument(
                                        method.getParameterTypes().size == 0,
                                        "Method %s must not accept arguments",
                                        method
                                    )

                                    val configField: StarlarkConfigurationField =
                                        method.getAnnotation<T>(StarlarkConfigurationField::class.java)
                                    lateBoundDefaultMap.put(
                                        configField.name(),
                                        StarlarkLateBoundDefault<Any?>(
                                            configField,
                                            fragmentClass,
                                            fragmentModule.name,
                                            method,
                                            key.toolsRepository
                                        )
                                    )
                                }
                            }
                        }
                        lateBoundDefaultMap.buildOrThrow()
                    })

        /**
         * Returns a [LateBoundDefault] which obtains a late-bound attribute value (of type 'label')
         * specifically by Starlark configuration fragment name and field name, as registered by [ ].
         * 
         * @param fragmentClass the configuration fragment class, which must have a valid Starlark name
         * @param fragmentFieldName the configuration field name, as registered by [     ] annotation
         * @param toolsRepository the Bazel tools repository path fragment
         * @throws InvalidConfigurationFieldException if there is no valid configuration field with the
         * given fragment class and field name
         */
        @Throws(InvalidConfigurationFieldException::class)
        fun <FragmentT> forConfigurationField(
            fragmentClass: java.lang.Class<FragmentT?>, fragmentFieldName: String?, toolsRepository: RepositoryName
        ): StarlarkLateBoundDefault<FragmentT?> {
            val cacheKey: CacheKey = com.google.devtools.build.lib.analysis.starlark.StarlarkLateBoundDefault.CacheKey(
                fragmentClass,
                toolsRepository
            )
            val resolver: StarlarkLateBoundDefault<*>? = fieldCache.get(cacheKey).get(fragmentFieldName)
            if (resolver == null) {
                val moduleAnnotation: net.starlark.java.annot.StarlarkBuiltin? =
                    net.starlark.java.annot.StarlarkAnnotations.getStarlarkBuiltin(fragmentClass)
                if (moduleAnnotation == null) {
                    throw java.lang.AssertionError("fragment class must have a valid Starlark name")
                }
                throw InvalidConfigurationFieldException(
                    String.format(
                        "invalid configuration field name '%s' on fragment '%s'",
                        fragmentFieldName, moduleAnnotation.name
                    )
                )
            }
            return resolver as StarlarkLateBoundDefault<FragmentT?> // unchecked cast
        }
    }
}
