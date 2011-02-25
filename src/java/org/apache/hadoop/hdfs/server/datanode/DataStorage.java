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

package org.apache.hadoop.hdfs.server.datanode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.LocalFileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.FileUtil.HardLink;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.protocol.FSConstants;
import org.apache.hadoop.hdfs.server.common.GenerationStamp;
import org.apache.hadoop.hdfs.server.common.InconsistentFSStateException;
import org.apache.hadoop.hdfs.server.common.Storage;
import org.apache.hadoop.hdfs.server.common.StorageInfo;
import org.apache.hadoop.hdfs.server.common.HdfsConstants.NodeType;
import org.apache.hadoop.hdfs.server.common.HdfsConstants.StartupOption;
import org.apache.hadoop.hdfs.server.protocol.NamespaceInfo;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.util.Daemon;
import org.apache.hadoop.util.DiskChecker;

/** 
 * Data storage information file.
 * <p>
 * @see Storage
 */
@InterfaceAudience.Private
public class DataStorage extends Storage {
  // Constants
  final static String BLOCK_SUBDIR_PREFIX = "subdir";
  final static String BLOCK_FILE_PREFIX = "blk_";
  final static String COPY_FILE_PREFIX = "dncp_";
  final static String STORAGE_DIR_RBW = "rbw";
  final static String STORAGE_DIR_FINALIZED = "finalized";
  final static String STORAGE_DIR_DETACHED = "detach";

  private static final Pattern PRE_GENSTAMP_META_FILE_PATTERN = 
    Pattern.compile("(.*blk_[-]*\\d+)\\.meta$");
  
  private String storageID;

  // flag to ensure initialzing storage occurs only once
  private boolean initilized = false;
  
  // BlockPoolStorage is map of <Block pool Id, BlockPoolStorage>
  private Map<String, BlockPoolStorage> bpStorageMap
    = new HashMap<String, BlockPoolStorage>();


  DataStorage() {
    super(NodeType.DATA_NODE);
    storageID = "";
  }
  
  public StorageInfo getBPStorage(String bpid) {
    return bpStorageMap.get(bpid);
  }
  
  public DataStorage(StorageInfo storageInfo, String strgID) {
    super(NodeType.DATA_NODE, storageInfo);
    this.storageID = strgID;
  }

  public String getStorageID() {
    return storageID;
  }
  
  void setStorageID(String newStorageID) {
    this.storageID = newStorageID;
  }
  
  /**
   * Analyze storage directories.
   * Recover from previous transitions if required. 
   * Perform fs state transition if necessary depending on the namespace info.
   * Read storage info.
   * <br>
   * This method should be synchronized between multiple DN threads.  Only the 
   * first DN thread does DN level storage dir recoverTransitionRead.
   * 
   * @param nsInfo namespace information
   * @param dataDirs array of data storage directories
   * @param startOpt startup option
   * @throws IOException
   */
  synchronized void recoverTransitionRead(NamespaceInfo nsInfo,
                             Collection<File> dataDirs,
                             StartupOption startOpt
                             ) throws IOException {
    if (initilized) {
      // DN storage has been initialized, no need to do anything
      return;
    }
    assert FSConstants.LAYOUT_VERSION == nsInfo.getLayoutVersion() :
      "Data-node and name-node layout versions must be the same.";
    
    // 1. For each data directory calculate its state and 
    // check whether all is consistent before transitioning.
    // Format and recover.
    this.storageID = "";
    this.storageDirs = new ArrayList<StorageDirectory>(dataDirs.size());
    ArrayList<StorageState> dataDirStates = new ArrayList<StorageState>(dataDirs.size());
    for(Iterator<File> it = dataDirs.iterator(); it.hasNext();) {
      File dataDir = it.next();
      StorageDirectory sd = new StorageDirectory(dataDir);
      StorageState curState;
      try {
        curState = sd.analyzeStorage(startOpt);
        // sd is locked but not opened
        switch(curState) {
        case NORMAL:
          break;
        case NON_EXISTENT:
          // ignore this storage
          LOG.info("Storage directory " + dataDir + " does not exist.");
          it.remove();
          continue;
        case NOT_FORMATTED: // format
          LOG.info("Storage directory " + dataDir + " is not formatted.");
          LOG.info("Formatting ...");
          format(sd, nsInfo);
          break;
        default:  // recovery part is common
          sd.doRecover(curState);
        }
      } catch (IOException ioe) {
        sd.unlock();
        throw ioe;
      }
      // add to the storage list
      addStorageDir(sd);
      dataDirStates.add(curState);
    }

    if (dataDirs.size() == 0)  // none of the data dirs exist
      throw new IOException(
          "All specified directories are not accessible or do not exist.");

    // 2. Do transitions
    // Each storage directory is treated individually.
    // During startup some of them can upgrade or rollback 
    // while others could be uptodate for the regular startup.
    for(int idx = 0; idx < getNumStorageDirs(); idx++) {
      doTransition(getStorageDir(idx), nsInfo, startOpt);
      assert this.getLayoutVersion() == nsInfo.getLayoutVersion() :
        "Data-node and name-node layout versions must be the same.";
      assert this.getCTime() == nsInfo.getCTime() :
        "Data-node and name-node CTimes must be the same.";
    }
    
    // make sure we have storage id set - if not - generate new one
    if(storageID.isEmpty()) {
      DataNode.setNewStorageID(DataNode.datanodeObject.dnRegistration);
      storageID = DataNode.datanodeObject.dnRegistration.storageID;
    }
    
    // 3. Update all storages. Some of them might have just been formatted.
    this.writeAll();
    
    // 4. mark DN storage is initilized
    this.initilized = true;
  }

