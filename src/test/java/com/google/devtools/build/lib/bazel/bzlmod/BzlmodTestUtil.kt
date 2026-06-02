// Copyright 2021 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule
import com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.InterimModuleBuilder.Companion.create

/** Utilities for bzlmod tests.  */
object BzlmodTestUtil {
    /** Simple wrapper around the [ModuleKey] constructor that takes a string version.  */
    fun createModuleKey(name: String?, version: String?): ModuleKey {
        try {
            return ModuleKey(name, Version.parse(version))
        } catch (e: Version.ParseException) {
            throw java.lang.IllegalArgumentException(e)
        }
    }


    @Throws(java.lang.Exception::class)
    fun buildModule(name: String?, version: String?): Module.Builder {
        return java.lang.Module.builder()
            .setName(name)
            .setVersion(Version.parse(version))
            .setRepoName(name)
            .setKey(createModuleKey(name, version))
            .setExtensionUsages(com.google.common.collect.ImmutableList.of<E?>())
            .setExecutionPlatformsToRegister(com.google.common.collect.ImmutableList.of<E?>())
            .setToolchainsToRegister(com.google.common.collect.ImmutableList.of<E?>())
    }

    fun createRepositoryMapping(key: ModuleKey, vararg names: String?): RepositoryMapping {
        val mappingBuilder: com.google.common.collect.ImmutableMap.Builder<String?, RepositoryName?> =
            com.google.common.collect.ImmutableMap.builder<String?, RepositoryName?>()
        var i = 0
        while (i < names.size) {
            mappingBuilder.put(names[i], RepositoryName.createUnvalidated(names[i + 1]))
            i += 2
        }
        return RepositoryMapping.create(
            mappingBuilder.buildOrThrow(), key.getCanonicalRepoNameWithoutVersion()
        )
    }

    fun createTagClass(vararg attrs: Attribute?): TagClass {
        return TagClass.create(
            com.google.common.collect.ImmutableList.< E > copyOf < E ? > (attrs),
            java.util.Optional.of<T?>("doc")
        )
    }

    @Throws(java.lang.Exception::class)
    fun buildTag(tagName: String?): TestTagBuilder {
        return TestTagBuilder(tagName)
    }

