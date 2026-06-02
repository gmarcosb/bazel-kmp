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
package com.google.devtools.build.lib.analysis.platform

import com.google.devtools.build.lib.analysis.config.ConfigMatchingProvider

/** Provider for a platform, which is a group of constraints and values.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class PlatformInfo private constructor(
    label: com.google.devtools.build.lib.cmdline.Label,
    constraints: ConstraintCollection,
    execProperties: com.google.devtools.build.lib.analysis.platform.PlatformProperties,
    flags: com.google.common.collect.ImmutableList<String?>,
    requiredSettings: com.google.common.collect.ImmutableList<ConfigMatchingProvider?>,
    checkToolchainTypes: Boolean,
    allowedToolchainTypes: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.cmdline.Label?>,
    missingToolchainErrorMessage: String?
) : NativeInfo(), PlatformInfoApi<ConstraintSettingInfo?, ConstraintValueInfo?> {
    private val label: com.google.devtools.build.lib.cmdline.Label
    private val constraints: ConstraintCollection

    private val execProperties: com.google.devtools.build.lib.analysis.platform.PlatformProperties

    private val flags: com.google.common.collect.ImmutableList<String?>

    private val requiredSettings: com.google.common.collect.ImmutableList<ConfigMatchingProvider?>

    private val checkToolchainTypes: Boolean
    private val allowedToolchainTypes: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.cmdline.Label?>

    @kotlin.jvm.JvmField
    val missingToolchainErrorMessage: String?

    init {
        this.label = label
        this.constraints = constraints
        this.execProperties = execProperties
        this.flags = flags
        this.requiredSettings = requiredSettings
        this.checkToolchainTypes = checkToolchainTypes
        this.allowedToolchainTypes = allowedToolchainTypes
        this.missingToolchainErrorMessage = missingToolchainErrorMessage
    }

    val provider: BuiltinProvider<PlatformInfo?>
        get() = com.google.devtools.build.lib.analysis.platform.PlatformInfo.Companion.PROVIDER

    override fun label(): com.google.devtools.build.lib.cmdline.Label {
        return label
    }

    override fun constraints(): ConstraintCollection {
        return constraints
    }

    fun execProperties(): com.google.common.collect.ImmutableMap<String?, String?>? {
        return execProperties.properties()
    }

    fun flags(): com.google.common.collect.ImmutableList<String?> {
        return flags
    }

    fun requiredSettings(): com.google.common.collect.ImmutableList<ConfigMatchingProvider?> {
        return requiredSettings
    }

    fun checkToolchainTypes(): Boolean {
        return checkToolchainTypes
    }

    fun allowedToolchainTypes(): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.cmdline.Label?> {
        return allowedToolchainTypes
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.append(String.format("PlatformInfo(%s, constraints=%s)", label, constraints))
    }

    /** Add this platform to the given fingerprint.  */
    fun addTo(fp: Fingerprint) {
        fp.addString(label.toString())
        constraints.addToFingerprint(fp)
        fp.addStringMap(execProperties.properties())
        fp.addStrings(flags)
        fp.addStrings(
            requiredSettings.stream()
                .map<Any?>(ConfigMatchingProvider::label)
                .map<R?> { obj: Any? -> obj.toString() }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>()))
        fp.addStrings(
            allowedToolchainTypes.stream()
                .map<String?> { obj: com.google.devtools.build.lib.cmdline.Label? -> obj.toString() }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>()))
        fp.addBoolean(checkToolchainTypes)
        fp.addNullableString(missingToolchainErrorMessage)
    }

    override fun equals(o: Any?): Boolean {
        if (o !is PlatformInfo) {
            return false
        }
        return label == o.label
                && constraints == o.constraints
                && execProperties == o.execProperties
                && flags == o.flags
                && requiredSettings == o.requiredSettings
                && (checkToolchainTypes == o.checkToolchainTypes)
                && allowedToolchainTypes == o.allowedToolchainTypes
                && missingToolchainErrorMessage == o.missingToolchainErrorMessage
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(
            label,
            constraints,
            execProperties,
            flags,
            requiredSettings,
            checkToolchainTypes,
            allowedToolchainTypes,
            missingToolchainErrorMessage
        )
    }

    /** Builder class to facilitate creating valid [PlatformInfo] instances.  */
    class Builder {
        private var parent: PlatformInfo? = null
        private var label: com.google.devtools.build.lib.cmdline.Label? = null
        private val constraints: com.google.devtools.build.lib.analysis.platform.ConstraintCollection.Builder =
            ConstraintCollection.Companion.builder()
        private val execPropertiesBuilder: com.google.devtools.build.lib.analysis.platform.PlatformProperties.Builder =
            com.google.devtools.build.lib.analysis.platform.PlatformProperties.Companion.builder()
        private val flags: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.Builder<String?>()
        private val requiredSettings: com.google.common.collect.ImmutableList.Builder<ConfigMatchingProvider?> =
            com.google.common.collect.ImmutableList.Builder<ConfigMatchingProvider?>()
        private var checkToolchainTypes = false
        private val allowedToolchainTypes: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.cmdline.Label?> =
            com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.cmdline.Label?>()
        private var missingToolchainErrorMessage: String? = null

        /**
         * Sets the parent [PlatformInfo] that this platform inherits from. Constraint values set
         * directly on this instance will be kept, but any other constraint settings will be found from
         * the parent, if set.
         * 
         * @param parent the platform that is the parent of this platform
         * @return the [Builder] instance for method chaining
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setParent(parent: PlatformInfo?): Builder {
            this.parent = parent
            if (parent == null) {
                this.constraints.parent(null)
                this.execPropertiesBuilder.setParent(null)
            } else {
                this.constraints.parent(parent.constraints)
                this.execPropertiesBuilder.setParent(parent.execProperties)
            }
            return this
        }

        /**
         * Sets the [Label] for this [PlatformInfo].
         * 
         * @param label the label identifying this platform
         * @return the [Builder] instance for method chaining
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setLabel(label: com.google.devtools.build.lib.cmdline.Label): Builder {
            this.label = label
            return this
        }

        /**
         * Adds the given constraint value to the constraints that define this [PlatformInfo].
         * 
         * @param constraint the constraint to add
         * @return the [Builder] instance for method chaining
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addConstraint(constraint: ConstraintValueInfo?): Builder {
            this.constraints.addConstraints(constraint)
            return this
        }

        /**
         * Adds the given constraint values to the constraints that define this [PlatformInfo].
         * 
         * @param constraints the constraints to add
         * @return the [Builder] instance for method chaining
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addConstraints(constraints: Iterable<ConstraintValueInfo?>?): Builder {
            this.constraints.addConstraints(constraints)
            return this
        }

        /**
         * Sets the execution properties.
         * 
         * 
         * If there is a parent [PlatformInfo] set, then all parent's properties will be
         * inherited. Any properties included in both will use the child's value. Use the value of empty
         * string to unset a property.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecProperties(properties: com.google.common.collect.ImmutableMap<String?, String?>?): Builder {
            this.execPropertiesBuilder.setProperties(properties)
            return this
        }

        /** Add the given flags to this [PlatformInfo].  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addFlags(flags: Iterable<String?>): Builder {
            this.flags.addAll(flags)
            return this
        }

        /** Add the given settings to this [PlatformInfo].  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addRequiredSettings(requiredSettings: MutableList<ConfigMatchingProvider?>): Builder {
            this.requiredSettings.addAll(requiredSettings)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun checkToolchainTypes(checkToolchainTypes: Boolean): Builder {
            this.checkToolchainTypes = checkToolchainTypes
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addAllowedToolchainTypes(allowedToolchainTypes: MutableList<com.google.devtools.build.lib.cmdline.Label?>): Builder {
            this.allowedToolchainTypes.addAll(allowedToolchainTypes)
            return this
        }

        /**
         * Sets an error message to display when a required toolchain cannot be resolved for this
         * platform.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setMissingToolchainErrorMessage(message: String?): Builder {
            if (message == null || message.isEmpty()) {
                this.missingToolchainErrorMessage = null
            } else {
                this.missingToolchainErrorMessage = message
            }
            return this
        }

        /**
         * Returns the new [PlatformInfo] instance.
         * 
         * @throws DuplicateConstraintException if more than one constraint value exists for the same
         * constraint setting
         */
        @Throws(
            com.google.devtools.build.lib.analysis.platform.ConstraintCollection.DuplicateConstraintException::class,
            ExecPropertiesException::class
        )
        fun build(): PlatformInfo {
            // Merge parent flags and this builder's flags. Parent flags always come first so that flags
            // from this builder will override or combine, depending on the flag type.
            val flagBuilder: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.Builder<String?>()
            if (this.parent != null) {
                flagBuilder.addAll(this.parent!!.flags)
            }
            flagBuilder.addAll(this.flags.build())

            // Required settings are explicitly **not** inherited from the parent, so do not merge.
            val settings: com.google.common.collect.ImmutableList<ConfigMatchingProvider?> = requiredSettings.build()

            return com.google.devtools.build.lib.analysis.platform.PlatformInfo(
                label,
                constraints.build(),
                execPropertiesBuilder.build(),
                flagBuilder.build(),
                settings,
                checkToolchainTypes,
                allowedToolchainTypes.build(),
                missingToolchainErrorMessage
            )
        }
    }

    /** Exception that indicates something is wrong in exec_properties configuration.  */
    class ExecPropertiesException internal constructor(message: String?) : java.lang.Exception(message)
    companion object {
        /** Name used in Starlark for accessing this provider.  */
        const val STARLARK_NAME: String = "PlatformInfo"

        /**
         * Empty [PlatformInfo] instance for a invalid or empty (e.g. builtin) actions. See also
         * src/main/starlark/builtins_bzl/platforms/BUILD#empty
         */
        @kotlin.jvm.JvmField
        val EMPTY_PLATFORM_INFO: PlatformInfo

        init {
            try {
                com.google.devtools.build.lib.analysis.platform.PlatformInfo.Companion.EMPTY_PLATFORM_INFO =
                    com.google.devtools.build.lib.analysis.platform.PlatformInfo.Companion.builder()
                        .setLabel(PlatformConstants.INTERNAL_PLATFORM).build()
            } catch (e: com.google.devtools.build.lib.analysis.platform.ConstraintCollection.DuplicateConstraintException) {
                // This can never happen since we're not passing any values to the builder.
                throw com.google.common.base.VerifyException(e)
            } catch (e: ExecPropertiesException) {
                throw com.google.common.base.VerifyException(e)
            }
        }

        /** Provider singleton constant.  */
        @kotlin.jvm.JvmField
        val PROVIDER: BuiltinProvider<PlatformInfo?> = object : BuiltinProvider<PlatformInfo?>(
            com.google.devtools.build.lib.analysis.platform.PlatformInfo.Companion.STARLARK_NAME,
            com.google.devtools.build.lib.analysis.platform.PlatformInfo::class.java
        ) {}

        /** Returns a new [Builder] for creating a fresh [PlatformInfo] instance.  */
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return com.google.devtools.build.lib.analysis.platform.PlatformInfo.Builder()
        }
    }
}
