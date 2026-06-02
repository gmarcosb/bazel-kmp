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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe

/**
 * A map that is backed by persistent storage. It uses two files on disk for this: The first file
 * contains all the entries and gets written when invoking the [.save] method. The second
 * file contains a journal of all entries that were added to or removed from the map since
 * constructing the instance of the map or the last invocation of [.save] and gets written
 * after each update of the map although sub-classes are free to implement their own journal update
 * strategy.
 * 
 * 
 * **Ceci n'est pas un Map**. Strictly speaking, the [Map] interface doesn't permit the
 * possibility of failure. This class uses persistence; persistence means I/O, and I/O means the
 * possibility of failure. Therefore the semantics of this may deviate from the Map contract in
 * failure cases. In particular, updates are not guaranteed to succeed. However, I/O failures are
 * guaranteed to be reported upon the subsequent call to a method that throws `IOException`
 * such as [.save].
 * 
 * 
 * To populate the map entries using the previously persisted entries call [.load] prior
 * to invoking any other map operation.
 * 
 * 
 * Like [Hashtable] but unlike [HashMap], this class does *not* allow
 * <tt>null</tt> to be used as a key or a value.
 * 
 * 
 * IO failures during reading or writing the map entries to disk may result in [ ] getting thrown from the failing method.
 * 
 * 
 * The constructor allows passing in a version number that gets written to the files on disk and
 * checked before reading from disk. Files with an incompatible version number will be ignored. This
 * allows the client code to change the persistence format without polluting the file system name
 * space.
 */