  /**
   * recoverTransitionRead for a specific block pool
   * 
   * @param bpID Block pool Id
   * @param nsInfo Namespace info of namenode corresponding to the block pool
   * @param dataDirs Storage directories
   * @param startOpt startup option
   * @throws IOException on error
   */
  void recoverTransitionRead(String bpID, NamespaceInfo nsInfo,
      Collection<File> dataDirs, StartupOption startOpt) throws IOException {
    // First ensure datanode level format/snapshot/rollback is completed
    recoverTransitionRead(nsInfo, dataDirs, startOpt);
    
    // Create list of storage directories for the block pool
    Collection<File> bpDataDirs = new ArrayList<File>();
    for(Iterator<File> it = dataDirs.iterator(); it.hasNext();) {
      File dnRoot = it.next();
      File bpRoot = getBpRoot(bpID, dnRoot);
      bpDataDirs.add(bpRoot);
    }
    // mkdir for the list of BlockPoolStorage
    makeBlockPoolDataDir(bpDataDirs, null);
    BlockPoolStorage bpStorage = new BlockPoolStorage(nsInfo.getNamespaceID(), 
        bpID, nsInfo.getCTime(), nsInfo.getClusterID());
    
    bpStorage.recoverTransitionRead(nsInfo, bpDataDirs, startOpt);
    addBlockPoolStorage(bpID, bpStorage);
  }

  /**
   * Create physical directory for block pools on the data node
   * 
   * @param dataDirs
   *          List of data directories
   * @param conf
   *          Configuration instance to use.
   * @throws IOException on errors
   */
  static void makeBlockPoolDataDir(Collection<File> dataDirs,
      Configuration conf) throws IOException {
    if (conf == null)
      conf = new HdfsConfiguration();

    LocalFileSystem localFS = FileSystem.getLocal(conf);
    FsPermission permission = new FsPermission(conf.get(
        DFSConfigKeys.DFS_DATANODE_DATA_DIR_PERMISSION_KEY,
        DFSConfigKeys.DFS_DATANODE_DATA_DIR_PERMISSION_DEFAULT));
    for (File data : dataDirs) {
      try {
        DiskChecker.checkDir(localFS, new Path(data.toURI()), permission);
      } catch ( IOException e ) {
        LOG.warn("Invalid directory in: " + data.getCanonicalPath() + ": "
            + e.getMessage());
      }
    }
  }

  /**
   * Get a block pool root directory based on data node root directory
   * @param bpID block pool ID
   * @param dnRoot directory of data node root
   * @return root directory for block pool
   */
  private static File getBpRoot(String bpID, File dnRoot) {
    File bpRoot = new File(new File(dnRoot, STORAGE_DIR_CURRENT), bpID);
    return bpRoot;
  }
  
  void format(StorageDirectory sd, NamespaceInfo nsInfo) throws IOException {
    sd.clearDirectory(); // create directory
    this.layoutVersion = FSConstants.LAYOUT_VERSION;
    this.clusterID = nsInfo.getClusterID();
    this.namespaceID = nsInfo.getNamespaceID();
    this.cTime = 0;
    // store storageID as it currently is
    sd.write();
  }

