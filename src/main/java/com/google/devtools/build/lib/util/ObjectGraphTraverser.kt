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
package com.google.devtools.build.lib.util

import com.google.common.flogger.GoogleLogger
import com.google.devtools.build.lib.collect.ConcurrentIdentitySet
import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * Traverses the Java object graph.
 * 
 * 
 * When given a Java object, it walks the objects reachable from it. Returns each object only
 * once, regardless of the number of edges it is reachable through.
 * 
 * 
 * For each object and reference edge found, the appropriate method of [ObjectReceiver] is
 * called.
 * 
 * 
 * The traversal is customizable by passing in [DomainSpecificTraverser] instances. Each
 * object can choose to handle any given object instance; in that case, it should return the
 * user-friendly "context" the object is encountered in and the outgoing edges in the object graph
 * it has.
 * 
 * 
 * If an object is not handled by any domain-specific traverser, Java reflection is used to
 * discover its outgoing references. In this case, domain-specific traversers are still consulted to
 * learn whether any of the fields should be ignored.
 * 
 * 
 * The traversal stops at objects that:
 * 
 * 
 *  * Are in the `seenObjects` set passed into the constructor
 *  * For which at least one [DomainSpecificTraverser] returns false in [       ][DomainSpecificTraverser.admit]
 * 
 * 
 * 
 * A traversal currently operates on a single thread. It's not an inherent limitation, it's just
 * that it was found to be much faster than spawning a new Executor task for each Java object.
 */
