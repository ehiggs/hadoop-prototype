/**
 * 
 */
package org.apache.hadoop.hdfs.server.datanode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.server.common.HdfsServerConstants.ReplicaState;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.FsVolumeSpi;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.LengthInputStream;
import org.apache.hadoop.hdfs.server.datanode.fsdataset.impl.FsDatasetUtil;


public class ProvidedReplica extends ReplicaInfo {

  private URI fileURI;
  private long fileOffset;
  private Configuration conf;
  FileSystem remoteFS;
  
  //TODO metaFile can also be remote?
  private File metaFile;
  
  /**
   * Constructor
   * @param blockId block id
   * @param fileURI remote URI this block is to be read from
   * @param fileOffset the offset in the remote URI
   * @param blockLen the length of the block
   * @param genStamp the generation stamp of the block
   * @param volume the volume this block belongs to
   */
  public ProvidedReplica(long blockId, URI fileURI, long fileOffset, long blockLen, 
      long genStamp, FsVolumeSpi volume, Configuration conf) {
    super(blockId, blockLen, genStamp, volume, null);
    this.fileURI = fileURI;
    this.fileOffset = fileOffset;
    this.conf = conf;
    try {
      this.remoteFS = FileSystem.newInstance(fileURI, this.conf);
    } catch (IOException e) {
      this.remoteFS = null;
    }
    
    this.metaFile = FsDatasetUtil.createNullChecksumFile(DatanodeUtil.getMetaName(getBlockName(), getGenerationStamp()));
  }

  public ProvidedReplica (ProvidedReplica r) {
    super(r);
    this.fileURI = r.fileURI;
    this.fileOffset = r.fileOffset;
    this.conf = r.conf;
    
    try {
      this.remoteFS = FileSystem.newInstance(fileURI, this.conf);
    } catch (IOException e) {
      this.remoteFS = null;
    }
    
    this.metaFile = FsDatasetUtil.createNullChecksumFile(DatanodeUtil.getMetaName(getBlockName(), getGenerationStamp()));
  }
  @Override
  public ReplicaState getState() {
    return ReplicaState.PROVIDED;
  }

  @Override
  public long getBytesOnDisk() {
    return getNumBytes();
  }

  @Override
  public long getVisibleLength() {
    return getNumBytes(); //all bytes are visible
  }
  
  @Override
  public URI getBlockURI() {
    return this.fileURI; 
  }

  @Override
  public String getDatastoreName() {
    //TODO make sure this is correct/consistent with the rest of the names
    return getBlockName();
  }

  @Override
  public InputStream getDataInputStream(long seekOffset) throws IOException {
    
    if (remoteFS != null) {
      FSDataInputStream ins = remoteFS.open(new Path(fileURI));
      ins.seek(fileOffset + seekOffset);
      return new FSDataInputStream(ins);
    }
    else
      throw new IOException("Remote filesystem for provided replica " + this + " does not exist");
  }
  
  @Override
  public OutputStream getDataOutputStream(boolean append) throws IOException {
    //TODO may be append?
    throw new IOException("OutputDataStream is not implemented");
  }
  
  @Override
  public boolean dataSourceExists() {
    if(remoteFS != null) {
      try {
        return remoteFS.exists(new Path(fileURI));
      } catch (IOException e) {
        return false;
      }
    }
    else {
      return false;
    }
  }
  
  @Override
  public boolean deleteDataSource() {
    //TODO
    return false;
  }
  
  @Override
  public long getDataSourceLength() {
    return this.getNumBytes();
  }
  
  @Override
  public URI getMetadataURI() {
    return metaFile.toURI();
  }
    
  @Override
  public LengthInputStream getMetadataInputStream() throws IOException {
    return new LengthInputStream(new FileInputStream(metaFile), metaFile.length());
  }
  
  @Override
  public OutputStream getMetadataOutputStream(boolean append) throws IOException {
    return new FileOutputStream(metaFile, append);
  }
  
  @Override
  public boolean metadataSourceExists() {
    return metaFile.exists();
  }
  
  @Override
  public boolean deleteMetadataSource() {
    //TODO 
    return false;
  }
  
  @Override
  public long getMetadataSourceLength() {
    return metaFile.length();
  }
  
}