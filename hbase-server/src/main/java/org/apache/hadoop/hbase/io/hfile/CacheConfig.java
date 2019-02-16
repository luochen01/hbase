/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.io.hfile;

import java.util.Optional;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptor;
import org.apache.hadoop.hbase.io.ByteBuffAllocator;
import org.apache.hadoop.hbase.io.hfile.BlockType.BlockCategory;
import org.apache.yetus.audience.InterfaceAudience;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hbase.thirdparty.com.google.common.annotations.VisibleForTesting;

/**
 * Stores all of the cache objects and configuration for a single HFile.
 */
@InterfaceAudience.Private
public class CacheConfig {
  private static final Logger LOG = LoggerFactory.getLogger(CacheConfig.class.getName());

  /**
   * Disabled cache configuration
   */
  public static final CacheConfig DISABLED = new CacheConfig();

  /**
   * Configuration key to cache data blocks on read. Bloom blocks and index blocks are always be
   * cached if the block cache is enabled.
   */
  public static final String CACHE_DATA_ON_READ_KEY = "hbase.block.data.cacheonread";

  /**
   * Configuration key to cache data blocks on write. There are separate
   * switches for bloom blocks and non-root index blocks.
   */
  public static final String CACHE_BLOCKS_ON_WRITE_KEY = "hbase.rs.cacheblocksonwrite";

  /**
   * Configuration key to cache leaf and intermediate-level index blocks on
   * write.
   */
  public static final String CACHE_INDEX_BLOCKS_ON_WRITE_KEY = "hfile.block.index.cacheonwrite";

  /**
   * Configuration key to cache compound bloom filter blocks on write.
   */
  public static final String CACHE_BLOOM_BLOCKS_ON_WRITE_KEY = "hfile.block.bloom.cacheonwrite";

  /**
   * Configuration key to cache data blocks in compressed and/or encrypted format.
   */
  public static final String CACHE_DATA_BLOCKS_COMPRESSED_KEY = "hbase.block.data.cachecompressed";

  /**
   * Configuration key to evict all blocks of a given file from the block cache
   * when the file is closed.
   */
  public static final String EVICT_BLOCKS_ON_CLOSE_KEY = "hbase.rs.evictblocksonclose";

  /**
   * Configuration key to prefetch all blocks of a given file into the block cache
   * when the file is opened.
   */
  public static final String PREFETCH_BLOCKS_ON_OPEN_KEY = "hbase.rs.prefetchblocksonopen";

  public static final String DROP_BEHIND_CACHE_COMPACTION_KEY =
      "hbase.hfile.drop.behind.compaction";

  // Defaults
  public static final boolean DEFAULT_CACHE_DATA_ON_READ = true;
  public static final boolean DEFAULT_CACHE_DATA_ON_WRITE = false;
  public static final boolean DEFAULT_IN_MEMORY = false;
  public static final boolean DEFAULT_CACHE_INDEXES_ON_WRITE = false;
  public static final boolean DEFAULT_CACHE_BLOOMS_ON_WRITE = false;
  public static final boolean DEFAULT_EVICT_ON_CLOSE = false;
  public static final boolean DEFAULT_CACHE_DATA_COMPRESSED = false;
  public static final boolean DEFAULT_PREFETCH_ON_OPEN = false;
  public static final boolean DROP_BEHIND_CACHE_COMPACTION_DEFAULT = true;

  /**
   * Whether blocks should be cached on read (default is on if there is a
   * cache but this can be turned off on a per-family or per-request basis).
   * If off we will STILL cache meta blocks; i.e. INDEX and BLOOM types.
   * This cannot be disabled.
   */
  private final boolean cacheDataOnRead;

  /** Whether blocks should be flagged as in-memory when being cached */
  private final boolean inMemory;

  /** Whether data blocks should be cached when new files are written */
  private boolean cacheDataOnWrite;

  /** Whether index blocks should be cached when new files are written */
  private final boolean cacheIndexesOnWrite;

  /** Whether compound bloom filter blocks should be cached on write */
  private final boolean cacheBloomsOnWrite;

  /** Whether blocks of a file should be evicted when the file is closed */
  private boolean evictOnClose;

  /** Whether data blocks should be stored in compressed and/or encrypted form in the cache */
  private final boolean cacheDataCompressed;

  /** Whether data blocks should be prefetched into the cache */
  private final boolean prefetchOnOpen;

  private final boolean dropBehindCompaction;

  // Local reference to the block cache
  private final BlockCache blockCache;

  private final ByteBuffAllocator byteBuffAllocator;

  /**
   * Create a cache configuration using the specified configuration object and
   * defaults for family level settings. Only use if no column family context.
   * @param conf hbase configuration
   */
  public CacheConfig(Configuration conf) {
    this(conf, null);
  }