class ObjectGraphTraverser @kotlin.jvm.JvmOverloads constructor(
    private val fieldCache: FieldCache,
    private val countInternedObjects: Boolean,
    private val reportTransientFields: Boolean,
    seenObjects: ConcurrentIdentitySet,
    collectContext: Boolean,
    receiver: ObjectReceiver,
    instanceId: Any?,
    private val needle: String? = null
) {
    /**
     * Cache for traversal data by object type.
     * 
     * 
     * Not a static field because it depends on what domain-specific traversers there are.
     */
    class FieldCache(domainSpecificTraversers: com.google.common.collect.ImmutableList<DomainSpecificTraverser>) {
        private val fieldCache: MutableMap<java.lang.Class<*>?, MutableList<java.lang.reflect.Field>>
        private val domainSpecificTraversers: com.google.common.collect.ImmutableList<DomainSpecificTraverser>

        init {
            this.fieldCache =
                ConcurrentHashMap<java.lang.Class<*>?, MutableList<java.lang.reflect.Field>>(
                    128,
                    0.75f,
                    java.lang.Runtime.getRuntime().availableProcessors()
                )
            this.domainSpecificTraversers = domainSpecificTraversers
        }
    }

    /** Domain-specific knowledge about classes to traverse.  */
    interface DomainSpecificTraverser {
        /**
         * Called for each object to be traversed.
         * 
         * 
         * In order for the traversal of an object to be attempted, the [.admit] admit
         * method of all domain-specific traversals must return true.
         * 
         * 
         * If the implementation knows how to traverse this object, it should return true and call
         * methods on [Traversal] accordingly.
         * 
         * 
         * If not domain-specific traversal handles an object, its fields will be visited by Java
         * reflection.
         * 
         * @return true if the object is handled.
         */
        fun maybeTraverse(o: Any?, traversal: Traversal?): Boolean

        /**
         * Should return true if the object is interned.
         * 
         * 
         * Reachable interned objects are always reported as seen objects, even if they are already
         * marked as seen. This makes sense because one can't assign a single owner to them so we either
         * assign them to everyone who references them or no one, and the latter would make us lose
         * track of their RAM use.
         */
        fun isInterned(o: Any?): Boolean

        /**
         * Called on each object to be traversed.
         * 
         * 
         * If the implementation thinks this instance should **not** be traversed, it should
         * return false. An implementation may well allow traversing an object and yet decline to handle
         * it in [.maybeTraverse]; in that case, the default traversal will be
         * applied to the object.
         * 
         * @return false if the implementation wants to prohibit the traversal of this object.
         */
        fun admit(o: Any?): Boolean

        /**
         * Compute the user-friendly context for an array item.
         * 
         * 
         * This is used to describe what an object is in a way that's more meaningful to the user
         * than its raw class. Only called of `collectContext` is true. If multiple
         * domain-specific traversals provide a context, the first one takes priority.
         * 
         * 
         * This method is not called for references reported by domain-specific traversers.
         * 
         * @param from the array the reference originates from
         * @param fromContext the context of the array the reference originates from
         * @param to the referenced object
         * @return the context of `to`, or null, if its class is enough
         */
        fun contextForArrayItem(from: Any?, fromContext: String?, to: Any?): String?

        /**
         * Compute the user-friendly context for a field.
         * 
         * 
         * This is used to describe what an object is in a way that's more meaningful to the user
         * than its raw class. Only called of `collectContext` is true. If multiple
         * domain-specific traversals provide a context, the first one takes priority.
         * 
         * 
         * This method is not called for references reported by domain-specific traversers.
         * 
         * @param from the object the reference originates from
         * @param fromContext the context of the object the reference originates from
         * @param field the field the reference is through
         * @param to the referenced object
         * @return the context of `to`, or null, if its class is enough
         */
        fun contextForField(from: Any?, fromContext: String?, field: java.lang.reflect.Field?, to: Any?): String?

        /**
         * Return the set of fields of a class that should be ignored.
         * 
         * @return the set of ignored fields or null if the implementation doesn't know about the given
         * class.
         */
        fun ignoredFields(clazz: java.lang.Class<*>?): com.google.common.collect.ImmutableSet<String?>?
    }

    /**
     * Callback through which [DomainSpecificTraverser] returns what objects and edges it found.
     */
    interface Traversal {
        /**
         * Should be called when the domain-specific traverser finds an object. It should be called for
         * every object for which [DomainSpecificTraverser.maybeTraverse]
         * returns true.
         * 
         * @param o the object found
         * @param context the context the object is in or null of its class is enough
         */
        fun objectFound(o: Any?, context: String?)

        /**
         * Should be called for each outgoing reference in an object handled by the [ ] implementation.
         * 
         * 
         * Objects reported through this method are subject to two kinds of filtering: each object is
         * only processed once and domain-specific traversers can prohibit the traversal of any object
         * by returning false from [DomainSpecificTraverser.admit].
         * 
         * @param to the object referenced
         * @param context the context of the referenced object or null if its class is enough
         */
        fun edgeFound(to: Any?, context: String?)
    }

    /** The type of an object graph edge.  */
    enum class EdgeType {
        /** An edge to an object discovered during this traversal.  */
        CURRENT_TRAVERSAL,

        /** An edge to an object already seen in previous traversals.  */
        ALREADY_SEEN
    }

    /** A callback where [ObjectGraphTraverser] reports the objects and edges encountered.  */
    interface ObjectReceiver {
        /** Reports an object in a given context.  */
        fun objectFound(o: Any?, context: String?)

        /** Reports an edge in the object graph.  */
        fun edgeFound(from: Any?, to: Any?, toContext: String?, edgeType: EdgeType?)
    }

    /** An object to be traversed in the queue.  */
    @kotlin.jvm.JvmRecord
    private data class WorkItem(val `object`: Any, val context: String?, val parent: WorkItem?)

    private val collectContext: Boolean
    private val traversal: Traversal
    private val receiver: ObjectReceiver
    private val instanceId: Any?

    private var currentWorkItem: WorkItem? = null
    private val queue: java.util.Queue<WorkItem> = ArrayDeque<WorkItem>()

    private val localObjects: ConcurrentIdentitySet
    private val seenObjects: ConcurrentIdentitySet

    /**
     * Traverses a given object.
     * 
     * 
     * Can be called multiple times, but no two traversals should be active at the same time in a
     * given [ObjectGraphTraverser] instance.
     */
    fun traverse(o: Any) {
        for (traverser in fieldCache.domainSpecificTraversers) {
            if (!traverser.admit(o)) {
                return
            }
        }

        queue.offer(WorkItem(o, null, null))
        while (!queue.isEmpty()) {
            val workItem: WorkItem = queue.remove()
            try {
                process(workItem)
            } catch (e: java.lang.RuntimeException) {
                logger.atSevere().withCause(e).log("While walking object graph for key %s:", instanceId)
            }
        }
    }

    private fun enqueueMaybe(to: Any?, toContext: String?) {
        if (to == null) {
            return
        }

        if (to is Int
            || to is Long
            || to is Short
            || to is Byte
            || to is Float
            || to is Double
            || to is Char
            || to is Boolean
        ) {
            // Boxed primitive type
            return
        }

        for (traverser in fieldCache.domainSpecificTraversers) {
            if (!traverser.admit(to)) {
                return
            }
        }

        if (!localObjects.add(to)) {
            // A reference to an object visited during this traversal.
            receiver.edgeFound(currentWorkItem!!.`object`, to, toContext, EdgeType.CURRENT_TRAVERSAL)
            return
        }

        var traverse: Boolean

        if (!seenObjects.add(to)) {
            // A reference to an object already seen, but not during this traversal.
            receiver.edgeFound(currentWorkItem!!.`object`, to, toContext, EdgeType.ALREADY_SEEN)
            traverse = false
            if (countInternedObjects) {
                for (traverser in fieldCache.domainSpecificTraversers) {
                    if (traverser.isInterned(to)) {
                        traverse = true
                        break
                    }
                }
            }
        } else {
            // A new object.
            receiver.edgeFound(currentWorkItem!!.`object`, to, toContext, EdgeType.CURRENT_TRAVERSAL)
            traverse = true
        }

        if (traverse) {
            queue.offer(WorkItem(to, toContext, currentWorkItem))
        }
    }

    private fun contextOrNull(context: String?, defaultContext: String?): String? {
        if (!collectContext) {
            return null
        }

        if (context != null) {
            return context
        }

        return defaultContext
    }

    /**
     * Creates a new traverser.
     * 
     * @param fieldCache the cache for reflection results.
     * @param countInternedObjects whether to count interned objects only once or each them they are
     * encountered
     * @param reportTransientFields whether to recurse into transient fields
     * @param seenObjects the set of objects already seen. These are not traversed and references to
     * them are reported as [EdgeType.ALREADY_SEEN] .
     * @param collectContext whether to collect context for each object. Costs some CPU.
     * @param receiver the object to report found objects and edges to.
     * @param instanceId an object identifying this traversal.
     */
    init {
        this.seenObjects = seenObjects
        this.collectContext = collectContext
        this.receiver = receiver
        this.instanceId = instanceId
        this.traversal =
            object : Traversal {
                override fun objectFound(o: Any?, context: String?) {
                    receiver.objectFound(o, context)
                }

                override fun edgeFound(to: Any?, context: String?) {
                    enqueueMaybe(to, context)
                }
            }

        this.localObjects = ConcurrentIdentitySet(64)
    }

    private fun dumpTrace(workItem: WorkItem?) {
        var workItem = workItem
        java.lang.System.err.println("Needle reached by path:")
        while (workItem != null) {
            java.lang.System.err.println("  " + workItem.`object`.javaClass.getName())
            workItem = workItem.parent
        }
        java.lang.System.err.println()
    }

    private fun process(workItem: WorkItem) {
        val o = workItem.`object`
        val context = workItem.context
        currentWorkItem = workItem

        if (needle != null && o.javaClass.getName() == needle) {
            dumpTrace(workItem)
        }

        if (o is String) {
            traversal.objectFound(o, contextOrNull(context, "STRING"))
            return
        }

        for (traverser in fieldCache.domainSpecificTraversers) {
            if (traverser.maybeTraverse(o, traversal)) {
                return
            }
        }

        if (o is java.lang.Class<*>) {
            traversal.objectFound(o, contextOrNull(context, "CLASS"))
            return
        }

        val clazz: java.lang.Class<*> = o.javaClass

        if (clazz.isArray()) {
            traversal.objectFound(o, contextOrNull(context, "[] " + clazz.getComponentType().getName()))

            // We only care about objects
            if (!clazz.getComponentType().isPrimitive()) {
                for (i in 0..<java.lang.reflect.Array.getLength(o)) {
                    val to: Any? = java.lang.reflect.Array.get(o, i)
                    var toContext: String? = null
                    if (collectContext) {
                        for (traverser in fieldCache.domainSpecificTraversers) {
                            val candidate: String? = traverser.contextForArrayItem(o, context, to)
                            if (candidate != null) {
                                toContext = candidate
                                break
                            }
                        }
                    }

                    enqueueMaybe(to, toContext)
                }
            }
        } else {
            traversal.objectFound(o, context)

            val fields: MutableList<java.lang.reflect.Field> =
                fieldCache.fieldCache.computeIfAbsent(clazz) { clazz: java.lang.Class<*>? -> this.getFields(clazz) }
            for (field in fields) {
                try {
                    val to: Any? = field.get(o)
                    var toContext: String? = null
                    if (collectContext) {
                        for (traverser in fieldCache.domainSpecificTraversers) {
                            val candidate: String? = traverser.contextForField(o, context, field, to)
                            if (candidate != null) {
                                toContext = candidate
                                break
                            }
                        }
                    }
                    enqueueMaybe(to, toContext)
                } catch (e: java.lang.IllegalAccessException) {
                    throw java.lang.IllegalStateException(e)
                }
            }
        }
    }

    private fun getFields(clazz: java.lang.Class<*>?): com.google.common.collect.ImmutableList<java.lang.reflect.Field?> {
        val fields: java.util.ArrayList<java.lang.reflect.Field?> = java.util.ArrayList<java.lang.reflect.Field?>()
        var next: java.lang.Class<*>? = clazz
        while (next != null) {
            var ignoreSet: com.google.common.collect.ImmutableSet<String?>? =
                com.google.common.collect.ImmutableSet.of<String?>()
            for (traverser in fieldCache.domainSpecificTraversers) {
                val candidate: com.google.common.collect.ImmutableSet<String?>? = traverser.ignoredFields(next)
                if (candidate != null) {
                    ignoreSet = candidate
                    break
                }
            }

            for (field in next.getDeclaredFields()) {
                if (!reportTransientFields && (field.getModifiers() and java.lang.reflect.Modifier.TRANSIENT) != 0) {
                    continue
                }

                // Skip static fields
                if ((field.getModifiers() and java.lang.reflect.Modifier.STATIC) != 0) {
                    continue
                }
                if (ignoreSet.contains(field.getName())) {
                    continue
                }

                if (field.getType().isPrimitive()) {
                    // We only care about the object graph
                    continue
                }

                if (field.getType().isEnum()) {
                    // Enum instances are not interesting, they are always known at compile time
                    continue
                }

                try {
                    field.setAccessible(true)
                } catch (e: java.lang.reflect.InaccessibleObjectException) {
                    // Ignore this field then, dunno why this happens.
                    continue
                }
                fields.add(field)
            }
            next = next.getSuperclass()
        }

        return com.google.common.collect.ImmutableList.copyOf<java.lang.reflect.Field?>(fields)
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        val NOOP_OBJECT_RECEIVER: ObjectReceiver = object : ObjectReceiver {
            override fun objectFound(o: Any?, context: String?) {}

            override fun edgeFound(from: Any?, to: Any?, toContext: String?, edgeType: EdgeType?) {}
        }
    }
}
