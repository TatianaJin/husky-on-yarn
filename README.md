Husky on Yarn
=============

[![Build Status](https://travis-ci.org/husky-team/husky-on-yarn.svg?branch=master)](https://travis-ci.org/husky-team/husky-on-yarn)

Make Husky run on [YARN](http://hadoop.apache.org/docs/current/hadoop-yarn/hadoop-yarn-site/YARN.html).


Dependencies
-------------

* Java 1.7+
* Maven 3.3.9
* Hadoop 2.6+

Usage
-------------

One can use `run.sh` to run a husky application on yarn:
```shell
python run.py --master=/path/to/master --worker=/path/to/worker --workers_info=/path/to/workers_info_file
```
This script will first build this project and submit a job to yarn.

Husky-on-yarn supports more arguments for different purposes. Get more information by running `run.sh` without giving any arguments:
```
 -app_name <arg>                   The name of the application
 -app_priority <arg>               A number to indicate the priority of
                                   the husky application
 -container_memory <arg>           Amount of memory in MB to be requested
                                   to run container. Each container is a
                                   worker node.
 -container_vcores <arg>           Number of virtual cores that a
                                   container can use
 -hdfs_namenode_host <arg>         HDFS Namenode host
 -hdfs_namenode_port <arg>         HDFS Namenode port
 -help                             Print Usage
 -jar <arg>                        Local path to the jar file of
                                   application master.
 -local_archives <arg>             Archives that need to pass to and be
                                   unarchived in working environment. Use
                                   comma(,) to split different archives.
 -local_files <arg>                Files that need to pass to working
                                   environment. Use comma(,) to split
                                   different files.
 -local_resrcrt <arg>              The root directory on hdfs where local
                                   resources store
 -log_to_hdfs                      Path on HDFS where to upload logs of
                                   application master and worker
                                   containers
 -master <arg>                     Executable for c++ husky master (on
                                   local file system or HDFS)
 -master_job_listener_port <arg>   Husky master job listener port
 -master_memory <arg>              Amount of memory in MB to be requested
                                   to run application master
 -master_port <arg>                Husky master port
 -worker <arg>                     Executable for c++ husky worker (on
                                   local file system or HDFS)
 -worker_infos <arg>               Specified hosts that husky worker will
                                   run on. Use comma(,) to split different
                                   archives.
 -workers_info_file <arg>          Workers info file for c++ husky master
                                   and worker(on local file system or
                                   HDFS)
```

Contributors
---------------

* zzxx (yjzhao@cse.cuhk.edu.hk, zzxx.is.me@gmail.com)
* legend (gxjiang@cse.cuhk.edu.hk, kygx.legend@gmail.com)


License
---------------

Copyright 2018 Husky Team

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
