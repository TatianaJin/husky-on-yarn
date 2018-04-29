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

package husky.client;

import husky.server.HuskyApplicationMaster;
import org.apache.commons.cli.*;
import org.apache.commons.cli.Options;
import org.apache.commons.math3.util.Pair;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.yarn.api.records.*;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.client.api.YarnClientApplication;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.util.ConverterUtils;
import org.apache.hadoop.yarn.util.Records;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.apache.hadoop.yarn.api.ApplicationConstants.Environment.JAVA_HOME;
import static org.apache.hadoop.yarn.api.records.FinalApplicationStatus.SUCCEEDED;
import static org.apache.hadoop.yarn.api.records.LocalResourceType.ARCHIVE;
import static org.apache.hadoop.yarn.api.records.LocalResourceType.FILE;
import static org.apache.hadoop.yarn.api.records.YarnApplicationState.FAILED;
import static org.apache.hadoop.yarn.api.records.YarnApplicationState.FINISHED;
import static org.apache.hadoop.yarn.api.records.YarnApplicationState.KILLED;
import static org.apache.hadoop.yarn.conf.YarnConfiguration.DEFAULT_YARN_APPLICATION_CLASSPATH;
import static org.apache.hadoop.yarn.conf.YarnConfiguration.YARN_APPLICATION_CLASSPATH;

// TODO(zzxx): Archive files not working
public class HuskyYarnClient {
  private static final Logger LOG = Logger.getLogger(HuskyYarnClient.class.getName());
  // path on hdfs where local resources store
  private static final String LOCAL_RESOURCES_HDFS_ROOT = "hdfs:///husky-yarn/";
  // name of application
  private static final String DEFAULT_APP_NAME = "Husky-on-Yarn-App";

  // Yarn
  private FileSystem mFileSystem = null;
  private YarnClient mYarnClient = null;
  private YarnConfiguration mYarnConf = null;
  // Yarn application
  private ApplicationId mAppId = null;
  private String mAppName;
  private String mAppMasterJar = "";  // Jar that contains Application class
  private String mLocalResourceHDFSPaths;  // Paths to resources that need to download to working environment
  private String mLocalFiles = "";  // Paths to resources that either locate in client machine or on HDFS
  private String mLocalArchives = "";  // Paths to archives that either locate in client machine or on HDFS
  // container resources
  private int mAppMasterMemory = 0;
  private int mContainerMemory = 0;  // Memory that can be used by a container
  private int mNumVirtualCores = 0;
  private int mAppPriority = 0;
  // husky application
  private String mMasterExec = "";
  private String mWorkerExec = "";
  private String mWorkersInfoFile = "";
  private ArrayList<Pair<String, Integer>> mWorkerInfos = new ArrayList<Pair<String, Integer>>();
  private String mHdfsNameNodeHost = "";
  private String mHdfsNameNodePort = "";
  private String mJobArgs = null;
  private String mLogPathToHDFS = "";

  public HuskyYarnClient() throws IOException {
    mYarnConf = new YarnConfiguration();
    mFileSystem = FileSystem.get(mYarnConf);
    mYarnClient = YarnClient.createYarnClient();
    mYarnClient.init(mYarnConf);
  }

  private Options createClientOptions() {
    Options opts = new Options();
    // set up options
    opts.addOption("help", false, "Print Usage");

    opts.addOption("app_name", true, "The name of the application");
    opts.addOption("jar", true, "Local path to the jar file of application master.");
    opts.addOption("local_resrcrt", true, "The root directory on hdfs where local resources store");
    opts.addOption("local_files", true,
        "Files that need to pass to working environment. Use comma(,) to split different files.");
    opts.addOption("local_archives", true,
        "Archives that need to pass to and be unarchived in working environment. Use comma(,) to split different archives.");

    opts.addOption("master_memory", true, "Amount of memory in MB to be requested to run application master");
    opts.addOption("container_memory", true,
        "Amount of memory in MB to be requested to run container. Each container is a worker node.");
    opts.addOption("container_vcores", true, "Number of virtual cores that a container can use");
    opts.addOption("app_priority", true, "A number to indicate the priority of the husky application");

    opts.addOption("master", true, "Executable for c++ husky master (on local file system or HDFS)");
    opts.addOption("worker", true, "Executable for c++ husky worker (on local file system or HDFS)");
    opts.addOption("workers_info_file", true,
        "Workers info file for c++ husky master and worker(on local file system or HDFS)");
    opts.addOption("worker_infos", true,
        "Specified hosts that husky worker will run on. Use comma(,) to split different archives.");
    opts.addOption("hdfs_namenode_host", true, "HDFS Namenode host");
    opts.addOption("hdfs_namenode_port", true, "HDFS Namenode port");
    opts.addOption("job_args", true, "Job arguments if submit. The first is job name");
    opts.addOption("log_to_hdfs", true, "Path on HDFS where to upload logs of application master and worker containers");
    return opts;
  }