  /*
   * Set ClusterID, StorageID, StorageType, CTime into
   * DataStorage VERSION file
  */
  @Override
  protected void setFields(Properties props, 
                           StorageDirectory sd 
                           ) throws IOException {
    props.setProperty("storageType", storageType.toString());
    props.setProperty("clusterID", clusterID);
    props.setProperty("cTime", String.valueOf(cTime));
    props.setProperty("layoutVersion", String.valueOf(layoutVersion));
    props.setProperty("storageID", storageID);
  }

  /*
   * Read ClusterID, StorageID, StorageType, CTime from 
   * DataStorage VERSION file and verify them.
   */
  @Override
  protected void getFields(Properties props, StorageDirectory sd)
      throws IOException {
    String scid = props.getProperty("clusterID");
    String sct = props.getProperty("cTime");
    String slv = props.getProperty("layoutVersion");
    String ssid = props.getProperty("storageID");
    String st = props.getProperty("storageType");

    if (scid == null || sct == null || slv == null|| ssid == null
        || st == null) {
      throw new InconsistentFSStateException(sd.getRoot(), "file "
          + STORAGE_FILE_VERSION + " is invalid.");
    }
    setClusterID(sd.getRoot(), scid);
    
    long rct = Long.parseLong(sct);
    cTime = rct;
    
    int rlv = Integer.parseInt(slv);
    setLayoutVersion(sd.getRoot(), rlv);
    
    NodeType rt = NodeType.valueOf(st);
    setStorageType(sd.getRoot(), rt);
    
    // valid storage id, storage id may be empty
    if ((!storageID.equals("") && !ssid.equals("") && !storageID.equals(ssid))) {
      throw new InconsistentFSStateException(sd.getRoot(),
          "has incompatible storage Id.");
    }
    
    if (storageID.equals("")) { // update id only if it was empty
      storageID = ssid;
    }
  }

  public boolean isConversionNeeded(StorageDirectory sd) throws IOException {
    File oldF = new File(sd.getRoot(), "storage");
    if (!oldF.exists())
      return false;
    // check the layout version inside the storage file
    // Lock and Read old storage file
    RandomAccessFile oldFile = new RandomAccessFile(oldF, "rws");
    FileLock oldLock = oldFile.getChannel().tryLock();
    try {
      oldFile.seek(0);
      int oldVersion = oldFile.readInt();
      if (oldVersion < LAST_PRE_UPGRADE_LAYOUT_VERSION)
        return false;
    } finally {
      oldLock.release();
      oldFile.close();
    }
    return true;
  }
  
  /**
   * Analize which and whether a transition of the fs state is required
   * and perform it if necessary.
   * 
   * Rollback if previousLV >= LAYOUT_VERSION && prevCTime <= namenode.cTime
   * Upgrade if this.LV > LAYOUT_VERSION || this.cTime < namenode.cTime
   * Regular startup if this.LV = LAYOUT_VERSION && this.cTime = namenode.cTime
   * 
   * @param sd  storage directory
   * @param nsInfo  namespace info
   * @param startOpt  startup option
   * @throws IOException
   */
  private void doTransition( StorageDirectory sd, 
                             NamespaceInfo nsInfo, 
                             StartupOption startOpt
                             ) throws IOException {
    if (startOpt == StartupOption.ROLLBACK) {
      doRollback(sd, nsInfo); // rollback if applicable
    }
    sd.read();
    checkVersionUpgradable(this.layoutVersion);
    assert this.layoutVersion >= FSConstants.LAYOUT_VERSION :
      "Future version is not allowed";

    if (!getClusterID().equals (nsInfo.getClusterID()))
      throw new IOException(
                            "Incompatible clusterIDs in " + sd.getRoot().getCanonicalPath()
                            + ": namenode clusterID = " + nsInfo.getClusterID() 
                            + "; datanode clusterID = " + getClusterID());
    // regular start up
    if (this.layoutVersion == FSConstants.LAYOUT_VERSION 
        && this.cTime == nsInfo.getCTime())
      return; // regular startup
    // verify necessity of a distributed upgrade
    verifyDistributedUpgradeProgress(nsInfo);
    // do upgrade
    if (this.layoutVersion > FSConstants.LAYOUT_VERSION
        || this.cTime < nsInfo.getCTime()) {
      doUpgrade(sd, nsInfo);  // upgrade
      return;
    }
    
    // layoutVersion == LAYOUT_VERSION && this.cTime > nsInfo.cTime
    // must shutdown
    throw new IOException("Datanode state: LV = " + this.getLayoutVersion() 
                          + " CTime = " + this.getCTime() 
                          + " is newer than the namespace state: LV = "
                          + nsInfo.getLayoutVersion() 
                          + " CTime = " + nsInfo.getCTime());
  }

