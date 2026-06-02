// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.ActionLookupKey

/** The class responsible for creating & interning the various types of AspectKeys.  */
object AspectKeyCreator {
    fun createAspectKey(
        aspectDescriptor: AspectDescriptor, baseConfiguredTargetKey: ConfiguredTargetKey?
    ): AspectKey {
        return createAspectKey(
            aspectDescriptor,  /*baseKeys=*/
            com.google.common.collect.ImmutableList.of<AspectKey?>(),
            baseConfiguredTargetKey
        )
    }

    fun createAspectKey(
        aspectDescriptor: AspectDescriptor,
        baseKeys: com.google.common.collect.ImmutableList<AspectKey?>,
        baseConfiguredTargetKey: ConfiguredTargetKey?
    ): AspectKey {
        return AspectKey.Companion.createAspectKey(baseConfiguredTargetKey, baseKeys, aspectDescriptor)
    }

    fun createTopLevelAspectsKey(
        topLevelAspectsClasses: com.google.common.collect.ImmutableList<AspectClass?>?,
        targetLabel: Label?,
        configuration: BuildConfigurationValue?,
        topLevelAspectsParameters: com.google.common.collect.ImmutableMap<String?, String?>?
    ): TopLevelAspectsKey {
        return TopLevelAspectsKey.Companion.createInternal(
            topLevelAspectsClasses,
            targetLabel,
            ConfiguredTargetKey.Companion.builder().setLabel(targetLabel).setConfiguration(configuration).build(),
            topLevelAspectsParameters
        )
    }

    /** Common superclass for [AspectKey] and [TopLevelAspectsKey].  */
    abstract class AspectBaseKey private constructor(baseConfiguredTargetKey: ConfiguredTargetKey?, hashCode: Int) :
        ActionLookupKey {
        private val baseConfiguredTargetKey: ConfiguredTargetKey?
        private val hashCode: Int

        init {
            this.baseConfiguredTargetKey = baseConfiguredTargetKey
            this.hashCode = hashCode
        }

        /** Returns the key for the base configured target for this aspect.  */
        fun getBaseConfiguredTargetKey(): ConfiguredTargetKey? {
            return baseConfiguredTargetKey
        }

        override fun hashCode(): Int {
            return hashCode
        }
    }

