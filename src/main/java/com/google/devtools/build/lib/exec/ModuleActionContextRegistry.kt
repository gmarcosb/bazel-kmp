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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.ActionContext

/**
 * Registry containing all available [action contexts][ActionContext].
 * 
 * 
 * Contexts can be [queried][.getContext] by a common subtype of [ActionContext]
 * that they implement (which can be the implementation class itself). It is possible to [ ][Builder.restrictTo]
 */
class ModuleActionContextRegistry
private constructor(identifyingTypeToContext: com.google.common.collect.ImmutableClassToInstanceMap<ActionContext?>) :
    ActionContext, ActionContext.ActionContextRegistry {
    private val identifyingTypeToContext: com.google.common.collect.ImmutableClassToInstanceMap<ActionContext?>

    init {
        this.identifyingTypeToContext = identifyingTypeToContext
    }

    public override fun <T : ActionContext?> getContext(identifyingType: java.lang.Class<T?>): T? {
        return identifyingTypeToContext.getInstance<T?>(identifyingType)
    }

    /**
     * Notifies all contexts stored in this registry that they are [ ][ActionContext.usedContext].
     */
    fun notifyUsed() {
        for (context in identifyingTypeToContext.values()) {
            context.usedContext(this)
        }
    }

    /**
     * Records the list of all contexts that can be [returned by this registry][.getContext]
     * to the given reporter.
     */
    fun logActionContexts() {
        for (typeToContext in identifyingTypeToContext.entrySet()) {
            logger.atInfo().log(
                "IdentifyingTypeToContext: \"%s\" = [%s]",
                typeToContext.getKey(), typeToContext.getValue().getClass().getSimpleName()
            )
        }
    }

    /**
     * Builder collecting the contexts and restrictions thereon for a [ ].
     */
    class Builder {
        private val actionContexts: MutableList<ActionContextInformation<*>> =
            java.util.ArrayList<ActionContextInformation<*>>()
        private val typeToRestriction: MutableMap<java.lang.Class<*>?, String> = HashMap<java.lang.Class<*>?, String>()

        /**
         * Restricts the registry to only return implementations for the given type if they were
         * [registered][.register] with the provided restriction as a command-line identifier.
         * 
         * 
         * Note that if no registered action context matches the requested command-line identifiers
         * when it is [built][.build] then the registry will return `null` when
         * queried for this identifying type.
         * 
         * 
         * This behavior can be reset by passing an empty restriction to this method which will cause
         * the default behavior (last implementation registered for the identifying type) to be used.
         * 
         * @param restriction command-line identifier used during registration of the desired
         * implementation or `""` to allow any implementation of the identifying type
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun restrictTo(identifyingType: java.lang.Class<*>?, restriction: String?): Builder {
            typeToRestriction.put(identifyingType, restriction!!)
            return this
        }

        /**
         * Registers an action context implementation identified by the given type and which can be
         * [restricted][.restrictTo] by its provided command-line identifiers.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun <T : ActionContext?> register(
            identifyingType: java.lang.Class<T?>?, context: T?, vararg commandLineIdentifiers: String?
        ): Builder {
            actionContexts.add(
                ActionContextInformation<T?>(
                    context,
                    identifyingType,
                    com.google.common.collect.ImmutableList.copyOf<String?>(commandLineIdentifiers)
                )
            )
            return this
        }

        /** Constructs the registry configured by this builder.  */
        @Throws(AbruptExitException::class)
        fun build(): ModuleActionContextRegistry {
            val usedTypes: HashSet<java.lang.Class<*>?> = HashSet<java.lang.Class<*>?>()
            val contextToInstance: com.google.common.collect.MutableClassToInstanceMap<ActionContext?> =
                com.google.common.collect.MutableClassToInstanceMap.create<ActionContext?>()
            for (actionContextInformation in actionContexts) {
                val identifyingType: java.lang.Class<out ActionContext?>? = actionContextInformation.identifyingType
                if (typeToRestriction.containsKey(identifyingType)) {
                    val restriction: String = typeToRestriction.get(identifyingType)!!
                    if (!actionContextInformation.commandLineIdentifiers.contains(restriction)
                        && !restriction.isEmpty()
                    ) {
                        continue
                    }
                }
                usedTypes.add(identifyingType)
                actionContextInformation.addToMap(contextToInstance)
            }

            val unusedRestrictions: com.google.common.collect.Sets.SetView<java.lang.Class<*>?> =
                com.google.common.collect.Sets.difference<java.lang.Class<*>?>(typeToRestriction.keySet(), usedTypes)
            if (!unusedRestrictions.isEmpty()) {
                throw AbruptExitException(
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setMessage(getMissingIdentifierErrorMessage(unusedRestrictions))
                            .setExecutionOptions(
                                FailureDetails.ExecutionOptions.newBuilder()
                                    .setCode(Code.RESTRICTION_UNMATCHED_TO_ACTION_CONTEXT)
                            )
                            .build()
                    )
                )
            }

            return ModuleActionContextRegistry(
                com.google.common.collect.ImmutableClassToInstanceMap.copyOf<ActionContext?, ActionContext?>(
                    contextToInstance
                )
            )
        }

        private fun getMissingIdentifierErrorMessage(unusedRestrictions: com.google.common.collect.Sets.SetView<java.lang.Class<*>?>): String {
            val typeToAvailableIdentifiers: com.google.common.collect.Multimap<java.lang.Class<*>?, String?> =
                com.google.common.collect.ArrayListMultimap.create<java.lang.Class<*>?, String?>()
            for (type in unusedRestrictions) {
                for (actionContextInformation in actionContexts) {
                    if (actionContextInformation.identifyingType == type) {
                        typeToAvailableIdentifiers.putAll(
                            type, actionContextInformation.commandLineIdentifiers
                        )
                    }
                }
            }
            val message: java.lang.StringBuilder = java.lang.StringBuilder()
            for (typeToIdentifiers in typeToAvailableIdentifiers.asMap().entrySet()) {
                val type: java.lang.Class<*> = typeToIdentifiers.getKey()
                message.append(
                    java.lang.String.format(
                        "No context of type %s registered for requested value '%s', available identifiers"
                                + " are: [%s]%n",
                        type.getSimpleName(),
                        typeToRestriction.get(type),
                        com.google.common.base.Joiner.on(", ").join(typeToIdentifiers.getValue())
                    )
                )
            }
            message.append("unused ").append(unusedRestrictions)
            return message.toString()
        }
    }

    internal class ActionContextInformation<T : ActionContext?>(
        context: T?,
        identifyingType: java.lang.Class<T?>?,
        commandLineIdentifiers: com.google.common.collect.ImmutableList<String?>?
    ) {
        private fun addToMap(map: com.google.common.collect.MutableClassToInstanceMap<ActionContext?>) {
            map.putInstance<T?>(this.identifyingType, this.context)
        }

        val context: T?
        val identifyingType: java.lang.Class<T?>?
        val commandLineIdentifiers: com.google.common.collect.ImmutableList<String?>?

        init {
            this.commandLineIdentifiers = commandLineIdentifiers
            this.identifyingType = identifyingType
            this.context = context
            java.util.Objects.requireNonNull<T?>(context, "context")
            java.util.Objects.requireNonNull<java.lang.Class<T?>?>(identifyingType, "identifyingType")
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<String?>?>(
                commandLineIdentifiers,
                "commandLineIdentifiers"
            )
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /**
         * Returns a new [Builder] suitable for creating instances of ModuleActionContextRegistry.
         */
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return com.google.devtools.build.lib.exec.ModuleActionContextRegistry.Builder()
        }
    }
}