    /** Builder class to create a `Entry<ModuleKey, Module>` entry faster inside UnitTests  */
    internal class InterimModuleBuilder private constructor() {
        var builder: InterimModule.Builder? = null
        var key: ModuleKey? = null
        var deps: com.google.common.collect.ImmutableMap.Builder<String?, ModuleKey?> =
            com.google.common.collect.ImmutableMap.Builder<String?, ModuleKey?>()
        var originalDeps: com.google.common.collect.ImmutableMap.Builder<String?, ModuleKey?> =
            com.google.common.collect.ImmutableMap.Builder<String?, ModuleKey?>()

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addDep(depRepoName: String?, key: ModuleKey?): InterimModuleBuilder {
            deps.put(depRepoName, key)
            return this
        }


        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addOriginalDep(depRepoName: String?, key: ModuleKey?): InterimModuleBuilder {
            originalDeps.put(depRepoName, key)
            return this
        }


        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addNodepDep(key: ModuleKey?): InterimModuleBuilder {
            builder.addNodepDep(key)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setKey(value: ModuleKey?): InterimModuleBuilder {
            this.key = value
            this.builder.setKey(value)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setRepoName(value: String?): InterimModuleBuilder {
            this.builder.setRepoName(value)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setRegistry(value: FakeRegistry?): InterimModuleBuilder {
            this.builder.setRegistry(value)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExecutionPlatformsToRegister(value: com.google.common.collect.ImmutableList<String?>?): InterimModuleBuilder {
            this.builder.addExecutionPlatformsToRegister(value)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addToolchainsToRegister(value: com.google.common.collect.ImmutableList<String?>?): InterimModuleBuilder {
            this.builder.addToolchainsToRegister(value)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(LabelSyntaxException::class)
        fun addFlagAlias(nativeFlag: String?, starlarkFlag: String?): InterimModuleBuilder {
            this.builder.addFlagAlias(nativeFlag, starlarkFlag)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExtensionUsage(value: ModuleExtensionUsage?): InterimModuleBuilder {
            this.builder.addExtensionUsage(value)
            return this
        }

        fun buildEntry(): MutableMap.MutableEntry<ModuleKey?, InterimModule?> {
            val module: InterimModule = this.build()
            return AbstractMap.SimpleEntry<ModuleKey?, InterimModule?>(this.key, module)
        }

        fun build(): InterimModule {
            val builtDeps: com.google.common.collect.ImmutableMap<String?, ModuleKey?> = this.deps.buildOrThrow()

            /* Copy dep entries that have not been changed to original deps */
            val initOriginalDeps: com.google.common.collect.ImmutableMap<String?, ModuleKey?> =
                this.originalDeps.buildOrThrow()
            for (e in builtDeps.entries) {
                if (!initOriginalDeps.containsKey(e.key)) {
                    originalDeps.put(e)
                }
            }
            val builtOriginalDeps: com.google.common.collect.ImmutableMap<String?, ModuleKey?> =
                this.originalDeps.buildOrThrow()

            return this.builder.setDeps(builtDeps).setOriginalDeps(builtOriginalDeps).build()
        }

        companion object {
            fun create(name: String?, version: Version?): InterimModuleBuilder {
                val moduleBuilder = InterimModuleBuilder()
                val key: ModuleKey = ModuleKey(name, version)
                moduleBuilder.key = key
                moduleBuilder.builder = InterimModule.builder().setName(name).setVersion(version).setKey(key)
                return moduleBuilder
            }

            @Throws(ParseException::class)
            fun create(name: String?, version: String?): InterimModuleBuilder? {
                return create(name, Version.parse(version))
            }
        }
    }

    /**
     * Builder helper for [ ]
     */
    class AugmentedModuleBuilder private constructor() {
        private var builder: AugmentedModule.Builder? = null
        private var key: ModuleKey? = null

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addChangedDep(
            name: String?, version: String?, oldVersion: String?, reason: ResolutionReason?
        ): AugmentedModuleBuilder {
            this.builder
                .addDep(name, createModuleKey(name, version))
                .addUnusedDep(name, createModuleKey(name, oldVersion))
                .addDepReason(name, reason)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addChangedDep(
            repoName: String?,
            moduleName: String?,
            version: String?,
            oldVersion: String?,
            reason: ResolutionReason?
        ): AugmentedModuleBuilder {
            this.builder
                .addDep(repoName, createModuleKey(moduleName, version))
                .addUnusedDep(repoName, createModuleKey(moduleName, oldVersion))
                .addDepReason(repoName, reason)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addDep(name: String?, version: String?): AugmentedModuleBuilder {
            this.builder
                .addDep(name, createModuleKey(name, version))
                .addDepReason(name, ResolutionReason.ORIGINAL)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addDep(repoName: String?, moduleName: String?, version: String?): AugmentedModuleBuilder {
            this.builder
                .addDep(repoName, createModuleKey(moduleName, version))
                .addDepReason(repoName, ResolutionReason.ORIGINAL)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addDependant(name: String?, version: String?): AugmentedModuleBuilder {
            this.builder.addDependant(createModuleKey(name, version))
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addDependant(key: ModuleKey?): AugmentedModuleBuilder {
            this.builder.addDependant(key)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addOriginalDependant(name: String?, version: String?): AugmentedModuleBuilder {
            this.builder.addOriginalDependant(createModuleKey(name, version))
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addOriginalDependant(key: ModuleKey?): AugmentedModuleBuilder {
            this.builder.addOriginalDependant(key)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addStillDependant(name: String?, version: String?): AugmentedModuleBuilder {
            this.builder.addOriginalDependant(createModuleKey(name, version))
            this.builder.addDependant(createModuleKey(name, version))
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addStillDependant(key: ModuleKey?): AugmentedModuleBuilder {
            this.builder.addOriginalDependant(key)
            this.builder.addDependant(key)
            return this
        }

        fun buildEntry(): MutableMap.MutableEntry<ModuleKey?, AugmentedModule?> {
            return AbstractMap.SimpleEntry<K?, V?>(this.key, this.builder.build())
        }

        companion object {
            fun buildAugmentedModule(
                key: ModuleKey?, name: String?, version: Version?, loaded: Boolean
            ): AugmentedModuleBuilder {
                val myBuilder = AugmentedModuleBuilder()
                myBuilder.key = key
                myBuilder.builder =
                    AugmentedModule.builder(key)
                        .setName(name)
                        .setVersion(version)
                        .setRepoName(name)
                        .setLoaded(loaded)
                return myBuilder
            }

            @Throws(ParseException::class)
            fun buildAugmentedModule(
                name: String?, version: String?, loaded: Boolean
            ): AugmentedModuleBuilder {
                val key: ModuleKey = createModuleKey(name, version)
                return buildAugmentedModule(key, name, Version.parse(version), loaded)
            }

            @Throws(ParseException::class)
            fun buildAugmentedModule(name: String?, version: String?): AugmentedModuleBuilder {
                val key: ModuleKey = createModuleKey(name, version)
                return buildAugmentedModule(key, name, Version.parse(version), true)
            }

            fun buildAugmentedModule(key: ModuleKey, name: String?): AugmentedModuleBuilder {
                return buildAugmentedModule(key, name, key.version(), true)
            }
        }
    }

    /** A builder for [Tag] for testing purposes.  */
    class TestTagBuilder private constructor(private val tagName: String?) {
        private val attrValuesBuilder: net.starlark.java.eval.Dict.Builder<String?, Any?> =
            Dict.builder<String?, Any?>()
        private val location: net.starlark.java.syntax.Location = net.starlark.java.syntax.Location.BUILTIN
        private var devDependency = false

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addAttr(attrName: String?, attrValue: Any?): TestTagBuilder {
            attrValuesBuilder.put(attrName, attrValue)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setDevDependency(): TestTagBuilder {
            devDependency = true
            return this
        }

        fun build(): Tag {
            return Tag.builder()
                .setTagName(tagName)
                .setLocation(location)
                .setAttributeValues(AttributeValues.create(attrValuesBuilder.buildImmutable()))
                .setDevDependency(devDependency)
                .build()
        }
    }
}
