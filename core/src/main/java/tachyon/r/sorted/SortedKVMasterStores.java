package tachyon.r.sorted;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.apache.thrift.TDeserializer;
import org.apache.thrift.TException;
import org.apache.thrift.TSerializer;
import org.apache.thrift.protocol.TBinaryProtocol;

import com.google.common.collect.ImmutableList;

import tachyon.Constants;
import tachyon.TachyonURI;
import tachyon.extension.ComponentException;
import tachyon.extension.MasterComponent;
import tachyon.master.MasterInfo;
import tachyon.thrift.ClientBlockInfo;
import tachyon.thrift.FileAlreadyExistException;
import tachyon.thrift.FileDoesNotExistException;
import tachyon.thrift.InvalidPathException;
import tachyon.thrift.NetAddress;
import tachyon.thrift.SortedStorePartitionInfo;
import tachyon.thrift.TachyonException;

/**
 * All key/value stores information in the master component;
 */
public class SortedKVMasterStores extends MasterComponent {
  private static final Logger LOG = Logger.getLogger(Constants.LOGGER_TYPE);

  private Map<Integer, MasterStore> mStores = new HashMap<Integer, MasterStore>();

  public SortedKVMasterStores(MasterInfo masterInfo) {
    super(masterInfo);
  }

  public synchronized boolean addPartition(SortedStorePartitionInfo info) throws TachyonException {
    int storeId = info.storeId;
    if (!mStores.containsKey(storeId)) {
      throw new TachyonException("Store does not exist for partition: " + info);
    }
    try {
      mStores.get(storeId).addPartition(
          new MasterPartition(info.storeId, info.partitionIndex, info.dataFileId, info.indexFileId,
              info.startKey, info.endKey));
    } catch (IOException e) {
      LOG.error(e.getMessage());
      throw new TachyonException(e.getMessage());
    }
    return true;
  }

  public synchronized int createStore(TachyonURI path) throws InvalidPathException,
      FileAlreadyExistException, TachyonException {
    if (!mMasterInfo.mkdirs(path, true)) {
      return -1;
    }
    int storeId = mMasterInfo.getFileId(path);
    MasterStore info = new MasterStore(storeId);

    if (mStores.containsKey(info.mInodeID)) {
      throw new FileAlreadyExistException("The store already exists: " + info);
    }

    mStores.put(info.mInodeID, info);

    return storeId;
  }

  public synchronized SortedStorePartitionInfo getPartition(int storeId, ByteBuffer key)
      throws FileDoesNotExistException, TachyonException {
    if (!mStores.containsKey(storeId)) {
      throw new TachyonException("Store does not exist: " + storeId);
    }
    MasterPartition partition = mStores.get(storeId).getPartition(key);

    if (partition == null) {
      SortedStorePartitionInfo res = new SortedStorePartitionInfo();
      res.partitionIndex = -1;
      return res;
    }
    SortedStorePartitionInfo res = partition.generateSortedStorePartitionInfo();
    if (!partition.hasLocation()) {
      int indexFileId = partition.mIndexFileID;
      List<ClientBlockInfo> blockInfo;
      try {
        blockInfo = mMasterInfo.getFileBlocks(indexFileId);
      } catch (IOException e) {
        throw new TachyonException(e.getMessage());
      }
      res.setLocation(blockInfo.get(0).locations.get(0));
      LOG.info("MasterPartition empty location: blockinfo(" + blockInfo
          + "); SortedStorePartitionInfo" + res);
    } else {
      LOG.info("MasterPartition with locations: " + res);
    }
    return res;
  }

  @Override
  public List<NetAddress> lookup(List<ByteBuffer> data) throws ComponentException {
    // TODO Auto-generated method stub
    return null;
  }

  public synchronized SortedStorePartitionInfo noPartition(NetAddress workerAddress, int storeId,
      int partitionIndex) throws TachyonException {
    // TODO the logic is wrong. Improve this.

    if (!mStores.containsKey(storeId)) {
      throw new TachyonException("Store does not exist: " + storeId);
    }
    MasterPartition partition = mStores.get(storeId).getPartition(partitionIndex);

    partition.removeLocation(workerAddress);

    SortedStorePartitionInfo res = partition.generateSortedStorePartitionInfo();
    if (!partition.hasLocation()) {
      int indexFileId = partition.mIndexFileID;
      List<ClientBlockInfo> blockInfo;
      try {
        blockInfo = mMasterInfo.getFileBlocks(indexFileId);
      } catch (FileDoesNotExistException e) {
        throw new TachyonException(e.getMessage());
      } catch (IOException e) {
        throw new TachyonException(e.getMessage());
      }
      res.setLocation(blockInfo.get(0).locations.get(0));
      LOG.info("kv_getPartition empty location: " + res);
    } else {
      LOG.info("kv_getPartition with locations: " + res);
    }
    return res;
  }

  @Override
  public List<ByteBuffer> process(List<ByteBuffer> data) throws ComponentException {
    minLengthCheck(data);

    MasterOperationType opType = null;
    try {
      opType = MasterOperationType.getOpType(data.get(0));
    } catch (IOException e) {
      throw new ComponentException(e);
    }

    try {
      switch (opType) {
        case CREATE_STORE: {
          lengthCheck(data, 2, opType.toString());
          int storeId = createStore(new TachyonURI(new String(data.get(1).array())));
          ByteBuffer buf = ByteBuffer.allocate(4);
          buf.putInt(storeId);
          buf.flip();
          return ImmutableList.of(buf);
        }
        case ADD_PARTITION: {
          lengthCheck(data, 2, opType.toString());

          TDeserializer deserializer = new TDeserializer(new TBinaryProtocol.Factory());
          SortedStorePartitionInfo info = new SortedStorePartitionInfo();
          deserializer.deserialize(info, data.get(1).array());

          boolean res = addPartition(info);
          ByteBuffer buf = ByteBuffer.allocate(1);
          buf.put((byte) (res ? 1 : 0));
          buf.flip();
          return ImmutableList.of(buf);
        }
        case GET_PARTITION: {
          lengthCheck(data, 3, opType.toString());

          int storeId = data.get(1).getInt();
          SortedStorePartitionInfo info = getPartition(storeId, data.get(2));

          TSerializer serializer = new TSerializer(new TBinaryProtocol.Factory());
          byte[] bytes = serializer.serialize(info);

          return ImmutableList.of(ByteBuffer.wrap(bytes));
        }
        case NO_PARTITION: {
          throw new ComponentException("NoPartition not supported yet.");
        }
        default :
          throw new ComponentException("Unprocessed MasterOperationType " + opType);
      }
    } catch (InvalidPathException e) {
      throw new ComponentException(e);
    } catch (FileAlreadyExistException e) {
      throw new ComponentException(e);
    } catch (TachyonException e) {
      throw new ComponentException(e);
    } catch (TException e) {
      throw new ComponentException(e);
    }
  }
}