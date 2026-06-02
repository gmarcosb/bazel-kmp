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
package com.google.devtools.common.options

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import java.util.HashMap
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/**
 * A selection of options data corresponding to a set of [OptionsBase] subclasses (options
 * classes). The data is collected using reflection, which can be expensive. Therefore this class
 * can be used internally to cache the results.
 * 
 * 
 * The data is isolated in the sense that it has not yet been processed to add
 * inter-option-dependent information -- namely, the results of evaluating expansion functions. The
 * [OptionsData] subclass stores this added information. The reason for the split is so that
 * we can avoid exposing to expansion functions the effects of evaluating other expansion functions,
 * to ensure that the order in which they run is not significant.
 * 
 * 
 * This class is immutable so long as the converters and default values associated with the
 * options are immutable.
 */
// TODO(b/159980134): Can this be folded into OptionsData?
@javax.annotation.concurrent.Immutable
open class IsolatedOptionsData private constructor(
    optionsClasses: MutableMap<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?, java.lang.reflect.Constructor<*>>,
    primaryOptionsClasses: com.google.common.collect.ImmutableList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>?,
    nameToField: MutableMap<String?, com.google.devtools.common.options.OptionDefinition>,
    oldNameToField: MutableMap<String?, com.google.devtools.common.options.OptionDefinition>,
    abbrevToField: MutableMap<Char?, com.google.devtools.common.options.OptionDefinition>
) : com.google.devtools.common.options.OpaqueOptionsData() {
    /**
     * A little class whose only virtue is that it has a constructor which can be used to mark cases
     * where it's ambiguous which subclass should be instantiated for a given options base class.
     */
    private class AmbiguousClassMarker {
        init {
            throw java.lang.IllegalStateException()
        }
    }

    /**
     * Mapping from each options class to its no-arg constructor. Entries appear in the same order
     * that they were passed to [.from].
     */
    private val optionsClasses: com.google.common.collect.ImmutableMap<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?, java.lang.reflect.Constructor<*>>

    /**
     * The list of options classes that were passed to the constructor. This is used to return the
     * options classes in the order they were provided, and avoids returning superclasses of
     * registered classes, which would lead to duplicate options in help messages.
     */
    private val primaryOptionsClasses: com.google.common.collect.ImmutableList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>?

    /**
     * Mapping from option name to `OptionDefinition`. Entries appear ordered first by their
     * options class (the order in which they were passed to [.from], and
     * then in alphabetic order within each options class.
     */
    private val nameToField: com.google.common.collect.ImmutableMap<String?, com.google.devtools.common.options.OptionDefinition>

    /**
     * For options that have an "OldName", this is a mapping from old name to its corresponding `OptionDefinition`. Entries appear ordered first by their options class (the order in which they
     * were passed to [.from], and then in alphabetic order within each
     * options class.
     */
    private val oldNameToField: com.google.common.collect.ImmutableMap<String?, com.google.devtools.common.options.OptionDefinition>

    /** Mapping from option abbreviation to `OptionDefinition` (unordered).  */
    private val abbrevToField: com.google.common.collect.ImmutableMap<Char?, com.google.devtools.common.options.OptionDefinition>

    init {
        this.optionsClasses =
            com.google.common.collect.ImmutableMap.copyOf<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?, java.lang.reflect.Constructor<*>?>(
                optionsClasses
            )
        this.primaryOptionsClasses = primaryOptionsClasses
        this.nameToField =
            com.google.common.collect.ImmutableMap.copyOf<String?, com.google.devtools.common.options.OptionDefinition?>(
                nameToField
            )
        this.oldNameToField =
            com.google.common.collect.ImmutableMap.copyOf<String?, com.google.devtools.common.options.OptionDefinition?>(
                oldNameToField
            )
        this.abbrevToField =
            com.google.common.collect.ImmutableMap.copyOf<Char?, com.google.devtools.common.options.OptionDefinition?>(
                abbrevToField
            )
    }

    protected constructor(other: IsolatedOptionsData) : this(
        other.optionsClasses,
        other.primaryOptionsClasses,
        other.nameToField,
        other.oldNameToField,
        other.abbrevToField
    )

    /**
     * Returns all options classes indexed by this options data object, in the order they were passed
     * to [.from].
     */
    fun getOptionsClasses(): MutableCollection<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>? {
        return primaryOptionsClasses
    }

    // The construction ensures that the case is always valid.
    fun <T : com.google.devtools.common.options.OptionsBase?> getConstructor(clazz: java.lang.Class<T?>?): java.lang.reflect.Constructor<T?>? {
        return optionsClasses.get(clazz) as java.lang.reflect.Constructor<T?>?
    }

    /**
     * Returns the option in this parser by the provided name, or `null` if none is found. This
     * will match both the canonical name of an option, and any old name listed that we still accept.
     */
    fun getOptionDefinitionFromName(name: String?): com.google.devtools.common.options.OptionDefinition? {
        return nameToField.getOrDefault(name, oldNameToField.get(name))
    }

    /**
     * Returns all [OptionDefinition] objects loaded, mapped by their canonical names. Entries
     * appear ordered first by their options class (the order in which they were passed to [ ][.from], and then in alphabetic order within each options class.
     */
    fun getAllOptionDefinitions(): com.google.common.collect.ImmutableSet<MutableMap.MutableEntry<String?, com.google.devtools.common.options.OptionDefinition?>?> {
        return nameToField.entrySet()
    }

    fun getFieldForAbbrev(abbrev: Char): com.google.devtools.common.options.OptionDefinition? {
        return abbrevToField.get(abbrev)
    }

    companion object {
        private val AMBIGUOUS_MARKER_CTOR: java.lang.reflect.Constructor<*>?

        init {
            try {
                com.google.devtools.common.options.IsolatedOptionsData.Companion.AMBIGUOUS_MARKER_CTOR =
                    com.google.devtools.common.options.IsolatedOptionsData.AmbiguousClassMarker::class.java.getConstructor()
            } catch (e: java.lang.NoSuchMethodException) {
                throw java.lang.IllegalStateException(e)
            }
        }

        /**
         * Cache for the options in an OptionsBase.
         * 
         * 
         * Mapping from options class to a list of all `OptionFields` in that class. The map
         * entries are unordered, but the fields in the lists are ordered alphabetically. This caches the
         * work of reflection done for the same `optionsBase` across multiple [OptionsData]
         * instances, and must be used through the thread safe [ ][.getAllOptionDefinitionsForClass]
         */
        private val allOptionsDefinitions: ConcurrentMap<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?, com.google.common.collect.ImmutableList<com.google.devtools.common.options.OptionDefinition?>> =
            ConcurrentHashMap<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?, com.google.common.collect.ImmutableList<com.google.devtools.common.options.OptionDefinition?>>()

        /** Returns all `optionDefinitions`, ordered by their option name (not their field name).  */
        fun getAllOptionDefinitionsForClass(
            optionsClass: java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?
        ): com.google.common.collect.ImmutableList<com.google.devtools.common.options.OptionDefinition> {
            return com.google.devtools.common.options.IsolatedOptionsData.Companion.allOptionsDefinitions.computeIfAbsent(
                optionsClass,
                java.util.function.Function { optionsBaseClass: java.lang.Class<out com.google.devtools.common.options.OptionsBase?>? ->
                    val builder: com.google.common.collect.ImmutableList.Builder<com.google.devtools.common.options.OptionDefinition?> =
                        com.google.common.collect.ImmutableList.builder<com.google.devtools.common.options.OptionDefinition?>()
                    com.google.common.base.Verify.verify(
                        optionsBaseClass.isAnnotationPresent(com.google.devtools.common.options.OptionsClass::class.java),
                        "Options class %s should be annotated with @OptionsClass",
                        optionsBaseClass.getName()
                    )

                    for (method in optionsBaseClass.getMethods()) {
                        val optionDefinition: com.google.devtools.common.options.MethodOptionDefinition? =
                            com.google.devtools.common.options.MethodOptionDefinition.Companion.from(method)
                        if (optionDefinition != null) {
                            builder.add(optionDefinition)
                        }
                    }
                    com.google.common.collect.ImmutableList.sortedCopyOf<com.google.devtools.common.options.OptionDefinition?>(
                        com.google.devtools.common.options.OptionDefinition.Companion.BY_OPTION_NAME,
                        builder.build()
                    )
                })
        }

        /**
         * Generic method to check for collisions between the names we give options. Useful for checking
         * both single-character abbreviations and full names.
         */
        @Throws(com.google.devtools.common.options.DuplicateOptionDeclarationException::class)
        private fun <A> checkForCollisions(
            aFieldMap: MutableMap<A?, com.google.devtools.common.options.OptionDefinition>,
            optionName: A?,
            definition: com.google.devtools.common.options.OptionDefinition?,
            description: String?,
            allowDuplicatesParsingEquivalently: Boolean
        ) {
            if (aFieldMap.containsKey(optionName)) {
                val otherDefinition: com.google.devtools.common.options.OptionDefinition = aFieldMap.get(optionName)
                if (allowDuplicatesParsingEquivalently
                    && com.google.devtools.common.options.OptionDefinition.Companion.equivalentForParsing(
                        otherDefinition,
                        definition
                    )
                ) {
                    return
                }
                throw com.google.devtools.common.options.DuplicateOptionDeclarationException(
                    "Duplicate option name, due to " + description + ": --" + optionName
                )
            }
        }

        /**
         * All options, even non-boolean ones, should check that they do not conflict with previously
         * loaded boolean options.
         */
        @Throws(com.google.devtools.common.options.DuplicateOptionDeclarationException::class)
        private fun checkForBooleanAliasCollisions(
            booleanAliasMap: MutableMap<String?, String?>, optionName: String?, description: String?
        ) {
            if (booleanAliasMap.containsKey(optionName)) {
                throw com.google.devtools.common.options.DuplicateOptionDeclarationException(
                    ("Duplicate option name, due to "
                            + description
                            + " --"
                            + optionName
                            + ", it conflicts with a negating alias for boolean flag --"
                            + booleanAliasMap.get(optionName))
                )
            }
        }

        /**
         * For an `option` of boolean type, this checks that the boolean alias does not conflict
         * with other names, and adds the boolean alias to a list so that future flags can find if they
         * conflict with a boolean alias..
         */
        @Throws(com.google.devtools.common.options.DuplicateOptionDeclarationException::class)
        private fun checkAndUpdateBooleanAliases(
            nameToFieldMap: MutableMap<String?, com.google.devtools.common.options.OptionDefinition>,
            oldNameToFieldMap: MutableMap<String?, com.google.devtools.common.options.OptionDefinition>,
            booleanAliasMap: MutableMap<String?, String?>,
            optionName: String?,
            optionDefinition: com.google.devtools.common.options.OptionDefinition?,
            allowDuplicatesParsingEquivalently: Boolean
        ) {
            // Check that the negating alias does not conflict with existing flags.
            com.google.devtools.common.options.IsolatedOptionsData.Companion.checkForCollisions<String?>(
                nameToFieldMap,
                "no" + optionName,
                optionDefinition,
                "boolean option alias",
                allowDuplicatesParsingEquivalently
            )
            com.google.devtools.common.options.IsolatedOptionsData.Companion.checkForCollisions<String?>(
                oldNameToFieldMap,
                "no" + optionName,
                optionDefinition,
                "boolean option alias",
                allowDuplicatesParsingEquivalently
            )

            // Record that the boolean option takes up additional namespace for its negating alias.
            booleanAliasMap.put("no" + optionName, optionName)
        }

        /**
         * Constructs an [IsolatedOptionsData] object for a parser that knows about the given [ ] classes. No inter-option analysis is done. Performs basic validity checks on each
         * option in isolation.
         * 
         * 
         * If `allowDuplicatesParsingEquivalently` is true, then options that collide in name but
         * parse equivalently (e.g. both of them accept a value or both of them do not), are allowed.
         */
        fun from(
            classes: MutableCollection<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>>,
            allowDuplicatesParsingEquivalently: Boolean
        ): IsolatedOptionsData {
            // Mind which fields have to preserve order.
            val constructorBuilder: MutableMap<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?, java.lang.reflect.Constructor<*>> =
                LinkedHashMap<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?, java.lang.reflect.Constructor<*>>()
            val primaryOptionsClassesBuilder: com.google.common.collect.ImmutableList.Builder<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> =
                com.google.common.collect.ImmutableList.builder<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>()
            val nameToFieldBuilder: MutableMap<String?, com.google.devtools.common.options.OptionDefinition> =
                LinkedHashMap<String?, com.google.devtools.common.options.OptionDefinition>()
            val oldNameToFieldBuilder: MutableMap<String?, com.google.devtools.common.options.OptionDefinition> =
                LinkedHashMap<String?, com.google.devtools.common.options.OptionDefinition>()
            val abbrevToFieldBuilder: MutableMap<Char?, com.google.devtools.common.options.OptionDefinition> =
                HashMap<Char?, com.google.devtools.common.options.OptionDefinition>()

            // Maps the negated boolean flag aliases to the original option name.
            val booleanAliasMap: MutableMap<String?, String?> = HashMap<String?, String?>()

            // Combine the option definitions for these options classes, and check that they do not
            // conflict. The options are individually checked for correctness at compile time in the
            // OptionProcessor.
            for (parsedOptionsClass in classes) {
                primaryOptionsClassesBuilder.add(parsedOptionsClass)
                val constructor: java.lang.reflect.Constructor<out com.google.devtools.common.options.OptionsBase?>?
                try {
                    var classToInstantiate: java.lang.Class<out com.google.devtools.common.options.OptionsBase?> =
                        parsedOptionsClass
                    if (parsedOptionsClass.isAnnotationPresent(com.google.devtools.common.options.OptionsClass::class.java)) {
                        classToInstantiate =
                            com.google.devtools.common.options.MethodOptionDefinition.Companion.getImplClass(
                                parsedOptionsClass
                            )
                    }
                    constructor = classToInstantiate.getConstructor()
                    constructorBuilder.put(parsedOptionsClass, constructor)
                } catch (e: java.lang.NoSuchMethodException) {
                    throw java.lang.IllegalArgumentException(
                        parsedOptionsClass.toString() + " lacks an accessible default constructor", e
                    )
                }

                for (superclass in com.google.devtools.common.options.IsolatedOptionsData.Companion.getAllSuperclasses(
                    parsedOptionsClass
                )) {
                    // If two options classes have the same base class or one is the base class of another,
                    // it's an option conflict. Except for fallback options (when
                    // allowDuplicatesParsingEquivalently is true), but then we don't instantiate any option
                    // classes
                    if (constructorBuilder.containsKey(superclass)) {
                        constructorBuilder.put(
                            superclass,
                            com.google.devtools.common.options.IsolatedOptionsData.Companion.AMBIGUOUS_MARKER_CTOR
                        )
                    } else {
                        constructorBuilder.put(superclass, constructor)
                    }
                }

                val optionDefinitions: com.google.common.collect.ImmutableList<com.google.devtools.common.options.OptionDefinition> =
                    com.google.devtools.common.options.IsolatedOptionsData.Companion.getAllOptionDefinitionsForClass(
                        parsedOptionsClass
                    )

                for (optionDefinition in optionDefinitions) {
                    try {
                        val optionName: String? = optionDefinition.getOptionName()
                        com.google.devtools.common.options.IsolatedOptionsData.Companion.checkForCollisions<String?>(
                            nameToFieldBuilder,
                            optionName,
                            optionDefinition,
                            "option name collision",
                            allowDuplicatesParsingEquivalently
                        )
                        com.google.devtools.common.options.IsolatedOptionsData.Companion.checkForCollisions<String?>(
                            oldNameToFieldBuilder,
                            optionName,
                            optionDefinition,
                            "option name collision with another option's old name",
                            allowDuplicatesParsingEquivalently
                        )
                        com.google.devtools.common.options.IsolatedOptionsData.Companion.checkForBooleanAliasCollisions(
                            booleanAliasMap,
                            optionName,
                            "option"
                        )
                        if (optionDefinition.usesBooleanValueSyntax()) {
                            com.google.devtools.common.options.IsolatedOptionsData.Companion.checkAndUpdateBooleanAliases(
                                nameToFieldBuilder,
                                oldNameToFieldBuilder,
                                booleanAliasMap,
                                optionName,
                                optionDefinition,
                                allowDuplicatesParsingEquivalently
                            )
                        }
                        nameToFieldBuilder.put(optionName, optionDefinition)

                        if (!optionDefinition.getOldOptionName().isEmpty()) {
                            val oldName: String? = optionDefinition.getOldOptionName()
                            com.google.devtools.common.options.IsolatedOptionsData.Companion.checkForCollisions<String?>(
                                nameToFieldBuilder,
                                oldName,
                                optionDefinition,
                                "old option name collision with another option's canonical name",
                                allowDuplicatesParsingEquivalently
                            )
                            com.google.devtools.common.options.IsolatedOptionsData.Companion.checkForCollisions<String?>(
                                oldNameToFieldBuilder,
                                oldName,
                                optionDefinition,
                                "old option name collision with another old option name",
                                allowDuplicatesParsingEquivalently
                            )
                            com.google.devtools.common.options.IsolatedOptionsData.Companion.checkForBooleanAliasCollisions(
                                booleanAliasMap,
                                oldName,
                                "old option name"
                            )
                            // If boolean, repeat the alias dance for the old name.
                            if (optionDefinition.usesBooleanValueSyntax()) {
                                com.google.devtools.common.options.IsolatedOptionsData.Companion.checkAndUpdateBooleanAliases(
                                    nameToFieldBuilder,
                                    oldNameToFieldBuilder,
                                    booleanAliasMap,
                                    oldName,
                                    optionDefinition,
                                    allowDuplicatesParsingEquivalently
                                )
                            }
                            // Now that we've checked for conflicts, confidently store the old name.
                            oldNameToFieldBuilder.put(oldName, optionDefinition)
                        }
                        if (optionDefinition.getAbbreviation() != '\u0000') {
                            com.google.devtools.common.options.IsolatedOptionsData.Companion.checkForCollisions<Char?>(
                                abbrevToFieldBuilder,
                                optionDefinition.getAbbreviation(),
                                optionDefinition,
                                "option abbreviation",
                                allowDuplicatesParsingEquivalently
                            )
                            abbrevToFieldBuilder.put(optionDefinition.getAbbreviation(), optionDefinition)
                        }
                    } catch (e: com.google.devtools.common.options.DuplicateOptionDeclarationException) {
                        throw com.google.devtools.common.options.ConstructionException(e)
                    }
                }
            }

            return com.google.devtools.common.options.IsolatedOptionsData(
                constructorBuilder,
                primaryOptionsClassesBuilder.build(),
                nameToFieldBuilder,
                oldNameToFieldBuilder,
                abbrevToFieldBuilder
            )
        }

        private fun getAllSuperclasses(
            clazz: java.lang.Class<out com.google.devtools.common.options.OptionsBase?>
        ): com.google.common.collect.ImmutableSet<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> {
            val builder: com.google.common.collect.ImmutableSet.Builder<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> =
                com.google.common.collect.ImmutableSet.builder<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>()
            var current: java.lang.Class<*> = clazz.getSuperclass()
            // We don't check for nullness because every class here should be a descendant of OptionsBase
            while (current != com.google.devtools.common.options.OptionsBase::class.java) {
                builder.add(current.asSubclass<com.google.devtools.common.options.OptionsBase?>(com.google.devtools.common.options.OptionsBase::class.java))
                current = current.getSuperclass()
            }
            return builder.build()
        }
    }
}
