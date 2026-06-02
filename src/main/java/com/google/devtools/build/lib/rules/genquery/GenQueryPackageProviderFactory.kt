// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.genquery

import com.google.devtools.build.lib.cmdline.Label

/**
 * Factory for [GenQueryPackageProvider] which directly relies on [ ] Skyframe data to collect required
 * information.
 */
object GenQueryPackageProviderFactory {
    @kotlin.jvm.JvmField
    val GENQUERY_SCOPE: SkyFunctionName = SkyFunctionName.createHermetic("GENQUERY_SCOPE")

    @kotlin.jvm.JvmField
    val FUNCTION: SkyFunction = Function()

    @Throws(java.lang.InterruptedException::class, BrokenQueryScopeException::class)
    fun constructPackageMap(
        env: SkyFunction.Environment, scope: com.google.common.collect.ImmutableList<Label?>
    ): GenQueryPackageProvider? {
        val value: SkyValue? = env.getValueOrThrow<BrokenQueryScopeException?>(
            com.google.devtools.build.lib.rules.genquery.GenQueryPackageProviderFactory.Key.Companion.create(scope),
            BrokenQueryScopeException::class.java
        )
        if (value == null) {
            return null
        }
        return (value as Value).genQueryPackageProvider
    }

    @Throws(java.lang.InterruptedException::class, BrokenQueryScopeException::class)
    private fun constructPackageMapImpl(
        env: SkyFunction.Environment, scope: com.google.common.collect.ImmutableList<Label?>
    ): GenQueryPackageProvider? {
        val computeState: ClassToInstanceMapSkyKeyComputeState =
            env.getState<ClassToInstanceMapSkyKeyComputeState>(java.util.function.Supplier { ClassToInstanceMapSkyKeyComputeState() })
        val mail: Mail = PartialReevaluationMailbox.from(computeState).getMail()
        val traversal: ScopeTraversal = computeState.getInstance<ScopeTraversal>(
            ScopeTraversal::class.java,
            java.util.function.Supplier { ScopeTraversal() })

        var labelsToVisit: LinkedHashSet<Label>? = null
        when (mail.kind()) {
            com.google.devtools.build.skyframe.PartialReevaluationMailbox.Kind.FRESHLY_INITIALIZED -> {
                // First evaluation, or, Skyframe compute state lost due to memory pressure or errors.
                // Either way, start from scratch.
                com.google.common.base.Preconditions.checkState(
                    traversal.collectedPackages.isEmpty(),
                    "expected empty collectedPackages"
                )
                com.google.common.base.Preconditions.checkState(
                    traversal.collectedTargets.isEmpty(),
                    "expected empty collectedTargets"
                )
                com.google.common.base.Preconditions.checkState(
                    traversal.labelsToVisitInLaterRestart.isEmpty(),
                    "expected empty labelsToVisitInLaterRestart"
                )
                com.google.common.base.Preconditions.checkState(
                    traversal.labelsToVisitInverse.isEmpty(),
                    "expected empty labelsToVisitInverse"
                )
                labelsToVisit = LinkedHashSet<Label>(scope)
            }

            com.google.devtools.build.skyframe.PartialReevaluationMailbox.Kind.CAUSES -> {
                val causes: Causes = mail.causes()
                if (causes.other) {
                    labelsToVisit = LinkedHashSet<Label>(traversal.labelsToVisitInLaterRestart.keySet())
                    traversal.labelsToVisitInLaterRestart.clear()
                    traversal.labelsToVisitInverse.clear()
                } else {
                    labelsToVisit = LinkedHashSet<Label>()
                    for (signaledDep in causes.signaledDeps) {
                        val labels: MutableCollection<Label?>? =
                            traversal.labelsToVisitInverse.asMap().remove(signaledDep)
                        // We may have been signaled by a dep whose value was observed during a previous
                        // restart; if so, then skip it because there is no work to do for it.
                        if (labels != null) {
                            for (label in labels) {
                                traversal.labelsToVisitInLaterRestart.remove(label)
                                labelsToVisit.add(label)
                            }
                        }
                    }
                }
            }

            com.google.devtools.build.skyframe.PartialReevaluationMailbox.Kind.EMPTY ->         // This reevaluation may have been triggered by a dep which completed after our previous
                // reevaluation started; another reevaluation gets scheduled in such a case.
                //
                // Adding that dep's key to our mailbox raced with our reading our mailbox in that previous
                // reevaluation. If the add won, then we consumed the key last time, and our mailbox may now
                // be empty. If so, then there's no work to do now, so we return.
                return null
        }

        // Constructing these here minimizes garbage creation. They're used in dep traversals below.
        val attrDepConsumer: LabelProcessor? =
            object : LabelProcessor() {
                var nextLabelsToVisitRef: LinkedHashSet<Label>? = null

                var keyForAttrDepNeedingRestart: SkyKey? = null
                var attrDepUnvisited: Boolean = false
                var hasAspects: Boolean = false
                var transitions: com.google.common.collect.HashMultimap<Attribute, Label?>? = null

                public override fun process(from: Target?, attribute: Attribute?, to: Label?) {
                    if (hasAspects && keyForAttrDepNeedingRestart == null) {
                        val skyKey: SkyKey? = traversal.labelsToVisitInLaterRestart.get(to)
                        if (skyKey != null) {
                            keyForAttrDepNeedingRestart = skyKey
                            return
                        }
                    }
                    if (!traversal.collectedTargets.containsKey(to)) {
                        attrDepUnvisited = true
                        nextLabelsToVisitRef.add(to)
                        return
                    }

                    if (hasAspects
                        && keyForAttrDepNeedingRestart == null && !attrDepUnvisited && attribute != null && DependencyFilter.NO_NODEP_ATTRIBUTES.test(
                            from as Rule?,
                            attribute
                        )
                    ) {
                        transitions.put(attribute, to)
                    }
                }
            }

        val aspectDepConsumer: java.util.function.BiConsumer<Attribute?, Label?>? =
            object : java.util.function.BiConsumer<Attribute?, Label?> {
                var nextLabelsToVisitRef: LinkedHashSet<Label>? = null

                override fun accept(aspectAttribute: Attribute?, aspectLabel: Label?) {
                    if (!traversal.collectedTargets.containsKey(aspectLabel)) {
                        nextLabelsToVisitRef.add(aspectLabel)
                    }
                }
            }

        while (!labelsToVisit.isEmpty()) {
            val nextLabelsToVisit: LinkedHashSet<Label> = LinkedHashSet<Label>()
            attrDepConsumer.nextLabelsToVisitRef = nextLabelsToVisit
            aspectDepConsumer.nextLabelsToVisitRef = nextLabelsToVisit
            for (label in labelsToVisit) {
                // If this is the first time label is visited, then collectedTargets will not contain an
                // entry for it. The else branch will do one of three things:
                // 1) discover that there is a problem with the label's package. If so, this throws
                //    BrokenQueryScopeException to stop this genquery evaluation.
                // 2) discover that needed package information has not been computed by Skyframe. If so,
                //    this records that label must be visited in a later Skyframe restart by adding it
                //    to labelsToVisitInLaterRestart; at that time that package information will have been
                //    computed.
                // 3) use the package information already computed by Skyframe to collect the label's target
                //    and package.
                //
                // Labels may be visited a second time. This happens if at least one of the label's target
                // is a rule with aspects and its dependency attributes' labels hadn't been visited when the
                // label was first visited. Note that this is the typical case for such rules! This code
                // ensures that all of a rule's dependency attributes' labels are visited at least once
                // before its label is visited a second time.
                //
                // If a rule's dependency attributes' labels have all already been visited (which may
                // occur the first time a label is visited, but is guaranteed to occur if it's visited a
                // second time) then:
                // 1) if all those dependency attributes' labels' targets have been collected, then this
                //    code will enqueue the rule's aspect dependencies' labels for visitation.
                // 2) otherwise, at least one of those dependency attributes' labels must have been added to
                //    labelsToVisitInLaterRestart, so the rule's aspect dependencies can't be computed
                //    during this Skyframe restart, so the rule's label also must be visited in a later
                //    Skyframe restart.

                var target: Target? = traversal.collectedTargets.get(label)
                if (target == null) {
                    try {
                        val o: Any? = TargetLoadingUtil.loadTarget(env, label)
                        if (o is TargetAndErrorIfAny) {
                            if (!o.isPackageLoadedSuccessfully()) {
                                throw BrokenQueryScopeException.Companion.of(o.getErrorLoadingTarget())
                            }

                            target = o.getTarget()
                            traversal.collectedTargets.put(label, target)
                            traversal.collectedPackages.put(
                                label.getPackageIdentifier(), o.getPackage()
                            )
                        } else {
                            val missingKey: SkyKey? = o as SkyKey?
                            traversal.labelsToVisitInLaterRestart.put(label, missingKey)
                            traversal.labelsToVisitInverse.put(missingKey, label)
                            continue
                        }
                    } catch (e: NoSuchTargetException) {
                        throw BrokenQueryScopeException.Companion.of(e)
                    } catch (e: NoSuchPackageException) {
                        throw BrokenQueryScopeException.Companion.of(e)
                    }
                }

                attrDepConsumer.keyForAttrDepNeedingRestart = null
                attrDepConsumer.attrDepUnvisited = false
                attrDepConsumer.hasAspects = target is Rule && (target as Rule).hasAspects()
                attrDepConsumer.transitions =
                    if (attrDepConsumer.hasAspects) com.google.common.collect.HashMultimap.create<Attribute?, Label?>() else null
                LabelVisitationUtils.visitTarget(
                    target, DependencyFilter.NO_NODEP_ATTRIBUTES_EXCEPT_VISIBILITY, attrDepConsumer
                )

                if (!attrDepConsumer.hasAspects) {
                    continue
                }

                if (attrDepConsumer.keyForAttrDepNeedingRestart != null) {
                    traversal.labelsToVisitInLaterRestart.put(
                        label, attrDepConsumer.keyForAttrDepNeedingRestart
                    )
                    traversal.labelsToVisitInverse.put(attrDepConsumer.keyForAttrDepNeedingRestart, label)
                    continue
                } else if (attrDepConsumer.attrDepUnvisited) {
                    // This schedules label to be visited a second time during this Skyframe restart. Because
                    // the loop above scheduled its unvisited attribute deps for visitation, and
                    // nextLabelsToVisit preserves insertion order, when label is visited a second time,
                    // attributeDepUnvisited will be false, and its aspect deps will be computable.
                    nextLabelsToVisit.add(label)
                    continue
                }

                val rule: Rule = target as Rule
                for (attribute in attrDepConsumer.transitions.keySet()) {
                    for (aspect in attribute.getAspects(rule)) {
                        if (hasDepThatSatisfies(
                                rule,
                                aspect,
                                attrDepConsumer.transitions.get(attribute),
                                traversal.collectedTargets
                            )
                        ) {
                            AspectDefinition.forEachLabelDepFromAllAttributesOfAspect(
                                aspect, DependencyFilter.ALL_DEPS, aspectDepConsumer
                            )
                        }
                    }
                }
            }
            labelsToVisit = nextLabelsToVisit
        }
        if (env.valuesMissing() || !traversal.labelsToVisitInLaterRestart.isEmpty()) {
            return null
        }

        return GenQueryPackageProvider(
            com.google.common.collect.ImmutableMap.copyOf<PackageIdentifier?, Package?>(traversal.collectedPackages),
            com.google.common.collect.ImmutableMap.copyOf<Label?, Target?>(traversal.collectedTargets)
        )
    }

