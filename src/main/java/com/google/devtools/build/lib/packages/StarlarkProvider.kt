// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.Label

/**
 * A provider defined in Starlark rather than in native code.
 * 
 * 
 * This is a result of calling the `provider()` function from Starlark ([ ][com.google.devtools.build.lib.analysis.starlark.StarlarkRuleClassFunctions.provider]).
 * 
 * 
 * `StarlarkProvider`s may be either schemaless or schemaful. Instances of schemaless
 * providers can have any set of fields on them, whereas instances of schemaful providers may have
 * only the fields that are named in the schema.
 * 
 * 
 * `StarlarkProvider` may have a custom initializer callback, which might perform
 * preprocessing or validation of field values. This callback (if defined) is automatically invoked
 * when the provider is called. To create instances of the provider without calling the initializer
 * callback, use the callable returned by `StarlarkProvider#createRawConstructor`.
 * 
 * 
 * Exporting a `StarlarkProvider` creates a key that is used to uniquely identify it.
 * Usually a provider is exported by calling [.export], but a test may wish to just create a
 * pre-exported provider directly. Exported providers use only their key for [.equals] and
 * [.hashCode].
 */
class StarlarkProvider private constructor(
    location: net.starlark.java.syntax.Location?,
    documentation: String?,
    schema: com.google.common.collect.ImmutableMap<String?, java.util.Optional<String?>?>?,
    init: net.starlark.java.eval.StarlarkCallable?,
    keyOrIdentityToken: Any
) : net.starlark.java.eval.StarlarkCallable, StarlarkExportable, com.google.devtools.build.lib.packages.Provider {
    private val location: net.starlark.java.syntax.Location?

    private val documentation: String?

    // For schemaful providers, the sorted list of allowed field names.
    // The requirement for sortedness comes from StarlarkInfoWithSchema and lets us bisect the fields.
    private val fields: com.google.common.collect.ImmutableList<String?>?

    // For schemaful providers, an optional map from field names to documentation strings (if any). In
    // accordance with the provider() Starlark API, either all schema fields have documentation
    // strings (possibly empty strings), or none do. The iteration order is the order of fields in the
    // provider() invocation in Starlark - thus, *not* the order of the `fields` list above.
    private val schema: com.google.common.collect.ImmutableMap<String?, java.util.Optional<String?>?>?

    // Optional custom initializer callback. If present, it is invoked with the same positional and
    // keyword arguments as were passed to the provider constructor. The return value must be a
    // Starlark dict mapping field names (string keys) to their values.
    private val init: net.starlark.java.eval.StarlarkCallable?

    /**
     * An identifier for this provider.
     * 
     * 
     * This is a [Key] if exported and a [SymbolGenerator.Symbol] otherwise.
     * 
     * 
     * Mutated by [.export].
     */
    private var keyOrIdentityToken: Any

    /**
     * For schemaful providers, an array of metadata concerning depset optimization.
     * 
     * 
     * Each index in the array holds an optional (nullable) depset element type. The value at that
     * index is initialized to be the element type of the first non-empty Depset to ever be stored in
     * the corresponding field from [.schema] on any instance of this provider, globally. If no
     * depsets (or only empty depsets) are ever stored in a field, the value at its index in this
     * array will remain null.
     * 
     * 
     * Whenever a field is stored in an instance of this provider type, if the value is a depset
     * whose element type matches the one stored in this array, it is optimized by unwrapping it down
     * to its `NestedSet`. Upon retrieval, the depset wrapper is reconstructed using this saved
     * element type.
     * 
     * 
     * The optimization may (harmlessly) fail to apply for provider fields that are not strongly
     * typed across all instances.
     * 
     * 
     * For large builds, this optimization has been observed to save half a percent in retained
     * heap.
     * 
     * 
     * In the future, the ad hoc heuristic of examining the first stored non-empty depset might be
     * replaced by stronger type information in the provider's Starlark declaration. However, this
     * optimization would remain relevant for provider declarations that do not supply such type info.
     */
    private var depsetTypePredictor: AtomicReferenceArray<java.lang.Class<*>?>? = null

    /** A builder which may be used to construct a StarlarkProvider.  */
    class Builder private constructor(location: net.starlark.java.syntax.Location?) {
        private val location: net.starlark.java.syntax.Location?

        private var documentation: String? = null

        private var schema: com.google.common.collect.ImmutableMap<String?, java.util.Optional<String?>?>? = null

        private var init: net.starlark.java.eval.StarlarkCallable? = null

        init {
            this.location = location
        }

        /**
         * Sets the list of allowed fields for the provider built by this builder, and marks the fields'
         * documentation as empty.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setSchema(fields: MutableCollection<String?>): Builder {
            val builder: com.google.common.collect.ImmutableMap.Builder<String?, java.util.Optional<String?>?> =
                com.google.common.collect.ImmutableMap.builder<String?, java.util.Optional<String?>?>()
            for (field in fields) {
                builder.put(field, java.util.Optional.empty<String?>())
            }
            this.schema = builder.buildOrThrow()
            return this
        }

        /**
         * Sets the list of allowed field names and their corresponding documentation strings for the
         * provider built by this builder.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setSchema(schemaWithDocumentation: MutableMap<String?, String?>): Builder {
            val builder: com.google.common.collect.ImmutableMap.Builder<String?, java.util.Optional<String?>?> =
                com.google.common.collect.ImmutableMap.builder<String?, java.util.Optional<String?>?>()
            for (entry in schemaWithDocumentation.entrySet()) {
                builder.put(entry.getKey(), java.util.Optional.of<String?>(entry.getValue()))
            }
            this.schema = builder.buildOrThrow()
            return this
        }

        /** Sets the documentation string for the provider built by this builder.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setDocumentation(documentation: String?): Builder {
            this.documentation = documentation
            return this
        }

        /**
         * Sets the custom initializer callback for the provider built by this builder.
         * 
         * 
         * The initializer callback will be automatically invoked when the provider is called. To
         * bypass the custom initializer callback, use the callable returned by [ ][StarlarkProvider.createRawConstructor].
         * 
         * @param init A callback that accepts the arguments passed to the provider constructor, and
         * which returns a dict mapping field names to their values. The resulting provider instance
         * is created as though the dict were passed as **kwargs to the raw constructor. In
         * particular, for a schemaful provider, the dict may not contain keys not listed in the
         * schema.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setInit(init: net.starlark.java.eval.StarlarkCallable?): Builder {
            this.init = init
            return this
        }

        /** Builds an exported StarlarkProvider.  */
        fun buildExported(key: Key): StarlarkProvider {
            return StarlarkProvider(location, documentation, schema, init, key)
        }

        /** Builds a unexported StarlarkProvider.  */
        fun buildWithIdentityToken(identityToken: net.starlark.java.eval.SymbolGenerator.Symbol<*>): StarlarkProvider {
            return StarlarkProvider(location, documentation, schema, init, identityToken)
        }
    }

    /**
     * Constructs the provider.
     * 
     * 
     * If `schema` is null, the provider is schemaless. If `init` is null, no custom
     * initializer callback will be used (i.e., calling the provider is the same as simply calling the
     * raw constructor). If `key` is null, the provider is unexported.
     */
    init {
        this.location = location
        this.documentation = documentation
        this.fields =
            if (schema != null) com.google.common.collect.ImmutableList.sortedCopyOf<String?>(schema.keySet()) else null
        this.schema = schema
        this.init = init
        this.keyOrIdentityToken = keyOrIdentityToken
        if (schema != null) {
            depsetTypePredictor = AtomicReferenceArray<java.lang.Class<*>?>(schema.size())
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun requestArgumentProcessor(thread: net.starlark.java.eval.StarlarkThread?): net.starlark.java.eval.StarlarkCallable.ArgumentProcessor {
        val factory = newStarlarkInfoFactory(thread)
        if (init != null) {
            val initArgumentProcessor: net.starlark.java.eval.StarlarkCallable.ArgumentProcessor =
                net.starlark.java.eval.Starlark.requestArgumentProcessor(thread, init)
            return ArgumentProcessorWithInit(this, factory, initArgumentProcessor, thread)
        } else {
            return RawArgumentProcessor(this, factory, thread)
        }
    }

    private fun newStarlarkInfoFactory(thread: net.starlark.java.eval.StarlarkThread?): StarlarkInfoFactory {
        return if (schema != null)
            StarlarkInfoWithSchema.Companion.newStarlarkInfoFactory(this, thread)
        else
            StarlarkInfoNoSchema.Companion.newStarlarkInfoFactory(this, thread)
    }

    internal class ArgumentProcessorWithInit(
        owner: StarlarkProvider,
        factory: StarlarkInfoFactory,
        initArgumentProcessor: net.starlark.java.eval.StarlarkCallable.ArgumentProcessor,
        thread: net.starlark.java.eval.StarlarkThread?
    ) : net.starlark.java.eval.StarlarkCallable.ArgumentProcessor(thread) {
        private val owner: StarlarkProvider
        private val factory: StarlarkInfoFactory
        private val initArgumentProcessor: net.starlark.java.eval.StarlarkCallable.ArgumentProcessor

        init {
            this.owner = owner
            this.factory = factory
            this.initArgumentProcessor = initArgumentProcessor
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun addPositionalArg(value: Any?) {
            initArgumentProcessor.addPositionalArg(value)
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun addNamedArg(name: String?, value: Any?) {
            initArgumentProcessor.addNamedArg(name, value)
        }

        override fun getCallable(): net.starlark.java.eval.StarlarkCallable {
            return owner
        }

        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        override fun call(thread: net.starlark.java.eval.StarlarkThread): Any? {
            val initResult: Any? =
                net.starlark.java.eval.Starlark.callViaArgumentProcessor(thread, owner.init, initArgumentProcessor)
            val kwargs: net.starlark.java.eval.Dict<String?, Any?>? =
                net.starlark.java.eval.Dict.cast<String?, Any?>(
                    initResult,
                    String::class.java,
                    Any::class.java,
                    "return value of provider init()"
                )
            return factory.createFromMap(kwargs)
        }
    }

    /**
     * A [RawArgumentProcessor] is used for calling two different types of StarlarkCallable:
     * StarlarkProvider in case it doesn't have an init function, and RawConstructor.
     */
    internal class RawArgumentProcessor(
        owner: net.starlark.java.eval.StarlarkCallable,
        factory: StarlarkInfoFactory,
        thread: net.starlark.java.eval.StarlarkThread?
    ) : net.starlark.java.eval.StarlarkCallable.ArgumentProcessor(thread) {
        private val owner: net.starlark.java.eval.StarlarkCallable // either StarlarkProvider or RawConstructor
        private val factory: StarlarkInfoFactory

        init {
            this.owner = owner
            this.factory = factory
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun addPositionalArg(value: Any?) {
            pushCallableAndThrow(
                net.starlark.java.eval.Starlark.errorf(
                    "%s: unexpected positional arguments",
                    owner.getName()
                )
            )
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun addNamedArg(name: String?, value: Any?) {
            factory.addNamedArg(name, value)
        }

        override fun getCallable(): net.starlark.java.eval.StarlarkCallable {
            return owner
        }

        @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
        override fun call(thread: net.starlark.java.eval.StarlarkThread?): Any? {
            return factory.createFromArgs()
        }
    }

    internal abstract class StarlarkInfoFactory(
        provider: StarlarkProvider?,
        thread: net.starlark.java.eval.StarlarkThread?
    ) {
        protected val provider: StarlarkProvider?
        protected val thread: net.starlark.java.eval.StarlarkThread?

        init {
            this.provider = provider
            this.thread = thread
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        abstract fun createFromArgs(): StarlarkInfo?

        @Throws(net.starlark.java.eval.EvalException::class)
        abstract fun createFromMap(map: MutableMap<String?, Any?>?): StarlarkInfo?

        @Throws(net.starlark.java.eval.EvalException::class)
        abstract fun addNamedArg(name: String?, value: Any?)
    }

    private class RawConstructor(provider: StarlarkProvider) : net.starlark.java.eval.StarlarkCallable {
        private val provider: StarlarkProvider

        init {
            this.provider = provider
        }

        override fun requestArgumentProcessor(thread: net.starlark.java.eval.StarlarkThread?): net.starlark.java.eval.StarlarkCallable.ArgumentProcessor {
            return RawArgumentProcessor(this, provider.newStarlarkInfoFactory(thread), thread)
        }

        override fun getName(): String {
            val name: java.lang.StringBuilder = java.lang.StringBuilder("<raw constructor")
            if (provider.isExported()) {
                name.append(" for ").append(provider.getName())
            }
            name.append(">")
            return name.toString()
        }

        override fun getLocation(): net.starlark.java.syntax.Location? {
            return provider.location
        }
    }

    fun createRawConstructor(): net.starlark.java.eval.StarlarkCallable {
        return RawConstructor(this)
    }

    /**
     * Returns the provider's custom initializer callback, or null if the provider doesn't have one.
     */
    fun getInit(): net.starlark.java.eval.StarlarkCallable? {
        return init
    }

    override fun getLocation(): net.starlark.java.syntax.Location? {
        return location
    }

    /**
     * Returns the value of the doc parameter passed to `provider()` in Starlark, or an empty
     * Optional if a doc parameter was not provided.
     */
    fun getDocumentation(): java.util.Optional<String?> {
        return java.util.Optional.ofNullable<String?>(documentation)
    }

    override fun isExported(): Boolean {
        return keyOrIdentityToken is Key
    }

    override fun getKey(): Key {
        com.google.common.base.Preconditions.checkState(
            isExported(),
            "Calling getKey() is disallowed on an unexported provider. location: %s, identity token:"
                    + " %s",
            location,
            keyOrIdentityToken
        )
        return keyOrIdentityToken as Key
    }

    override fun getName(): String? {
        if (keyOrIdentityToken is Key) {
            return keyOrIdentityToken.getExportedName()
        }
        return "<no name>"
    }

    override fun getPrintableName(): String? {
        return getName()
    }

    /**
     * Returns the sorted list of fields allowed by this provider, or null if the provider is
     * schemaless.
     */
    fun getFields(): com.google.common.collect.ImmutableList<String?>? {
        return fields
    }

    /**
     * Returns the map of fields allowed by this provider mapping to their corresponding documentation
     * strings (if any), or null if this provider is schemaless.
     * 
     * 
     * The returned map's iteration order matches the order of fields in the `provider()`
     * invocation in Starlark - thus, different from the order of fields in [.getFields].
     */
    fun getSchema(): com.google.common.collect.ImmutableMap<String?, java.util.Optional<String?>?>? {
        return schema
    }

    override fun getErrorMessageForUnknownField(name: String?): String? {
        return java.lang.String.format(
            "'%s' value has no field or method '%s'", if (isExported()) getName() else "struct", name
        )
    }

    // TODO(bazel-team): use exportedLocation as the callable symbol's location.
    override fun export(
        handler: EventHandler?,
        extensionLabel: Label,
        exportedName: String?,
        exportedLocation: net.starlark.java.syntax.Location?
    ) {
        com.google.common.base.Preconditions.checkState(!isExported())
        val identifier: net.starlark.java.eval.SymbolGenerator.Symbol<*> =
            keyOrIdentityToken as net.starlark.java.eval.SymbolGenerator.Symbol<*>
        if (identifier.getOwner() is BzlLoadValue.Key) {
            // In production code, StarlarkProviders are created only when loading .bzl files so the owner
            // of the Symbol should be a BzlLoadValue.Key.
            checkArgument(
                extensionLabel.equals(bzlKey.getLabel()),
                "export extensionLabel=%s, but owner=%s",
                extensionLabel,
                bzlKey
            )
            this.keyOrIdentityToken = com.google.devtools.build.lib.packages.StarlarkProvider.Key(bzlKey, exportedName)
        } else {
            // In tests, the symbol may be arbitrary.
            if (!com.google.devtools.build.lib.util.TestType.isInTest()) {
                sendNonFatalBugReport(
                    java.lang.IllegalStateException(
                        java.lang.String.format(
                            "exporting StarlarkProvider defined at %s as %s:%s but thread owner=%s was not"
                                    + " a BzlLoadValue.Key",
                            location, extensionLabel, exportedName, identifier.getOwner()
                        )
                    )
                )
            }
            this.keyOrIdentityToken =
                com.google.devtools.build.lib.packages.StarlarkProvider.Key(
                    if (StarlarkBuiltinsValue.isBuiltinsRepo(extensionLabel.getRepository()))
                        BzlLoadValue.keyForBuiltins(extensionLabel)
                    else
                        BzlLoadValue.keyForBuild(extensionLabel),
                    exportedName
                )
        }
    }

    override fun hashCode(): Int {
        return keyOrIdentityToken.hashCode()
    }

    override fun equals(otherObject: Any?): Boolean {
        if (this === otherObject) {
            return true
        }
        if (otherObject is StarlarkProvider) {
            return this.keyOrIdentityToken == otherObject.keyOrIdentityToken
        }
        return false
    }

    override fun isImmutable(): Boolean {
        // Hash code for non exported constructors may be changed
        return isExported()
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.append("<provider>")
    }

    override fun toString(): String {
        return net.starlark.java.eval.Starlark.repr(this, net.starlark.java.eval.StarlarkSemantics.DEFAULT)
    }

    /**
     * For schemaful providers, given a value to store in the field identified by `index`,
     * returns a possibly optimized version of the value. The result (optimized or not) should be
     * decoded by [.retrieveOptimizedField].
     * 
     * 
     * Mutable values are never optimized.
     */
    fun optimizeField(index: Int, value: Any?): Any? {
        if (value is Depset) {
            com.google.common.base.Preconditions.checkArgument(depsetTypePredictor != null)
            if (value.isEmpty()) {
                // Most empty depsets have the empty (null) type. We can't store this type because it
                // would clash with whatever the actual element type is for non-empty depsets in that
                // field. So instead just store the optimized (unwrapped) NestedSet without any type
                // information, and assume it's the empty type upon retrieval.
                //
                // This only loses information in the relatively rare case of a native-constructed empty
                // depset with a type restriction (e.g. empty set of artifacts). In that scenario, an
                // empty depset retrieved from the provider may "incorrectly" allow itself to participate
                // in a union with depsets of other types, whereas the original depset would trigger a
                // Starlark eval error. This is a user-observable difference but a very minor one; the
                // hazard would be logical errors that are masked by the provider machinery but triggered
                // by a refactoring of Starlark code. See TODO in Depset#of(Class, NestedSet) for notes
                // about eliminating this semantic confusion.
                //
                // This problem shouldn't arise for non-empty depsets since distinct non-empty element
                // types are not compatible with one another (i.e. there's no Depset<Any> schema).
                return value.getSet()
            }
            val elementClass: java.lang.Class<*>? = value.getElementClass()
            val witness: java.lang.Class<*>? = depsetTypePredictor.compareAndExchange(index, null, elementClass)
            if (witness == elementClass || witness == null) {
                return value.getSet()
            }
        }
        return value
    }

    fun retrieveOptimizedField(index: Int, value: Any?): Any? {
        if (value is NestedSet<*>) {
            // We subvert Depset.of()'s static type checking for consistency between the type token and
            // NestedSet type. This is safe because these values came from a previous Depset, so we
            // already know they're consistent.
            val nestedSet: NestedSet<Any?> = value as NestedSet<Any?>
            if (nestedSet.isEmpty()) {
                // This matches empty depsets created in Starlark with `depset()`.
                return Depset.of(Any::class.java, nestedSet)
            }
            val depset:  // can't parameterize Class literal by a non-raw type
                    Depset? = Depset.of(depsetTypePredictor.get(index) as java.lang.Class<Any?>?, nestedSet)
            return depset
        }
        return value
    }

    fun isOptimised(index: Int, value: Any?): Boolean {
        return value is NestedSet<*>
    }

    fun getKeyOrIdentityToken(): Any {
        return keyOrIdentityToken
    }

    /**
     * A serializable representation of Starlark-defined [StarlarkProvider] that uniquely
     * identifies all [StarlarkProvider]s that are exposed to SkyFrame.
     */
    // TODO: b/335901349 - this is identical to SymbolGenerator.GlobalSymbol<BzlLoadValue.Key> and
    // serves essentially the same purpose. Consider unifying these types.
    class Key(key: BzlLoadValue.Key?, exportedName: String?) : com.google.devtools.build.lib.packages.Provider.Key() {
        private val key: BzlLoadValue.Key
        @kotlin.jvm.JvmField
        private val exportedName: String

        init {
            this.key = com.google.common.base.Preconditions.checkNotNull<BzlLoadValue.Key>(key)
            this.exportedName = com.google.common.base.Preconditions.checkNotNull<String>(exportedName)
        }

        fun getExtensionLabel(): Label? {
            return key.getLabel()
        }

        fun getExportedName(): String {
            return exportedName
        }

        fun getBzlLoadKey(): BzlLoadValue.Key {
            return key
        }

        override fun hashCode(): Int {
            return HashCodes.hashObjects(key, exportedName)
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }

            if (obj !is Key) {
                return false
            }
            return this.key == obj.key
                    && this.exportedName == obj.exportedName
        }

        override fun fingerprint(fp: Fingerprint) {
            // False => Not native.
            fp.addBoolean(false)
            fp.addString(getExtensionLabel().getCanonicalForm())
            fp.addString(getExportedName())
        }

        override fun toString(): String {
            return exportedName
        }
    }

    companion object {
        /**
         * Returns a new empty builder.
         * 
         * 
         * By default (unless [Builder.setExported] is called), the builder will build a provider
         * which is unexported and would need to be exported later via [.export].
         * 
         * 
         * By default (unless [Builder.setSchema] is called), the builder will build a provider
         * which is schemaless.
         * 
         * @param location the location of the Starlark definition for this provider (tests may use [     ][Location.BUILTIN])
         */
        fun builder(location: net.starlark.java.syntax.Location?): Builder {
            return com.google.devtools.build.lib.packages.StarlarkProvider.Builder(location)
        }
    }
}