  private void printUsage() {
    new HelpFormatter().printHelp("HuskyYarnClient", createClientOptions());
  }

  private boolean init(String[] args) throws ParseException, IOException {
    // parse options
    CommandLine cliParser = new GnuParser().parse(createClientOptions(), args);

    // analyze options
    if (args.length == 0 || cliParser.hasOption("help")) {
      printUsage();
      return false;
    }

    mAppName = cliParser.getOptionValue("app_name", cliParser.getOptionValue("app_name", DEFAULT_APP_NAME));
    mLocalResourceHDFSPaths = cliParser.getOptionValue("local_resrcrt", LOCAL_RESOURCES_HDFS_ROOT);
    mLocalFiles = cliParser.getOptionValue("local_files", "");
    mLocalArchives = cliParser.getOptionValue("local_archives", "");

    if (cliParser.hasOption("jar")) {
      mAppMasterJar = cliParser.getOptionValue("jar");
    } else {
      mAppMasterJar = JobConf.findContainingJar(HuskyApplicationMaster.class);
      if (mAppMasterJar == null) {
        throw new IllegalArgumentException("No jar specified for husky application master");
      }
    }
    LOG.info("Husky Application Master's jar is " + mAppMasterJar);

    mAppMasterMemory = Integer.parseInt(cliParser.getOptionValue("master_memory", "2048"));
    if (mAppMasterMemory <= 0) {
      throw new IllegalArgumentException(
          "Illegal memory specified for application master. Specified memory: " + mAppMasterMemory);
    }

    mContainerMemory = Integer.parseInt(cliParser.getOptionValue("container_memory", "512"));
    if (mContainerMemory <= 0) {
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

    if (cliParser.hasOption("job_args")) {
      mJobArgs = cliParser.getOptionValue("job_args");
      LOG.info("Job args: " + mJobArgs);
    }

    mLogPathToHDFS = cliParser.getOptionValue("log_to_hdfs", "");
    if (!mLogPathToHDFS.isEmpty()) {
      if (!mFileSystem.isDirectory(new Path(mLogPathToHDFS))) {
        throw new IllegalArgumentException("The given log path is not a directory on HDFS: " + mLogPathToHDFS);
      }
    }

    return true;
  }

  // should access through getLocalResources()
  private HashMap<String, LocalResource> localResources = null;

  private Pair<String, LocalResource> constructLocalResource(String name, String path, LocalResourceType type)
      throws IOException {
    LOG.info("To copy " + name + "(" + path + ") from local file system");

    Path resourcePath = new Path(path);
    if (path.startsWith("hdfs://")) {
      FileStatus fileStatus = mFileSystem.getFileStatus(resourcePath);
      if (!fileStatus.isFile()) {
        throw new RuntimeException("Only files can be provided as local resources.");
      }
    } else {
      File file = new File(path);
      if (!file.exists()) {
        throw new RuntimeException("File not exist: " + path);
      }
      if (!file.isFile()) {
        throw new RuntimeException("Only files can be provided as local resources.");
      }
    }

    // if the file is not on hdfs, upload it to hdfs first.
    if (!path.startsWith("hdfs://")) {
      Path src = resourcePath;
      String newPath = mLocalResourceHDFSPaths + '/' + mAppName + '/' + mAppId + '/' + name;
      resourcePath = new Path(newPath);
      mFileSystem.copyFromLocalFile(false, true, src, resourcePath);
      LOG.info("Upload " + path + " to " + newPath);
      path = newPath;
    }

    FileStatus fileStatus = mFileSystem.getFileStatus(resourcePath);

    LocalResource resource = Records.newRecord(LocalResource.class);
    resource.setType(type);
    resource.setVisibility(LocalResourceVisibility.APPLICATION);
    resource.setResource(ConverterUtils.getYarnUrlFromPath(resourcePath));
    resource.setTimestamp(fileStatus.getModificationTime());
    resource.setSize(fileStatus.getLen());

    return new Pair<String, LocalResource>(path, resource);
  }

  private Map<String, LocalResource> getLocalResources() throws IOException {
    if (localResources == null) {
      localResources = new HashMap<String, LocalResource>();

      Pair<String, LocalResource> resource = constructLocalResource("HuskyAppMaster.jar", mAppMasterJar, FILE);
      mAppMasterJar = resource.getFirst();
      localResources.put("HuskyAppMaster.jar", resource.getSecond());

      resource = constructLocalResource("HuskyMasterExec", mMasterExec, FILE);
      mMasterExec = resource.getFirst();
      localResources.put("HuskyMasterExec", resource.getSecond());

      resource = constructLocalResource("HuskyWorkerExec", mWorkerExec, FILE);
      mWorkerExec = resource.getFirst();
      localResources.put("HuskyWorkerExec", resource.getSecond());

      resource = constructLocalResource("HuskyWorkersInfo", mWorkersInfoFile, FILE);
      mWorkersInfoFile = resource.getFirst();
      localResources.put("HuskyWorkersInfo", resource.getSecond());

      StringBuilder builder = new StringBuilder();
      for (String i : mLocalFiles.split(",")) { // single file
        i = i.trim();
        if (!i.isEmpty()) {
          resource = constructLocalResource(i, i, FILE);
          localResources.put(i, resource.getSecond());
          builder.append(resource.getFirst()).append(',');
        }
      }
      builder.setLength(Math.max(0, builder.length() - 1));
      mLocalFiles = builder.toString();

      builder.setLength(0);
      for (String i : mLocalArchives.split(",")) { // archive file
        i = i.trim();
        if (!i.isEmpty()) {
          Path path = new Path(i);
          RemoteIterator<LocatedFileStatus> fileIter = mFileSystem.listFiles(path, true);
          while (fileIter.hasNext()) {
            LocatedFileStatus s = fileIter.next();
            String name = s.getPath().getName();
            resource = constructLocalResource(name, s.getPath().toString(), ARCHIVE);
            localResources.put(name, resource.getSecond());
            builder.append(resource.getFirst()).append(',');
          }
        }
      }
      builder.setLength(Math.max(0, builder.length() - 1));
      mLocalArchives = builder.toString();

      // delete the directory recursively on exit
      mFileSystem.deleteOnExit(new Path(mLocalResourceHDFSPaths + '/' + mAppName + '/' + mAppId));
    }
    return localResources;
  }

  private Map<String, String> getEnvironment() {
    String[] paths = mYarnConf.getStrings(YARN_APPLICATION_CLASSPATH, DEFAULT_YARN_APPLICATION_CLASSPATH);
    StringBuilder classpath = new StringBuilder();
    classpath.append("./*");
    for (String s : paths) {
      classpath.append(":").append(s);
    }
    return Collections.singletonMap("CLASSPATH", classpath.toString());
  }

  private boolean monitorApp() throws YarnException, IOException {
    while (true) {
      try {
        Thread.sleep(10000);  // 10 seconds
      } catch (InterruptedException ignore) {
      }

      ApplicationReport report = mYarnClient.getApplicationReport(mAppId);

      YarnApplicationState yarnState = report.getYarnApplicationState();
      FinalApplicationStatus appState = report.getFinalApplicationStatus();
      LOG.info("YarnState = " + yarnState + ", AppState = " + appState + ", Progress = " + report.getProgress());

      if (yarnState == FINISHED) {
        if (appState == SUCCEEDED) {
          LOG.info("Application completed successfully.");
          return true;
        } else {
          LOG.info("Application completed unsuccessfully. YarnState: " + yarnState.toString() + ", ApplicationState: "
              + appState.toString());
          return false;
        }
      } else if (yarnState == KILLED || yarnState == FAILED) {
        LOG.info("Application did not complete. YarnState: " + yarnState.toString() + ", ApplicationState: "
            + appState.toString());
        return false;
      }
    }
  }

  private boolean run() throws YarnException, IOException {
    mYarnClient.start();

    YarnClientApplication app = mYarnClient.createApplication();

    ApplicationSubmissionContext appContext = app.getApplicationSubmissionContext();
    appContext.setApplicationName(mAppName);

    Resource resource = Records.newRecord(Resource.class);
    resource.setMemory(mAppMasterMemory);
    resource.setVirtualCores(mNumVirtualCores);
    appContext.setResource(resource);

    mAppId = appContext.getApplicationId();

    ContainerLaunchContext amContainer = Records.newRecord(ContainerLaunchContext.class);
    amContainer.setLocalResources(getLocalResources());
    amContainer.setEnvironment(getEnvironment());

    StringBuilder cmdBuilder = new StringBuilder();
    cmdBuilder.append(JAVA_HOME.$()).append("/bin/java")
        .append(" -Xmx").append(mAppMasterMemory).append("m ")
        .append(HuskyApplicationMaster.class.getName())
        .append(" --container_memory ").append(mContainerMemory)
        .append(" --container_vcores ").append(mNumVirtualCores)
        .append(" --app_priority ").append(mAppPriority)
        .append(" --app_master_log_dir <LOG_DIR>")
        .append(" --master ").append(mMasterExec)
        .append(" --worker ").append(mWorkerExec)
        .append(" --workers_info_file ").append(mWorkersInfoFile)
        .append(" --hdfs_namenode_host ").append(mHdfsNameNodeHost)
        .append(" --hdfs_namenode_port ").append(mHdfsNameNodePort);
    if (mJobArgs != null)
        cmdBuilder.append(" --job_args ").append(mJobArgs);
    cmdBuilder.append(" --worker_infos ").append(mWorkerInfos.get(0).getFirst())
      .append(":").append(mWorkerInfos.get(0).getSecond());
    for (int i = 1; i < mWorkerInfos.size(); i++) {
      cmdBuilder.append(',').append(mWorkerInfos.get(i).getFirst())
        .append(":").append(mWorkerInfos.get(i).getSecond());
    }
    if (!mLocalFiles.isEmpty()) {
      cmdBuilder.append(" --local_files \"").append(mLocalFiles).append("\"");
    }
    if (!mLocalArchives.isEmpty()) {
      cmdBuilder.append(" --local_archives \"").append(mLocalArchives).append("\"");
    }
    if (!mLogPathToHDFS.isEmpty()) {
      cmdBuilder.append(" --log_to_hdfs \"").append(mLogPathToHDFS).append("\"");
    }
    cmdBuilder.append(" 1>").append("<LOG_DIR>/HuskyAppMaster.stdout")
        .append(" 2>").append("<LOG_DIR>/HuskyAppMaster.stderr");
    if (!mLogPathToHDFS.isEmpty()) {
      cmdBuilder.append("; am_exit_code=$?")
          .append("; hadoop fs -put -f <LOG_DIR>/HuskyAppMaster.stdout ").append(mLogPathToHDFS)
          .append("; hadoop fs -put -f <LOG_DIR>/HuskyAppMaster.stderr ").append(mLogPathToHDFS)
          .append("; exit \"$am_exit_code\"");
    }

    amContainer.setCommands(Collections.singletonList(cmdBuilder.toString()));

    LOG.info("Command: " + amContainer.getCommands().get(0));

    appContext.setAMContainerSpec(amContainer);

    mYarnClient.submitApplication(appContext);

    return monitorApp();
  }

  public static void main(String[] args) {
    LOG.info("Start running HuskyYarnClient");
    boolean result = false;
    try {
      HuskyYarnClient client = new HuskyYarnClient();
      try {
        // argument initialization errors
        if (!client.init(args)) {
          System.exit(0);
        }
      } catch (Exception e) {
        LOG.log(Level.SEVERE, "Exception on parsing arguments", e);
        client.printUsage();
        System.exit(-1);
      }
      // runtime exceptions
      result = client.run();
    } catch (Exception e) {
      LOG.log(Level.SEVERE, "Exception on running application master", e);
      e.printStackTrace();
      System.exit(-1);
    }
    if (!result) {
      System.exit(-1);
    }
    System.exit(0);
  }
}
