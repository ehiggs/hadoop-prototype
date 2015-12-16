package org.apache.hadoop.hdfs.server.blockmanagement;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.BlockListAsLongs;
import org.apache.hadoop.hdfs.protocol.DatanodeID;
import org.apache.hadoop.hdfs.protocol.DatanodeInfo;
import org.apache.hadoop.hdfs.protocol.DatanodeInfoWithStorage;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.server.protocol.DatanodeStorage;
import org.apache.hadoop.hdfs.server.protocol.DatanodeStorage.State;
import org.apache.hadoop.hdfs.util.RwLock;
import org.apache.hadoop.util.ReflectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;

public class ProvidedStorageMap {

  private static final Logger LOG = LoggerFactory.getLogger(ProvidedStorageMap.class);

  public static final String FMT = "hdfs.namenode.block.provider.class";
  public static final String STORAGE_ID = "hdfs.namenode.block.provider.id";

  // limit to a single provider for now
  private final BlockProvider p;
  private final String storageId;
  private final ProvidedDescriptor dns;
  private volatile DatanodeStorageInfo storage;

  ProvidedStorageMap(RwLock lock, BlockManager bm, Configuration conf)
      throws IOException {
    storageId = conf.get(STORAGE_ID);
    if (null == storageId) {
      // disable mapping
      p = null;
      dns = null;
      storage = null;
      return;
    }
    //State.READ_ONLY_SHARED ?
    DatanodeStorage ds =
      new DatanodeStorage(storageId, State.NORMAL, StorageType.PROVIDED);
    // TODO need a subclass for DNDesc, DNSI, or both
    dns = new ProvidedDescriptor();
    storage = dns.createProvidedStorage(ds);

    // load block reader into storage
    Class<? extends BlockProvider> fmt =
      conf.getClass(FMT, BlockProvider.class, BlockProvider.class);
    p = ReflectionUtils.newInstance(fmt, conf);
    p.init(lock, bm, storage);
    LOG.info("Loaded block provider class: " + p.getClass() + " storage: " + storage);
  }

  // TODO: need to capture DN registration w/ storage
  //       possibly snare from getStorage

  DatanodeStorageInfo getStorage(DatanodeDescriptor dn, DatanodeStorage s) {
    if (storageId != null && storageId.equals(s.getStorageID())) {
      if (StorageType.PROVIDED.equals(s.getStorageType())) {
        // poll service, initiate 
        p.start();
        return dns.getStorage(dn, s);
      }
      LOG.warn("Reserved storage {} reported as non-provided from {}", s, dn);
    }
    return dn.getStorageInfo(s.getStorageID());
  }

  public LocatedBlockBuilder newLocatedBlocks(int maxValue) {
    if (null == storageId) {
      return new LocatedBlockBuilder(maxValue);
    }
    return new ProvidedBlocksBuilder(maxValue);
  }

  class ProvidedBlocksBuilder extends LocatedBlockBuilder {

    ShadowDNIWS pending;

    ProvidedBlocksBuilder(int maxBlocks) {
      super(maxBlocks);
      pending = new ShadowDNIWS(dns, storageId);
    }

    @Override
    LocatedBlock newLocatedBlock(ExtendedBlock eb,
        DatanodeStorageInfo[] storages, long pos, boolean isCorrupt) {
      DatanodeInfoWithStorage[] locs =
        new DatanodeInfoWithStorage[storages.length];
      String[] sids = new String[storages.length];
      StorageType[] types = new StorageType[storages.length];
      for (int i = 0; i < storages.length; ++i) {
        if (StorageType.PROVIDED.equals(storages[i].getStorageType())) {
          locs[i] = pending;
        } else {
          locs[i] = new DatanodeInfoWithStorage(
              storages[i].getDatanodeDescriptor(), sids[i], types[i]);
        }
        sids[i] = storages[i].getStorageID();
        types[i] = storages[i].getStorageType();
      }
      return new LocatedBlock(eb, locs, sids, types, pos, isCorrupt, null);
    }

    @Override
    LocatedBlocks build(DatanodeDescriptor client) {
      // TODO: to support multiple provided storages, need to pass/maintain map
      // set all fields of pending DatanodeInfo
      DatanodeDescriptor dn = dns.choose(client);
      pending.replaceInternal(dn);
      return new LocatedBlocks(flen, isUC, blocks, last, lastComplete, feInfo);
    }
    @Override
    LocatedBlocks build() {
      return build(dns.chooseRandom());
    }
  }

  static class ShadowDNIWS extends DatanodeInfoWithStorage {
    String shadowUuid;

    ShadowDNIWS(DatanodeDescriptor d, String storageId) {
      super(d, storageId, StorageType.PROVIDED);
    }

    @Override
    public String getDatanodeUuid() {
      return shadowUuid;
    }
    public void setDatanodeUuid(String uuid) {
      shadowUuid = uuid;
    }
    void replaceInternal(DatanodeDescriptor dn) {
      updateRegInfo(dn); // overwrite DatanodeID (except UUID)
      setCapacity(dn.getCapacity());
      setDfsUsed(dn.getDfsUsed());
      setRemaining(dn.getRemaining());
      setBlockPoolUsed(dn.getBlockPoolUsed());
      setCacheCapacity(dn.getCacheCapacity());
      setCacheUsed(dn.getCacheUsed());
      setLastUpdate(dn.getLastUpdate());
      setLastUpdateMonotonic(dn.getLastUpdateMonotonic());
      setXceiverCount(dn.getXceiverCount());
      setNetworkLocation(dn.getNetworkLocation());
      adminState = dn.getAdminState();
      setUpgradeDomain(dn.getUpgradeDomain());
    }

  }