    private fun hasDepThatSatisfies(
        fromRule: Rule, aspect: Aspect?, toLabels: Iterable<Label?>, targets: MutableMap<Label?, Target?>
    ): Boolean {
        for (toLabel in toLabels) {
            val toTarget: Target =
                com.google.common.base.Preconditions.checkNotNull(
                    targets.get(toLabel),
                    "%s dep %s should have been visited but was not",
                    fromRule.getLabel(),
                    toLabel
                )
            val advertisedProviderSet: AdvertisedProviderSet? =
                if (toTarget is Rule) toTarget.getRuleClassObject().getAdvertisedProviders() else null
            if (advertisedProviderSet != null
                && AspectDefinition.satisfies(aspect, advertisedProviderSet)
            ) {
                return true
            }
        }
        return false
    }

    /**
     * It can be common, due to macro expansion, that several genquery rules share the same value for
     * their scope attribute. By doing scope traversal as its own Skyframe node, a set of genquery
     * rules sharing the same scope will require only one scope traversal to occur.
     */
    @AutoCodec
    class Key private constructor(arg: com.google.common.collect.ImmutableList<Label?>) :
        WithCachedHashCode<com.google.common.collect.ImmutableList<Label?>?>(
            com.google.common.collect.ImmutableList.sortedCopyOf<E?>(arg)
        ) {
        override fun functionName(): SkyFunctionName {
            return GENQUERY_SCOPE
        }

        override fun supportsPartialReevaluation(): Boolean {
            return true
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.rules.genquery.GenQueryPackageProviderFactory.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Instantiator
            fun create(arg: com.google.common.collect.ImmutableList<Label?>): Key {
                return com.google.devtools.build.lib.rules.genquery.GenQueryPackageProviderFactory.Key.Companion.interner.intern(
                    Key(arg)
                )
            }
        }
    }