  /**
   * Upgrade -- Move current storage into a backup directory,
   * and hardlink all its blocks into the new current directory.
   * 
   * Upgrade from pre-0.22 to 0.22 or later release e.g. 0.19/0.20/ => 0.22/0.23
   * <ul>
   * <li> If <SD>/previous exists then delete it </li>
   * <li> Rename <SD>/current to <SD>/previous.tmp </li>
   * <li>Create new <SD>/current/<bpid>/current directory<li>
   * <ul>
   * <li> Hard links for block files are created from <SD>/previous.tmp 
   * to <SD>/current/<bpid>/current </li>
   * <li> Saves new version file in <SD>/current/<bpid>/current directory </li>
   * </ul>
   * <li> Rename <SD>/previous.tmp to <SD>/previous </li>
   * </ul>
   * 
   * There should be only ONE namenode in the cluster for first 
   * time upgrade to 0.22
   * @param sd  storage directory
   * @throws IOException on error
   */
  void doUpgrade(StorageDirectory sd, NamespaceInfo nsInfo) throws IOException {
    //  bp root directory <SD>/current/<bpid>
    File bpRootDir = getBpRoot(nsInfo.getBlockPoolID(), sd.getRoot());

    // regular startup if <SD>/current/<bpid> direcotry exist,
    // i.e. the stored version is 0.22 or later release
    if (bpRootDir.exists())
      return;
    
    LOG.info("Upgrading storage directory " + sd.getRoot()
             + ".\n   old LV = " + this.getLayoutVersion()
             + "; old CTime = " + this.getCTime()
             + ".\n   new LV = " + nsInfo.getLayoutVersion()
             + "; new CTime = " + nsInfo.getCTime());
    File curDir = sd.getCurrentDir();
    File prevDir = sd.getPreviousDir();
    assert curDir.exists() : "Data node current directory must exist.";
    // Cleanup directory "detach"
    cleanupDetachDir(new File(curDir, STORAGE_DIR_DETACHED));
    
    // 1. delete <SD>/previous dir before upgrading
    if (prevDir.exists())
      deleteDir(prevDir);
    // get previous.tmp directory, <SD>/previous.tmp
    File tmpDir = sd.getPreviousTmp();
    assert !tmpDir.exists() : 
      "Data node previous.tmp directory must not exist.";
    
    // 2. Rename <SD>/current to <SD>/previous.tmp
    rename(curDir, tmpDir);
    
    // 3. Format BP and hard link blocks from previous directory
    File curBpDir = getBpRoot(nsInfo.getBlockPoolID(), curDir);
    BlockPoolStorage bpStorage = new BlockPoolStorage(nsInfo.getNamespaceID(), 
        nsInfo.getBlockPoolID(), nsInfo.getCTime(), nsInfo.getClusterID());
    bpStorage.format(new StorageDirectory(curBpDir), nsInfo);
    linkAllBlocks(tmpDir, curBpDir);
    
    // 4. Write version file under <SD>/current/<bpid>/current
    layoutVersion = FSConstants.LAYOUT_VERSION;
    cTime = nsInfo.getCTime();
    sd.write();
    
    // 5. Rename <SD>/previous.tmp to <SD>/previous
    rename(tmpDir, prevDir);
    LOG.info("Upgrade of " + sd.getRoot()+ " is complete.");
    addBlockPoolStorage(nsInfo.getBlockPoolID(), bpStorage);
  }

  /**
   * Cleanup the detachDir. 
   * 
   * If the directory is not empty report an error; 
   * Otherwise remove the directory.
   * 
   * @param detachDir detach directory
   * @throws IOException if the directory is not empty or it can not be removed
   */
  private void cleanupDetachDir(File detachDir) throws IOException {
    if (layoutVersion >= PRE_RBW_LAYOUT_VERSION &&
        detachDir.exists() && detachDir.isDirectory() ) {
      
        if (detachDir.list().length != 0 ) {
          throw new IOException("Detached directory " + detachDir +
              " is not empty. Please manually move each file under this " +
              "directory to the finalized directory if the finalized " +
              "directory tree does not have the file.");
        } else if (!detachDir.delete()) {
          throw new IOException("Cannot remove directory " + detachDir);
        }
    }
  }
  