  // NOTE: never resolved through registerDatanode, so not in the topology
  static class ProvidedDescriptor extends DatanodeDescriptor {

    final NavigableMap<String,DatanodeDescriptor> dns =
      new ConcurrentSkipListMap<>();

    ProvidedDescriptor() {
      super(new DatanodeID(
            null,                         // String ipAddr,
            null,                         // String hostName,
            UUID.randomUUID().toString(), // String datanodeUuid,
            0,                            // int xferPort,
            0,                            // int infoPort,
            0,                            // int infoSecurePort,
            0));                          // int ipcPort
    }

    // TODO: examine BM::getBlocksWithLocations (used by rebalancer?)

    DatanodeStorageInfo getStorage(DatanodeDescriptor dn, DatanodeStorage s) {
      dns.put(dn.getDatanodeUuid(), dn);
      return storageMap.get(s.getStorageID());
    }

    DatanodeStorageInfo createProvidedStorage(DatanodeStorage ds) {
      assert null == storageMap.get(ds.getStorageID());
      DatanodeStorageInfo storage = new DatanodeStorageInfo(this, ds);
      storageMap.put(storage.getStorageID(), storage);
      return storage;
    }

    DatanodeDescriptor choose(DatanodeDescriptor client) {
      // exact match for now
      DatanodeDescriptor dn = dns.get(client.getDatanodeUuid());
      if (null == dn) {
        dn = chooseRandom();
      }
      return dn;
    }

    DatanodeDescriptor chooseRandom() {
      // XXX Not uniformally random; skewed toward sparse sections of the ids
      Map.Entry<String,DatanodeDescriptor> d =
        dns.ceilingEntry(UUID.randomUUID().toString());
      if (null == d) {
        d = dns.firstEntry();
      }
      return d.getValue();
    }

    // TODO: recovery? invalidation?

    @Override
    void addBlockToBeReplicated(Block block, DatanodeStorageInfo[] targets) {
      // pick a random datanode, delegate to it
      chooseRandom().addBlockToBeReplicated(block, targets);
    }

  }

  static class ProvidedBlockList extends BlockListAsLongs {

    final Iterator<Block> inner;

    ProvidedBlockList(Iterator<Block> inner) {
      this.inner = inner;
    }

    @Override
    public Iterator<BlockReportReplica> iterator() {
      return new Iterator<BlockReportReplica>() {
        @Override
        public BlockReportReplica next() {
          return new BlockReportReplica(inner.next());
        }
        @Override
        public boolean hasNext() {
          return inner.hasNext();
        }
        @Override
        public void remove() {
          throw new UnsupportedOperationException();
        }
      };
    }

    @Override
    public int getNumberOfBlocks() {
      // VERIFY: only printed for debugging
      return -1;
    }

    @Override
    public ByteString getBlocksBuffer() {
      throw new UnsupportedOperationException();
      //Builder builder = builder();
      //for (Replica replica : this) {
      //  builder.add(replica);
      //}
      //return builder.build().getBlocksBuffer();
    }

    @Override
    public long[] getBlockListAsLongs() {
      // should only be used for backwards compat, DN.ver > NN.ver
      throw new UnsupportedOperationException();
    }

  }

  // use common Service framework from common? No instances in HDFS, yet
  public static class BlockProvider implements Iterable<Block> {

    RwLock lock;
    Executor exec;
    BlockManager bm;
    DatanodeStorageInfo storage;
    // XXX need to respect DN lifecycle, proper concurrency
    AtomicBoolean hasDNs = new AtomicBoolean(false);

    void init(RwLock lock, BlockManager bm, DatanodeStorageInfo storage) {
      this.bm = bm;
      this.lock = lock;
      this.storage = storage;
      // set ThreadFactory to return daemon thread
      exec = Executors.newSingleThreadExecutor();
    }

    void start() {
      if (!hasDNs.compareAndSet(false, true)) {
        // use only one thread (hack)
        return;
      }
      // create background thread to read from fmt
      // forall blocks
      //   batch ProvidedBlockList from BlockProvider
      //   report BM::processFirstBlockReport
      //   subsequently report BM::processReport
      FutureTask<Boolean> t = new FutureTask<>(new RefreshBlocks());
      exec.execute(t);
      LOG.info("DEBUG DN loading storage: " + storage.getStorageID());
      try {
        boolean result = t.get();
        LOG.info("Returned " + result);
      } catch (ExecutionException e) {
        throw new RuntimeException("Load failed", e.getCause());
      } catch (InterruptedException e) {
        throw new RuntimeException("Load interrupted", e);
      }
      // TODO: report the result at some point
    }

    class RefreshBlocks implements Callable<Boolean> {

      @Override
      public Boolean call() throws IOException {
        lock.writeLock();
        try {
          LOG.info("Calling process first blk report from storage: " + storage);
          // first pass; periodic refresh should call bm.processReport
          bm.processFirstBlockReport(storage, new ProvidedBlockList(iterator()));
          return true;
        } finally {
          lock.writeUnlock();
        }
      }

    }

    @Override
    public Iterator<Block> iterator() {
      // default to empty storage
      return Collections.emptyListIterator();
    }

  }

}