  public CacheConfig(Configuration conf, BlockCache blockCache) {
    this(conf, null, blockCache, ByteBuffAllocator.HEAP);
  }

  /**
   * Create a cache configuration using the specified configuration object and
   * family descriptor.
   * @param conf hbase configuration
   * @param family column family configuration
   */
  public CacheConfig(Configuration conf, ColumnFamilyDescriptor family, BlockCache blockCache,
      ByteBuffAllocator byteBuffAllocator) {
    this.cacheDataOnRead = conf.getBoolean(CACHE_DATA_ON_READ_KEY, DEFAULT_CACHE_DATA_ON_READ) &&
        (family == null ? true : family.isBlockCacheEnabled());
    this.inMemory = family == null ? DEFAULT_IN_MEMORY : family.isInMemory();
    this.cacheDataCompressed =
        conf.getBoolean(CACHE_DATA_BLOCKS_COMPRESSED_KEY, DEFAULT_CACHE_DATA_COMPRESSED);
    this.dropBehindCompaction =
        conf.getBoolean(DROP_BEHIND_CACHE_COMPACTION_KEY, DROP_BEHIND_CACHE_COMPACTION_DEFAULT);
    // For the following flags we enable them regardless of per-schema settings
    // if they are enabled in the global configuration.
    this.cacheDataOnWrite =
        conf.getBoolean(CACHE_BLOCKS_ON_WRITE_KEY, DEFAULT_CACHE_DATA_ON_WRITE) ||
            (family == null ? false : family.isCacheDataOnWrite());
    this.cacheIndexesOnWrite =
        conf.getBoolean(CACHE_INDEX_BLOCKS_ON_WRITE_KEY, DEFAULT_CACHE_INDEXES_ON_WRITE) ||
            (family == null ? false : family.isCacheIndexesOnWrite());
    this.cacheBloomsOnWrite =
        conf.getBoolean(CACHE_BLOOM_BLOCKS_ON_WRITE_KEY, DEFAULT_CACHE_BLOOMS_ON_WRITE) ||
            (family == null ? false : family.isCacheBloomsOnWrite());
    this.evictOnClose = conf.getBoolean(EVICT_BLOCKS_ON_CLOSE_KEY, DEFAULT_EVICT_ON_CLOSE) ||
        (family == null ? false : family.isEvictBlocksOnClose());
    this.prefetchOnOpen = conf.getBoolean(PREFETCH_BLOCKS_ON_OPEN_KEY, DEFAULT_PREFETCH_ON_OPEN) ||
        (family == null ? false : family.isPrefetchBlocksOnOpen());
    this.blockCache = blockCache;
    this.byteBuffAllocator = byteBuffAllocator;
    LOG.info("Created cacheConfig: " + this + (family == null ? "" : " for family " + family) +
        " with blockCache=" + blockCache);
  }

  /**
   * Constructs a cache configuration copied from the specified configuration.
   * @param cacheConf
   */
  public CacheConfig(CacheConfig cacheConf) {
    this.cacheDataOnRead = cacheConf.cacheDataOnRead;
    this.inMemory = cacheConf.inMemory;
    this.cacheDataOnWrite = cacheConf.cacheDataOnWrite;
    this.cacheIndexesOnWrite = cacheConf.cacheIndexesOnWrite;
    this.cacheBloomsOnWrite = cacheConf.cacheBloomsOnWrite;
    this.evictOnClose = cacheConf.evictOnClose;
    this.cacheDataCompressed = cacheConf.cacheDataCompressed;
    this.prefetchOnOpen = cacheConf.prefetchOnOpen;
    this.dropBehindCompaction = cacheConf.dropBehindCompaction;
    this.blockCache = cacheConf.blockCache;
    this.byteBuffAllocator = cacheConf.byteBuffAllocator;
  }

  private CacheConfig() {
    this.cacheDataOnRead = false;
    this.inMemory = false;
    this.cacheDataOnWrite = false;
    this.cacheIndexesOnWrite = false;
    this.cacheBloomsOnWrite = false;
    this.evictOnClose = false;
    this.cacheDataCompressed = false;
    this.prefetchOnOpen = false;
    this.dropBehindCompaction = false;
    this.blockCache = null;
    this.byteBuffAllocator = ByteBuffAllocator.HEAP;
  }

  /**
   * Returns whether the DATA blocks of this HFile should be cached on read or not (we always
   * cache the meta blocks, the INDEX and BLOOM blocks).
   * @return true if blocks should be cached on read, false if not
   */
  public boolean shouldCacheDataOnRead() {
    return cacheDataOnRead;
  }

  public boolean shouldDropBehindCompaction() {
    return dropBehindCompaction;
  }

