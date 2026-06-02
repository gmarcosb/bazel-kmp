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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.ActionLookupKey

/** Reports cycles between skyframe values whose keys contains [Label]s.  */
abstract class AbstractLabelCycleReporter internal constructor(packageProvider: PackageProvider) : SingleCycleReporter {
    private val packageProvider: PackageProvider

    init {
        this.packageProvider = packageProvider
    }

    /** Returns the associated Label of the SkyKey.  */
    protected abstract fun getLabel(key: SkyKey?): Label?

    protected abstract fun canReportCycle(topLevelKey: SkyKey?, cycleInfo: CycleInfo?): Boolean

    /** Returns the String representation of the `SkyKey`.  */
    protected open fun prettyPrint(rawKey: Any?): String {
        if (rawKey is ActionLookupKey) {
            return rawKey.getLabel().toString()
        }
        return getLabel(rawKey as SkyKey?).toString()
    }

    /** Can be used to skip individual keys on the path to the cycle.  */
    protected open fun shouldSkipOnPathToCycle(key: SkyKey?): Boolean {
        return false
    }

    /** Can be used to skip intermediate keys on the cycle itself.  */
    protected open fun shouldSkipIntermediateKeyOnCycle(key: SkyKey?): Boolean {
        return false
    }

    /**
     * Can be used to report an additional message about the cycle.
     * 
     * @param eventHandler
     * @param topLevelKey
     * @param cycleInfo
     */
    protected open fun getAdditionalMessageAboutCycle(
        eventHandler: ExtendedEventHandler?, topLevelKey: SkyKey?, cycleInfo: CycleInfo?
    ): String? {
        return ""
    }

    override fun maybeReportCycle(
        topLevelKey: SkyKey?,
        cycleInfo: CycleInfo,
        alreadyReported: Boolean,
        eventHandler: ExtendedEventHandler?
    ): Boolean {
        com.google.common.base.Preconditions.checkNotNull<Any?>(eventHandler)
        if (!canReportCycle(topLevelKey, cycleInfo)) {
            return false
        }

        if (alreadyReported) {
            if (!shouldSkipOnPathToCycle(topLevelKey)) {
                val label: Label? = getLabel(topLevelKey)
                val target = getTargetForLabel(eventHandler, label)
                eventHandler.handle(
                    Event.error(
                        target.getLocation(),
                        ("in "
                                + target.getTargetKind()
                                + " "
                                + label
                                + ": cycle in dependency graph: target depends on an already-reported cycle")
                    )
                )
            }
        } else {
            val cycleMessage: java.lang.StringBuilder = java.lang.StringBuilder("cycle in dependency graph:")
            val pathToCycle: com.google.common.collect.ImmutableList<SkyKey?> = cycleInfo.getPathToCycle()
            val cycle: com.google.common.collect.ImmutableList<SkyKey?> = cycleInfo.getCycle()
            for (value in pathToCycle) {
                if (shouldSkipOnPathToCycle(value)) {
                    continue
                }
                cycleMessage.append("\n    ")
                cycleMessage.append(prettyPrint(value))
            }

            val cycleValue: SkyKey? =
                printCycle(
                    cycle,
                    cycleMessage,
                    com.google.common.base.Function { rawKey: Any? -> this.prettyPrint(rawKey) },
                    java.util.function.Predicate { key: SkyKey? -> this.shouldSkipIntermediateKeyOnCycle(key) })

            cycleMessage.append(getAdditionalMessageAboutCycle(eventHandler, topLevelKey, cycleInfo))

            val label: Label? = getLabel(cycleValue)
            val target = getTargetForLabel(eventHandler, label)
            eventHandler.handle(
                Event.error(
                    target.getLocation(),
                    "in " + target.getTargetKind() + " " + label + ": " + cycleMessage
                )
            )
        }

        return true
    }

    protected fun getTargetForLabel(
        eventHandler: ExtendedEventHandler?, label: Label?
    ): Target {
        try {
            return Uninterruptibles.callUninterruptibly(object : java.util.concurrent.Callable<Target?> {
                @Throws(
                    NoSuchPackageException::class,
                    NoSuchTargetException::class,
                    java.lang.InterruptedException::class
                )
                override fun call(): Target {
                    return packageProvider.getTarget(eventHandler, label)
                }
            })
        } catch (e: NoSuchThingException) {
            // This method is used for getting the target from a label in a circular dependency.
            // If we have a cycle that means that we need to have accessed the target (to get its
            // dependencies). So all the labels in a dependency cycle need to exist.
            throw java.lang.IllegalStateException(e)
        } catch (e: java.lang.Exception) {
            throw java.lang.IllegalStateException(e)
        }
    }

    companion object {
        /** Prints the SkyKey-s in cycle into cycleMessage using the print function.  */
        fun printCycle(
            cycle: com.google.common.collect.ImmutableList<SkyKey?>,
            cycleMessage: java.lang.StringBuilder,
            printFunction: com.google.common.base.Function<Any?, String?>
        ): SkyKey? {
            return printCycle(
                cycle,
                cycleMessage,
                printFunction,
                com.google.common.base.Predicates.alwaysFalse<SkyKey?>()
            )
        }

        private fun printCycle(
            cycle: com.google.common.collect.ImmutableList<SkyKey?>,
            cycleMessage: java.lang.StringBuilder,
            printFunction: com.google.common.base.Function<Any?, String?>,
            shouldSkipIntermediateKey: java.util.function.Predicate<SkyKey?>
        ): SkyKey? {
            com.google.common.base.Preconditions.checkArgument(!cycle.isEmpty())
            var cycleValue: SkyKey? = null
            var valuesPrinted = 0
            for (value in com.google.common.collect.Iterables.concat<SkyKey?>(
                cycle,
                com.google.common.collect.ImmutableList.of<SkyKey?>(cycle.get(0))
            )) {
                if (cycleValue == null) { // first item
                    cycleValue = value
                    cycleMessage.append("\n.-> ")
                } else if (value === cycleValue) { // last item of the cycle
                    if (valuesPrinted == 1) {
                        cycleMessage.append(" [self-edge]")
                        cycleMessage.append("\n`--")
                        break
                    } else {
                        cycleMessage.append("\n`-- ")
                    }
                } else if (shouldSkipIntermediateKey.test(value)) {
                    continue
                } else {
                    cycleMessage.append("\n|   ")
                }
                cycleMessage.append(printFunction.apply(value))
                valuesPrinted++
            }

            return cycleValue
        }
    }
}