  /** 
   * Rolling back to a snapshot in previous directory by moving it to current
   * directory.
   * Rollback procedure:
   * <br>
   * If previous directory exists:
   * <ol>
   * <li> Rename current to removed.tmp </li>
   * <li> Rename previous to current </li>
   * <li> Remove removed.tmp </li>
   * </ol>
   * 
   * Do nothing, if previous directory does not exist.
   */
  void doRollback( StorageDirectory sd,
                   NamespaceInfo nsInfo
                   ) throws IOException {
    File prevDir = sd.getPreviousDir();
    // regular startup if previous dir does not exist
    if (!prevDir.exists())
      return;
    DataStorage prevInfo = new DataStorage();
    StorageDirectory prevSD = prevInfo.new StorageDirectory(sd.getRoot());
    prevSD.read(prevSD.getPreviousVersionFile());

    // We allow rollback to a state, which is either consistent with
    // the namespace state or can be further upgraded to it.
    if (!(prevInfo.getLayoutVersion() >= FSConstants.LAYOUT_VERSION
          && prevInfo.getCTime() <= nsInfo.getCTime()))  // cannot rollback
      throw new InconsistentFSStateException(prevSD.getRoot(),
          "Cannot rollback to a newer state.\nDatanode previous state: LV = "
              + prevInfo.getLayoutVersion() + " CTime = " + prevInfo.getCTime()
              + " is newer than the namespace state: LV = "
              + nsInfo.getLayoutVersion() + " CTime = " + nsInfo.getCTime());
    LOG.info("Rolling back storage directory " + sd.getRoot()
             + ".\n   target LV = " + nsInfo.getLayoutVersion()
             + "; target CTime = " + nsInfo.getCTime());
    File tmpDir = sd.getRemovedTmp();
    assert !tmpDir.exists() : "removed.tmp directory must not exist.";
    // rename current to tmp
    File curDir = sd.getCurrentDir();
    assert curDir.exists() : "Current directory must exist.";
    rename(curDir, tmpDir);
    // rename previous to current
    rename(prevDir, curDir);
    // delete tmp dir
    deleteDir(tmpDir);
    LOG.info("Rollback of " + sd.getRoot() + " is complete.");
  }
  
  /**
   * Finalize procedure deletes an existing snapshot.
   * <ol>
   * <li>Rename previous to finalized.tmp directory</li>
   * <li>Fully delete the finalized.tmp directory</li>
   * </ol>
   * 
   * Do nothing, if previous directory does not exist
   */
  void doFinalize(StorageDirectory sd) throws IOException {
    File prevDir = sd.getPreviousDir();
    if (!prevDir.exists())
      return; // already discarded
    
    final String dataDirPath = sd.getRoot().getCanonicalPath();
    LOG.info("Finalizing upgrade for storage directory " 
             + dataDirPath 
             + ".\n   cur LV = " + this.getLayoutVersion()
             + "; cur CTime = " + this.getCTime());
    assert sd.getCurrentDir().exists() : "Current directory must exist.";
    final File tmpDir = sd.getFinalizedTmp();//finalized.tmp directory
    // 1. rename previous to finalized.tmp
    rename(prevDir, tmpDir);

    // 2. delete finalized.tmp dir in a separate thread
    new Daemon(new Runnable() {
        public void run() {
          try {
            deleteDir(tmpDir);
          } catch(IOException ex) {
            LOG.error("Finalize upgrade for " + dataDirPath + " failed.", ex);
          }
          LOG.info("Finalize upgrade for " + dataDirPath + " is complete.");
        }
        public String toString() { return "Finalize " + dataDirPath; }
      }).start();
  }
  
  
  /*
   * Finalize the upgrade for a block pool
   */
  void finalizeUpgrade(String bpID) throws IOException {
    // To handle finalizing a snapshot taken at datanode level while 
    // upgrading to federation, if datanode level snapshot previous exists, 
    // then finalize it. Else finalize the corresponding BP.
    for (StorageDirectory sd : storageDirs) {
      File prevDir = sd.getPreviousDir();
      if (prevDir.exists()) {
        // data node level storage finalize
        doFinalize(sd);
      } else {
        // block pool storage finalize using specific bpID
        File dnRoot = sd.getRoot();
        BlockPoolStorage bpStorage = bpStorageMap.get(bpID);
        File bpRoot = getBpRoot(bpID, dnRoot);
        bpStorage.doFinalize(new StorageDirectory(bpRoot));
      }
    }
  }