  /**
   * Should we cache a block of a particular category? We always cache
   * important blocks such as index blocks, as long as the block cache is
   * available.
   */
  public boolean shouldCacheBlockOnRead(BlockCategory category) {
    return cacheDataOnRead || category == BlockCategory.INDEX || category == BlockCategory.BLOOM ||
        (prefetchOnOpen && (category != BlockCategory.META && category != BlockCategory.UNKNOWN));
  }

  /**
   * @return true if blocks in this file should be flagged as in-memory
   */
  public boolean isInMemory() {
    return this.inMemory;
  }

  /**
   * @return true if data blocks should be written to the cache when an HFile is
   *         written, false if not
   */
  public boolean shouldCacheDataOnWrite() {
    return this.cacheDataOnWrite;
  }

  /**
   * @param cacheDataOnWrite whether data blocks should be written to the cache
   *                         when an HFile is written
   */
  @VisibleForTesting
  public void setCacheDataOnWrite(boolean cacheDataOnWrite) {
    this.cacheDataOnWrite = cacheDataOnWrite;
  }

  /**
   * @return true if index blocks should be written to the cache when an HFile
   *         is written, false if not
   */
  public boolean shouldCacheIndexesOnWrite() {
    return this.cacheIndexesOnWrite;
  }

  /**
   * @return true if bloom blocks should be written to the cache when an HFile
   *         is written, false if not
   */
  public boolean shouldCacheBloomsOnWrite() {
    return this.cacheBloomsOnWrite;
  }

  /**
   * @return true if blocks should be evicted from the cache when an HFile
   *         reader is closed, false if not
   */
  public boolean shouldEvictOnClose() {
    return this.evictOnClose;
  }

  /**
   * Only used for testing.
   * @param evictOnClose whether blocks should be evicted from the cache when an
   *                     HFile reader is closed
   */
  @VisibleForTesting
  public void setEvictOnClose(boolean evictOnClose) {
    this.evictOnClose = evictOnClose;
  }

  /**
   * @return true if data blocks should be compressed in the cache, false if not
   */
  public boolean shouldCacheDataCompressed() {
    return this.cacheDataOnRead && this.cacheDataCompressed;
  }

  /**
   * @return true if this {@link BlockCategory} should be compressed in blockcache, false otherwise
   */
  public boolean shouldCacheCompressed(BlockCategory category) {
    switch (category) {
      case DATA:
        return this.cacheDataOnRead && this.cacheDataCompressed;
      default:
        return false;
    }
  }

  /**
   * @return true if blocks should be prefetched into the cache on open, false if not
   */
  public boolean shouldPrefetchOnOpen() {
    return this.prefetchOnOpen;
  }

  /**
   * Return true if we may find this type of block in block cache.
   * <p>
   * TODO: today {@code family.isBlockCacheEnabled()} only means {@code cacheDataOnRead}, so here we
   * consider lots of other configurations such as {@code cacheDataOnWrite}. We should fix this in
   * the future, {@code cacheDataOnWrite} should honor the CF level {@code isBlockCacheEnabled}
   * configuration.
   */
  public boolean shouldReadBlockFromCache(BlockType blockType) {
    if (cacheDataOnRead) {
      return true;
    }
    if (prefetchOnOpen) {
      return true;
    }
    if (cacheDataOnWrite) {
      return true;
    }
    if (blockType == null) {
      return true;
    }
    if (blockType.getCategory() == BlockCategory.BLOOM ||
        blockType.getCategory() == BlockCategory.INDEX) {
      return true;
    }
    return false;
  }

  /**
   * If we make sure the block could not be cached, we will not acquire the lock
   * otherwise we will acquire lock
   */
  public boolean shouldLockOnCacheMiss(BlockType blockType) {
    if (blockType == null) {
      return true;
    }
    return shouldCacheBlockOnRead(blockType.getCategory());
  }

  /**
   * Returns the block cache.
   *
   * @return the block cache, or null if caching is completely disabled
   */
  public Optional<BlockCache> getBlockCache() {
    return Optional.ofNullable(this.blockCache);
  }

  public ByteBuffAllocator getByteBuffAllocator() {
    return this.byteBuffAllocator;
  }

  @Override
  public String toString() {
    return "cacheDataOnRead=" + shouldCacheDataOnRead() + ", cacheDataOnWrite="
        + shouldCacheDataOnWrite() + ", cacheIndexesOnWrite=" + shouldCacheIndexesOnWrite()
        + ", cacheBloomsOnWrite=" + shouldCacheBloomsOnWrite() + ", cacheEvictOnClose="
        + shouldEvictOnClose() + ", cacheDataCompressed=" + shouldCacheDataCompressed()
        + ", prefetchOnOpen=" + shouldPrefetchOnOpen();
  }
}
