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

import org.apache.commons.math3.util.Pair;
import org.apache.hadoop.fs.Path;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

class HuskyMaster extends Thread {
  private static final Logger LOG = Logger.getLogger(HuskyMaster.class.getName());
  private HuskyApplicationMaster mAppMaster = null;

  HuskyMaster(HuskyApplicationMaster appMaster) {
    LOG.info("Husky master will start as a sub process in application master container");
    mAppMaster = appMaster;
  }

  private List<String> getCommands() throws InterruptedException {
    LOG.info("Constructing husky master starting command.");
    ArrayList<String> commands = new ArrayList<String>();
    commands.add("./HuskyMasterExec");
    commands.add("--master_host=" + mAppMaster.getAppMasterHost());
    commands.add("--master_port=" + mAppMaster.getMasterPort());
    commands.add("--master_job_listener_port=" + mAppMaster.getMasterJobListenerPort());
    commands.add("--workers_info_path=./HuskyWorkersInfo");
    commands.add("--master_husky_scratch_dir=./");
    commands.add("--log_dir=./");
    commands.add("--logtostderr=1");
    commands.add("--worker_checkpoint_dir=./checkpoint");
    commands.add("--enable_dashboard=false");
    commands.add("--heartbeat_timeout=600");
    commands.add("1>" + mAppMaster.getAppMasterLogDir() + "/HuskyMaster.stdout");
    commands.add("2>" + mAppMaster.getAppMasterLogDir() + "/HuskyMaster.stderr");
    StringBuilder builder = new StringBuilder();
    for (String i : commands) {
      builder.append(i).append(' ');
    }
    LOG.info("Master command: " + builder.toString());
    return commands;
  }

  @Override
  public void run() {
    try {
      LOG.info("Starting husky master process");
      ProcessBuilder mHuskyMasterProcess = new ProcessBuilder(getCommands());
      mHuskyMasterProcess.redirectOutput(new File(mAppMaster.getAppMasterLogDir() + "/HuskyMaster.stdout"));
      mHuskyMasterProcess.redirectError(new File(mAppMaster.getAppMasterLogDir() + "/HuskyMaster.stderr"));
      Process p = mHuskyMasterProcess.start();
      p.waitFor();
      if (p.exitValue() == 0) {
        LOG.info("Husky master exits successfully");
      } else {
        LOG.info("Husky master exits with code " + p.exitValue());
      }
    } catch (Exception e) {
      LOG.log(Level.SEVERE, " Failed to start c++ husky master process: ", e);
    } finally {
      if (!mAppMaster.getLogPathToHDFS().isEmpty()) {
        try {
          mAppMaster.getFileSystem().copyFromLocalFile(false, true, new Path[]{
              new Path(mAppMaster.getAppMasterLogDir() + "/HuskyMaster.stdout"),
              new Path(mAppMaster.getAppMasterLogDir() + "/HuskyMaster.stderr")
          }, new Path(mAppMaster.getLogPathToHDFS()));
        } catch (IOException e) {
          LOG.log(Level.INFO, "Failed to upload logs of husky master to hdfs", e);
        }
      }
    }
  }
}
