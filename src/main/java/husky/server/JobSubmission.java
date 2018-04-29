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

class JobSubmission extends Thread {
  private static final Logger LOG = Logger.getLogger(JobSubmission.class.getName());
  private HuskyApplicationMaster mAppMaster = null;
  private String mJobArgs = null;
  private String mJobFile = null;

  JobSubmission(HuskyApplicationMaster appMaster) {
    LOG.info("Submit job to H4 master: " + appMaster.getJobArgs());
    mAppMaster = appMaster;

    String[] args = appMaster.getJobArgs().split(",", 2);
    mJobFile = args[0].trim();
    if (args.length > 1)
        mJobArgs = args[1];
  }

  private List<String> getCommands() throws InterruptedException {
    LOG.info("Constructing job submission command.");
    ArrayList<String> commands = new ArrayList<String>();
    commands.add("./submit-job.py");
    commands.add("--master_host=" + mAppMaster.getAppMasterHost());
    commands.add("--job_port=" + mAppMaster.getMasterJobListenerPort());
    commands.add("--job_name=" + mJobFile);
    if (mJobArgs != null) {
        commands.add("--job_args=" + mJobArgs);
    }
    commands.add("--shutdown");
    commands.add("1>" + mAppMaster.getAppMasterLogDir() + "/JobSubmission.stdout");
    commands.add("2>" + mAppMaster.getAppMasterLogDir() + "/JobSubmission.stderr");
    StringBuilder builder = new StringBuilder();
    for (String i : commands) {
      builder.append(i).append(' ');
    }
    LOG.info("Job submission command: " + builder.toString());
    return commands;
  }

  @Override
  public void run() {
    try {
      ProcessBuilder process = new ProcessBuilder(getCommands());
      process.redirectOutput(new File(mAppMaster.getAppMasterLogDir() + "/JobSubmission.stdout"));
      process.redirectError(new File(mAppMaster.getAppMasterLogDir() + "/JobSubmission.stderr"));
      Process p = process.start();
      p.waitFor();
      if (p.exitValue() == 0) {
        LOG.info("Job submitted successfully");
      } else {
        LOG.info("Job submission failed with code " + p.exitValue());
      }
    } catch (Exception e) {
      LOG.log(Level.SEVERE, " Failed to start job submission python script: ", e);
    }
  }
}