  /**
   * Hardlink all finalized and RBW blocks in fromDir to toDir
   * @param fromDir directory where the snapshot is stored
   * @param toDir the current data directory
   * @throws IOException if error occurs during hardlink
   */
  private void linkAllBlocks(File fromDir, File toDir) throws IOException {
    // do the link
    int diskLayoutVersion = this.getLayoutVersion();
    if (diskLayoutVersion < PRE_RBW_LAYOUT_VERSION) { // RBW version
      // hardlink finalized blocks in tmpDir/finalized
      linkBlocks(new File(fromDir, STORAGE_DIR_FINALIZED), 
          new File(toDir, STORAGE_DIR_FINALIZED), diskLayoutVersion);
      // hardlink rbw blocks in tmpDir/finalized
      linkBlocks(new File(fromDir, STORAGE_DIR_RBW), 
          new File(toDir, STORAGE_DIR_RBW), diskLayoutVersion);
    } else { // pre-RBW version
      // hardlink finalized blocks in tmpDir
      linkBlocks(fromDir, 
          new File(toDir, STORAGE_DIR_FINALIZED), diskLayoutVersion);      
    }    
  }
  
  static void linkBlocks(File from, File to, int oldLV) throws IOException {
    if (!from.exists()) {
      return;
    }
    if (!from.isDirectory()) {
      if (from.getName().startsWith(COPY_FILE_PREFIX)) {
        FileInputStream in = new FileInputStream(from);
        try {
          FileOutputStream out = new FileOutputStream(to);
          try {
            IOUtils.copyBytes(in, out, 16*1024);
          } finally {
            out.close();
          }
        } finally {
          in.close();
        }
      } else {
        
        //check if we are upgrading from pre-generation stamp version.
        if (oldLV >= PRE_GENERATIONSTAMP_LAYOUT_VERSION) {
          // Link to the new file name.
          to = new File(convertMetatadataFileName(to.getAbsolutePath()));
        }
        
        HardLink.createHardLink(from, to);
      }
      return;
    }
    // from is a directory
    if (!to.mkdirs())
      throw new IOException("Cannot create directory " + to);
    String[] blockNames = from.list(new java.io.FilenameFilter() {
        public boolean accept(File dir, String name) {
          return name.startsWith(BLOCK_SUBDIR_PREFIX) 
            || name.startsWith(BLOCK_FILE_PREFIX)
            || name.startsWith(COPY_FILE_PREFIX);
        }
      });
    
    for(int i = 0; i < blockNames.length; i++)
      linkBlocks(new File(from, blockNames[i]), 
                 new File(to, blockNames[i]), oldLV);
  }

  protected void corruptPreUpgradeStorage(File rootDir) throws IOException {
    File oldF = new File(rootDir, "storage");
    if (oldF.exists())
      return;
    // recreate old storage file to let pre-upgrade versions fail
    if (!oldF.createNewFile())
      throw new IOException("Cannot create file " + oldF);
    RandomAccessFile oldFile = new RandomAccessFile(oldF, "rws");
    // write new version into old storage file
    try {
      writeCorruptedData(oldFile);
    } finally {
      oldFile.close();
    }
  }

  private void verifyDistributedUpgradeProgress(
                  NamespaceInfo nsInfo
                ) throws IOException {
    UpgradeManagerDatanode um = DataNode.getDataNode().upgradeManager;
    assert um != null : "DataNode.upgradeManager is null.";
    um.setUpgradeState(false, getLayoutVersion());
    um.initializeUpgrade(nsInfo);
  }
  
  /**
   * This is invoked on target file names when upgrading from pre generation 
   * stamp version (version -13) to correct the metatadata file name.
   * @param oldFileName
   * @return the new metadata file name with the default generation stamp.
   */
  private static String convertMetatadataFileName(String oldFileName) {
    Matcher matcher = PRE_GENSTAMP_META_FILE_PATTERN.matcher(oldFileName); 
    if (matcher.matches()) {
      //return the current metadata file name
      return FSDataset.getMetaFileName(matcher.group(1),
          GenerationStamp.GRANDFATHER_GENERATION_STAMP); 
    }
    return oldFileName;
  }

  /**
   * Add bpStorage into bpStorageMap
   */
  private void addBlockPoolStorage(String bpID, BlockPoolStorage bpStorage)
      throws IOException {
    if (!this.bpStorageMap.containsKey(bpID)) {
      this.bpStorageMap.put(bpID, bpStorage);
    }
  }
}