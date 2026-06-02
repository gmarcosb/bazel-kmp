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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.actions.Artifact

/**
 * Helpers for setting up the serialization registry (e.g. explicit codecs and constants).
 * 
 * 
 * The vast majority of codecs are automatically registered (see [AutoRegistry] and [ ]). This class provides methods to register additional codecs and constants,
 * depending on the usage context.
 */
object SerializationRegistrySetupHelpers {
    private val OUTPUT_PATHS: com.google.common.collect.ImmutableList<String?> =
        com.google.common.collect.ImmutableList.of<String?>("k8-opt", "k8-fastbuild", "k8-debug")

    fun makeReferenceConstants(
        directories: BlazeDirectories,
        ruleClassProvider: ConfiguredRuleClassProvider,
        workspaceName: String?
    ): com.google.common.collect.ImmutableList<Any?> {
        val referenceConstants: com.google.common.collect.ImmutableList.Builder<Any?> =
            com.google.common.collect.ImmutableList.builder<Any?>()
                .add(directories)
                .add(directories.getExecRootBase().getFileSystem())
                .add(directories.getBuildDataDirectory(workspaceName))
                .add(
                    ruleClassProvider.getFragmentRegistry().getAllFragments()
                ) // Commonly referenced if --trim_test_configuration is enabled.
                .add(
                    ruleClassProvider
                        .getFragmentRegistry()
                        .getAllFragments()
                        .trim(TestConfiguration::class.java)
                )

        val virtualRoot: Root? = directories.getVirtualSourceRoot()
        if (virtualRoot != null) {
            referenceConstants.add(ArtifactRoot.asSourceRoot(virtualRoot))
        }

        // The builtins bzl root (if it exists) lives on a separate InMemoryFileSystem.
        val builtinsRoot: Root? = ruleClassProvider.getBundledBuiltinsRoot()
        if (builtinsRoot != null) {
            referenceConstants.add(builtinsRoot)
        }

        for (outputDirectory in OutputDirectory.values()) {
            for (outputPath in OUTPUT_PATHS) {
                referenceConstants.add(outputDirectory.getRoot(outputPath, directories, workspaceName))
            }
        }
        return referenceConstants.build()
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addStarlarkFunctionality(
        builder: com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry.Builder,
        ruleClassProvider: ConfiguredRuleClassProvider
    ): com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry.Builder {
        val starlarkEnv: BazelStarlarkEnvironment = ruleClassProvider.getBazelStarlarkEnvironment()
        builder
            .addReferenceConstant(StructProvider.STRUCT)
            .addReferenceConstant(net.starlark.java.eval.Starlark.NONE)
            .addReferenceConstant(net.starlark.java.syntax.Location.BUILTIN)
            .addReferenceConstant(net.starlark.java.eval.SymbolGenerator.CONSTANT_SYMBOL)
            .addReferenceConstants(
                com.google.common.collect.ImmutableSortedMap.copyOf(starlarkEnv.getUninjectedBuildBzlEnv()).values()
            )

        // Make reference constants for all the native module's potential elements, so that something
        // like myvar = native.cc_test in a .bzl file doesn't cause problems (otherwise we'd have to
        // know how to serialize native.cc_test).
        //
        // Some of these elements may be overridden to Starlark values by builtins injection; see
        // StarlarkBuiltinsFunction. The native module object itself is not registered because it is
        // constructed during builtins injection.
        //
        // TODO(b/111564291): how do we get access to all other Starlark built-ins (ones in
        // apple_common, for instance) and register those? Currently most of those objects are fairly
        // simple to serialize, but that may change in the future. Also be mindful of whether
        // StarlarkSemantics (i.e., incompatible/experimental flags) can affect the bindings we see
        // here. [brandjon: May be able to use the new method
        // BazelStarlarkEnvironment#getUninjectedBuildBzlEnv.]
        builder
            .addReferenceConstants(
                com.google.common.collect.ImmutableSortedMap.copyOf(starlarkEnv.getUninjectedBuildBzlNativeBindings())
                    .values()
            )
            .addReferenceConstants(
                com.google.common.collect.ImmutableSortedMap.copyOf(starlarkEnv.getUninjectedModuleBzlNativeBindings())
                    .values()
            )

        return builder
    }

    @kotlin.jvm.JvmStatic
    fun analysisCachingCodecs(): com.google.common.collect.ImmutableList<ObjectCodec<*>?> {
        return AnalysisCachingCodecsHolder.INSTANCE
    }

    private fun createCoreOptionsImplCodec(): ObjectCodec<*> {
        try {
            val coreOptionsImplClass: java.lang.Class<*> =
                java.lang.Class.forName("com.google.devtools.build.lib.analysis.config.CoreOptionsImpl")
            return DynamicCodec.Companion.createWithOverrides(
                coreOptionsImplClass,
                com.google.common.collect.ImmutableMap.of<java.lang.reflect.Field?, FieldHandler?>(
                    coreOptionsImplClass.getDeclaredField("checkVisibility"),
                    object : FieldHandler {
                        override fun serialize(
                            context: SerializationContext?, codedOut: CodedOutputStream?, obj: Any?
                        ) {
                            // checkVisibility is omitted because it will be derived from BuildOptions
                            // during deserialization.
                        }

                        override fun deserialize(
                            context: AsyncDeserializationContext, codedIn: CodedInputStream?, obj: Any
                        ) {
                            // checkVisibility is not in the serialized stream, and must be initialized from
                            // the value in BuildOptions, which is provided as a dependency.
                            (obj as CoreOptions)
                                .setCheckVisibility(
                                    context
                                        .getDependency<BuildOptions?>(BuildOptions::class.java)
                                        .get(CoreOptions::class.java)
                                        .getCheckVisibility()
                                )
                        }
                    })
            )
        } catch (e: java.lang.ReflectiveOperationException) {
            throw java.lang.ExceptionInInitializerError(e)
        }
    }

    /**
     * Initializes an [ObjectCodecRegistry.Builder] for analysis serialization.
     * 
     * 
     * This may be an expensive operation because it can trigger codec scanning.
     */
    fun initializeAnalysisCodecRegistryBuilder(
        ruleClassProvider: ConfiguredRuleClassProvider,
        additionalReferenceConstants: com.google.common.collect.ImmutableList<Any?>?
    ): com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry.Builder {
        var builder: com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry.Builder =
            AutoRegistry.get()
                .getBuilder()
                .addReferenceConstants(additionalReferenceConstants)
                .computeChecksum(false)
        builder = addStarlarkFunctionality(builder, ruleClassProvider)
        analysisCachingCodecs().forEach(java.util.function.Consumer { codec: ObjectCodec<*>? -> builder.add(codec) })
        return builder
    }

    /**
     * Holder to ensure codecs are not loaded unless [.analysisCachingCodecs] is called.
     * 
     * 
     * This class is loaded on-demand, which is especially important for
     * bazel_bootstrap_distfile_test, where AutoCodec doesn't exist. This is fine for the test,
     * because it doesn't actually use the codecs. See [Initialization on
     * demand idiom](https://en.wikipedia.org/wiki/Initialization-on-demand_holder_idiom).
     */
    private object AnalysisCachingCodecsHolder {
        private val AUTOCODEC_CLASSES_FOR_VALUE_SHARING: com.google.common.collect.ImmutableList<java.lang.Class<*>> =
            com.google.common.collect.ImmutableList.of<java.lang.Class<*>?>(
                EnvironmentGroupConfiguredTarget::class.java,
                InputFileConfiguredTarget::class.java,
                MergedConfiguredTarget::class.java,
                OutputFileConfiguredTarget::class.java,
                PackageGroupConfiguredTarget::class.java,
                RuleConfiguredTarget::class.java,
                FeatureConfiguration::class.java,
                RunfilesArtifactValue::class.java,
                AspectValue::class.java,
                AliasConfiguredTarget::class.java,
                BuildConfigurationValue::class.java,
                InlineFileArtifactValue::class.java,
                AspectKeyCreator.AspectKey::class.java
            )

        private val INSTANCE: com.google.common.collect.ImmutableList<ObjectCodec<*>?>

        init {
            val builder: com.google.common.collect.ImmutableList.Builder<ObjectCodec<*>?> =
                com.google.common.collect.ImmutableList.builder<ObjectCodec<*>?>()
                    .add(ArrayCodec.Companion.forComponentType<Artifact?>(Artifact::class.java))
                    .add(DeferredNestedSetCodec())
                    .add(ValueSharingAdapter<T?>(Label.deferredCodec()))
                    .add(ModuleCodec.Companion.moduleCodec())
                    .add(ValueSharingAdapter<T?>(PackageIdentifier.deferredCodec()))
                    .add(ConfiguredTargetKey.valueSharingCodec())
                    .add(TransitiveInfoProviderMapImpl.valueSharingCodec())
                    .add(RemoteConfiguredTargetValue.codec())
                    .add(BuildOptions.valueSharingCodec())
                    .addAll(ArtifactCodecs.VALUE_SHARING_CODECS)
                    .add(createCoreOptionsImplCodec())

            for (classForValueSharing in AUTOCODEC_CLASSES_FOR_VALUE_SHARING) {
                try {
                    // Looks up the AutoCodec implementations with reflection. Since the autocodec-plugin is
                    // not marked with generates_api = True (to avoid build time impact) the actual AutoCodec
                    // classes are not visible as imports. The dependency on the respective class ensures that
                    // the required target dependency exists. The corresponding AutoCodec class will be in the
                    // same jar file.
                    val autoCodecConstructor: java.lang.reflect.Constructor<*> =
                    // AutoCodec generated codecs for inner classes use '_' as a separator in the
                        // generated class name.
                        java.lang.Class.forName(classForValueSharing.getName().replace('$', '_') + "_AutoCodec")
                            .getDeclaredConstructor()
                    autoCodecConstructor.setAccessible(true)
                    builder.add(
                        ValueSharingAdapter<Any?>(
                            autoCodecConstructor.newInstance() as DeferredObjectCodec<*>?
                        )
                    )
                } catch (e: java.lang.ReflectiveOperationException) {
                    throw java.lang.ExceptionInInitializerError(e)
                }
            }
            INSTANCE = builder.build()
        }
    }
}