    // Specific subtypes of aspect keys.
    /**
     * Represents an aspect applied to a particular target.
     * 
     * 
     * Extended by two classes: [SimpleAspectKey] for aspects that do not depend on other
     * aspects and [AspectKeyWithBaseAspects] for aspects depending on one or more base aspects.
     * This separation is for memory optimization as in most cases the aspect will not depend on other
     * aspects and its `baseKeys` list will be empty.
     */
    @AutoCodec
    abstract class AspectKey private constructor(
        baseConfiguredTargetKey: ConfiguredTargetKey?,
        aspectDescriptor: AspectDescriptor,
        hashCode: Int
    ) : AspectBaseKey(baseConfiguredTargetKey, hashCode), CqueryNode {
        private val aspectDescriptor: AspectDescriptor

        init {
            this.aspectDescriptor = aspectDescriptor
        }

        @kotlin.jvm.JvmField
        abstract val baseKeys: com.google.common.collect.ImmutableList<AspectKey?>?

        abstract val description: String?

        public override fun getDescription(labelPrinter: LabelPrinter?): String? {
            return this.description
        }

        public override fun functionName(): SkyFunctionName {
            return SkyFunctions.ASPECT
        }

        val aspectName: String
            /**
             * Gets the name of the aspect that would be returned by the corresponding value's `aspectValue.getAspect().getAspectClass().getName()`, if the value could be produced.
             * 
             * 
             * Only needed for reporting errors in BEP when the key's AspectValue fails evaluation.
             */
            get() = aspectDescriptor.getDescription()

        val label: Label?
            get() = getBaseConfiguredTargetKey().getLabel()

        val skyKeyInterner: SkyKeyInterner<AspectKey?>
            get() = interner

        val lookupKey: ActionLookupKey?
            get() = this

        val aspectClass: AspectClass
            get() = aspectDescriptor.getAspectClass()

        val parameters: AspectParameters?
            get() = aspectDescriptor.getParameters()

        fun getAspectDescriptor(): AspectDescriptor {
            return aspectDescriptor
        }

        val configurationKey: BuildConfigurationKey?
            /**
             * Returns the key of the configured target of the aspect; that is, the configuration in which
             * the aspect will be evaluated.
             */
            get() = getBaseConfiguredTargetKey().getConfigurationKey()

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other !is AspectKey) {
                return false
            }
            return hashCode() == other.hashCode() && this.baseKeys == other.baseKeys
                    && getBaseConfiguredTargetKey() == other.getBaseConfiguredTargetKey()
                    && aspectDescriptor == other.aspectDescriptor
        }

        fun prettyPrint(): String? {
            if (this.label == null) {
                return "null"
            }

            val baseKeysString: String? =
                if (this.baseKeys.isEmpty()) "" else java.lang.String.format(" (over %s)", this.baseKeys)
            return java.lang.String.format(
                "%s with aspect %s%s",
                this.label, aspectDescriptor.getAspectClass().getName(), baseKeysString
            )
        }

        override fun toString(): String {
            val toStringHelper: com.google.common.base.MoreObjects.ToStringHelper =
                com.google.common.base.MoreObjects.toStringHelper(this)
                    .add("baseConfiguredTargetKey", getBaseConfiguredTargetKey())
                    .add("aspectDescriptor", aspectDescriptor)

            if (!this.baseKeys.isEmpty()) {
                toStringHelper.add("baseKeys", this.baseKeys)
            }

            return toStringHelper.toString()
        }

        fun withLabel(label: Label?): AspectKey {
            val newBaseKeys: com.google.common.collect.ImmutableList<AspectKey?> =
                this.baseKeys.stream()
                    .map<AspectKey?>(java.util.function.Function { k: AspectKey? -> k!!.withLabel(label) })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<AspectKey?>())

            return createAspectKey(
                ConfiguredTargetKey.Companion.builder()
                    .setLabel(label)
                    .setConfigurationKey(getBaseConfiguredTargetKey().getConfigurationKey())
                    .build(),
                newBaseKeys,
                aspectDescriptor
            )
        }

        internal class SimpleAspectKey(
            baseConfiguredTargetKey: ConfiguredTargetKey?,
            aspectDescriptor: AspectDescriptor,
            hashCode: Int
        ) : AspectKey(baseConfiguredTargetKey, aspectDescriptor, hashCode) {
            override fun getBaseKeys(): com.google.common.collect.ImmutableList<AspectKey?> {
                return com.google.common.collect.ImmutableList.of<AspectKey?>()
            }

            override fun getDescription(): String? {
                return java.lang.String.format("%s of %s", this.aspectClass.getName(), this.label)
            }
        }

        internal class AspectKeyWithBaseAspects private constructor(
            baseConfiguredTargetKey: ConfiguredTargetKey?,
            baseKeys: com.google.common.collect.ImmutableList<AspectKey?>,
            aspectDescriptor: AspectDescriptor,
            hashCode: Int
        ) : AspectKey(baseConfiguredTargetKey, aspectDescriptor, hashCode) {
            private val baseKeys: com.google.common.collect.ImmutableList<AspectKey?>

            init {
                this.baseKeys = baseKeys
            }

            override fun getBaseKeys(): com.google.common.collect.ImmutableList<AspectKey?> {
                return baseKeys
            }

            override fun getDescription(): String? {
                return java.lang.String.format(
                    "%s on top of %s",
                    this.aspectClass.getName(),
                    baseKeys.stream().map<String?>(java.util.function.Function { obj: AspectKey? -> obj!!.description })
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
                )
            }
        }

        /**
         * Compares the [AspectKey] graph structure for specific dependencies.
         * 
         * 
         * An [AspectKey] for a dependency is determined by [ ][com.google.devtools.build.lib.analysis.AspectCollection.buildAspectKey]. This means that the
         * [AspectKey] is structured like a DAG with the following properties.
         * 
         * 
         *  * The [AspectKey.getBaseConfiguredTargetKey] is the same across all nodes.
         *  * Each DAG node has a unique [AspectKey.getAspectDescriptor].
         * 
         * 
         * 
         * Given the above, it's sufficient to traverse unique [AspectDescriptor]s to
         * understand the toplogy of both graphs.
         * 
         * 
         * NB: a new instance of this comparator must be constructed for each comparison.
         */
        private class DescriptorGraphComparator : java.util.Comparator<AspectKey?> {
            private val visited: HashSet<AspectDescriptor?> = HashSet<AspectDescriptor?>()

            override fun compare(left: AspectKey, right: AspectKey): Int {
                val leftDescriptor: AspectDescriptor = left.getAspectDescriptor()
                val rightDescriptor: AspectDescriptor = right.getAspectDescriptor()
                if (!leftDescriptor.equals(rightDescriptor)) {
                    return leftDescriptor.getDescription().compareTo(rightDescriptor.getDescription())
                }
                if (!visited.add(leftDescriptor)) {
                    return 0
                }

                return com.google.common.collect.Comparators.lexicographical<AspectKey?, AspectKey?>(this).compare(
                    left.baseKeys,
                    right.baseKeys
                )
            }
        }

        companion object {
            private val interner: SkyKeyInterner<AspectKey?> = SkyKey.newInterner<SkyKey?>()
            val ORDERING: java.util.Comparator<AspectKey?>? =
                java.util.Comparator.comparing<AspectKey?, ConfiguredTargetKey?>(
                    java.util.function.Function { obj: AspectKey? -> obj!!.getBaseConfiguredTargetKey() },
                    ConfiguredTargetKey.Companion.ORDERING
                )
                    .thenComparing(java.util.Comparator { left: AspectKey?, right: AspectKey? ->
                        DescriptorGraphComparator().compare(
                            left!!,
                            right!!
                        )
                    })

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Instantiator
            fun createAspectKey(
                baseConfiguredTargetKey: ConfiguredTargetKey?,
                baseKeys: com.google.common.collect.ImmutableList<AspectKey?>,
                aspectDescriptor: AspectDescriptor
            ): AspectKey {
                if (baseKeys.isEmpty()) {
                    return interner.intern(
                        SimpleAspectKey(
                            baseConfiguredTargetKey,
                            aspectDescriptor,
                            HashCodes.hashObjects(baseConfiguredTargetKey, aspectDescriptor)
                        )
                    )
                }
                // Keep the list of {@code baseKeys} sorted to avoid running the same aspect twice because
                // of different {@code baseKeys} order even if the {@link AspectKey} objects in the list are
                // the same.
                val sortedBaseKeys: com.google.common.collect.ImmutableList<AspectKey?> =
                    com.google.common.collect.ImmutableList.sortedCopyOf<E?>(
                        java.util.Comparator.comparing<T?, U?> { k: AspectKey? -> k!!.aspectClass.getName() }  // For aspects that appear more than once, comparing aspects parameters based on
                            // their string representation to avoid adding a lot of logic for this
                            // comparison which is expected to be not frequently needed.
                            .thenComparing<Any?>(java.util.function.Function { k: T? -> k.getParameters().toString() }),
                        baseKeys
                    )

                return interner.intern(
                    AspectKeyWithBaseAspects(
                        baseConfiguredTargetKey,
                        sortedBaseKeys,
                        aspectDescriptor,
                        HashCodes.hashObjects(baseConfiguredTargetKey, sortedBaseKeys, aspectDescriptor)
                    )
                )
            }
        }
    }

    /**
     * The key for top level aspects specified by --aspects option and their parameters specified by
     * --aspects_parameters applied on a top level target.
     */
    @AutoCodec
    class TopLevelAspectsKey private constructor(
        topLevelAspectsClasses: com.google.common.collect.ImmutableList<AspectClass?>?,
        targetLabel: Label?,
        baseConfiguredTargetKey: ConfiguredTargetKey?,
        topLevelAspectsParameters: com.google.common.collect.ImmutableMap<String?, String?>?,
        hashCode: Int
    ) : AspectBaseKey(baseConfiguredTargetKey, hashCode) {
        private val topLevelAspectsClasses: com.google.common.collect.ImmutableList<AspectClass?>?
        private val targetLabel: Label?
        private val topLevelAspectsParameters: com.google.common.collect.ImmutableMap<String?, String?>?

        init {
            this.topLevelAspectsClasses = topLevelAspectsClasses
            this.targetLabel = targetLabel
            this.topLevelAspectsParameters = topLevelAspectsParameters
        }

        public override fun functionName(): SkyFunctionName {
            return SkyFunctions.TOP_LEVEL_ASPECTS
        }

        val configurationKey: BuildConfigurationKey?
            get() = getBaseConfiguredTargetKey().getConfigurationKey()

        fun getTopLevelAspectsClasses(): com.google.common.collect.ImmutableList<AspectClass?>? {
            return topLevelAspectsClasses
        }

        fun getTopLevelAspectsParameters(): com.google.common.collect.ImmutableMap<String?, String?>? {
            return topLevelAspectsParameters
        }

        val label: Label?
            get() = targetLabel

        val description: String?
            get() = java.lang.String.format(
                "%s with parameters %s on %s",
                topLevelAspectsClasses, topLevelAspectsParameters, targetLabel
            )

        override fun equals(o: Any?): Boolean {
            if (o === this) {
                return true
            }
            if (o !is TopLevelAspectsKey) {
                return false
            }

            return hashCode() == o.hashCode() && targetLabel == o.targetLabel
                    && getBaseConfiguredTargetKey() == o.getBaseConfiguredTargetKey()
                    && topLevelAspectsClasses == o.topLevelAspectsClasses
                    && topLevelAspectsParameters == o.topLevelAspectsParameters
        }

        val skyKeyInterner: SkyKeyInterner<TopLevelAspectsKey?>
            get() = interner

        companion object {
            private val interner: SkyKeyInterner<TopLevelAspectsKey?> = SkyKey.newInterner<SkyKey?>()

            @AutoCodec.Instantiator
            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            fun createInternal(
                topLevelAspectsClasses: com.google.common.collect.ImmutableList<AspectClass?>?,
                targetLabel: Label?,
                baseConfiguredTargetKey: ConfiguredTargetKey?,
                topLevelAspectsParameters: com.google.common.collect.ImmutableMap<String?, String?>?
            ): TopLevelAspectsKey {
                return interner.intern(
                    TopLevelAspectsKey(
                        topLevelAspectsClasses,
                        targetLabel,
                        baseConfiguredTargetKey,
                        topLevelAspectsParameters,
                        HashCodes.hashObjects(
                            topLevelAspectsClasses,
                            targetLabel,
                            baseConfiguredTargetKey,
                            topLevelAspectsParameters
                        )
                    )
                )
            }
        }
    }
}