    private class Value(genQueryPackageProvider: GenQueryPackageProvider?) : SkyValue {
        private val genQueryPackageProvider: GenQueryPackageProvider?

        init {
            this.genQueryPackageProvider = genQueryPackageProvider
        }
    }

    private class Function : SkyFunction {
        @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
        override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
            val scope: com.google.common.collect.ImmutableList<Label?> =
                skyKey.argument() as com.google.common.collect.ImmutableList<Label?>

            val provider: GenQueryPackageProvider?
            try {
                provider = constructPackageMapImpl(env, scope)
            } catch (e: BrokenQueryScopeException) {
                throw BrokenQueryScopeSkyFunctionException(e, Transience.PERSISTENT)
            }
            if (provider == null) {
                return null
            }
            return Value(provider)
        }
    }

    private class BrokenQueryScopeSkyFunctionException(cause: BrokenQueryScopeException?, transience: Transience?) :
        SkyFunctionException(cause, transience)

    /**
     * This factory's strategy relies on Skyframe "state" to prevent redundant work from being done
     * across Skyframe restarts.
     * 
     * 
     * The `collectedPackages` and `collectedTargets` fields are populated by [ ][.constructPackageMap]'s target dependency traversal, until `collectedTargets` contains
     * the transitive closure of the specified `scope` and `collectedPackages` contains
     * (at least; see [0] below) all the packages for the targets in `collectedTargets`.
     * 
     * 
     * ([0] In the future, `collectedPackages` might also contain packages needed to evaluate
     * "buildfiles" functions; see b/123795023.)
     * 
     * 
     * The `labelsToVisitInLaterRestart` field contains labels of targets belonging to
     * previously unloaded packages, the "frontier" of the last Skyframe evaluation attempt's
     * traversal.
     */
    private class ScopeTraversal : SkyKeyComputeState {
        // TODO(https://github.com/bazelbuild/bazel/issues/23852): support lazy macro expansion
        private val collectedPackages: LinkedHashMap<PackageIdentifier?, Package?> =
            LinkedHashMap<PackageIdentifier?, Package?>()
        private val collectedTargets: LinkedHashMap<Label?, Target?> = LinkedHashMap<Label?, Target?>()

        private val labelsToVisitInLaterRestart: LinkedHashMap<Label?, SkyKey?> = LinkedHashMap<Label?, SkyKey?>()
        private val labelsToVisitInverse: com.google.common.collect.LinkedHashMultimap<SkyKey?, Label?> =
            com.google.common.collect.LinkedHashMultimap.create<SkyKey?, Label?>()
    }

    internal class BrokenQueryScopeException : java.lang.Exception {
        private constructor() : super("errors were encountered while computing transitive closure of the scope")

        private constructor(cause: NoSuchThingException) : super(
            "errors were encountered while computing transitive closure of the scope: "
                    + cause.getMessage(),
            cause
        )

        companion object {
            fun of(cause: NoSuchThingException?): BrokenQueryScopeException {
                return if (cause == null) BrokenQueryScopeException() else BrokenQueryScopeException(cause)
            }
        }
    }
}
