/* Copyright 2016 Husky Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package husky.server;

import org.apache.commons.cli.*;
import org.apache.commons.math3.util.Pair;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.yarn.api.records.FinalApplicationStatus;
import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.client.api.AMRMClient.ContainerRequest;
import org.apache.hadoop.yarn.client.api.async.AMRMClientAsync;
import org.apache.hadoop.yarn.client.api.async.NMClientAsync;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.util.Records;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class HuskyApplicationMaster {
  private static final Logger LOG = Logger.getLogger(HuskyApplicationMaster.class.getName());

  private YarnConfiguration mYarnConf = null;
  private FileSystem mFileSystem = null;

  private String mLocalFiles = "";
  private String mLocalArchives = "";
  private String mAppMasterLogDir = "";
  private String mLogPathToHDFS = "";

  private String mMasterExec = "";
  private String mWorkerExec = "";
  private String mWorkersInfoFile = "";
  private ArrayList<Pair<String, Integer>> mWorkerInfos = new ArrayList<Pair<String, Integer>>();
  private String mMasterPort = "";
  private String mMasterJobListenerPort = "";
  private String mHdfsNameNodeHost = "";
  private String mHdfsNameNodePort = "";

  private int mContainerMemory = 0;
  private int mNumVirtualCores = 0;
  private int mAppPriority = 0;

  private HuskyRMCallbackHandler mRMClientListener = null;
  private AMRMClientAsync<ContainerRequest> mRMClient = null;
  private HuskyNMCallbackHandler mContainerListener = null;
  private NMClientAsync mNMClient = null;

  public HuskyApplicationMaster() throws IOException {
    mYarnConf = new YarnConfiguration();
    mFileSystem = FileSystem.get(mYarnConf);
  }

  private Options createAppMasterOptions() {
    Options opts = new Options();
    opts.addOption("help", false, "Print Usage");
    opts.addOption("app_master_log_dir", true, "Log directory where application master stores its logs");
    opts.addOption("container_memory", true, "Amount of memory in MB to be requested to run a husky worker node");
    opts.addOption("container_vcores", true, "Number of virtual cores to be requested to run a husky worker node");
    opts.addOption("app_priority", true, "A number to indicate the priority to run a husky worker node");
    opts.addOption("master", true, "Executable for c++ husky master");
    opts.addOption("worker", true, "Executable for c++ husky worker");
    opts.addOption("workers_info_file", true,
        "Workers info file for c++ husky master and worker(on local file system or HDFS)");
    opts.addOption("worker_infos", true,
        "Specified hosts that husky worker will run on. Use comma(,) to split different archives.");
    opts.addOption("hdfs_namenode_host", true, "HDFS Namenode host");
    opts.addOption("hdfs_namenode_port", true, "HDFS Namenode port");
    opts.addOption("local_files", true,
        "Files that need to pass to working environment. Use comma(,) to split different files.");
    opts.addOption("local_archives", true,
        "Archives that need to pass to and be unarchived in working environment. Use comma(,) to split different archives.");
    opts.addOption("log_to_hdfs", true,
        "Path on HDFS where to upload the logs of worker nodes");
    return opts;
  }

  private void printUsage() {
    new HelpFormatter().printHelp("HuskyYarnClient", createAppMasterOptions());
  }

  private boolean init(String[] args) throws ParseException, IOException {
    // parse options
    CommandLine cliParser = new GnuParser().parse(createAppMasterOptions(), args);

    if (args.length == 0 || cliParser.hasOption("help")) {
      printUsage();
      return false;
    }

    mLocalFiles = cliParser.getOptionValue("local_files", "");
    mLocalArchives = cliParser.getOptionValue("local_archives", "");
    mLogPathToHDFS = cliParser.getOptionValue("log_to_hdfs", "");
    if (!mLogPathToHDFS.isEmpty()) {
      if (!mFileSystem.isDirectory(new Path(mLogPathToHDFS))) {
        throw new IllegalArgumentException("The given log path is not a directory on HDFS: " + mLogPathToHDFS);
      }
    }
    if (!cliParser.hasOption("app_master_log_dir")) {
      throw new IllegalArgumentException("Log directory of application master is not set");
    }
    mAppMasterLogDir = cliParser.getOptionValue("app_master_log_dir");

    if (!cliParser.hasOption("master")) {
      throw new IllegalArgumentException("No executable specified for c++ husky master");
    }
    mMasterExec = cliParser.getOptionValue("master");

    if (!cliParser.hasOption("worker")) {
      throw new IllegalArgumentException("No executable specified for c++ husky workers");
    }
    mWorkerExec = cliParser.getOptionValue("worker");

    if (!cliParser.hasOption("workers_info_file")) {
      throw new IllegalArgumentException("No workers info file given for c++ husky master and worker");
    }
    mWorkersInfoFile = cliParser.getOptionValue("workers_info_file");

    if (cliParser.hasOption("worker_infos")) {
      for (String i : cliParser.getOptionValue("worker_infos").split(",")) {
        String[] pair = i.trim().split(":");
        if (pair.length != 2) {
          throw new IllegalArgumentException("Invalid worker info: " + i.trim());
        }
        try {
          Pair<String, Integer> p = new Pair<String, Integer>(pair[0], Integer.parseInt(pair[1]));
          if (p.getSecond() <= 0) {
            throw new IllegalArgumentException("Invalid worker info, number of worker should be large than 0: " + i.trim());
          }
          mWorkerInfos.add(p);
        } catch (NumberFormatException e) {
          LOG.log(Level.SEVERE, "Invalid number of worker given in worker_infos: " + i.trim());
          throw e;
        }
      }
      if (mWorkerInfos.isEmpty()) {
        throw new IllegalArgumentException("Parameter `worker_infos` is empty.");
      }
    } else {
      throw new IllegalArgumentException("No worker information is provided. Parameter `worker_infos` is not set.");
    }

    if (!cliParser.hasOption("hdfs_namenode_host")) {
      throw new IllegalArgumentException("No HDFS Namenode host");
    }
    mHdfsNameNodeHost = cliParser.getOptionValue("hdfs_namenode_host");

    if (!cliParser.hasOption("hdfs_namenode_port")) {
      throw new IllegalArgumentException("No HDFS Namenode port");
    }
    mHdfsNameNodePort = cliParser.getOptionValue("hdfs_namenode_port");

    mContainerMemory = Integer.parseInt(cliParser.getOptionValue("container_memory", "2048"));
    if (mContainerMemory < 0) {
      throw new IllegalArgumentException(
          "Illegal memory specified for container. Specified memory: " + mContainerMemory);
    }

    mNumVirtualCores = Integer.parseInt(cliParser.getOptionValue("container_vcores", "1"));
    if (mNumVirtualCores <= 0) {
      throw new IllegalArgumentException(
          "Illegal number of virtual cores specified for container. Specified number of vcores: " + mNumVirtualCores);
    }

    mAppPriority = Integer.parseInt(cliParser.getOptionValue("app_priority", "1"));
    if (mAppPriority <= 0) {
      throw new IllegalArgumentException(
          "Illegal priority for husky application. Specified priority: " + mAppPriority);
    }

    mMasterPort = Integer.toString(getAvaiablePort());
    if (mMasterPort.isEmpty()) {
      throw new IllegalArgumentException("Invalid port for husky master");
    }
    LOG.info("Get husky master port" + mMasterPort);

    mMasterJobListenerPort = Integer.toString(getAvaiablePort());
    if (mMasterJobListenerPort.isEmpty()) {
      throw new IllegalArgumentException("Invalid job listener port for husky master");
    }
    LOG.info("Get husky master job listener port" + mMasterJobListenerPort);

    return true;
  }

  private ContainerRequest setupContainerAskForRMSpecific(String host) {
    Priority priority = Records.newRecord(Priority.class);
    priority.setPriority(mAppPriority);

    Resource capability = Records.newRecord(Resource.class);
    capability.setMemory(mContainerMemory);
    capability.setVirtualCores(mNumVirtualCores);

    // The second arg controls the hosts of containers
    return new ContainerRequest(capability, new String[]{host}, null, priority, false);
  }

  private void run() throws YarnException, IOException, InterruptedException, ExecutionException {
    LOG.info("Run App Master");

    mRMClientListener = new HuskyRMCallbackHandler(this);
    mRMClient = AMRMClientAsync.createAMRMClientAsync(1000, mRMClientListener);
    mRMClient.init(mYarnConf);
    mRMClient.start();

    mContainerListener = new HuskyNMCallbackHandler();
    mNMClient = NMClientAsync.createNMClientAsync(mContainerListener);
    mNMClient.init(mYarnConf);
    mNMClient.start();

    // Register with ResourceManager
    LOG.info("registerApplicationMaster started");
    mRMClient.registerApplicationMaster("", 0, "");
    LOG.info("registerApplicationMaster done");

    // Ask RM to start `mNumContainer` containers, each is a worker node
    LOG.info("Ask RM for " + mWorkerInfos.size() + " containers");
    for (Pair<String, Integer> i : mWorkerInfos) {
      mRMClient.addContainerRequest(setupContainerAskForRMSpecific(i.getFirst()));
    }

    FinalApplicationStatus status = mRMClientListener.getFinalNumSuccess() == mWorkerInfos.size()
        ? FinalApplicationStatus.SUCCEEDED : FinalApplicationStatus.FAILED;

    mRMClient.unregisterApplicationMaster(status, mRMClientListener.getStatusReport(), null);
  }

  static public void main(String[] args) {
    LOG.info("Start running HuskyApplicationMaster");
    try {
      HuskyApplicationMaster appMaster = new HuskyApplicationMaster();
      if (!appMaster.init(args)) {
        System.exit(0);
      }
      appMaster.run();
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Error running HuskyApplicationMaster", e);
      System.exit(-1);
    }
    LOG.info("HuskyApplicationMaster completed successfully");
    System.exit(0);
  }

  protected int getAvaiablePort() {
    ServerSocket socket = null;
    try {
      socket = new ServerSocket(0);
      socket.setReuseAddress(true);
      int port = socket.getLocalPort();
      try {
        socket.close();
      } catch (IOException e) {
      }
      return port;
    } catch (IOException e) { 
    } finally {
      if (socket != null) {
        try {
          socket.close();
        } catch (IOException e) {
        }
      }
    }
    throw new IllegalStateException("Could not find a free TCP/IP port");
  }

  public FileSystem getFileSystem() {
    return mFileSystem;
  }

  public AMRMClientAsync<ContainerRequest> getRMClient() {
    return mRMClient;
  }

  public NMClientAsync getNMClient() {
    return mNMClient;
  }

  public HuskyNMCallbackHandler getContainerListener() {
    return mContainerListener;
  }

  public String getAppMasterLogDir() {
    return mAppMasterLogDir;
  }

  public String getMasterExec() {
    return mMasterExec;
  }

  public String getWorkerExec() {
    return mWorkerExec;
  }

  public String getLocalFiles() {
    return mLocalFiles;
  }

  public String getLocalArchives() {
    return mLocalArchives;
  }

  public String getWorkersInfoFile() {
    return mWorkersInfoFile;
  }

  public ArrayList<Pair<String, Integer>> getWorkerInfos() {
    return mWorkerInfos;
  }

  public String getMasterPort() {
    return mMasterPort;
  }

  public String getMasterJobListenerPort() {
    return mMasterJobListenerPort;
  }

  public String getHdfsNameNodeHost() {
    return mHdfsNameNodeHost;
  }

  public String getHdfsNameNodePort() {
    return mHdfsNameNodePort;
  }

  public String getAppMasterHost() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      LOG.log(Level.SEVERE, " Cannot fetch the hostname of application master: ", e);
      return "127.0.0.1"; // if fails to get hostname, use this instead.
    }
  }

  public String getLogPathToHDFS() {
    return mLogPathToHDFS;
  }

}
