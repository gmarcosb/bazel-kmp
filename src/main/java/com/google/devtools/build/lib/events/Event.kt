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
package com.google.devtools.build.lib.events

import java.io.IOException

/**
 * A situation encountered by the build system that's worth reporting.
 * 
 * 
 * An event specifies an [EventKind], a message, and (optionally) additional properties.
 */
@javax.annotation.concurrent.Immutable
@com.google.errorprone.annotations.CheckReturnValue
class Event private constructor(
    kind: com.google.devtools.build.lib.events.EventKind?,
    message: Any?,
    properties: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?
) : com.google.devtools.build.lib.events.Reportable {
    private val kind: com.google.devtools.build.lib.events.EventKind

    /**
     * This field has type [String] or [byte[]].
     * 
     * 
     * If this field is a byte array then it contains the UTF-8-encoded bytes of a message. This
     * optimization avoids converting back and forth between strings and bytes.
     */
    private val message: Any

    /**
     * This map's entries are ordered by [Class.getName].
     * 
     * 
     * That is not a total ordering because of classloaders. The order of entries whose key names
     * are equal is not deterministic.
     */
    private val properties: com.google.common.collect.ImmutableClassToInstanceMap<Any?>

    private var hashCode = 0

    init {
        this.kind =
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.events.EventKind>(kind)
        this.message = com.google.common.base.Preconditions.checkNotNull<Any>(message)
        this.properties =
            com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableClassToInstanceMap<Any?>>(
                properties
            )
    }

    override fun reportTo(handler: com.google.devtools.build.lib.events.ExtendedEventHandler) {
        handler.handle(this)
    }

    override fun storeForReplay(): Boolean {
        return kind != com.google.devtools.build.lib.events.EventKind.PROGRESS && kind != com.google.devtools.build.lib.events.EventKind.WARNING && kind != com.google.devtools.build.lib.events.EventKind.INFO && kind != com.google.devtools.build.lib.events.EventKind.DEBUG
    }

    fun getKind(): com.google.devtools.build.lib.events.EventKind {
        return kind
    }

    fun getMessage(): String {
        return if (message is String) message else String(
            message as ByteArray?,
            java.nio.charset.StandardCharsets.UTF_8
        )
    }

    val messageBytes: ByteArray?
        /**
         * Returns this event's message as a [byte[]]. If this event was instantiated using a [ ], the returned byte array is encoded using [ ][java.nio.charset.StandardCharsets.UTF_8].
         */
        get() = if (message is ByteArray) message else (message as String).getBytes(java.nio.charset.StandardCharsets.UTF_8)

    /** Returns the property value associated with `type` if any, and `null` otherwise.  */
    fun <T> getProperty(type: java.lang.Class<T?>): T? {
        return properties.getInstance<T?>(type)
    }

    /**
     * Returns an [Event] instance that has the same type, message, and properties as the event
     * this is called on, and additionally associates `propertyValue` (if non-`null`) with
     * `type`.
     * 
     * 
     * If the event this is called on already has a property associated with `type` and
     * `propertyValue` is non-`null`, the returned event will have `propertyValue`
     * associated with it instead. If `propertyValue` is non-`null`, the returned event
     * will have no property associated with `type`.
     * 
     * 
     * If the event this is called on has no property associated with `type`, and `propertyValue` is `null`, then this returns that event (it does not create a new [ ] instance).
     * 
     * 
     * In any case, the event this is called on does not change.
     */
    // This implementation would be inefficient if #withProperty is called repeatedly because it may
    // copy and sort the key collection. In practice we expect it to be called a small number of times
    // per event (e.g. fewer than 5; usually 0).
    //
    // If that changes then consider an Event.Builder strategy instead.
    fun <T> withProperty(type: java.lang.Class<T?>?, propertyValue: T?): Event {
        val orderedKeys: Iterable<java.lang.Class<*>>
        val containsKey: Boolean = properties.containsKey(type)
        if (!containsKey && propertyValue != null) {
            TODO(
                """
                |Cannot convert element
                |With text:
                |orderedKeys =
                |          Stream.<Class<?>>concat(properties.keySet().stream(), Stream.<Class<T>>of(type))
                |              .sorted(<Class<?>, String>comparing(Class::getName)
                """.trimMargin()
            )
            if (collect(TODO("Cannot convert element")) < Class) shr com . google . common . collect . ImmutableList . toImmutableList < kotlin . Any ? > ()
        } else if (containsKey) {
            orderedKeys = properties.keySet()
        } else {
            // !containsKey and propertyValue is null, so there's nothing to change.
            return this
        }

        val newProperties: com.google.common.collect.ImmutableClassToInstanceMap.Builder<Any?> =
            com.google.common.collect.ImmutableClassToInstanceMap.Builder<Any?>()
        for (key in orderedKeys) {
            if (key == type) {
                if (propertyValue != null) {
                    newProperties.put<T?>(type, propertyValue)
                }
            } else {
                addToBuilder(newProperties, key)
            }
        }

        return com.google.devtools.build.lib.events.Event(kind, message, newProperties.build())
    }

    /**
     * This type-parameterized method solves a problem where a `properties.getInstance(key)`
     * expression would have type [Object] when `key` is a wildcard-parameterized [ ]. That [Object]-typed expression would then fail to type check in a `builder.put(key, properties.getInstance(key))` statement.
     */
    private fun <T> addToBuilder(
        builder: com.google.common.collect.ImmutableClassToInstanceMap.Builder<Any?>,
        key: java.lang.Class<T?>
    ) {
        builder.put<T?>(key, com.google.common.base.Preconditions.checkNotNull<T?>(properties.getInstance<T?>(key)))
    }

    /**
     * {@inheritDoc}
     * 
     * 
     * Behaves like [.withProperty], with `type.equals(String.class)`.
     * 
     * 
     * Additionally, if the event this is called on already has a [String] property with
     * value `tag`, or if `tag` is `null` and the event has no [String]
     * property, then this returns that event (it does not create a new [Event] instance).
     */
    override fun withTag(tag: String?): Event? {
        if (tag == getProperty<String?>(String::class.java)) {
            return this
        }
        return withProperty<String?>(String::class.java, tag)
    }

    /**
     * Returns a new event with the provided [ProcessOutput] property. See [.withProperty]
     * for more specifics.
     */
    fun withProcessOutput(processOutput: ProcessOutput?): Event {
        return withProperty<ProcessOutput?>(
            com.google.devtools.build.lib.events.Event.ProcessOutput::class.java,
            processOutput
        )
    }

    val tag: String?
        /**
         * Returns the [String] property, if any, asssociated with the event. When non-null, this
         * value typically describes some property of the action that generated the event.
         */
        get() = getProperty<String?>(String::class.java)

    val processOutput: ProcessOutput?
        get() = getProperty<ProcessOutput?>(com.google.devtools.build.lib.events.Event.ProcessOutput::class.java)

    val stdOut: ByteArray?
        /** Returns the stdout bytes associated with this event if any, and `null` otherwise.  */
        get() {
            val processOutput =
                getProperty<ProcessOutput?>(com.google.devtools.build.lib.events.Event.ProcessOutput::class.java)
            if (processOutput == null) {
                return null
            }
            return processOutput.stdOut
        }

    val stdErr: ByteArray?
        /** Returns the stderr bytes associated with this event if any, and `null` otherwise.  */
        get() {
            val processOutput =
                getProperty<ProcessOutput?>(com.google.devtools.build.lib.events.Event.ProcessOutput::class.java)
            if (processOutput == null) {
                return null
            }
            return processOutput.stdErr
        }

    val location: net.starlark.java.syntax.Location?
        /**
         * Returns the location of this event, if any. Returns null iff the event wasn't associated with
         * any particular location, for example, a progress message.
         */
        get() = getProperty<net.starlark.java.syntax.Location?>(net.starlark.java.syntax.Location::class.java)

    /** Returns the event formatted as `"ERROR foo.bzl:1:2: oops"`.  */
    override fun toString(): String {
        val location: net.starlark.java.syntax.Location? = this.location
        // TODO(adonovan): <no location> is just noise.
        return (kind
            .toString() + " "
                + (if (location != null) location.toString() else "<no location>")
                + ": "
                + getMessage())
    }

    override fun hashCode(): Int {
        // We defer the computation of hashCode until it is needed to avoid the overhead of computing it
        // and then never using it. In particular, we use Event for streaming stdout and stderr, which
        // are both large and the hashCode is never used.
        //
        // This uses the same construction as String.hashCode. We don't lock, so reads and writes to the
        // field can race. However, integer reads and writes are atomic, and this code guarantees that
        // all writes have the same value, so the memory location can only be either 0 or the final
        // value. Note that a reader could see the final value on the first read and 0 on the second
        // read, so we must take care to only read the field once.
        var h = hashCode
        if (h == 0) {
            h =
                java.util.Objects.hash(
                    kind,
                    if (message is String) message else java.util.Arrays.hashCode(message as ByteArray?),
                    properties
                )
            hashCode = h
        }
        return h
    }

    override fun equals(other: Any?): Boolean {
        if (other === this) {
            return true
        }
        if (other == null || other.getClass() != getClass()) {
            return false
        }
        val that = other as Event
        return this.kind == that.kind
                && this.message.getClass() == that.message.getClass()
                && (if (this.message is String)
            (this.message == that.message)
        else
            java.util.Arrays.equals(this.message as ByteArray, that.message as ByteArray))
                && this.properties == that.properties
    }

    /**
     * Process output associated with an event. The contents is just-about-certainly on disk, so
     * special care should be taken when accessing it.
     * 
     * 
     * Note that this indirection exists partially for documentation sake, but also to keep the
     * event library lightweight and broadly usable by avoiding bringing in all of the dependencies
     * that come with dealing with process output (specifically the filesystem library).
     */
    interface ProcessOutput {
        /**
         * Returns the string representation of the path containing the process's stdout for
         * logging/debugging purposes.
         */
        @kotlin.jvm.JvmField
        val stdOutPath: String?

        @kotlin.jvm.JvmField
        @get:Throws(IOException::class)
        val stdOutSize: Long

        @kotlin.jvm.JvmField
        val stdOut: ByteArray?

        /**
         * Returns the string representation of the path containing the process's stderr for
         * logging/debugging purposes.
         */
        @kotlin.jvm.JvmField
        val stdErrPath: String?

        @kotlin.jvm.JvmField
        @get:Throws(IOException::class)
        val stdErrSize: Long

        @kotlin.jvm.JvmField
        val stdErr: ByteArray?
    }

    companion object {
        /** Constructs an event with the provided [EventKind] and [String] message.  */
        fun of(kind: com.google.devtools.build.lib.events.EventKind?, message: String?): Event {
            return com.google.devtools.build.lib.events.Event(
                kind,
                message,
                com.google.common.collect.ImmutableClassToInstanceMap.of<Any?>()
            )
        }

        /**
         * Constructs an event with the provided [EventKind], [String] message, and single
         * property value.
         * 
         * 
         * See [.withProperty] if more than one property value is desired.
         */
        fun <T> of(
            kind: com.google.devtools.build.lib.events.EventKind?,
            message: String?,
            propertyType: java.lang.Class<T?>,
            propertyValue: T?
        ): Event {
            return com.google.devtools.build.lib.events.Event(
                kind,
                message,
                com.google.common.collect.ImmutableClassToInstanceMap.of<Any?, T?>(propertyType, propertyValue)
            )
        }

        /** Constructs an event with the provided [EventKind] and [byte[]] message.  */
        fun of(kind: com.google.devtools.build.lib.events.EventKind?, messageBytes: ByteArray?): Event {
            return com.google.devtools.build.lib.events.Event(
                kind,
                messageBytes,
                com.google.common.collect.ImmutableClassToInstanceMap.of<Any?>()
            )
        }

        /**
         * Constructs an event with the provided [EventKind], [byte[]] message, and single
         * property value.
         * 
         * 
         * See [.withProperty] if more than one property value is desired.
         */
        fun <T> of(
            kind: com.google.devtools.build.lib.events.EventKind?,
            messageBytes: ByteArray?,
            propertyType: java.lang.Class<T?>,
            propertyValue: T?
        ): Event {
            return com.google.devtools.build.lib.events.Event(
                kind,
                messageBytes,
                com.google.common.collect.ImmutableClassToInstanceMap.of<Any?, T?>(propertyType, propertyValue)
            )
        }

        /**
         * Constructs an event with the provided [EventKind] and [String] message, with an
         * optional [Location].
         */
        fun of(
            kind: com.google.devtools.build.lib.events.EventKind?,
            location: net.starlark.java.syntax.Location?,
            message: String?
        ): Event {
            return if (location == null) com.google.devtools.build.lib.events.Event.Companion.of(
                kind,
                message
            ) else com.google.devtools.build.lib.events.Event.Companion.of<net.starlark.java.syntax.Location?>(
                kind,
                message,
                net.starlark.java.syntax.Location::class.java,
                location
            )
        }

        /**
         * Constructs an event with a `byte[]` array instead of a [String] for its message.
         * 
         * 
         * The bytes must be decodable as UTF-8 text.
         */
        fun of(
            kind: com.google.devtools.build.lib.events.EventKind?,
            location: net.starlark.java.syntax.Location?,
            messageBytes: ByteArray?
        ): Event {
            return if (location == null)
                com.google.devtools.build.lib.events.Event.Companion.of(kind, messageBytes)
            else
                com.google.devtools.build.lib.events.Event.Companion.of<net.starlark.java.syntax.Location?>(
                    kind,
                    messageBytes,
                    net.starlark.java.syntax.Location::class.java,
                    location
                )
        }

        /** Constructs an event with kind [EventKind.FATAL].  */
        fun fatal(message: String?): Event {
            return com.google.devtools.build.lib.events.Event.Companion.of(
                com.google.devtools.build.lib.events.EventKind.FATAL,
                message
            )
        }

        /** Constructs an event with kind [EventKind.ERROR], with an optional [Location].  */
        fun error(location: net.starlark.java.syntax.Location?, message: String?): Event {
            return if (location == null)
                com.google.devtools.build.lib.events.Event.Companion.of(
                    com.google.devtools.build.lib.events.EventKind.ERROR,
                    message
                )
            else
                com.google.devtools.build.lib.events.Event.Companion.of<net.starlark.java.syntax.Location?>(
                    com.google.devtools.build.lib.events.EventKind.ERROR,
                    message,
                    net.starlark.java.syntax.Location::class.java,
                    location
                )
        }

        /** Constructs an event with kind [EventKind.ERROR].  */
        @kotlin.jvm.JvmStatic
        fun error(message: String?): Event {
            return com.google.devtools.build.lib.events.Event.Companion.of(
                com.google.devtools.build.lib.events.EventKind.ERROR,
                message
            )
        }

        /** Constructs an event with kind [EventKind.WARNING], with an optional [Location].  */
        fun warn(location: net.starlark.java.syntax.Location?, message: String?): Event {
            return if (location == null)
                com.google.devtools.build.lib.events.Event.Companion.of(
                    com.google.devtools.build.lib.events.EventKind.WARNING,
                    message
                )
            else
                com.google.devtools.build.lib.events.Event.Companion.of<net.starlark.java.syntax.Location?>(
                    com.google.devtools.build.lib.events.EventKind.WARNING,
                    message,
                    net.starlark.java.syntax.Location::class.java,
                    location
                )
        }

        /** Constructs an event with kind [EventKind.WARNING].  */
        @kotlin.jvm.JvmStatic
        fun warn(message: String?): Event {
            return com.google.devtools.build.lib.events.Event.Companion.of(
                com.google.devtools.build.lib.events.EventKind.WARNING,
                message
            )
        }

        /** Constructs an event with kind [EventKind.INFO], with an optional [Location].  */
        fun info(location: net.starlark.java.syntax.Location?, message: String?): Event {
            return if (location == null)
                com.google.devtools.build.lib.events.Event.Companion.of(
                    com.google.devtools.build.lib.events.EventKind.INFO,
                    message
                )
            else
                com.google.devtools.build.lib.events.Event.Companion.of<net.starlark.java.syntax.Location?>(
                    com.google.devtools.build.lib.events.EventKind.INFO,
                    message,
                    net.starlark.java.syntax.Location::class.java,
                    location
                )
        }

        /** Constructs an event with kind [EventKind.INFO].  */
        @kotlin.jvm.JvmStatic
        fun info(message: String?): Event {
            return com.google.devtools.build.lib.events.Event.Companion.of(
                com.google.devtools.build.lib.events.EventKind.INFO,
                message
            )
        }

        /**
         * Constructs an event with kind [EventKind.PROGRESS], with an optional [Location].
         */
        fun progress(location: net.starlark.java.syntax.Location?, message: String?): Event {
            return if (location == null)
                com.google.devtools.build.lib.events.Event.Companion.of(
                    com.google.devtools.build.lib.events.EventKind.PROGRESS,
                    message
                )
            else
                com.google.devtools.build.lib.events.Event.Companion.of<net.starlark.java.syntax.Location?>(
                    com.google.devtools.build.lib.events.EventKind.PROGRESS,
                    message,
                    net.starlark.java.syntax.Location::class.java,
                    location
                )
        }

        /** Constructs an event with kind [EventKind.PROGRESS].  */
        @kotlin.jvm.JvmStatic
        fun progress(message: String?): Event {
            return com.google.devtools.build.lib.events.Event.Companion.of(
                com.google.devtools.build.lib.events.EventKind.PROGRESS,
                message
            )
        }

        /** Constructs an event with kind [EventKind.DEBUG], with an optional [Location].  */
        fun debug(location: net.starlark.java.syntax.Location?, message: String?): Event {
            return if (location == null)
                com.google.devtools.build.lib.events.Event.Companion.of(
                    com.google.devtools.build.lib.events.EventKind.DEBUG,
                    message
                )
            else
                com.google.devtools.build.lib.events.Event.Companion.of<net.starlark.java.syntax.Location?>(
                    com.google.devtools.build.lib.events.EventKind.DEBUG,
                    message,
                    net.starlark.java.syntax.Location::class.java,
                    location
                )
        }

        /** Constructs an event with kind [EventKind.DEBUG].  */
        @kotlin.jvm.JvmStatic
        fun debug(message: String?): Event {
            return com.google.devtools.build.lib.events.Event.Companion.of(
                com.google.devtools.build.lib.events.EventKind.DEBUG,
                message
            )
        }

        /** Replays a sequence of events on `handler`.  */
        fun replayEventsOn(handler: com.google.devtools.build.lib.events.EventHandler, events: Iterable<Event?>) {
            for (event in events) {
                handler.handle(event)
            }
        }

        /** Converts a list of [SyntaxError]s to events and replays them on `handler`.  */
        fun replayEventsOn(
            handler: com.google.devtools.build.lib.events.EventHandler,
            errors: MutableList<net.starlark.java.syntax.SyntaxError>
        ) {
            for (error in errors) {
                handler.handle(
                    com.google.devtools.build.lib.events.Event.Companion.error(
                        error.location(),
                        error.message()
                    )
                )
            }
        }

        /**
         * Returns a [StarlarkThread.PrintHandler] that sends [EventKind.DEBUG] events to the
         * provided [EventHandler].
         */
        fun makeDebugPrintHandler(h: com.google.devtools.build.lib.events.EventHandler): net.starlark.java.eval.StarlarkThread.PrintHandler {
            return net.starlark.java.eval.StarlarkThread.PrintHandler { thread: net.starlark.java.eval.StarlarkThread?, msg: String? ->
                h.handle(
                    com.google.devtools.build.lib.events.Event.Companion.debug(thread.getCallerLocation(), msg)
                )
            }
        }
    }
}