abstract class PersistentMap<K, V> protected constructor(
    private val version: Int,
    codec: MapCodec<K?, V?>,
    map: ConcurrentMap<K?, V?>,
    mapFile: com.google.devtools.build.lib.vfs.Path,
    journalFile: com.google.devtools.build.lib.vfs.Path
) : com.google.common.collect.ForwardingConcurrentMap<K?, V?>() {
    @javax.annotation.concurrent.GuardedBy("this")
    private val mapFile: com.google.devtools.build.lib.vfs.Path

    @javax.annotation.concurrent.GuardedBy("this")
    private val journalFile: com.google.devtools.build.lib.vfs.Path

    private val journal: LinkedBlockingQueue<K?>

    /**
     * If non-null, contains the message from an `IOException` thrown by a previously failed
     * write. This error is deferred until the next call to a method which is able to throw an
     * exception.
     */
    @javax.annotation.concurrent.GuardedBy("this")
    private var deferredIOFailure: String? = null

    /**
     * 'loaded' is true when the in-memory representation is at least as recent as the on-disk
     * representation.
     */
    private var loaded = false

    private val delegate: ConcurrentMap<K?, V?>

    private val codec: MapCodec<K?, V?>

    /**
     * Creates a new PersistentMap instance using the specified backing map.
     * 
     * @param version the version tag. Changing the version tag allows updating the on disk format.
     * The map will never read from a file that was written using a different version tag.
     * @param codec the codec used to convert between the in-memory and on-disk representations.
     * @param map the backing map to use for this PersistentMap.
     * @param mapFile the file to save the map entries to.
     * @param journalFile the journal file to write entries between invocations of [.save].
     */
    init {
        this.codec = codec
        journal = LinkedBlockingQueue<K?>()
        this.mapFile = mapFile
        this.journalFile = journalFile
        delegate = map
    }

    override fun delegate(): ConcurrentMap<K?, V?> {
        return delegate
    }

    @ThreadSafe
    override fun put(key: K?, value: V?): V? {
        val previous: V? = delegate.put(key, value)
        journal.add(key)
        maybeFlushJournal()
        return previous
    }

    @ThreadSafe
    override fun putIfAbsent(key: K?, value: V?): V? {
        val previous: V? = delegate.putIfAbsent(key, value)
        if (previous == null) {
            journal.add(key)
            maybeFlushJournal()
        }
        return previous
    }

    /**
     * Potentially flushes the in-memory journal to disk, as determined by [ ][.shouldFlushJournal].
     */
    @ThreadSafe
    private fun maybeFlushJournal() {
        if (shouldFlushJournal()) {
            flushJournal()
        }
    }

    /**
     * Determines whether the in-memory journal should be flushed to disk.
     * 
     * 
     * Called whenever an update is appended to the in-memory journal. The default is to flush it
     * immediately, but subclasses are free to override this to implement their own strategy.
     */
    protected open fun shouldFlushJournal(): Boolean {
        return true
    }

    @ThreadSafe
    override fun remove(`object`: Any?): V? {
        val previous: V? = delegate.remove(`object`)
        if (previous != null) {
            // we know that 'object' must be an instance of K, because the
            // remove call succeeded, i.e. 'object' was mapped to 'previous'.
            journal.add(`object` as K?) // unchecked
            maybeFlushJournal()
        }
        return previous
    }

    override fun replace(key: K?, value: V?): V? {
        throw java.lang.UnsupportedOperationException()
    }

    override fun replace(key: K?, oldValue: V?, newValue: V?): Boolean {
        throw java.lang.UnsupportedOperationException()
    }

    /** Flushes the in-memory journal to disk.  */
    @kotlin.jvm.Synchronized
    fun flushJournal() {
        // Append to a preexisting journal file, which may have been left around after the last save()
        // because shouldKeepJournal() was true.
        try {
            codec.createWriter(journalFile, version,  /* overwrite= */false).use { journalOut ->
                // Journal may have duplicates, we can ignore them.
                val keys: LinkedHashSet<K?> =
                    com.google.common.collect.Sets.newLinkedHashSetWithExpectedSize<K?>(journal.size)
                journal.drainTo(keys)
                writeEntries(journalOut, keys)
            }
        } catch (e: IOException) {
            this.deferredIOFailure = e.message + " during journal append"
        }
    }

    /**
     * Loads the previous written map entries from disk.
     * 
     * 
     * If no on-disk state exists, loading is successful and produces an empty map.
     * 
     * 
     * Data corruption is handled differently for each file:
     * 
     * 
     *  * Corruption in the map file is treated as an error, as the file is updated atomically.
     *  * Corruption in the journal file is tolerated by ignoring the remaining contents, as the
     * file is updated non-atomically.
     * 
     * @throws IncompatibleFormatException if the on-disk data is in an incompatible format
     * @throws IOException if data corruption is detected and cannot be recovered from, or some other
     * I/O error occurs
     */
    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    fun load() {
        if (!loaded) {
            if (mapFile.exists()) {
                loadEntries(mapFile)
            }
            if (journalFile.exists()) {
                try {
                    loadEntries(journalFile)
                } catch (e: IOException) {
                    if (e is IncompatibleFormatException) {
                        throw e
                    }
                }
                // Merge the journal into the map file and delete the former, ensuring that we don't keep
                // appending to a corrupted journal.
                // TODO(tjgq): Avoid doing this unless journal corruption was detected.
                save( /* fullSave= */true)
            }
            loaded = true
        }
    }

    @kotlin.jvm.Synchronized
    override fun clear() {
        super.clear()
        try {
            // We must do a full save because we're bypassing the journal.
            save( /* fullSave= */true)
        } catch (e: IOException) {
            this.deferredIOFailure = e.message + " during map clear"
        }
    }

    /**
     * Saves the map to disk.
     * 
     * @throws IOException if there was an I/O error during this call, or any previous call since the
     * last save().
     */
    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    fun save(): Long {
        return save(false)
    }

    /**
     * Saves the map to disk.
     * 
     * @param fullSave if true, the journal file will be merged into the map file and deleted;
     * otherwise, the decision is made by [.shouldKeepJournal].
     * @throws IOException if there was an I/O error during this call, or any previous call since the
     * last save().
     */
    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    private fun save(fullSave: Boolean): Long {
        /* Report a previously failing I/O operation. */
        if (deferredIOFailure != null) {
            try {
                throw IOException(deferredIOFailure)
            } finally {
                deferredIOFailure = null
            }
        }
        if (!fullSave && shouldKeepJournal()) {
            flushJournal()
        } else {
            val mapTemp: com.google.devtools.build.lib.vfs.Path =
                mapFile.getRelative(
                    com.google.devtools.build.lib.vfs.FileSystemUtils.replaceExtension(
                        mapFile.asFragment(),
                        ".tmp"
                    )
                )
            try {
                saveEntries(mapTemp)
                mapFile.delete()
                mapTemp.renameTo(mapFile)
            } finally {
                mapTemp.delete()
            }
            clearJournal()
            journalFile.delete()
        }
        return journalSize() + cacheSize()
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    protected fun journalSize(): Long {
        return if (journalFile.exists()) journalFile.getFileSize() else 0
    }

    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    protected fun cacheSize(): Long {
        return if (mapFile.exists()) mapFile.getFileSize() else 0
    }

    /**
     * Whether to keep the journal file on save.
     * 
     * 
     * The default is to always merge the journal file into the main file and delete the former,
     * but subclasses are free to override this to implement their own strategy.
     */
    protected open fun shouldKeepJournal(): Boolean {
        return false
    }

    @kotlin.jvm.Synchronized
    private fun clearJournal() {
        journal.clear()
    }

    /**
     * Loads all entries from the given file into the backing map.
     * 
     * @throws IncompatibleFormatException if the file is in an incompatible format
     * @throws IOException if the file is corrupted or an I/O error occurs
     */
    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    private fun loadEntries(mapFile: com.google.devtools.build.lib.vfs.Path) {
        codec.createReader(mapFile, version.toLong()).use { `in` ->
            readEntries(`in`)
        }
    }

    /** Saves all backing map entries to the given file, overwriting preexisting contents.  */
    @kotlin.jvm.Synchronized
    @Throws(IOException::class)
    private fun saveEntries(mapFile: com.google.devtools.build.lib.vfs.Path) {
        codec.createWriter(mapFile, version,  /* overwrite= */true).use { out ->
            writeEntries(out, null)
        }
    }

    /**
     * Writes backing map entries for a set of keys into a [MapCodec.Writer].
     * 
     * @param out the [MapCodec.Writer] to write to.
     * @param keys the keys that are to be written, or null to write all keys.
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun writeEntries(out: com.google.devtools.build.lib.util.MapCodec.Writer, keys: MutableSet<K?>?) {
        val map: MutableMap<K?, V?> = delegate()
        for (key in if (keys != null) keys else map.keys) {
            out.writeEntry(key, map.get(key))
        }
    }

    /**
     * Reads entries from a [MapCodec.Reader] into the backing map.
     * 
     * @param in the [MapCodec.Reader] to read from.
     */
    @Throws(IOException::class)
    private fun readEntries(`in`: com.google.devtools.build.lib.util.MapCodec.Reader) {
        val map: MutableMap<K?, V?> = delegate()
        var entry: com.google.devtools.build.lib.util.MapCodec.Entry<K?, V?>?
        while ((`in`.readEntry().also { entry = it }) != null) {
            val key: K? = entry.key
            val value: V? = entry.value
            if (value != null) {
                map.put(key, value)
            } else {
                map.remove(key)
            }
        }
    }
}
