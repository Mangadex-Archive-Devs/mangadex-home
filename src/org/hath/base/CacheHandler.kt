/*

Copyright 2008-2016 E-Hentai.org
https://forums.e-hentai.org/
ehentai@gmail.com

This file is part of Hentai@Home.

Hentai@Home is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Hentai@Home is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Hentai@Home.  If not, see <http://www.gnu.org/licenses/>.

*/

package org.hath.base

import java.lang.Thread
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Enumeration
import java.util.Hashtable

class CacheHandler @Throws(java.io.IOException::class)
constructor(client: HentaiAtHomeClient) {
    private var staticRangeOldest: Hashtable<String, Long>? = null
    private val client: HentaiAtHomeClient? = null
    private var cachedir: File? = null
    private var lruCacheTable: ShortArray? = null
    var cacheCount = 0
        private set
    private var lruClearPointer = 0
    private var lruSkipCheckCycle = 0
    var pruneAggression = 1
        private set
    private var cacheSize: Long = 0
    private var cacheLoaded = false

    private val persistentLRUFile: File
        get() = File(Settings.dataDir, "pcache_lru")

    private val persistentInfoFile: File
        get() = File(Settings.dataDir, "pcache_info")

    private val persistentAgesFile: File
        get() = File(Settings.dataDir, "pcache_ages")

    init {
        this.client = client

        cachedir = Settings.cacheDir

        // delete orphans from the temp dir
        for (tmpfile in Settings.tempDir!!.listFiles()) {
            if (tmpfile.isFile()) {
                // some silly people might set the data and/or log dir to the same as the temp dir
                if (!tmpfile.getName().startsWith("log_") && !tmpfile.getName().startsWith("pcache_") && tmpfile.getName() != "client_login") {
                    Out.debug("CacheHandler: Deleted orphaned temporary file $tmpfile")
                    tmpfile.delete()
                }
            } else {
                Out.warning("CacheHandler: Found a non-file $tmpfile in the temp directory, won't delete.")
            }
        }

        var fastStartup = false

        if (!Settings.isRescanCache) {
            Out.info("CacheHandler: Attempting to load persistent cache data...")

            if (loadPersistentData()) {
                Out.info("CacheHandler: Successfully loaded persistent cache data")
                fastStartup = true
            } else {
                Out.info("CacheHandler: Persistent cache data is not available")
            }
        }

        deletePersistentData()

        if (!fastStartup) {
            Out.info("CacheHandler: Initializing the cache system...")

            // do the initial cache cleanup/reorg. this will move any qualifying files left in the first-level cache directory to the second level.
            startupCacheCleanup()
            System.gc()

            if (client.isShuttingDown) {
                return
            }

            // we need to zero out everything in case of a partially failed persistent load
            lruClearPointer = 0
            cacheCount = 0
            cacheSize = 0

            // this is a map with the static ranges in the cache as key and the oldest lastModified file timestamp for every range as value. this is used to find old files to delete if the cache fills up.
            staticRangeOldest = Hashtable<String, Long>((Settings.staticRangeCount * 1.5).toInt())

            if (!Settings.isUseLessMemory) {
                lruCacheTable = ShortArray(MEMORY_TABLE_ELEMENTS)
            }

            // scan the cache to calculate the total filecount and size, as well as initialize the LRU cache based on the lastModified timestamps.
            // this verifies that the files are the correct size and in an assigned static range, and optionally verifies the SHA-1 hash.
            // the staticRangeOldest hashtable of static ranges and the oldest file timestamp in that range will also be built here.
            startupInitCache()
            System.gc()
        }

        if (!recheckFreeDiskSpace()) {
            // note: if the client ends up being starved on disk space with static ranges assigned, it will cause a major loss of trust.
            client.setFastShutdown()
            HentaiAtHomeClient.dieWithError("The storage device does not have enough space available to hold the given cache size.\nFree up space for H@H, or reduce the cache size from the H@H settings page:\nhttps://e-hentai.org/hentaiathome.php?cid=" + Settings.clientID)
        }

        if (cacheCount < 1 && Settings.staticRangeCount > 20) {
            // note: if the client is started with an empty cache and many static ranges assigned, it will cause a major loss of trust.
            client.setFastShutdown()
            HentaiAtHomeClient.dieWithError("This client has static ranges assigned to it, but the cache is empty. Check file permissions and file system integrity.\nIf the cache has been deleted or is otherwise lost, you have to manually reset your static ranges from the H@H settings page.\nhttps://e-hentai.org/hentaiathome.php?cid=" + Settings.clientID)
        }

        val cacheLimit = Settings.diskLimitBytes

        if (cacheSize > cacheLimit) {
            Out.info("CacheHandler: We are over the cache limit, pruning until the limit is met")
            var iterations = 0
            val f = java.text.DecimalFormat("###.00")

            while (cacheSize > cacheLimit) {
                if (iterations++ % 100 == 0) {
                    Out.info("CacheHandler: Cache is currently at " + f.format(100.0 * cacheSize / cacheLimit) + "%")
                }

                recheckFreeDiskSpace()
                System.gc()
            }

            Out.info("CacheHandler: Finished startup cache pruning")
        }

        cacheLoaded = true
    }

    private fun loadPersistentData(): Boolean {
        if (!persistentInfoFile.exists()) {
            Out.debug("CacheHandler: Missing pcache_info, forcing rescan")
            return false
        }

        var success = false

        try {
            val cacheinfo = Tools.getStringFileContents(persistentInfoFile).split("\n".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()
            var infoChecksum = 0
            var agesHash: String? = null
            var lruHash: String? = null

            for (keyval in cacheinfo) {
                val s = keyval.split("=".toRegex()).dropLastWhile({ it.isEmpty() }).toTypedArray()

                when (s[0]) {
                    "cacheCount" -> {
                        cacheCount = Integer.parseInt(s[1])
                        Out.debug("CacheHandler: Loaded persistent cacheCount=$cacheCount")
                        infoChecksum = infoChecksum or 1
                    }
                    "cacheSize" -> {
                        cacheSize = java.lang.Long.parseLong(s[1])
                        Out.debug("CacheHandler: Loaded persistent cacheSize=$cacheSize")
                        infoChecksum = infoChecksum or 2
                    }
                    "lruClearPointer" -> {
                        lruClearPointer = Integer.parseInt(s[1])
                        Out.debug("CacheHandler: Loaded persistent lruClearPointer=$lruClearPointer")
                        infoChecksum = infoChecksum or 4
                    }
                    "agesHash" -> {
                        agesHash = s[1]
                        Out.debug("CacheHandler: Found agesHash=" + agesHash!!)
                        infoChecksum = infoChecksum or 8
                    }
                    "lruHash" -> {
                        lruHash = s[1]
                        Out.debug("CacheHandler: Found lruHash=" + lruHash!!)
                        infoChecksum = infoChecksum or 16
                    }
                }
            }

            if (infoChecksum != 31) {
                Out.info("CacheHandler: Persistent fields were missing, forcing rescan")
            } else {
                Out.info("CacheHandler: All persistent fields found, loading remaining objects")

                staticRangeOldest = readCacheObject(persistentAgesFile, agesHash) as Hashtable<String, Long>
                Out.info("CacheHandler: Loaded static range ages")

                if (!Settings.isUseLessMemory) {
                    lruCacheTable = readCacheObject(persistentLRUFile, lruHash) as ShortArray
                    Out.info("CacheHandler: Loaded LRU cache")
                }

                updateStats()
                success = true
            }
        } catch (e: Exception) {
            Out.debug(e.message)
        }

        System.gc()

        return success
    }

    private fun savePersistentData() {
        if (!cacheLoaded) {
            return
        }

        try {
            val agesHash = writeCacheObject(persistentAgesFile, staticRangeOldest)
            val lruHash = if (lruCacheTable == null) "null" else writeCacheObject(persistentLRUFile, lruCacheTable)
            Tools.putStringFileContents(persistentInfoFile, "cacheCount=$cacheCount\ncacheSize=$cacheSize\nlruClearPointer=$lruClearPointer\nagesHash=$agesHash\nlruHash=$lruHash")
        } catch (e: java.io.IOException) {
            e.printStackTrace()
        }

    }

    @Throws(java.io.IOException::class, java.lang.ClassNotFoundException::class)
    private fun readCacheObject(file: File, expectedHash: String?): Any {
        if (!file.exists()) {
            Out.warning("CacheHandler: Missing $file, forcing rescan")
            throw java.io.IOException("Missing file")
        }

        if (Tools.getSHA1String(file) != expectedHash) {
            Out.warning("CacheHandler: Incorrect file hash while reading $file, forcing rescan")
            throw java.io.IOException("Incorrect file hash")
        }

        val objectReader = ObjectInputStream(FileInputStream(file))
        val `object` = objectReader.readObject()
        objectReader.close()
        return `object`
    }

    @Throws(java.io.IOException::class)
    private fun writeCacheObject(file: File, `object`: Any?): String? {
        Out.debug("Writing cache object $file")
        val objectWriter = ObjectOutputStream(FileOutputStream(file))
        objectWriter.writeObject(`object`)
        objectWriter.close()
        val hash = Tools.getSHA1String(file)
        Out.debug("Wrote cache object " + file + " with size=" + file.length() + " hash=" + hash)
        return hash
    }

    private fun deletePersistentData() {
        val persistentInfoFile = persistentInfoFile
        val persistentAgesFile = persistentAgesFile
        val persistentLRUFile = persistentLRUFile

        if (persistentInfoFile.exists()) {
            persistentInfoFile.delete()
        }

        if (persistentAgesFile.exists()) {
            persistentAgesFile.delete()
        }

        if (persistentLRUFile.exists()) {
            persistentLRUFile.delete()
        }
    }

    fun terminateCache() {
        savePersistentData()
    }

    private fun startupCacheCleanup() {
        Out.info("CacheHandler: Cache cleanup pass..")

        val l1dirs = Tools.listSortedFiles(cachedir!!)
        var checkedCounter = 0
        var checkedCounterPct = 0

        // this sanity check can be tightened up when 1.2.6 is EOL and everyone have upgraded to the two-level cache tree
        //if(l1dirs.length > Settings.getStaticRangeCount()) {
        if (l1dirs!!.size > 5 && Settings.staticRangeCount == 0) {
            Out.warning("WARNING: There are " + l1dirs.size + " directories in the cache directory, but the server has only assigned us " + Settings.staticRangeCount + " static ranges.")
            Out.warning("If this is NOT expected, please close H@H with Ctrl+C or Program -> Shutdown H@H before this timeout expires.")
            Out.warning("Waiting 30 seconds before proceeding with cache cleanup...")

            try {
                Thread.currentThread().sleep(30000)
            } catch (e: Exception) {
            }

        }

        if (client!!.isShuttingDown) {
            return
        }

        for (l1dir in l1dirs) {
            // time to take out the trash
            System.gc()

            if (!l1dir.isDirectory) {
                l1dir.delete()
                continue
            }

            val l2dirs = Tools.listSortedFiles(l1dir)

            if (l2dirs == null || l2dirs.size == 0) {
                l1dir.delete()
                continue
            }

            for (l2dir in l2dirs) {
                if (l2dir.isDirectory) {
                    continue
                }

                // file in the level 1 directory - move it to its proper location
                val hvFile = HVFile.getHVFileFromFile(l2dir)

                if (hvFile == null) {
                    Out.debug("CacheHandler: The file $l2dir was not recognized.")
                    l2dir.delete()
                } else if (!Settings.isStaticRange(hvFile.fileid)) {
                    Out.debug("CacheHandler: The file $l2dir was not in an active static range.")
                    l2dir.delete()
                } else {
                    moveFileToCacheDir(l2dir, hvFile)
                    Out.debug("CacheHandler: Relocated file " + hvFile.fileid + " to " + hvFile.localFileRef)
                }
            }

            ++checkedCounter

            if (l1dirs.size > 9) {
                if (checkedCounter * 100 / l1dirs.size >= checkedCounterPct + 10) {
                    checkedCounterPct += 10
                    Out.info("CacheHandler: Cleanup pass at $checkedCounterPct%")
                }
            }
        }

        Out.info("CacheHandler: Finished scanning $checkedCounter cache subdirectories")
    }

    private fun startupInitCache() {
        val recentlyAccessedCutoff = System.currentTimeMillis() - 604800000

        // update actions:
        // staticRangeOldest	- add oldest modified timestamp for every static range
        // addFileToActiveCache	- increments cacheCount and cacheSize
        // markRecentlyAccessed	- marks files with timestamp > 7 days in the LRU cache

        // if --verify-cache was specified, we use this shiny new FileValidator to avoid having to create a new MessageDigest and ByteBuffer for every single file in the cache
        var validator: FileValidator? = null
        val printFreq: Int

        if (Settings.isVerifyCache) {
            Out.info("CacheHandler: Loading cache with full file verification. Depending on the size of your cache, this can take a long time.")
            validator = FileValidator()
            printFreq = 1000
        } else {
            Out.info("CacheHandler: Loading cache...")
            printFreq = 10000
        }

        var foundStaticRanges = 0

        // cache register pass
        for (l1dir in Tools.listSortedFiles(cachedir!!)!!) {
            if (!l1dir.isDirectory) {
                continue
            }

            for (l2dir in Tools.listSortedFiles(l1dir)!!) {
                // the garbage, it must be collected
                System.gc()

                if (!l2dir.isDirectory) {
                    continue
                }

                val files = Tools.listSortedFiles(l2dir)

                if (files!!.size == 0) {
                    l2dir.delete()
                    continue
                }

                var oldestLastModified = System.currentTimeMillis()

                for (cfile in files) {
                    if (!cfile.isFile) {
                        continue
                    }

                    val hvFile = HVFile.getHVFileFromFile(cfile, validator)

                    if (hvFile == null) {
                        Out.debug("CacheHandler: The file $cfile was corrupt.")
                        cfile.delete()
                    } else if (!Settings.isStaticRange(hvFile.fileid)) {
                        Out.debug("CacheHandler: The file $cfile was not in an active static range.")
                        cfile.delete()
                    } else {
                        addFileToActiveCache(hvFile)
                        val fileLastModified = cfile.lastModified()

                        if (fileLastModified > recentlyAccessedCutoff) {
                            // if lastModified is from the last week, mark this as recently accessed in the LRU cache. (this does not update the metadata)
                            markRecentlyAccessed(hvFile, true)
                        }

                        oldestLastModified = Math.min(oldestLastModified, fileLastModified)

                        if (cacheCount % printFreq == 0) {
                            Out.info("CacheHandler: Loaded $cacheCount files so far...")
                        }
                    }
                }

                val staticRange = l1dir.name + l2dir.name
                staticRangeOldest!![staticRange] = oldestLastModified

                if (++foundStaticRanges % 100 == 0) {
                    Out.info("CacheHandler: Found $foundStaticRanges static ranges with files so far...")
                }
            }
        }

        Out.info("CacheHandler: Finished initializing the cache ($cacheCount files, $cacheSize bytes)")
        Out.info("CacheHandler: Found a total of $foundStaticRanges static ranges with files")
        updateStats()
    }

    fun recheckFreeDiskSpace(): Boolean {
        if (lruSkipCheckCycle > 0) {
            // this is called every 10 seconds from the main thread, but depending on what happened in earlier runs, we skip checks when they are not necessary
            // we'll check every 10 minutes if the free cache during the last run was over 1GB, and every minute if less than 1GB but over 100MB
            --lruSkipCheckCycle
            return true
        }

        val wantFree: Long = 104857600
        val cacheLimit = Settings.diskLimitBytes
        var bytesToFree: Long = 0

        if (cacheSize > cacheLimit) {
            bytesToFree = wantFree + cacheSize - cacheLimit
        } else if (cacheLimit - cacheSize < wantFree) {
            bytesToFree = wantFree - (cacheLimit - cacheSize)
        }

        Out.debug("CacheHandler: Checked cache space (cacheSize=" + cacheSize + ", cacheLimit=" + cacheLimit + ", cacheFree=" + (cacheLimit - cacheSize) + ")")

        if (bytesToFree > 0 && cacheCount > 0 && Settings.staticRangeCount > 0) {
            var pruneStaticRange: String? = null
            val nowtime = System.currentTimeMillis()
            var oldestRangeAge = nowtime
            val staticRanges = staticRangeOldest!!.keys()

            while (staticRanges.hasMoreElements()) {
                val checkStaticRange = staticRanges.nextElement()
                val thisRangeOldestAge = staticRangeOldest!![checkStaticRange].toLong()

                if (thisRangeOldestAge < oldestRangeAge) {
                    pruneStaticRange = checkStaticRange
                    oldestRangeAge = thisRangeOldestAge
                }
            }

            if (pruneStaticRange == null) {
                Out.warning("CacheHandler: Failed to find aged static range to prune (oldestRangeAge=$oldestRangeAge)")
                return false
            }

            val staticRangeDir = File(cachedir, pruneStaticRange.substring(0, 2) + "/" + pruneStaticRange.substring(2, 4) + "/")
            var lruLastModifiedPruneCutoff = oldestRangeAge

            if (oldestRangeAge < nowtime - 15552000000L) {
                // oldest file is more than six months old, prune files newer than up to 30 days after this file
                lruLastModifiedPruneCutoff += 2592000000L
            } else if (oldestRangeAge < nowtime - 7776000000L) {
                // oldest file is between three and six months old, prune files newer than up to 7 days after this file
                lruLastModifiedPruneCutoff += 604800000L
            } else {
                // oldest file is less than three months old, prune files newer than up to 3 days after this file
                lruLastModifiedPruneCutoff += 259200000L
            }

            Out.debug("CacheHandler: Trying to free $bytesToFree bytes, currently scanning range $pruneStaticRange")

            if (!staticRangeDir.isDirectory) {
                Out.warning("CacheHandler: Expected static range directory $staticRangeDir could not be accessed")
            } else if (lruLastModifiedPruneCutoff > System.currentTimeMillis() - 604800000) {
                Out.warning("CacheHandler: Sanity check failed: lruLastModifiedPruneCutoff $lruLastModifiedPruneCutoff is less than a week old, cache pruning halted")
            } else {
                val files = staticRangeDir.listFiles()
                var oldestLastModified = nowtime

                if (files != null && files.size > 0) {
                    Out.debug("CacheHandler: Examining " + files.size + " files with lruLastModifiedPruneCutoff=" + lruLastModifiedPruneCutoff)

                    for (file in files) {
                        val lastModified = file.lastModified()

                        if (lastModified < lruLastModifiedPruneCutoff) {
                            val toRemove = HVFile.getHVFileFromFileid(file.name)

                            if (toRemove == null) {
                                Out.warning("CacheHandler: Removed invalid file $file")
                                file.delete()
                            } else {
                                deleteFileFromCache(toRemove)
                                bytesToFree -= toRemove.size.toLong()
                                Out.debug("CacheHandler: Pruned file had lastModified=" + lastModified + " size=" + toRemove.size + " bytesToFree=" + bytesToFree + " cacheCount=" + cacheCount)
                            }
                        } else {
                            oldestLastModified = Math.min(oldestLastModified, lastModified)
                        }
                    }
                }

                // we don't have any guarantees that there were any files to prune in this directory since there is a chance the oldest file was accessed after the oldest timestamp record was updated
                // regardless, we update the record with the freshly computed last modified timestamp. this will usually knock this range back from the front of the queue
                // if we still need to prune files, we will do another pass shortly
                staticRangeOldest!![pruneStaticRange] = oldestLastModified

                Out.debug("CacheHandler: Updated static range $pruneStaticRange with new oldestLastModified=$oldestLastModified")
            }
        } else {
            lruSkipCheckCycle = if (cacheLimit - cacheSize > wantFree * 10) 60 else 6
        }

        // if we are more than 10MB above where we want to be, start turning up the prune aggression, which determines how many times this cleanup function is run per cycle
        // realistically, this is almost certainly unnecessary since the 1.3.2 pruner was added, but it doesn't hurt to have it just in case
        pruneAggression = if (bytesToFree > 10485760) (bytesToFree / 10485760).toInt() else 1

        if (Settings.isSkipFreeSpaceCheck) {
            Out.debug("CacheHandler: Disk free space check is disabled.")
            return true
        } else {
            val diskFreeSpace = cachedir!!.freeSpace

            if (diskFreeSpace < Math.max(Settings.diskMinRemainingBytes, wantFree)) {
                Out.warning("CacheHandler: Did not meet space constraints: Disk free space limit reached ($diskFreeSpace bytes free on device)")
                return false
            } else {
                Out.debug("CacheHandler: Disk space constraints met ($diskFreeSpace bytes free on device)")
                return true
            }
        }
    }

    @Synchronized
    fun processBlacklist(deltatime: Long) {
        Out.info("CacheHandler: Retrieving list of blacklisted files...")
        val blacklisted = client!!.serverHandler!!.getBlacklist(deltatime)

        if (blacklisted == null) {
            Out.warning("CacheHandler: Failed to retrieve file blacklist, will try again later.")
            return
        }

        Out.info("CacheHandler: Looking for and deleting blacklisted files...")

        var counter = 0

        for (fileid in blacklisted) {
            val hvFile = HVFile.getHVFileFromFileid(fileid)

            if (hvFile != null) {
                val file = hvFile.localFileRef

                if (file.exists()) {
                    deleteFileFromCache(hvFile)
                    Out.debug("CacheHandler: Removed blacklisted file $fileid")
                    ++counter
                }
            }
        }

        Out.info("CacheHandler: $counter blacklisted files were removed.")
    }

    private fun updateStats() {
        Stats.setCacheCount(cacheCount)
        Stats.setCacheSize(cacheSize)
    }

    // used to add proxied files to cache. this function assumes that tempFile has been validated
    fun importFile(tempFile: File, hvFile: HVFile): Boolean {
        if (moveFileToCacheDir(tempFile, hvFile)) {
            addFileToActiveCache(hvFile)
            markRecentlyAccessed(hvFile, true)

            // check that the static range oldest timestamp cache has an entry for this static range
            val staticRange = hvFile.staticRange
            if (!staticRangeOldest!!.containsKey(staticRange)) {
                Out.debug("CacheHandler: Created staticRangeOldest entry for $staticRange")
                staticRangeOldest!![staticRange] = System.currentTimeMillis()
            }

            return true
        }

        return false
    }

    // will just move the file into its correct location. addFileToActiveCache must be called afterwards to add the file to the cache counters.
    private fun moveFileToCacheDir(file: File, hvFile: HVFile): Boolean {
        val toFile = hvFile.localFileRef

        try {
            Tools.checkAndCreateDir(toFile.parentFile)
            Files.move(file.toPath(), toFile.toPath(), StandardCopyOption.REPLACE_EXISTING)

            if (file.exists()) {
                // moving failed, let's try copying
                Files.copy(file.toPath(), toFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                file.delete()
            }

            if (toFile.exists()) {
                Out.debug("CacheHandler: Imported file " + file + " as " + hvFile.fileid)
                return true
            } else {
                Out.warning("CacheHandler: Failed to move file $file")
            }
        } catch (e: java.io.IOException) {
            e.printStackTrace()
            Out.warning("CacheHandler: Encountered exception $e when moving file $file")
        }

        return false
    }

    private fun addFileToActiveCache(hvFile: HVFile) {
        ++cacheCount
        cacheSize += hvFile.size.toLong()
        updateStats()
    }

    private fun deleteFileFromCache(toRemove: HVFile) {
        try {
            val file = toRemove.localFileRef

            if (file.exists()) {
                file.delete()
                --cacheCount
                cacheSize -= toRemove.size.toLong()
                updateStats()
                Out.debug("CacheHandler: Deleted cached file " + toRemove.fileid)
            }
        } catch (e: Exception) {
            Out.error("CacheHandler: Failed to delete cache file")
            HentaiAtHomeClient.dieWithError(e)
        }

    }

    fun cycleLRUCacheTable() {
        if (lruCacheTable != null) {
            // this function is called every 10 seconds. clearing 17 of the shorts for each call means that each element will live up to a week (since 1048576 / (8640 * 7) is roughly 17).
            // if --use-less-memory is set, the LRU cache will never have been created, and this does nothing.

            val clearUntil = Math.min(MEMORY_TABLE_ELEMENTS, lruClearPointer + 17)

            //Out.debug("CacheHandler: Clearing lruCacheTable from " + lruClearPointer + " to " + clearUntil);

            while (lruClearPointer < clearUntil) {
                lruCacheTable[lruClearPointer++] = 0
            }

            if (clearUntil >= MEMORY_TABLE_ELEMENTS) {
                lruClearPointer = 0
            }
        }
    }

    @JvmOverloads
    fun markRecentlyAccessed(hvFile: HVFile, skipMetaUpdate: Boolean = false) {
        var markFile = true

        if (lruCacheTable != null) {
            // if --use-less-memory is not set, we use this as a first step in order to determine if the timestamp should be updated or not.
            // lruCacheTable can hold 16^5 = 1048576 shorts consisting of 16 bits each.
            // we need to compute the array index and bitmask for this particular fileid. if the bit is set, we do nothing. if not, we update the timestamp and set the bit.
            // when determening what bit to set, we skip the first four nibbles (bit 0-15) of the hash due to static range grouping
            // we use the next five nibbles (bit 16-35) to get the index of the array, and the tenth nibble (bit 36-39) to determine which bit in the short to read/set.
            // while collisions are not unlikely to occur due to the birthday paradox, they should not cause any major issues with files not having their timestamp updated.
            // any impact of this will be negligible, as it will only cause the LRU mechanism to be slightly less efficient.
            val fileid = hvFile.fileid

            // bit 16-35
            val arrayIndex = Integer.parseInt(fileid.substring(4, 9), 16)

            // bit 36-39
            val bitMask = (1 shl java.lang.Short.parseShort(fileid.substring(9, 10), 16)).toShort()

            if (lruCacheTable!![arrayIndex] and bitMask != 0) {
                //Out.debug("LRU bit for " + fileid + " = " + arrayIndex + ":" + fileid.charAt(9) + " was set");
                markFile = false
            } else {
                //Out.debug("Written bit for " + fileid + " = " + arrayIndex + ":" + fileid.charAt(9) + " was not set - marking");
                lruCacheTable[arrayIndex] = lruCacheTable[arrayIndex] or bitMask
            }
        }

        if (markFile && !skipMetaUpdate) {
            val file = hvFile.localFileRef
            val nowtime = System.currentTimeMillis()

            if (file.lastModified() < nowtime - 604800000) {
                file.setLastModified(nowtime)
            }
        }
    }

    companion object {
        private val MEMORY_TABLE_ELEMENTS = 1048576
    }
}